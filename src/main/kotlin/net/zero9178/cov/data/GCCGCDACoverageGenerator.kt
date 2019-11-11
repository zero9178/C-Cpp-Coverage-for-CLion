package net.zero9178.cov.data

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.ParseException
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ceil

class GCCGCDACoverageGenerator(private val myGcov: String, private val myMajorVersion: Int) :
    CoverageGenerator {

    private sealed class Item {
        class File(val path: String) : Item()

        class Function(val startLine: Int, val endLine: Int, val count: Long, val name: String) : Item()

        class LCount(val line: Int, val count: Long) : Item()

        class Branch(val line: Int, val branchType: BranchType) : Item() {
            enum class BranchType {
                notexec,
                taken,
                nottaken
            }
        }
    }

    private fun parseGcovIR(
        lines: List<List<String>>,
        project: Project,
        env: CPPEnvironment
    ): CoverageData? {

        abstract class GCovCommonGrammar : Grammar<List<Item>>() {

            //Lexems
            val num by token("\\d+")
            val comma by token(",")
            val colon by token(":")
            val newLine by token("\n")
            val ws by token("[ \t]+", ignore = true)
            val file by token("file:.*")
            val function by token("function:.*")
            val version by token("version:.*\n", ignore = true)

            //Keywords
            val lcount by token("lcount")
            val branch by token("branch")
            val notexec by token("notexec")
            val taken by token("taken")
            val nottaken by token("nottaken")
            val nonKeyword by token("[a-zA-Z_]\\w*")

            val word by nonKeyword or file or function or lcount or branch or notexec or nottaken

            val fileLine by file use {
                Item.File(env.toLocalPath(text.removePrefix("file:")).replace('\\', '/'))
            }

            val branchLine by -branch and -colon and num and -comma and (notexec or taken or nottaken) map { (count, type) ->
                Item.Branch(count.text.toInt(), Item.Branch.BranchType.valueOf(type.text))
            }
        }

        val govUnder8Grammer = object : GCovCommonGrammar() {

            val functionLine by function use {
                object : Grammar<Item.Function>() {

                    val num by token("\\d+")
                    val comma by token(",")
                    val rest by token(".*")

                    override val rootParser by num and -comma and num and -comma and rest map { (line, count, name) ->
                        Item.Function(line.text.toInt(), -1, count.text.toLong(), name.text)
                    }
                }.parseToEnd(text.removePrefix("function:"))
            }

            val lcountLine by -lcount and -colon and num and -comma and num map { (line, count) ->
                Item.LCount(line.text.toInt(), count.text.toLong())
            }

            override val rootParser by separatedTerms(
                fileLine or functionLine or lcountLine or branchLine,
                newLine
            )
        }

        val gcov8Grammer = object : GCovCommonGrammar() {

            val functionLine by function use {
                object : Grammar<Item.Function>() {

                    val num by token("\\d+")
                    val comma by token(",")
                    val rest by token(".*")

                    override val rootParser by num and -comma and num and -comma and num and -comma and rest map { (startLine, endLine, count, name) ->
                        Item.Function(startLine.text.toInt(), endLine.text.toInt(), count.text.toLong(), name.text)
                    }
                }.parseToEnd(text.removePrefix("function:"))
            }

            val lcountLine by -lcount and -colon and num and -comma and num and -comma and -num map { (line, count) ->
                Item.LCount(line.text.toInt(), count.text.toLong())
            }

            override val rootParser by separatedTerms(
                fileLine or functionLine or lcountLine or branchLine,
                newLine
            )
        }

        val result = lines.chunked(ceil(lines.size.toDouble() / Thread.activeCount()).toInt()).map {
            ApplicationManager.getApplication().executeOnPooledThread<List<Item>> {
                it.flatMap { gcovFile ->
                    try {
                        val ast = if (myMajorVersion == 8) {
                            gcov8Grammer.parseToEnd(gcovFile.joinToString("\n"))
                        } else {
                            govUnder8Grammer.parseToEnd(gcovFile.joinToString("\n"))
                        }
                        ast.filter { it !is Item.Branch }
                    } catch (e: ParseException) {
                        val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                            "Error parsing gcov generated files",
                            "This is either due to a bug in the plugin or gcov",
                            "Parser output:${e.errorResult}",
                            NotificationType.ERROR
                        )
                        Notifications.Bus.notify(notification, project)
                        emptyList<Item>()
                    }
                }
            }
        }.flatMap { it.get() }
        return linesToCoverageData(result)
    }

    private fun linesToCoverageData(lines: List<Item>): CoverageData {
        val files = mutableListOf<CoverageFileData>()
        var lineCopy = lines
        while (lineCopy.isNotEmpty()) {
            val item = lineCopy[0]
            lineCopy = lineCopy.subList(1, lineCopy.size)
            val file = item as? Item.File ?: continue
            val functions = mutableListOf<Triple<Int, String, MutableMap<Int, Long>>>()
            lineCopy = lineCopy.dropWhile {
                if (it is Item.Function) {
                    functions += Triple(it.startLine, it.name, mutableMapOf())
                    true
                } else {
                    false
                }
            }
            lineCopy = lineCopy.dropWhile {
                if (it is Item.LCount) {
                    val func = functions.findLast { function -> function.first <= it.line } ?: return@dropWhile true
                    func.third[it.line] = it.count
                    true
                } else {
                    false
                }
            }
            if (functions.isEmpty()) {
                continue
            }
            files += CoverageFileData(file.path, functions.map { (startLine, name, lines) ->
                CoverageFunctionData(
                    startLine, Int.MAX_VALUE, name, FunctionLineData(lines),
                    emptyList()
                )
            }.associateBy { it.functionName })
        }

        return CoverageData(
            files.associateBy { it.filePath },
            false,
            CoverageGeneratorSettings.getInstance().calculateExternalSources
        )
    }

    override fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return null
        val files =
            config.configurationGenerationDir.walkTopDown().filter {
                it.isFile && it.name.endsWith(".gcda")
            }.map { environment.toEnvPath(it.absolutePath) }.toList()

        val p = environment.hostMachine.runProcess(
            GeneralCommandLine(
                listOf(
                    myGcov,
                    "-i",
                    "-m"
                ) + files
            ).withWorkDirectory(config.configurationGenerationDir), null, -1
        )
        val lines = p.stdout
        val retCode = p.exitCode
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "gcov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${(listOf(
                    myGcov,
                    "-i",
                    "-m"
                ) + files).joinToString(" ")}\n Stderr: ${lines}",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        files.forEach { Files.deleteIfExists(Paths.get(environment.toLocalPath(it))) }

        val filter = config.configurationGenerationDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".gcov")
        }?.toList() ?: emptyList()

        val output = filter.map {
            it.readLines()
        }

        filter.forEach { it.delete() }

        return parseGcovIR(output, configuration.project, environment)
    }
}