package net.zero9178.cov.data

import com.beust.klaxon.*
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCVisitor
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.ComparablePair
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

class LLVMCoverageGenerator(
    private val myLLVMCov: String,
    private val myLLVMProf: String,
    private val myDemangler: String?
) : CoverageGenerator {

    companion object {
        val log = Logger.getInstance(LLVMCoverageGenerator::class.java)
    }

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
            environment.toEnvPath(
                config.configurationGenerationDir.resolve("${config.target.name}-%p.profraw").absolutePath
            )
        )
    }

    private data class Root(val data: List<Data>, val version: String, val type: String)

    private data class Data(val files: List<File>, val functions: List<Function>)

    private data class File(val filename: String)

    private data class Function(
        val name: String,
        val count: Long,
        val regions: List<Region>,
        val filenames: List<String>
    )

    private data class Region(
        val start: ComparablePair<Int, Int>,
        val end: ComparablePair<Int, Int>,
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
        val jsonStart = System.nanoTime()
        val root = Klaxon().converter(object : Converter {
            override fun canConvert(cls: Class<*>) = cls == Region::class.java

            override fun fromJson(jv: JsonValue): Any? {
                val array = jv.array ?: return null
                return Region(
                    (array[0] as Number).toInt() toCP
                            (array[1] as Number).toInt(),
                    (array[2] as Number).toInt() toCP
                            (array[3] as Number).toInt(),
                    (array[4] as Number).toLong(),
                    (array[5] as Number).toInt(),
                    (array[6] as Number).toInt(),
                    (array[7] as Number).toInt()
                )
            }

            override fun toJson(value: Any): String {
                val region = value as? Region ?: return ""
                return "[${region.start.first},${region.start.second},${region.start.first},${region.start.second}," +
                        "${region.executionCount},${region.fileId},${region.expandedFileId},${region.regionKind}]"
            }
        }).maybeParse<Root>(Parser.jackson().parse(StringReader(jsonContent)) as JsonObject)
            ?: return CoverageData(emptyMap(), false, CoverageGeneratorSettings.getInstance().calculateExternalSources)
        log.info("JSON parse took ${System.nanoTime() - jsonStart}ns")

        return processRoot(root, environment, project)
    }

    private fun processRoot(
        root: Root,
        environment: CPPEnvironment,
        project: Project
    ): CoverageData {
        val mangledNames = root.data.flatMap { data -> data.functions.map { it.name } }
        val demangledNames = demangle(environment, mangledNames)

        val sources = CMakeWorkspace.getInstance(project).module?.let { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap {
                it.sourceFolderFiles.toList()
            }.map {
                it.path
            }.toHashSet()
        } ?: emptySet<String>()

        val filesMap = root.data.flatMap { data ->
            //Associates the filename with a list of all functions in that file
            val funcMap =
                data.functions.flatMap { func ->
                    func.filenames.map { it to func }
                }.groupBy({ it.first }) {
                    it.second
                }

            data.files.fold(emptyList<CoverageFileData>()) fileFold@{ result, file ->

                val filePath = environment.toLocalPath(file.filename).replace('\\', '/')
                if (!CoverageGeneratorSettings.getInstance().calculateExternalSources && !sources.any {
                        it == filePath
                    }) {
                    return@fileFold result
                }

                val activeCount = Thread.activeCount()
                val functionsMap = funcMap.getOrDefault(
                    file.filename,
                    emptyList()
                ).chunked(ceil(data.files.size / activeCount.toDouble()).toInt()).map { functions ->
                    CompletableFuture.supplyAsync<List<CoverageFunctionData>> {
                        functions.fold(emptyList()) { result, function ->

                            val regions = function.regions.filter {
                                function.filenames[it.fileId] == file.filename
                            }.sortedWith(Comparator { lhs, rhs ->
                                when {
                                    lhs.start != rhs.start -> {
                                        lhs.start.compareTo(rhs.start)
                                    }
                                    lhs.end != rhs.end -> {
                                        lhs.end.compareTo(rhs.end)
                                    }
                                    else -> {
                                        lhs.regionKind.compareTo(rhs.regionKind)
                                    }
                                }
                            })

                            val nonGaps = regions.filter {
                                it.regionKind != Region.GAP
                            }

                            if (regions.isEmpty()) {
                                return@fold result
                            }

                            val functionRegions = regions.map { region ->
                                FunctionRegionData.Region(
                                    region.start,
                                    region.end,
                                    region.executionCount,
                                    when (region.regionKind) {
                                        Region.GAP -> FunctionRegionData.Region.Kind.Gap
                                        Region.SKIPPED -> FunctionRegionData.Region.Kind.Skipped
                                        Region.EXPANSION -> FunctionRegionData.Region.Kind.Expanded
                                        else -> FunctionRegionData.Region.Kind.Code
                                    }
                                )
                            }

                            result + CoverageFunctionData(
                                regions.first().start.first,
                                regions.first().end.first,
                                demangledNames[function.name] ?: function.name,
                                FunctionRegionData(functionRegions),
                                if (CoverageGeneratorSettings.getInstance()
                                        .branchCoverageEnabled
                                )
                                    findStatementsForBranches(
                                        regions.first().start, regions.last().end,
                                        nonGaps.toMutableList(),
                                        environment.toLocalPath(file.filename),
                                        project
                                    ) else emptyList()
                            )
                        }
                    }
                }.flatMap { it.get() }.associateBy { it.functionName }

                result + CoverageFileData(
                    filePath,
                    functionsMap
                )

            }
        }.associateBy { it.filePath }
        return CoverageData(
            filesMap,
            CoverageGeneratorSettings.getInstance().branchCoverageEnabled,
            CoverageGeneratorSettings.getInstance().calculateExternalSources
        )
    }

    private fun demangle(
        environment: CPPEnvironment,
        mangledNames: List<String>
    ): Map<String, String> {
        if (mangledNames.isEmpty()) {
            return emptyMap()
        }
        return if (myDemangler != null) {
            val isUndname = myDemangler.contains("undname")
            val p = environment.hostMachine.createProcess(
                GeneralCommandLine(listOf(myDemangler) + if (isUndname) listOf("--no-calling-convention") else emptyList()).withRedirectErrorStream(
                    true
                ),
                false,
                false
            )

            var isFirst = true
            var result = listOf<String>()
            p.process.outputStream.bufferedWriter().use { writer ->
                p.process.inputStream.bufferedReader().use { reader ->
                    result = mangledNames.map { mangled ->
                        val input =
                            if (mangled.matches("^(_{1,3}Z|\\?).*".toRegex())) mangled else mangled.substringAfter(':')
                        writer.appendln(input)
                        writer.flush()
                        if (isUndname) {
                            if (!isFirst) {
                                reader.readLine()
                            } else {
                                isFirst = false
                            }
                            val echo = reader.readLine()
                            assert(echo == input)
                        }
                        val output: String? = reader.readLine()
                        if (output == "error: Invalid mangled name") {
                            return@map input
                        }
                        output ?: input
                    }
                }
            }
            p.destroyProcess()
            mangledNames.zip(result).associate { it }
        } else {
            mangledNames.associateBy { it }
        }
    }

    private fun findStatementsForBranches(
        functionStart: ComparablePair<Int, Int>,
        functionEnd: ComparablePair<Int, Int>,
        regions: MutableList<Region>,
        file: String,
        project: Project
    ): List<CoverageBranchData> {
        if (regions.isEmpty()) {
            return emptyList()
        }
        return DumbService.getInstance(project).runReadActionInSmartMode<List<CoverageBranchData>> {
            val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadActionInSmartMode emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadActionInSmartMode emptyList()
            val document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadActionInSmartMode emptyList()

            val range = TextRange(
                document.getLineStartOffset(functionStart.first - 1) + functionStart.second - 1,
                document.getLineStartOffset(functionEnd.first - 1) + functionEnd.second - 1
            )

            val branches = mutableListOf<CoverageBranchData>()

            object : OCVisitor(), PsiRecursiveVisitor {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    var curr: PsiElement? = element.firstChild
                    while (curr != null) {
                        val textRange = curr.textRange
                        if (range.contains(textRange)) {
                            curr.accept(this)
                        } else if (range.intersects(textRange)) {
                            visitElement(curr)
                        }
                        curr = curr.nextSibling
                    }
                }

                override fun visitIfStatement(stmt: OCIfStatement?) {
                    stmt ?: return
                    stmt.initStatement?.accept(this)
                    stmt.condition?.accept(this)
                    try {
                        if (!CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled) {
                            return
                        }
                        val expression = stmt.condition?.expression ?: return
                        val body = stmt.thenBranch ?: return
                        matchCondThen(stmt.lParenth?.startOffset ?: stmt.textOffset, expression, body)
                    } finally {
                        stmt.thenBranch?.accept(this)
                        stmt.elseBranch?.accept(this)
                    }
                }

                override fun visitConditionalExpression(expression: OCConditionalExpression?) {
                    expression ?: return super.visitConditionalExpression(expression)
                    if (!CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled) {
                        return super.visitConditionalExpression(expression)
                    }
                    val pos =
                        expression.getPositiveExpression(false) ?: return super.visitConditionalExpression(expression)
                    val neg = expression.negativeExpression ?: return super.visitConditionalExpression(expression)
                    val quest = PsiTreeUtil.findSiblingForward(expression.condition, OCTokenTypes.QUEST, null)
                        ?: return super.visitConditionalExpression(expression)
                    matchThenElse(quest.textOffset, pos, neg, false)
                    super.visitConditionalExpression(expression)
                }

                override fun visitBinaryExpression(expression: OCBinaryExpression?) {
                    expression ?: return super.visitBinaryExpression(expression)
                    if (!CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled) {
                        return super.visitBinaryExpression(expression)
                    }
                    when (expression.operationSignNode.text) {
                        "||", "or", "&&", "and" -> {
                            val left = expression.left ?: return
                            val right = expression.right ?: return
                            matchCondThen(expression.operationSignNode.startOffset, left, right, false)
                        }
                    }
                    super.visitBinaryExpression(expression)
                }

                override fun visitLambdaExpression(lambdaExpression: OCLambdaExpression?) {
                    return
                }

                private fun find(element: OCElement, removeRegions: Boolean): Region? {
                    val startLine = document.getLineNumber(element.textOffset) + 1
                    val startColumn = element.textOffset - document.getLineStartOffset(startLine - 1) + 1
                    val startPos = startLine toCP startColumn
                    val endLine = document.getLineNumber(element.textRange.endOffset) + 1
                    val endColumn = element.textRange.endOffset - document.getLineStartOffset(endLine - 1) + 1
                    val endPos = endLine toCP endColumn
                    val conIndex = regions.indexOfFirst {
                        it.start == startPos && it.end == endPos
                    }
                    if (conIndex < 0) {
                        //log.warn("Could not find Region that starts at $startPos to $endPos")
                        return null
                    }
                    val result = regions[conIndex]
                    if (removeRegions) {
                        regions.removeAll(regions.slice(0..conIndex))
                    }
                    return result
                }

                private fun matchCondThen(
                    offset: Int,
                    condition: OCElement,
                    body: OCElement,
                    removeRegions: Boolean = true
                ) {
                    val conRegion = find(condition, removeRegions) ?: return
                    val bodyRegion = find(body, removeRegions) ?: return

                    val lineNumber = document.getLineNumber(offset)
                    branches += CoverageBranchData(
                        lineNumber + 1 toCP offset - document.getLineStartOffset(lineNumber) + 1,
                        bodyRegion.executionCount.toInt(),
                        (conRegion.executionCount - bodyRegion.executionCount).toInt()
                    )
                }

                private fun matchThenElse(
                    offset: Int,
                    thenBranch: OCElement,
                    elseBranch: OCElement,
                    removeRegions: Boolean = true
                ) {
                    val thenRegion = find(thenBranch, removeRegions) ?: return
                    val elseRegion = find(elseBranch, removeRegions) ?: return

                    val lineNumber = document.getLineNumber(offset)
                    branches += CoverageBranchData(
                        lineNumber + 1 toCP offset - document.getLineStartOffset(lineNumber) + 1,
                        thenRegion.executionCount.toInt(),
                        elseRegion.executionCount.toInt()
                    )
                }
            }.visitElement(psiFile)
            branches
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

        val p = environment.hostMachine.createProcess(
            GeneralCommandLine(listOf(
                myLLVMProf,
                "merge",
                "-output=${config.target.name}.profdata"
            ) + files.map { environment.toEnvPath(it.absolutePath) })
                .withWorkDirectory(environment.toEnvPath(config.configurationGenerationDir.absolutePath)),
            false,
            false
        )
        var retCode = p.process.waitFor()
        var lines = p.process.errorStream.bufferedReader().readLines()
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

        val input = listOf(
            myLLVMCov,
            "export",
            "-instr-profile",
            "${config.target.name}.profdata",
            environment.toEnvPath(config.productFile?.absolutePath ?: "")
        )
        val llvmCov = environment.hostMachine.runProcess(
            GeneralCommandLine(input).withWorkDirectory(
                environment.toEnvPath(
                    config.configurationGenerationDir.absolutePath
                )
            ),
            null,
            -1
        )
        lines = llvmCov.stdoutLines
        retCode = llvmCov.exitCode
        if (retCode != 0) {
            val errorOutput = llvmCov.stderrLines
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "llvm-cov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${input.joinToString(" ")}\n Stderr: $errorOutput",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        return processJson(lines.joinToString(), environment, configuration.project)
    }
}