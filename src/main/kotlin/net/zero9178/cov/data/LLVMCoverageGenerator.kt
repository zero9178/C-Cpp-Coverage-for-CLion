package net.zero9178.cov.data

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.io.delete
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import com.jetbrains.cidr.cpp.execution.testing.CMakeTestRunConfiguration
import com.jetbrains.cidr.cpp.execution.testing.ctest.CidrCTestRunConfigurationData
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCVisitor
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.ComparablePair
import net.zero9178.cov.util.getCMakeConfigurations
import net.zero9178.cov.util.isCTestInstalled
import net.zero9178.cov.util.toCP
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue

private sealed class Component

private data class RegexComponent(val pattern: Regex) : Component() {
    override fun equals(other: Any?): Boolean {
        if (other !is RegexComponent) {
            return false
        }
        return pattern.pattern == other.pattern.pattern
    }

    override fun hashCode(): Int {
        return pattern.pattern.hashCode()
    }
}

private data class PathComponent(val relFile: File) : Component()

private val LLVM_PROFILE_DIRS = Key<List<List<Component>>>("LLVM_PROFILE_DIRS")
private val PROFILE_INSTR_GENERATE = "-fprofile-instr-generate=?(.+)".toRegex()
private val SUPPORTED_PATTERNS = "%(p|h|c|\\d*m)".toRegex()

class LLVMCoverageGenerator(
    val majorVersion: Int,
    private val myLLVMCov: String,
    private val myLLVMProf: String,
    private val myDemangler: String?
) : CoverageGenerator {

    companion object {
        val log = Logger.getInstance(LLVMCoverageGenerator::class.java)
        const val CODE = 0
        const val EXPANSION = 1
        const val SKIPPED = 2
        const val GAP = 3
    }

    private fun profPatternToRegex(filename: String): Regex {
        var pattern = ""
        var curr = filename
        while (curr.isNotEmpty()) {
            val result = SUPPORTED_PATTERNS.find(curr)
            if (result == null) {
                pattern += Regex.escape(curr)
                curr = ""
                continue
            }
            pattern += Regex.escape(curr.substring(0, result.range.first))
            pattern = when (result.groupValues[1]) {
                "p" ->
                    "$pattern\\d+"
                "h" -> "$pattern.+"
                "c" -> pattern
                else -> {
                    //\\d+m
                    "$pattern\\d+_\\d+"
                }
            }
            curr = curr.substring(result.range.last + 1)
        }
        return pattern.toRegex()
    }

    private fun localPathToComponents(file: File): List<Component> {
        // Filter out the %t directory early. It does not expand to a regex match
        val path = if (file.toString().contains("%t")) {
            Paths.get(file.toString().replace("%t", System.getenv("TMPDIR") ?: ""))
        } else {
            file.toPath()
        }
        return path.fold<Path, List<Component>>(listOf(PathComponent(path.root.toFile()))) { result, curr ->
            when {
                curr.toString().contains(SUPPORTED_PATTERNS) -> {
                    result + RegexComponent(profPatternToRegex(curr.toString()))
                }
                result.lastOrNull() is PathComponent -> {
                    val prev = result.lastOrNull() as PathComponent
                    result.dropLast(1) + PathComponent(prev.relFile.resolve(curr.toFile()))
                }
                else -> {
                    result + PathComponent(curr.toFile())
                }
            }
        }
    }

    private fun toAbsoluteProfileDir(
        file: String,
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        workingDir: File?
    ): List<List<Component>> {
        val asFile = File(environment.toLocalPath(file))
        return if (asFile.isAbsolute) {
            listOf(localPathToComponents(asFile))
        } else {
            if (configuration is CMakeTestRunConfiguration && isCTestInstalled() && configuration.testData is CidrCTestRunConfigurationData) {
                val data = configuration.testData as CidrCTestRunConfigurationData
                data.testListCopy?.mapNotNull {
                    val directory = it.command?.workDirectory ?: it.command?.exePath?.run { File(this).parentFile }
                    directory?.resolve(asFile)?.normalize()?.run {
                        localPathToComponents(this)
                    }
                }?.distinct() ?: emptyList()
            } else {
                val result = workingDir?.resolve(
                    asFile
                )?.normalize()
                if (result == null) {
                    emptyList()
                } else {
                    listOf(localPathToComponents(result))
                }
            }
        }
    }

    override fun patchEnvironment(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: CMakeBuildProfileExecutionTarget,
        cmdLine: GeneralCommandLine,
        context: ConfigurationExtensionContext
    ) {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return
        val workingDirectory = cmdLine.workDirectory ?: config.productFile?.parentFile
        val env = cmdLine.environment["LLVM_PROFILE_FILE"]
        if (env != null) {
            context.putUserData(
                LLVM_PROFILE_DIRS,
                toAbsoluteProfileDir(env, configuration, environment, workingDirectory)
            )
            return
        }
        val paths = getCMakeConfigurations(configuration, executionTarget).flatMap {
            CLanguageKind.values().flatMap { language ->
                it.getCombinedCompilerFlags(language, null).map { param ->
                    val result = PROFILE_INSTR_GENERATE.find(param)
                    if (result != null) {
                        result.groupValues[1]
                    } else {
                        null
                    }
                }
            }
        }.filterNotNull().toList().distinct()
        if (paths.isEmpty()) {
            val toEnvPath = environment.toEnvPath(
                config.configurationGenerationDir.resolve("%4m.profraw").absolutePath
            )
            cmdLine.withEnvironment(
                "LLVM_PROFILE_FILE",
                toEnvPath
            )
            context.putUserData(
                LLVM_PROFILE_DIRS,
                listOf(
                    listOf(
                        PathComponent(config.configurationGenerationDir),
                        RegexComponent("\\d+_\\d+\\.profraw".toRegex())
                    )
                )
            )
            return
        }
        context.putUserData(LLVM_PROFILE_DIRS, paths.flatMap {
            toAbsoluteProfileDir(it, configuration, environment, workingDirectory)
        }.distinct())
    }

    private data class Data(val files: List<String>, val functions: List<Function>)

    private data class Function(
        val name: String,
        val regions: List<Region>,
        val filenames: List<String>,
        val branches: List<Branch>? = null
    )

    private data class Region(
        val start: ComparablePair<Int, Int>,
        val end: ComparablePair<Int, Int>,
        val executionCount: Long, val fileId: Int, val expandedFileId: Int, val regionKind: Int
    )

    private data class Branch(
        val start: ComparablePair<Int, Int>,
        val end: ComparablePair<Int, Int>,
        val executionCount: Long,
        val falseExecutionCount: Long,
        val fileId: Int,
        val expandedFileId: Int,
        val regionKind: Int
    )

    private fun processJson(
        jsonContent: String,
        environment: CPPEnvironment,
        project: Project,
        indicator: ProgressIndicator
    ): CoverageData {
        val jsonParseStart = System.nanoTime()
        val jsonObject = Parser.jackson().parse(StringReader(jsonContent)) as JsonObject
        log.info("JSON parse took ${System.nanoTime() - jsonParseStart}ns")
        val jsonProcessStart = System.nanoTime()
        val data = jsonObject.array<JsonObject>("data")?.mapNotNull {
            val files = it.array<JsonObject>("files")?.mapNotNull { file ->
                file.string("filename")
            }
            val functions = it.array<JsonObject>("functions")?.mapNotNull { function ->
                val name = function.string("name")
                val regions = function.array<JsonArray<*>>("regions")?.map { array ->
                    Region(
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
                val filenames = function.array<String>("filenames")?.toList()
                val branches = function.array<JsonArray<*>>("branches")?.map { array ->
                    Branch(
                        (array[0] as Number).toInt() toCP
                                (array[1] as Number).toInt(),
                        (array[2] as Number).toInt() toCP
                                (array[3] as Number).toInt(),
                        (array[4] as Number).toLong(),
                        (array[5] as Number).toLong(),
                        (array[6] as Number).toInt(),
                        (array[7] as Number).toInt(),
                        (array[8] as Number).toInt()
                    )
                }
                if (name != null && regions != null && filenames != null) {
                    Function(name, regions, filenames, branches)
                } else {
                    if (name != null) {
                        log.warn("JSON fields of $name are malformed")
                    }
                    null
                }
            }
            if (files != null && functions != null) {
                Data(files, functions)
            } else {
                null
            }
        } ?: return CoverageData(emptyMap(), false, CoverageGeneratorSettings.getInstance().calculateExternalSources)

        log.info("JSON processing took ${System.nanoTime() - jsonProcessStart}ns")
        return processRoot(data, environment, project, indicator)
    }

    private fun processRoot(
        datas: List<Data>,
        environment: CPPEnvironment,
        project: Project,
        indicator: ProgressIndicator
    ): CoverageData {
        val mangledNames = datas.flatMap { data -> data.functions.map { it.name } }
        val demangledNames = demangle(environment, mangledNames, indicator)

        val processDataStart = System.nanoTime()
        val sources = CMakeWorkspace.getInstance(project).module?.let { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap {
                it.sourceFolderFiles.toList()
            }.map {
                it.path
            }.toHashSet()
        } ?: emptySet()

        val filesMap = datas.flatMap { data ->
            //Associates the filename with a list of all functions in that file
            val functions =
                data.functions.flatMap { func ->
                    func.filenames.filter {
                        val filePath = environment.toLocalPath(it).replace('\\', '/')
                        CoverageGeneratorSettings.getInstance().calculateExternalSources || sources.any {
                            it == filePath
                        }
                    }.map { it to func }
                }

            if (majorVersion >= 12 || !CoverageGeneratorSettings.getInstance()
                    .branchCoverageEnabled
            ) {
                functions.mapNotNull {
                    processFunction(environment, project, demangledNames, it.first, it.second)?.let { result ->
                        it.first to result
                    }
                }
            } else {
                val queue = ConcurrentLinkedQueue(functions.sortedByDescending {
                    it.second.regions.size
                })
                DumbService.getInstance(project).runReadActionInSmartMode<List<Pair<String, CoverageFunctionData>>> {
                    ProgressManager.checkCanceled()
                    try {
                        (0 until Thread.activeCount()).map {
                            CompletableFuture.supplyAsync {
                                runReadAction {
                                    val list = mutableListOf<Pair<String, CoverageFunctionData>>()
                                    ProgressManager.getInstance().executeProcessUnderProgress({
                                        generateSequence { queue.poll() }.forEach {
                                            processFunction(
                                                environment,
                                                project,
                                                demangledNames,
                                                it.first,
                                                it.second
                                            )?.let { result ->
                                                list.add(it.first to result)
                                            }
                                        }
                                    }, indicator)
                                    list
                                }
                            }
                        }.flatMap { it.join() }
                    } catch (e: CompletionException) {
                        val cause = e.cause
                        if (cause != null) {
                            throw cause
                        } else {
                            throw e
                        }
                    }
                }
            }.groupBy { it.first }.map { entry ->
                CoverageFileData(
                    environment.toLocalPath(entry.key).replace('\\', '/'),
                    entry.value.map { it.second }.associateBy { it.functionName })
            }
        }.associateBy { it.filePath }
        log.info("Processing coverage data took ${System.nanoTime() - processDataStart}ns")
        return CoverageData(
            filesMap,
            CoverageGeneratorSettings.getInstance().branchCoverageEnabled,
            CoverageGeneratorSettings.getInstance().calculateExternalSources
        )
    }

    private fun processFunction(
        environment: CPPEnvironment,
        project: Project,
        demangledNames: Map<String, String>,
        file: String,
        function: Function
    ): CoverageFunctionData? {

        var regions = function.regions.filter {
            function.filenames[it.fileId] == file
        }

        if (regions.isEmpty()) {
            return null
        }

        val functionRegions = regions.map { region ->
            FunctionRegionData.Region(
                region.start,
                region.end,
                region.executionCount,
                when (region.regionKind) {
                    GAP -> FunctionRegionData.Region.Kind.Gap
                    SKIPPED -> FunctionRegionData.Region.Kind.Skipped
                    EXPANSION -> FunctionRegionData.Region.Kind.Expanded
                    else -> FunctionRegionData.Region.Kind.Code
                }
            )
        }

        return CoverageFunctionData(
            regions.first().start,
            regions.first().end,
            demangledNames[function.name] ?: function.name,
            FunctionRegionData(functionRegions),
            if (CoverageGeneratorSettings.getInstance()
                    .branchCoverageEnabled
            )
                if (function.branches == null) {
                    regions = regions.sortedWith { lhs, rhs ->
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
                    }
                    val nonGaps = regions.filter {
                        it.regionKind != GAP
                    }
                    ProgressManager.checkCanceled()
                    findStatementsForBranches(
                        regions.first().start, regions.last().end,
                        nonGaps.toMutableList(),
                        environment.toLocalPath(file),
                        project
                    )
                } else {
                    ProgressManager.checkCanceled()
                    function.branches.filter {
                        function.filenames[it.fileId] == file
                    }.map {
                        CoverageBranchData(
                            it.start,
                            it.executionCount.toInt(),
                            it.falseExecutionCount.toInt()
                        )
                    }
                }
            else emptyList()
        )
    }

    private fun demangle(
        environment: CPPEnvironment,
        mangledNames: List<String>,
        indicator: ProgressIndicator
    ): Map<String, String> {
        if (mangledNames.isEmpty()) {
            return emptyMap()
        }
        return if (myDemangler != null) {
            val demangleStart = System.nanoTime()
            val isUndname = myDemangler.contains("undname")
            val tempFile = Files.createTempFile(environment.hostMachine.tempDirectory, null, null)
            Files.write(tempFile, mangledNames.joinToString(" ") {
                if (it.matches("^(_{1,3}Z|\\?).*".toRegex())) it else it.substringAfter(':')
            }.toByteArray())

            val commandLine = listOf(myDemangler) + if (isUndname) {
                listOf("--no-calling-convention", "@${tempFile.fileName}")
            } else {
                listOf("-n", "@${tempFile.fileName}")
            }

            val p = environment.hostMachine.runProcess(
                GeneralCommandLine(
                    commandLine
                ).withRedirectErrorStream(
                    true
                ).withWorkDirectory(tempFile.parent.toString()),
                indicator,
                -1
            )
            indicator.checkCanceled()
            var result = p.stdoutLines
            tempFile.delete()
            result = if (!isUndname) {
                result
            } else {
                result.chunked(2).map { current ->
                    if (current.size < 2) {
                        current[0]
                    } else {
                        val (orig, demangled) = current
                        if (demangled.contains("Invalid mangled name")) {
                            orig
                        } else {
                            demangled
                        }
                    }
                }
            }
            log.info("Demangling took ${System.nanoTime() - demangleStart}ns")
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
        val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return emptyList()
        val document =
            PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return emptyList()

        val startOffset = if (functionStart.first - 1 >= document.lineCount) {
            document.getLineEndOffset(document.lineCount - 1)
        } else {
            document.getLineStartOffset(functionStart.first - 1) + functionStart.second - 1
        }
        val endOffset = if (functionEnd.first - 1 >= document.lineCount) {
            document.getLineEndOffset(document.lineCount - 1)
        } else {
            document.getLineStartOffset(functionEnd.first - 1) + functionEnd.second - 1
        }
        val range = TextRange(startOffset, endOffset)

        val branches = mutableListOf<CoverageBranchData>()

        object : OCVisitor(), PsiRecursiveVisitor {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
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
                matchThenElse(quest.textOffset, pos, neg)
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
                try {
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
                        regions.removeAll(regions.slice(0..conIndex).toSet())
                    }
                    return result
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e)
                    return null
                }
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
                elseBranch: OCElement
            ) {
                val thenRegion = find(thenBranch, false) ?: return
                val elseRegion = find(elseBranch, false) ?: return

                val lineNumber = document.getLineNumber(offset)
                branches += CoverageBranchData(
                    lineNumber + 1 toCP offset - document.getLineStartOffset(lineNumber) + 1,
                    thenRegion.executionCount.toInt(),
                    elseRegion.executionCount.toInt()
                )
            }
        }.visitElement(psiFile)
        return branches
    }

    override fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: CMakeBuildProfileExecutionTarget,
        indicator: ProgressIndicator,
        context: ConfigurationExtensionContext
    ): CoverageData? {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return null
        val configurationGenerationDir = config.configurationGenerationDir

        val files = context.getUserData(LLVM_PROFILE_DIRS)?.filter { it.isNotEmpty() }?.flatMap { components ->
            val initial = when (val first = components.first()) {
                is PathComponent ->
                    listOf(first.relFile)
                is RegexComponent ->
                    // Kinda screwed in this case. No choice but to pick a root
                    File.listRoots().firstOrNull()?.listFiles()?.filter { it.name.matches(first.pattern) }
                        ?: emptyList()
            }
            components.drop(1).fold(initial) { result, curr ->
                when (curr) {
                    is PathComponent ->
                        result.map {
                            it.resolve(curr.relFile)
                        }
                    is RegexComponent ->
                        result.flatMap {
                            it.listFiles()?.filter { file -> file.name.matches(curr.pattern) } ?: emptyList()
                        }
                }
            }
        }?.distinct() ?: emptyList()

        val tempFile = Files.createTempFile(configurationGenerationDir.toPath(), null, ".profdata")
        val profdataStart = System.nanoTime()
        val p = environment.hostMachine.runProcess(
            GeneralCommandLine(
                listOf(
                    myLLVMProf,
                    "merge",
                    "-output=${tempFile}"
                ) + files.map { environment.toEnvPath(it.absolutePath) })
                .withWorkDirectory(environment.toEnvPath(configurationGenerationDir.absolutePath)),
            indicator,
            -1
        )
        indicator.checkCanceled()
        if (p.exitCode != 0) {
            NotificationGroupManager.getInstance().getNotificationGroup("C/C++ Coverage Notification")
                .createNotification(
                    "llvm-profdata returned error code ${p.exitCode} with error output:\n${
                        p.stderr
                    }",
                    NotificationType.ERROR
                ).notify(configuration.project)
            return null
        }
        log.info("LLVM profdata took ${System.nanoTime() - profdataStart}ns")

        files.forEach { it.delete() }

        val executable =
            if (!isCTestInstalled()
                || configuration !is CMakeTestRunConfiguration
            ) {
                listOf(environment.toEnvPath(config.productFile?.absolutePath ?: ""))
            } else {
                val testData = configuration.testData
                if (testData !is CidrCTestRunConfigurationData) {
                    listOf(environment.toEnvPath(config.productFile?.absolutePath ?: ""))
                } else {
                    testData.testListCopy?.mapNotNull {
                        it?.command?.exePath
                    }?.distinct() ?: emptyList()
                }
            }

        if (executable.isEmpty()) {
            return null
        }

        val covStart = System.nanoTime()
        val input = listOf(
            myLLVMCov,
            "export",
            "-instr-profile",
            "$tempFile",
            executable.first()
        ) + executable.flatMap {
            listOf("-object", it)
        }
        val llvmCov = environment.hostMachine.runProcess(
            GeneralCommandLine(input).withWorkDirectory(
                environment.toEnvPath(
                    configurationGenerationDir.absolutePath
                )
            ),
            indicator,
            -1
        )
        tempFile.delete()
        indicator.checkCanceled()
        val result = llvmCov.stdout
        if (llvmCov.exitCode != 0) {
            NotificationGroupManager.getInstance().getNotificationGroup("C/C++ Coverage Notification")
                .createNotification(
                    "llvm-cov returned error code ${llvmCov.exitCode}",
                    "Invocation: ${input.joinToString(" ")}\n Stderr: ${llvmCov.stderr}",
                    NotificationType.ERROR
                ).setSubtitle("Invocation and error output:").notify(configuration.project)
            return null
        }
        log.info("LLVM cov took ${System.nanoTime() - covStart}ns")

        return processJson(result, environment, configuration.project, indicator)
    }
}