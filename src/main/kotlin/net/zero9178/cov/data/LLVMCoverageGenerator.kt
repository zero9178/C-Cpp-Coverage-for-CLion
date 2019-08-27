package net.zero9178.cov.data

import com.beust.klaxon.*
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
import com.jetbrains.cidr.cpp.toolchains.CPPToolSet
import com.jetbrains.cidr.lang.psi.*
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

    @Suppress("ConvertCallChainIntoSequence")
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
            ?: return CoverageData(emptyMap())
        log.info("JSON parse took ${System.nanoTime() - jsonStart}ns")

        val mangledNames = root.data.flatMap { data -> data.functions.map { it.name } }
        val demangledNames = demangle(environment, mangledNames)

        return CoverageData(root.data.flatMap { data ->
            //Associates the filename with a list of all functions in that file
            val funcMap =
                data.functions.flatMap { func -> func.filenames.map { it to func } }.groupBy({ it.first }) {
                    it.second
                }

            data.files.map { file ->
                val entries = file.segments.filter { it.isRegionEntry }
                val activeCount = Thread.activeCount()
                CoverageFileData(
                    environment.toLocalPath(file.filename).replace('\\', '/'),
                    funcMap.getOrDefault(
                        file.filename,
                        emptyList()
                    ).chunked(ceil(data.files.size / activeCount.toDouble()).toInt()).map { functions ->
                        ApplicationManager.getApplication().executeOnPooledThread<List<CoverageFunctionData>> {
                            functions.fold(emptyList()) { result, function ->

                                val regions =
                                    function.regions.filter { it.regionKind != Region.GAP && function.filenames[it.fileId] == file.filename }
                                        .fold(
                                            emptyList<Region>()
                                        ) { regionResult, region ->
                                            if (regionResult.isEmpty()) {
                                                regionResult + region
                                            } else {
                                                //regionResult is always an ascending sorted list of regions that do not intersect.
                                                //This means that both starting lines and columns as well as end lines and columns are sorted ascending
                                                val afterIndex =
                                                    regionResult.binarySearchBy(region.end) { it.end }.let {
                                                        //If binary search does not succeed it returns the index where the
                                                        //object should be inserted to remain order in the form of
                                                        // -(index + 1)
                                                        if (it < 0) {
                                                            -it - 1
                                                        } else {
                                                            it
                                                        }
                                                    }

                                                regionResult.slice(0 until afterIndex) + intersectRegions(
                                                    regionResult[afterIndex],
                                                    region
                                                ) + regionResult.slice(afterIndex + 1..regionResult.lastIndex)
                                            }
                                        }

                                if (regions.isEmpty()) {
                                    result
                                } else {
                                    val branches =
                                        regions.filter { region ->
                                            entries.any {
                                                it.pos == region.start
                                            }
                                        }

                                    result + CoverageFunctionData(
                                        regions.first().start.first,
                                        regions.first().end.first,
                                        demangledNames[function.name] ?: function.name,
                                        FunctionRegionData(regions.map { region ->
                                            FunctionRegionData.Region(
                                                region.start,
                                                region.end,
                                                region.executionCount
                                            )
                                        }),
                                        if (CoverageGeneratorSettings.getInstance().branchCoverageEnabled) findStatementsForBranches(
                                            regions.first().start.first to regions.last().end.first,
                                            branches,
                                            regions,
                                            environment.toLocalPath(file.filename),
                                            project
                                        ) else emptyList()
                                    )
                                }
                            }
                        }
                    }.flatMap { it.get() }.associateBy { it.functionName })
            }
        }.associateBy { it.filePath })
    }

    private fun demangle(
        environment: CPPEnvironment,
        mangledNames: List<String>
    ): Map<String, String> {
        return if (myDemangler != null && Paths.get(environment.toLocalPath(myDemangler)).exists()) {
            val p = ProcessBuilder().command(
                (if (environment.toolchain.toolSetKind == CPPToolSet.Kind.WSL) listOf(
                    environment.toolchain.toolSetPath,
                    "run"
                ) else emptyList()) + listOf(myDemangler)
            ).redirectErrorStream(true).start()
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
    }

    private fun intersectRegions(regionOne: Region, regionTwo: Region): List<Region> {
        val result = mutableListOf<Region>()

        val first = if (regionOne.start < regionTwo.start) regionOne else regionTwo
        val second = if (first === regionOne) regionTwo else regionOne

        if (second.start < first.end) {
            //first and second overlap
            if (first.start != second.start) {
                result += Region(
                    first.start,
                    second.start,
                    first.executionCount,
                    first.fileId,
                    first.expandedFileId,
                    first.regionKind
                )
            }
            if (first.end > second.end) {
                //Second is fully inside of first
                result += second
                if (first.end != second.end) {
                    result += Region(
                        second.end,
                        first.end,
                        first.executionCount,
                        first.fileId,
                        first.expandedFileId,
                        first.regionKind
                    )
                }
            } else {
                result += Region(
                    second.start,
                    first.end,
                    first.executionCount,
                    first.fileId,
                    first.expandedFileId,
                    first.regionKind
                )
                result += Region(
                    first.end,
                    second.end,
                    first.executionCount,
                    first.fileId,
                    first.expandedFileId,
                    first.regionKind
                )
            }
        } else {
            result += listOf(first, second)
        }

        return result
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
                    val con = expression.condition
                    val pos =
                        expression.getPositiveExpression(false) ?: return super.visitConditionalExpression(expression)
                    match(con, pos)
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

                    val endLine = document.getLineNumber(element.textRange.endOffset) + 1
                    val endColumn = element.textRange.endOffset - document.getLineStartOffset(endLine - 1) + 1
                    val endPos = endLine toCP endColumn

                    val aboveIndex = allRegions.binarySearch {
                        when {
                            startPos < it.start -> 1
                            startPos > it.end -> -1
                            else -> 0
                        }
                    }
                    val afterIndex =
                        allRegions.binarySearch {
                            when {
                                endPos < it.start -> 1
                                endPos > it.end -> -1
                                else -> 0
                            }
                        }
                    val aboveItem = allRegions.getOrNull(aboveIndex)
                    val afterItem = allRegions.getOrNull(afterIndex)
                    (if (aboveItem == null || startPos !in aboveItem.start..aboveItem.end)
                        null
                    else allRegions[aboveIndex]) to
                            (if (afterItem == null || endPos !in afterItem.start..afterItem.end)
                                null
                            else allRegions[afterIndex])
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
            (if (environment.toolchain.toolSetKind == CPPToolSet.Kind.WSL) listOf(
                environment.toolchain.toolSetPath,
                "run"
            ) else emptyList())
                    + listOf(
                myLLVMProf,
                "merge",
                "-output=${config.target.name}.profdata"
            ) + files.map { environment.toEnvPath(it.absolutePath) }
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
            (if (environment.toolchain.toolSetKind == CPPToolSet.Kind.WSL) listOf(
                environment.toolchain.toolSetPath,
                "run"
            ) else emptyList()) +
                    listOf(
                        myLLVMCov,
                        "export",
                        "-instr-profile",
                        "${config.target.name}.profdata",
                        environment.toEnvPath(config.productFile?.absolutePath ?: "")
                    )
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