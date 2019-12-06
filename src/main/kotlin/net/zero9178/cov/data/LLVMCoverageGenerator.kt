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
import com.intellij.psi.tree.IElementType
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import com.jetbrains.cidr.system.RemoteUtil
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.ComparablePair
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Files
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
            environment.toEnvPath(config.configurationGenerationDir.resolve("${config.target.name}-%p.profraw").absolutePath)
        )
    }

    private data class Root(val data: List<Data>, val version: String, val type: String)

    private data class Data(val files: List<File>, val functions: List<Function>)

    private data class File(val filename: String, val segments: List<Segment>)

    private data class Segment(
        val pos: ComparablePair<Int, Int>,
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
        val root = Klaxon()
            .converter(object : Converter {
                override fun canConvert(cls: Class<*>) = cls == Segment::class.java

                override fun fromJson(jv: JsonValue): Any? {
                    val array = jv.array ?: return null
                    return Segment(
                        (array[0] as Number).toInt() toCP
                                (array[1] as Number).toInt(),
                        (array[2] as Number).toLong(),
                        if (array[3] is Boolean) array[3] as Boolean else array[3] as Number != 0,
                        if (array[4] is Boolean) array[4] as Boolean else array[4] as Number != 0
                    )
                }

                override fun toJson(value: Any): String {
                    val segment = value as? Segment ?: return ""
                    return "[${segment.pos.first},${segment.pos.second},${segment.count},${segment.hasCount},${segment.isRegionEntry}]"
                }
            }).converter(object : Converter {
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

        val mangledNames = root.data.flatMap { data -> data.functions.map { it.name } }
        val demangledNames = demangle(environment, mangledNames)

        val sources = CMakeWorkspace.getInstance(project).module?.let { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap {
                it.sourceFolderFiles.toList()
            }
        }

        return CoverageData(
            root.data.flatMap { data ->
                //Associates the filename with a list of all functions in that file
                val funcMap =
                    data.functions.flatMap { func -> func.filenames.map { it to func } }.groupBy({ it.first }) {
                        it.second
                    }

                data.files.fold(emptyList<CoverageFileData>()) fileFold@{ result, file ->

                    val filePath = environment.toLocalPath(file.filename).replace('\\', '/')
                    if (!CoverageGeneratorSettings.getInstance().calculateExternalSources && sources?.any {
                            it.path == filePath
                        } == false) {
                        return@fileFold result
                    }
                    val activeCount = Thread.activeCount()
                    result + CoverageFileData(
                        filePath,
                        funcMap.getOrDefault(
                            file.filename,
                            emptyList()
                        ).chunked(ceil(data.files.size / activeCount.toDouble()).toInt()).map { functions ->
                            CompletableFuture.supplyAsync<List<CoverageFunctionData>> {
                                val segments = file.segments.toMutableList()
                                functions.fold(emptyList()) { result, function ->

                                    val filter =
                                        function.regions.firstOrNull { it.regionKind != Region.GAP && function.filenames[it.fileId] == file.filename }
                                            ?: return@fold result
                                    val funcStart = segments.binarySearchBy(filter.start) {
                                        it.pos
                                    }
                                    if (funcStart < 0) {
                                        val insertionPoint = -funcStart + 1
                                        log.warn(
                                            "Function start ${function.name} could not be found in segments. Searched pos: ${filter.start}. Nearest Indices: ${segments.getOrNull(
                                                insertionPoint
                                            )},${segments.getOrNull(insertionPoint + 1)}"
                                        )
                                        return@fold result
                                    }
                                    val gapsPos =
                                        function.regions.filter { it.regionKind == Region.GAP && function.filenames[it.fileId] == file.filename }
                                            .map { it.start }.toHashSet()
                                    val branches = mutableListOf<Region>()
                                    var funcEnd = segments.binarySearchBy(
                                        filter.end,
                                        funcStart
                                    ) {
                                        it.pos
                                    }
                                    if (funcEnd < 0) {
                                        funcEnd = -funcEnd - 2
                                    }

                                    val window = segments.slice(
                                        funcStart..funcEnd
                                    ).windowed(2) { (first, second) ->
                                        val new = Region(first.pos, second.pos, first.count, 0, 0, Region.CODE)
                                        if (first.isRegionEntry) {
                                            branches += new
                                        }
                                        new
                                    }
                                    val nonGaps = mutableListOf<Region>()
                                    val regions =
                                        window.mapIndexed { index, region ->
                                            if (!gapsPos.contains(region.start)) {
                                                nonGaps += region
                                                region
                                            } else {
                                                //I could leave out the gaps but this makes it look kinda ugly and harder to
                                                //look at IMO. Might introduce a setting later on for those that want Gaps
                                                //to be, well, gaps but for now am just gonna keep it like that making it
                                                //look identical to when it used to work with regions
                                                val count =
                                                    (if (index == 0) 0L else window[index - 1].executionCount) + if (index + 1 > window.lastIndex) 0L else window[index + 1].executionCount
                                                Region(
                                                    region.start,
                                                    region.end,
                                                    count,
                                                    region.fileId,
                                                    region.expandedFileId,
                                                    Region.GAP
                                                )
                                            }
                                        }

                                    if (funcEnd > funcStart && segments.size <= funcEnd + 1) {
                                        segments.subList(funcStart, funcEnd + 1).clear()
                                    }

                                    if (regions.isEmpty()) {
                                        result
                                    } else {
                                        result + CoverageFunctionData(
                                            regions.first().start.first,
                                            regions.first().end.first,
                                            demangledNames[function.name] ?: function.name,
                                            FunctionRegionData(regions.map { region ->
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
                                            }),
                                            if (CoverageGeneratorSettings.getInstance().branchCoverageEnabled) findStatementsForBranches(
                                                regions.first().start.first to regions.last().end.first,
                                                branches,
                                                nonGaps,
                                                environment.toLocalPath(file.filename),
                                                project
                                            ) else emptyList()
                                        )
                                    }
                                }
                            }
                        }.flatMap { it.get() }.associateBy { it.functionName })
                }
            }.associateBy { it.filePath },
            CoverageGeneratorSettings.getInstance().branchCoverageEnabled,
            CoverageGeneratorSettings.getInstance().calculateExternalSources
        )
    }

    private fun demangle(
        environment: CPPEnvironment,
        mangledNames: List<String>
    ): Map<String, String> {
        return if (myDemangler != null) {
            val p = environment.hostMachine.createProcess(
                GeneralCommandLine(myDemangler).withRedirectErrorStream(true),
                false,
                false
            )

            var result = listOf<String>()
            p.process.outputStream.bufferedWriter().use { writer ->
                p.process.inputStream.bufferedReader().use { reader ->
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
            p.destroyProcess()
            mangledNames.zip(result).associate { it }
        } else {
            mangledNames.associateBy { it }
        }
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

            val branches = mutableListOf<Pair<Region, OCElement>>()

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
                    match(loop, body)
                    super.visitLoopStatement(loop)
                }

                override fun visitIfStatement(stmt: OCIfStatement?) {
                    stmt ?: return super.visitIfStatement(stmt)
                    if (!CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled) {
                        return super.visitIfStatement(stmt)
                    }
                    val body = stmt.thenBranch ?: return super.visitIfStatement(stmt)
                    match(stmt, body)
                    super.visitIfStatement(stmt)
                }

                override fun visitConditionalExpression(expression: OCConditionalExpression?) {
                    expression ?: return super.visitConditionalExpression(expression)
                    if (!CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled) {
                        return super.visitConditionalExpression(expression)
                    }
                    val con = expression.condition
                    val pos =
                        expression.getPositiveExpression(false) ?: return super.visitConditionalExpression(expression)
                    match(con, pos)
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
                            match(left, right)
                        }
                    }
                    super.visitBinaryExpression(expression)
                }

                private fun match(parent: OCElement, body: OCElement) {
                    val regionIndex = regionEntries.binarySearchBy(body.textOffset) {
                        document.getLineStartOffset(it.start.first - 1) + it.start.second - 1
                    }
                    if (regionIndex < 0) {
                        return
                    }
                    branches += regionEntries[regionIndex] to parent
                }
            }.visitElement(psiFile)

            branches.fold(emptyList()) { list, (region, element) ->

                val (above, after) = {
                    val startLine = document.getLineNumber(element.textOffset) + 1
                    val startColumn = element.textOffset - document.getLineStartOffset(startLine - 1) + 1
                    val startPos = startLine toCP startColumn

                    val aboveIndex = allRegions.binarySearch {
                        when {
                            startPos < it.start -> 1
                            startPos > it.end -> -1
                            else -> 0
                        }
                    }
                    val aboveItem = allRegions.getOrNull(aboveIndex)
                    (if (aboveItem == null || startPos !in aboveItem.start..aboveItem.end)
                        null
                    else allRegions[aboveIndex]) to
                            {
                                //As after is only needed for loops we implement it with lazy calculation
                                val endLine = document.getLineNumber(element.textRange.endOffset) + 1
                                val endColumn =
                                    element.textRange.endOffset - document.getLineStartOffset(endLine - 1) + 1
                                val endPos = endLine toCP endColumn
                                val afterIndex =
                                    allRegions.binarySearch {
                                        when {
                                            endPos < it.start -> 1
                                            endPos > it.end -> -1
                                            else -> 0
                                        }
                                    }
                                val afterItem = allRegions.getOrNull(afterIndex)
                                (if (afterItem == null || endPos !in afterItem.start..afterItem.end)
                                    null
                                else allRegions[afterIndex])
                            }
                }()

                if (above == null) {
                    list
                } else {

                    fun findSiblingForward(
                        element: PsiElement,
                        vararg elementTypes: IElementType
                    ): PsiElement? {
                        var e: PsiElement? = element.nextSibling
                        while (e != null) {
                            if (elementTypes.contains(e.node.elementType)) {
                                return e
                            }
                            e = e.nextSibling
                        }
                        return null
                    }

                    val startLine = when (element) {
                        is OCLoopStatement -> document.getLineNumber(
                            element.lParenth?.textRange?.endOffset ?: element.firstChild.textRange.endOffset
                        ) + 1
                        is OCIfStatement -> document.getLineNumber(
                            element.lParenth?.textRange?.endOffset ?: element.firstChild.textRange.endOffset
                        ) + 1
                        is OCExpression -> document.getLineNumber(
                            findSiblingForward(
                                element,
                                OCTokenTypes.ANDAND,
                                OCTokenTypes.OROR,
                                OCTokenTypes.QUEST
                            )?.textRange?.endOffset ?: element.lastChild.textRange.endOffset
                        ) + 1
                        else -> document.getLineNumber(element.firstChild.textRange.endOffset) + 1
                    }

                    val startColumn = when (element) {
                        is OCLoopStatement -> (element.lParenth?.textRange?.startOffset
                            ?: element.firstChild.textRange.startOffset) - document.getLineStartOffset(startLine - 1) + 1
                        is OCIfStatement -> (element.lParenth?.textRange?.startOffset
                            ?: element.firstChild.textRange.startOffset) - document.getLineStartOffset(startLine - 1) + 1
                        is OCExpression -> (findSiblingForward(
                            element,
                            OCTokenTypes.ANDAND,
                            OCTokenTypes.OROR,
                            OCTokenTypes.QUEST
                        )?.textRange?.endOffset
                            ?: element.lastChild.textRange.endOffset) - document.getLineStartOffset(startLine - 1) + 1
                        else -> element.firstChild.textRange.startOffset - document.getLineStartOffset(startLine - 1) + 1
                    }

                    list + CoverageBranchData(
                        startLine toCP startColumn, region.executionCount.toInt(),
                        (if (above.executionCount >= region.executionCount) above.executionCount - region.executionCount
                        else after()?.executionCount ?: 1).toInt()
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

        val hostMachine = environment.hostMachine
        val remotePath = hostMachine.getPath(config.configurationGenerationDir.absolutePath)
        val dir = hostMachine.resolvePath(remotePath)
        val files = dir.listFiles()
            ?.filter { it.name.matches("${config.target.name}-\\d*.profraw".toRegex()) } ?: emptyList()

        val p = environment.hostMachine.runProcess(
            GeneralCommandLine(listOf(
                myLLVMProf,
                "merge",
                "-output=${config.target.name}.profdata"
            ) + files.map { environment.toEnvPath(it.absolutePath) })
                .withWorkDirectory(config.configurationGenerationDir),
            null,
            -1
        )
        var retCode = p.exitCode
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "llvm-profdata returned error code $retCode with error output:\n${p.stderrLines.joinToString(
                    "\n"
                )}", NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        val remoteCredentials = environment.toolchain.remoteCredentials
        files.forEach {
            if (hostMachine.isRemote) {
                if (remoteCredentials != null) {
                    RemoteUtil.rm(remoteCredentials, environment.toEnvPath(dir.resolve(it).toString()))
                }
            } else {
                Files.deleteIfExists(dir.resolve(it).toPath())
            }
        }

        val input = listOf(
            myLLVMCov,
            "export",
            "-instr-profile",
            "${config.target.name}.profdata",
            environment.toEnvPath(config.productFile?.absolutePath ?: "")
        )
        val llvmCov = environment.hostMachine.runProcess(
            GeneralCommandLine(input).withWorkDirectory(config.configurationGenerationDir),
            null,
            -1
        )
        val lines = llvmCov.stdoutLines
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