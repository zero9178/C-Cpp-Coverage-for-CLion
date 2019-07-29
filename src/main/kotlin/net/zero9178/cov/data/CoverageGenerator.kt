package net.zero9178.cov.data

import com.beust.klaxon.*
import com.beust.klaxon.jackson.jackson
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.tryParseToEnd
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.Parsed
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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.ComparablePair
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

interface CoverageGenerator {
    fun patchEnvironment(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        cmdLine: GeneralCommandLine
    ) {
    }

    val executable: String

    fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        return null
    }
}

private class LLVMCoverageGenerator(
    override val executable: String,
    val llvmProf: String,
    val demangler: String?
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
                        array[3] as Boolean,
                        array[4] as Boolean
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
        val demangledNames = if (demangler != null && Paths.get(demangler).exists()) {
            val p = ProcessBuilder().command(demangler).redirectErrorStream(true).start()
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
            data.files.chunked(ceil(data.files.size / Thread.activeCount().toDouble()).toInt()).map { files ->
                ApplicationManager.getApplication().executeOnPooledThread<List<CoverageFileData>> {
                    files.map { file ->
                        val entries = file.segments.filter { it.isRegionEntry }
                        CoverageFileData(
                            environment.toLocalPath(file.filename).replace('\\', '/'),
                            data.functions.filter { it.filenames.contains(file.filename) && it.regions.isNotEmpty() }.map { function ->

                                val regions = function.regions.filter { it.regionKind != Region.GAP }
                                val branches =
                                    regions.filter { region -> entries.any { (it.line to it.column) == (region.lineStart to region.columnStart) } }
                                CoverageFunctionData(
                                    function.regions.first().lineStart,
                                    function.regions.first().lineEnd,
                                    demangledNames[function.name] ?: function.name,
                                    FunctionRegionData(regions.map { region ->
                                        FunctionRegionData.Region(
                                            region.lineStart toCP region.columnStart,
                                            region.lineEnd toCP region.columnEnd,
                                            region.executionCount
                                        )
                                    }),
                                    findStatementsForBranches(
                                        function.regions.first().lineStart to function.regions.first().lineEnd,
                                        branches,
                                        regions,
                                        environment.toLocalPath(file.filename),
                                        project
                                    )
                                )
                            }.associateBy { it.functionName })
                    }
                }
            }.map { it.get() }.flatten()
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
                llvmProf,
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
            executable,
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

private class GCCGCDACoverageGenerator(override val executable: String, private val myMajorVersion: Int) :
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
            val version by token("version:.*\n", ignore = true)

            //Keywords
            val function by token("function")
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

            val functionLine by -function and -colon and num and -comma and num and -comma and word map { (line, count, name) ->
                Item.Function(line.text.toInt(), -1, count.text.toLong(), name.text)
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

            val functionLine by -function and -colon and num and -comma and num and -comma and num and -comma and word map { (startLine, endLine, count, name) ->
                Item.Function(startLine.text.toInt(), endLine.text.toInt(), count.text.toLong(), name.text)
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
                it.map { gcovFile ->
                    val ast = if (myMajorVersion == 8) {
                        gcov8Grammer.tryParseToEnd(gcovFile.joinToString("\n"))
                    } else {
                        govUnder8Grammer.tryParseToEnd(gcovFile.joinToString("\n"))
                    }
                    when (ast) {
                        is ErrorResult -> {
                            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                                "Error parsing gcov generated files",
                                "This is either due to a bug in the plugin or gcov",
                                "Parser output:$ast",
                                NotificationType.ERROR
                            )
                            Notifications.Bus.notify(notification, project)
                            emptyList()
                        }
                        is Parsed -> ast.value.filter { it !is Item.Branch }
                    }
                }.flatten()
            }
        }.map { it.get() }.flatten()
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

        return CoverageData(files.associateBy { it.filePath })
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
            }.map { it.absolutePath }.toList()

        val processBuilder =
            ProcessBuilder().command(listOf(executable, "-b", "-i", "-m") + files).redirectErrorStream(true)
                .directory(config.configurationGenerationDir)
        val p = processBuilder.start()
        val lines = p.inputStream.bufferedReader().readLines()
        val retCode = p.waitFor()
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "gcov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${processBuilder.command().joinToString(" ")}\n Stderr: ${lines.joinToString("\n")}",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        files.forEach { Files.deleteIfExists(Paths.get(it)) }

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

private class GCCJSONCoverageGenerator(override val executable: String) : CoverageGenerator {

    private fun findStatementsForBranches(
        lines: List<Line>,
        file: String,
        project: Project
    ): List<CoverageBranchData> {
        if (lines.isEmpty()) {
            return emptyList()
        }
        return DumbService.getInstance(project).runReadActionInSmartMode<List<CoverageBranchData>> {
            val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadActionInSmartMode emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadActionInSmartMode emptyList()
            val document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadActionInSmartMode emptyList()
            lines.filter { it.branches.isNotEmpty() }.map { line ->
                val startOffset = document.getLineStartOffset(line.lineNumber - 1)
                val lineEndOffset = document.getLineEndOffset(line.lineNumber - 1)
                val result = mutableListOf<OCElement>()
                val leftOutStmts = mutableListOf<OCStatement>()
                object : OCRecursiveVisitor(
                    TextRange(
                        startOffset,
                        lineEndOffset
                    )
                ) {
                    override fun visitLoopStatement(loop: OCLoopStatement?) {
                        loop ?: return super.visitLoopStatement(loop)
                        matchBranch(loop)
                        super.visitLoopStatement(loop)
                    }

                    override fun visitIfStatement(stmt: OCIfStatement?) {
                        stmt ?: return super.visitIfStatement(stmt)
                        matchBranch(stmt)
                        super.visitIfStatement(stmt)
                    }

                    private fun matchBranch(element: OCElement) {
                        //If and if or loop statement has an operator that can short circuit inside its expression
                        //then we dont have any branch coverage for those itself. We return here as we are handling
                        //it in the BinaryExpression
                        val isShortCicuit = { statement: OCStatement, condition: PsiElement ->
                            val list = PsiTreeUtil.getChildrenOfTypeAsList(
                                condition,
                                OCBinaryExpression::class.java
                            )
                            if (list.any { listOf("||", "or", "&&", "and").contains(it.operationSignNode.text) }) {
                                leftOutStmts += statement
                                true
                            } else {
                                false
                            }
                        }

                        when (element) {
                            is OCIfStatement -> {
                                if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                    return
                                }
                                if (element.condition?.let { isShortCicuit(element, it) } == true) {
                                    return
                                }
                            }
                            is OCDoWhileStatement -> {
                                if (element.lastChild.textOffset !in startOffset..lineEndOffset) {
                                    return
                                }
                            }
                            else -> {
                                if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                    return
                                }
                            }
                        }

                        if (element is OCLoopStatement) {
                            if (element.condition?.let { isShortCicuit(element, it) } == true) {
                                return
                            }
                        }

                        result += element
                    }

                    override fun visitBinaryExpression(expression: OCBinaryExpression?) {
                        super.visitBinaryExpression(expression)
                        expression ?: return
                        when (expression.operationSignNode.text) {
                            "||", "or", "&&", "and" -> {
                                //Calling with the operands here as it creates a branch for each operand
                                val left = expression.left
                                if (left != null) {
                                    if (PsiTreeUtil.findChildrenOfType(left, OCBinaryExpression::class.java).none {
                                            listOf("||", "or", "&&", "and").contains(it.operationSignNode.text)
                                        }) {
                                        matchBranch(left)
                                    }
                                }
                                val right = expression.right
                                if (right != null) {
                                    if (PsiTreeUtil.findChildrenOfType(right, OCBinaryExpression::class.java).none {
                                            listOf("||", "or", "&&", "and").contains(it.operationSignNode.text)
                                        }) {
                                        matchBranch(right)
                                    }
                                }
                            }
                        }
                    }
                }.visitElement(psiFile)

                val zip =
                    line.branches.chunked(2).filter { it.none { branch -> branch.throwing } && it.size == 2 }
                        .map { it[0] to it[1] }
                        .zip(result)

                fun OCStatement.getCondition() = when (this) {
                    is OCIfStatement -> this.condition
                    is OCLoopStatement -> this.condition
                    else -> null
                }

                fun OCStatement.getLParenth() = when (this) {
                    is OCIfStatement -> this.lParenth
                    is OCLoopStatement -> this.lParenth
                    else -> null
                }

                /**
                 * OR Test code:
                 * for(; E0 || ... || En;) {
                 *      ...
                 * }
                 *
                 * How gcov sees it:
                 * {
                 *  int i = 0;
                 * check:
                 *  if(E0)//Branch 1
                 *  {
                 *      goto body;
                 *  }
                 *  else if(E1)
                 *  {
                 *
                 *  }
                 *  ...
                 *  else if(!(En))//Branch 2
                 *  {
                 *      goto end;
                 *  }
                 *  else
                 *  {
                 *      goto body;
                 *  }
                 *  body:
                 *  ...
                 *  goto check;
                 *  end:
                 *
                 *  AND Test code:
                 * for(int i = 0; i < 5 && i % 2 == 0; i++) {
                 *      ...
                 * }
                 *
                 * How gcov sees it:
                 * {
                 *  int i = 0;
                 * check:
                 *  if(i < 5)//Branch 1
                 *  {
                 *      if(i % 2 == 0)//Branch 2
                 *      {
                 *          goto body;
                 *      }
                 *      else
                 *      {
                 *          goto end;
                 *      }
                 *  }
                 *  else
                 *  {
                 *      goto end;
                 *  }
                 *  body:
                 *  ...
                 *  goto check;
                 *  end:
                 *
                 *  To figure out the branch probability of a loop or if that has short circuiting
                 *  we need to check how many times the else was NOT reached incase of OR or check how many times
                 *  all branches reached the deepest body
                 */
                val stmts = leftOutStmts.map { thisStmt ->
                    val filter =
                        zip.filter { thisStmt.getCondition()?.textRange?.contains(it.second.textRange) ?: false }
                    thisStmt to filter
                        .foldIndexed<Pair<Pair<Branch, Branch>, OCElement>, Pair<Int, Int>?>(null) { index, current, (branches, element) ->
                            val skipped = if (branches.first.fallthrough) branches.second else branches.first
                            val steppedIn = if (branches.first.fallthrough) branches.first else branches.second

                            //if current != null than operand is always the one in the second branch so to say
                            val parentExpression = element.parent as? OCBinaryExpression ?: return@foldIndexed current
                            val isLast = index == filter.lastIndex
                            when (parentExpression.operationSignNode.text) {
                                "or", "||" -> if (current == null) {
                                    skipped.count to steppedIn.count
                                } else {
                                    if (current.second != 0) {
                                        if (isLast) {
                                            current.first + skipped.count to steppedIn.count
                                        } else {
                                            current.first + steppedIn.count to skipped.count
                                        }
                                    } else {
                                        current
                                    }
                                }
                                "and", "&&" -> if (current == null) {
                                    steppedIn.count to skipped.count
                                } else {
                                    if (current.first != 0) {
                                        steppedIn.count to current.second + skipped.count
                                    } else {
                                        current
                                    }
                                }
                                else -> current
                            }
                        }
                }.filter { it.second != null }.map { it.first to it.second!! }.map { (thisIf, pair) ->
                    val startLine = document.getLineNumber(thisIf.getLParenth()?.startOffset ?: thisIf.textOffset) + 1
                    val startColumn =
                        (thisIf.getLParenth()?.startOffset ?: thisIf.textOffset) - document.getLineStartOffset(
                            startLine - 1
                        ) + 1
                    CoverageBranchData(
                        startLine toCP startColumn,
                        pair.first, pair.second
                    )
                }

                zip.filter {
                    when (it.second) {
                        is OCLoopStatement -> CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
                        is OCIfStatement -> CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
                        is OCExpression -> CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
                        else -> true
                    }
                }.fold(stmts) { list, (branches, element) ->
                    val steppedIn = if (branches.first.fallthrough) branches.first else branches.second
                    val skipped = if (branches.first.fallthrough) branches.second else branches.first
                    list + CoverageBranchData(
                        when (element) {
                            is OCLoopStatement -> {
                                val startLine = document.getLineNumber(
                                    element.lParenth?.textRange?.startOffset ?: element.textOffset
                                ) + 1
                                val column = (element.lParenth?.textRange?.startOffset
                                    ?: element.textOffset) - document.getLineStartOffset(startLine - 1) + 1
                                startLine toCP column
                            }
                            is OCIfStatement -> {
                                val startLine = document.getLineNumber(
                                    element.lParenth?.textRange?.startOffset ?: element.textOffset
                                ) + 1
                                val column = (element.lParenth?.textRange?.startOffset
                                    ?: element.textOffset) - document.getLineStartOffset(startLine - 1) + 1
                                startLine toCP column
                            }
                            is OCExpression -> {
                                val startLine =
                                    document.getLineNumber(element.textOffset) + 1
                                val column =
                                    element.textOffset - document.getLineStartOffset(
                                        startLine - 1
                                    ) + 1
                                startLine toCP column
                            }
                            else -> {
                                val startLine = document.getLineNumber(element.textOffset) + 1
                                val column = element.textOffset - document.getLineStartOffset(startLine - 1) + 1
                                startLine toCP column
                            }
                        }, steppedIn.count, skipped.count
                    )
                }
            }.flatten()
        }
    }

    @Suppress("ConvertCallChainIntoSequence")
    private fun rooToCoverageData(root: Root, env: CPPEnvironment, project: Project) =
        CoverageData(root.files.chunked(ceil(root.files.size / Thread.activeCount().toDouble()).toInt()).map {
            ApplicationManager.getApplication().executeOnPooledThread<List<CoverageFileData>> {
                it.filter { it.lines.isNotEmpty() || it.functions.isNotEmpty() }.map { file ->
                    CoverageFileData(env.toLocalPath(file.file).replace('\\', '/'), file.functions.map { function ->
                        val lines = file.lines.filter {
                            it.functionName == function.name
                        }
                        CoverageFunctionData(
                            function.startLine,
                            function.endLine,
                            function.demangledName,
                            FunctionLineData(lines.associate { it.lineNumber to it.count }),
                            findStatementsForBranches(
                                lines,
                                env.toLocalPath(file.file),
                                project
                            )
                        )
                    }.associateBy { it.functionName })
                }
            }
        }.map { it.get() }.flatten().associateBy { it.filePath })

    private data class Root(
        @Json(name = "current_working_directory") val currentWorkingDirectory: String,
        @Json(name = "data_file") val dataFile: String,
        @Json(name = "gcc_version") val gccVersion: String,
        val files: List<File>
    )

    private data class File(val file: String, val functions: List<Function>, val lines: List<Line>)

    private data class Function(
        val blocks: Int, @Json(name = "blocks_executed") val blocksExecuted: Long, @Json(name = "demangled_name") val demangledName: String, @Json(
            name = "end_column"
        ) val endColumn: Int, @Json(name = "end_line") val endLine: Int, @Json(name = "execution_count") val executionCount: Long,
        val name: String, @Json(name = "start_column") val startColumn: Int, @Json(name = "start_line") val startLine: Int
    )

    private data class Line(
        val branches: List<Branch>,
        val count: Long, @Json(name = "line_number") val lineNumber: Int, @Json(name = "unexecuted_block") val unexecutedBlock: Boolean, @Json(
            name = "function_name"
        ) val functionName: String = ""
    )

    private data class Branch(val count: Int, val fallthrough: Boolean, @Json(name = "throw") val throwing: Boolean)

    private fun processJson(
        jsonContents: List<String>,
        env: CPPEnvironment,
        project: Project
    ): CoverageData {

        val root = jsonContents.map {
            ApplicationManager.getApplication().executeOnPooledThread<List<File>> {
                Klaxon().maybeParse<Root>(Parser.jackson().parse(StringReader(it)) as JsonObject)?.files
            }
        }.map {
            it.get()
        }.flatten()

        return rooToCoverageData(Root("", "", "", root), env, project)
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
            }.map { it.absolutePath }.toList()

        val processBuilder =
            ProcessBuilder().command(listOf(executable, "-b", "-i", "-m", "-t") + files).redirectErrorStream(true)
        val p = processBuilder.start()
        val lines = p.inputStream.bufferedReader().readLines()
        val retCode = p.waitFor()
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "gcov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${processBuilder.command().joinToString(" ")}\n Stderr: ${lines.joinToString("\n")}",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        files.forEach { Files.deleteIfExists(Paths.get(it)) }

        return processJson(lines, environment, configuration.project)
    }
}

private fun extractVersion(line: String): Triple<Int, Int, Int> {
    val result = "\\d+\\.\\d+\\.\\d+".toRegex().find(line) ?: return Triple(0, 0, 0)
    var value = result.value
    val first = value.substring(0, value.indexOf('.'))
    value = value.removeRange(0, first.length + 1)
    val second = value.substring(0, value.indexOf('.'))
    value = value.removeRange(0, first.length + 1)
    return Triple(first.toInt(), second.toInt(), value.toInt())
}

fun getGeneratorFor(
    executable: String,
    maybeOptionalLLVMProf: String?,
    optionalDemangler: String?
): Pair<CoverageGenerator?, String?> {
    if (executable.isBlank()) {
        return null to "No executable specified"
    }
    if (!Paths.get(executable).exists()) {
        return null to "Executable does not exist"
    }
    val p = ProcessBuilder(executable, "--version").start()
    val lines = p.inputStream.bufferedReader().readLines()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
        return null to "Executable timed out"
    }
    val retCode = p.exitValue()
    if (retCode != 0) {
        val stderrOutput = p.errorStream.bufferedReader().readLines()
        return null to "Executable returned with error code $retCode and error output:\n ${stderrOutput.joinToString("\n")}"
    }
    when {
        lines[0].contains("LLVM", true) -> {
            if (maybeOptionalLLVMProf == null) {
                return null to "No llvm-profdata specified to accompany llvm-cov"
            }
            return LLVMCoverageGenerator(executable, maybeOptionalLLVMProf, optionalDemangler) to null
        }
        lines[0].contains("gcov", true) -> {
            val version = extractVersion(lines[0])
            return if (version.first >= 9) {
                GCCJSONCoverageGenerator(executable) to null
            } else {
                GCCGCDACoverageGenerator(executable, version.first) to null
            }
        }
        else ->
            return null to "Executable identified as neither gcov nor llvm-cov"
    }
}