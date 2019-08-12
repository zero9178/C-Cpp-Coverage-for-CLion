package net.zero9178.cov.data

import com.beust.klaxon.*
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.psi.OCIfStatement
import com.jetbrains.cidr.lang.psi.OCLoopStatement
import com.jetbrains.cidr.lang.psi.OCStatement
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.ComparablePair
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Paths
import kotlin.math.ceil

class LLVMCoverageGenerator(
    private val myLLVMCov: String,
    private val myLLVMProf: String,
    private val myDemangler: String?
) : CoverageGenerator {

    override fun patchEnvironment(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        cmdLine: GeneralCommandLine
    ) {
        val executionTarget = ExecutionTargetManager.getInstance(configuration.project).activeTarget
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return
        cmdLine.withEnvironment(
            "LLVM_PROFILE_FILE",
            environment.toEnvPath(config.configurationGenerationDir.resolve("${config.target.name}-%p.profraw").absolutePath)
        )
    }

    private data class Root(val data: List<Data>, val version: String, val type: String)

    private data class Data(val files: List<File>, val functions: List<Function>)

    private data class File(val filename: String, val segments: List<Segment>)

    private data class Segment(
        val line: Int,
        val column: Int,
        val count: Long,
        val hasCount: Boolean,
        val isRegionEntry: Boolean
    )

    private data class Function(
        val name: String,
        val count: Long,
        val regions: List<Region>,
        val filenames: List<String>
    )

    private data class Region(
        val lineStart: Int,
        val columnStart: Int,
        val lineEnd: Int,
        val columnEnd: Int,
        val executionCount: Long, val fileId: Int, val expandedFileId: Int, val regionKind: Int
    ) {
        companion object {
            const val CODE = 0
            const val EXPANSION = 1
            const val SKIPPED = 2
            const val GAP = 3
        }
    }

    @Suppress("ConvertCallChainIntoSequence")
    private fun processJson(
        jsonContent: String,
        environment: CPPEnvironment,
        project: Project
    ): CoverageData {
        val root = Klaxon()
            .converter(object : Converter {
                override fun canConvert(cls: Class<*>) = cls == Segment::class.java

                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                override fun fromJson(jv: JsonValue): Any? {
                    val array = jv.array ?: return null
                    return Segment(
                        (array[0] as Number).toInt(),
                        (array[1] as Number).toInt(),
                        (array[2] as Number).toLong(),
                        if (array[3] is Boolean) array[3] as Boolean else array[3] as Number != 0,
                        if (array[4] is Boolean) array[4] as Boolean else array[4] as Number != 0
                    )
                }

                override fun toJson(value: Any): String {
                    val segment = value as? Segment ?: return ""
                    return "[${segment.line},${segment.column},${segment.count},${segment.hasCount},${segment.isRegionEntry}]"
                }
            }).converter(object : Converter {
                override fun canConvert(cls: Class<*>) = cls == Region::class.java

                override fun fromJson(jv: JsonValue): Any? {
                    val array = jv.array ?: return null
                    return Region(
                        (array[0] as Number).toInt(),
                        (array[1] as Number).toInt(),
                        (array[2] as Number).toInt(),
                        (array[3] as Number).toInt(),
                        (array[4] as Number).toLong(),
                        (array[5] as Number).toInt(),
                        (array[6] as Number).toInt(),
                        (array[7] as Number).toInt()
                    )
                }

                override fun toJson(value: Any): String {
                    val region = value as? Region ?: return ""
                    return "[${region.lineStart},${region.columnStart},${region.lineEnd},${region.columnEnd},${region.executionCount},${region.fileId},${region.expandedFileId},${region.regionKind}]"
                }
            }).maybeParse<Root>(Parser.jackson().parse(StringReader(jsonContent)) as JsonObject)
            ?: return CoverageData(emptyMap())

        val mangledNames = root.data.map { data -> data.functions.map { it.name } }.flatten()
        val demangledNames = if (myDemangler != null && Paths.get(myDemangler).exists()) {
            val p = ProcessBuilder().command(myDemangler).redirectErrorStream(true).start()
            var result = listOf<String>()
            p.outputStream.bufferedWriter().use { writer ->
                p.inputStream.bufferedReader().use { reader ->
                    result = mangledNames.map { mangled ->
                        writer.appendln(
                            if (mangled.startsWith('_')) mangled else mangled.substring(
                                1 + mangled.indexOf(
                                    ':'
                                )
                            )
                        )
                        writer.flush()
                        val output: String? = reader.readLine()
                        output ?: mangled
                    }
                }
            }
            p.destroyForcibly()
            mangledNames.zip(result).associate { it }
        } else {
            mangledNames.associateBy { it }
        }

        return CoverageData(root.data.map { data ->
            //Associates the filename with a list of all functions in that file
            val funcMap =
                data.functions.map { func -> func.filenames.map { it to func } }.flatten().groupBy({ it.first }) {
                    it.second
                }

            data.files.map { file ->
                val entries = file.segments.filter { it.isRegionEntry }
                CoverageFileData(
                    environment.toLocalPath(file.filename).replace('\\', '/'),
                    funcMap.getOrDefault(
                        file.filename,
                        emptyList()
                    ).chunked(ceil(data.files.size / Thread.activeCount().toDouble()).toInt()).map { functions ->
                        ApplicationManager.getApplication().executeOnPooledThread<List<CoverageFunctionData>> {
                            functions.fold(emptyList()) { result, function ->

                                val regions =
                                    function.regions.filter { it.regionKind != Region.GAP && function.filenames[it.fileId] == file.filename }
                                if (regions.isEmpty()) {
                                    result
                                } else {
                                    val branches =
                                        regions.filter { region -> entries.any { it.line == region.lineStart && it.column == region.columnStart } }
                                    result + CoverageFunctionData(
                                        regions.first().lineStart,
                                        regions.first().lineEnd,
                                        demangledNames[function.name] ?: function.name,
                                        FunctionRegionData(regions.map { region ->
                                            FunctionRegionData.Region(
                                                region.lineStart toCP region.columnStart,
                                                region.lineEnd toCP region.columnEnd,
                                                region.executionCount
                                            )
                                        }),
                                        if (CoverageGeneratorSettings.getInstance().branchCoverageEnabled) findStatementsForBranches(
                                            regions.first().lineStart to regions.first().lineEnd,
                                            branches,
                                            regions,
                                            environment.toLocalPath(file.filename),
                                            project
                                        ) else emptyList()
                                    )
                                }
                            }
                        }
                    }.map { it.get() }.flatten().associateBy { it.functionName })
            }


        }.flatten().associateBy { it.filePath })
    }

    private fun findStatementsForBranches(
        functionPos: Pair<Int, Int>,
        regionEntries: List<Region>,
        allRegions: List<Region>,
        file: String,
        project: Project
    ): List<CoverageBranchData> {
        if (regionEntries.isEmpty()) {
            return emptyList()
        }
        return DumbService.getInstance(project).runReadActionInSmartMode<List<CoverageBranchData>> {
            val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadActionInSmartMode emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadActionInSmartMode emptyList()
            val document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadActionInSmartMode emptyList()

            val branches = mutableListOf<Pair<Region, OCStatement>>()

            object : OCRecursiveVisitor(
                TextRange(
                    document.getLineStartOffset(functionPos.first - 1),
                    document.getLineEndOffset(functionPos.second - 1)
                )
            ) {
                override fun visitLoopStatement(loop: OCLoopStatement?) {
                    loop ?: return super.visitLoopStatement(loop)
                    if (!CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled) {
                        return super.visitLoopStatement(loop)
                    }
                    val body = loop.body ?: return super.visitLoopStatement(loop)
                    matchStatement(loop, body)
                    super.visitLoopStatement(loop)
                }

                override fun visitIfStatement(stmt: OCIfStatement?) {
                    stmt ?: return super.visitIfStatement(stmt)
                    if (!CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled) {
                        return super.visitIfStatement(stmt)
                    }
                    val body = stmt.thenBranch ?: return super.visitIfStatement(stmt)
                    matchStatement(stmt, body)
                    super.visitIfStatement(stmt)
                }

                private fun matchStatement(parent: OCStatement, body: OCStatement) {
                    branches += regionEntries.filter {
                        body.textOffset == document.getLineStartOffset(it.lineStart - 1) + it.columnStart - 1
                    }.map { it to parent }
                }
            }.visitElement(psiFile)

            branches.fold(emptyList()) { list, (region, element) ->

                val (above, after) = {
                    val startLine = document.getLineNumber(element.textOffset) + 1
                    val startColumn = element.textOffset - document.getLineStartOffset(startLine - 1) + 1
                    val startPos = ComparablePair(startLine, startColumn)

                    val endLine = document.getLineNumber(element.textRange.endOffset) + 1
                    val endColumn = element.textRange.endOffset - document.getLineStartOffset(endLine - 1) + 1
                    val endPos = endLine toCP endColumn + 1

                    allRegions.findLast {
                        startPos in (it.lineStart toCP it.columnStart)..(it.lineEnd toCP it.columnEnd)
                    } to allRegions.findLast { endPos in (it.lineStart toCP it.columnStart)..(it.lineEnd toCP it.columnEnd) }
                }()

                if (above == null) {
                    list
                } else {

                    val startLine = when (element) {
                        is OCLoopStatement -> document.getLineNumber(
                            element.lParenth?.textRange?.endOffset ?: element.firstChild.textRange.endOffset
                        ) + 1
                        is OCIfStatement -> document.getLineNumber(
                            element.lParenth?.textRange?.endOffset ?: element.firstChild.textRange.endOffset
                        ) + 1
                        else -> document.getLineNumber(element.firstChild.textRange.endOffset) + 1
                    }

                    val startColumn = when (element) {
                        is OCLoopStatement -> (element.lParenth?.textRange?.startOffset
                            ?: element.firstChild.textRange.startOffset) - document.getLineStartOffset(startLine - 1) + 1
                        is OCIfStatement -> (element.lParenth?.textRange?.startOffset
                            ?: element.firstChild.textRange.startOffset) - document.getLineStartOffset(startLine - 1) + 1
                        else -> element.firstChild.textRange.startOffset - document.getLineStartOffset(startLine - 1) + 1
                    }

                    list + CoverageBranchData(
                        startLine toCP startColumn, region.executionCount.toInt(),
                        (if (above.executionCount >= region.executionCount) above.executionCount - region.executionCount else after?.executionCount
                            ?: 1).toInt()
                    )
                }
            }
        }
    }

    override fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return null

        val files = config.configurationGenerationDir.listFiles()
            ?.filter { it.name.matches("${config.target.name}-\\d*.profraw".toRegex()) } ?: emptyList()

        val llvmProf = ProcessBuilder().command(
            listOf(
                myLLVMProf,
                "merge",
                "-output=${config.target.name}.profdata"
            ) + files.map { it.absolutePath }
        ).directory(config.configurationGenerationDir).start()
        var retCode = llvmProf.waitFor()
        var lines = llvmProf.errorStream.bufferedReader().readLines()
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "llvm-profdata returned error code $retCode with error output:\n${lines.joinToString(
                    "\n"
                )}", NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        files.forEach { it.delete() }

        val processBuilder = ProcessBuilder().command(
            myLLVMCov,
            "export",
            "-instr-profile",
            "${config.target.name}.profdata",
            config.productFile?.absolutePath ?: ""
        ).directory(config.configurationGenerationDir).redirectErrorStream(true)
        val llvmCov = processBuilder.start()
        lines = llvmCov.inputStream.bufferedReader().readLines()
        retCode = llvmCov.waitFor()
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "llvm-cov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${processBuilder.command().joinToString(" ")}\n Stderr: ${lines.joinToString("\n")}",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        return processJson(lines.joinToString(), environment, configuration.project)
    }
}