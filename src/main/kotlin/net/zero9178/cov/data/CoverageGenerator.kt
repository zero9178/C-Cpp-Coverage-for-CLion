package net.zero9178.cov.data

import com.beust.klaxon.Converter
import com.beust.klaxon.Json
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.psi.OCForStatement
import com.jetbrains.cidr.lang.psi.OCIfStatement
import com.jetbrains.cidr.lang.psi.OCLoopStatement
import com.jetbrains.cidr.lang.psi.OCStatement
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import net.zero9178.cov.notification.CoverageNotification
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

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

private class LLVMCoverageGenerator(override val executable: String, val llvmProf: String) : CoverageGenerator {

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

    private operator fun <A : Comparable<A>, B : Comparable<B>> Pair<A, B>.compareTo(pair: Pair<A, B>): Int {
        val result = first.compareTo(pair.first)
        return if (result != 0) {
            result
        } else {
            second.compareTo(pair.second)
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
            }).parse<Root>(jsonContent) ?: return CoverageData(emptyMap())

        return CoverageData(root.data.map { data ->
            data.files.map { file ->
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
                            function.name,
                            FunctionRegionData(regions.map { region ->
                                FunctionRegionData.Region(
                                    region.lineStart to region.columnStart,
                                    region.lineEnd to region.columnEnd,
                                    region.executionCount
                                )
                            }),
                            findStatementsForBranches(
                                branches,
                                regions,
                                environment.toLocalPath(file.filename),
                                project
                            )
                        )
                    }.associateBy { it.functionName })
            }
        }.flatten().associateBy { it.filePath })
    }

    private fun findStatementsForBranches(
        regionEntries: List<Region>,
        allRegions: List<Region>,
        file: String,
        project: Project
    ): List<CoverageBranchData> {
        if (regionEntries.isEmpty()) {
            return emptyList()
        }
        return ApplicationManager.getApplication().runReadAction<List<CoverageBranchData>> {
            val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadAction emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadAction emptyList()
            val document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction emptyList()
            /**
             * We must filter out the increment expression in a for loop. This is due to it not actually being executed as
             * many times as the containing statement.
             *
             * Consider the case:
             * for(int i = 0; i < 5; i++) {
             *      if(i > 2) {
             *          ...
             *      }
             *      break;
             * }
             *
             * Due to the break the i++ is never executed yet the if was giving us a false delta and therefore false
             * branch coverage.
             * This makes a lot of sense if one turns a for into an equivalent while aka:
             * {
             *      int i = 0;
             *      while(i < 5) {
             *          if( i > 2) {
             *              ...
             *          }
             *          break;
             *          i++;
             *      }
             * }
             *
             */
            val forIncrements = mutableListOf<OCStatement>()
            val branches = mutableListOf<Pair<Region, OCStatement>>()

            object : OCRecursiveVisitor() {
                override fun visitLoopStatement(loop: OCLoopStatement?) {
                    loop ?: return super.visitLoopStatement(loop)
                    val body = loop.body ?: return super.visitLoopStatement(loop)
                    matchStatement(loop, body)
                    super.visitLoopStatement(loop)
                }

                override fun visitIfStatement(stmt: OCIfStatement?) {
                    stmt ?: return super.visitIfStatement(stmt)
                    val body = stmt.thenBranch ?: return super.visitIfStatement(stmt)
                    matchStatement(stmt, body)
                    super.visitIfStatement(stmt)
                }

                override fun visitForStatement(stmt: OCForStatement?) {
                    super.visitForStatement(stmt)
                    stmt ?: return
                    forIncrements += (stmt.increment ?: return)
                }

                private fun matchStatement(parent: OCStatement, body: OCStatement) {
                    branches += regionEntries.filter {
                        body.textOffset == document.getLineStartOffset(it.lineStart - 1) + it.columnStart - 1
                    }.map { it to parent }
                }
            }.visitElement(psiFile)


            val otherRegions = allRegions.filter { region ->
                forIncrements.none {
                    val startLine = document.getLineNumber(it.textOffset) + 1
                    val startColumn = it.textOffset - document.getLineStartOffset(startLine - 1) + 1
                    region.lineStart == startLine && region.columnStart == startColumn
                }
            }

            branches.map { it.first to it.second as PsiElement }
                .fold(emptyList<Pair<CoverageBranchData, PsiElement>>()) { list, (region, element) ->

                    val (above, after) = {
                        val startLine = document.getLineNumber(element.textOffset) + 1
                        val startColumn = element.textOffset - document.getLineStartOffset(startLine - 1) + 1

                        val endLine = document.getLineNumber(element.textRange.endOffset) + 1
                        val endColumn = element.textRange.endOffset - document.getLineStartOffset(endLine - 1) + 1

                        otherRegions.findLast { (it.lineStart to it.columnStart) < (startLine to startColumn) } to
                                otherRegions.findLast { (it.lineEnd to it.columnEnd) > (endLine to endColumn) }
                    }()

                    if (above == null) {
                        list
                    } else {

                        val startLine = document.getLineNumber(element.firstChild.textRange.endOffset) + 1
                        val startColumn =
                            element.firstChild.textRange.endOffset - document.getLineStartOffset(startLine - 1) + 1

                        val ifStat = list.lastOrNull()?.second as? OCIfStatement
                        if (ifStat == null || ifStat.elseBranch != element) {
                            list + (CoverageBranchData(
                                startLine to startColumn, region.executionCount.toInt(),
                                (if (above.executionCount >= region.executionCount) above.executionCount - region.executionCount else after?.executionCount
                                    ?: 1).toInt()
                            ) to element)
                        } else {
                            list + (CoverageBranchData(
                                startLine to startColumn, region.executionCount.toInt(),
                                (list.last().first.skippedCount - region.executionCount).toInt()
                            ) to element)
                        }
                    }
                }.map { it.first }
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

private class GCCGCDACoverageGenerator(override val executable: String) : CoverageGenerator {

}

private class GCCJSONCoverageGenerator(override val executable: String) : CoverageGenerator {

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
        ) val functionName: String
    )

    private data class Branch(val count: Int, val fallthrough: Boolean, @Json(name = "throw") val throwing: Boolean)

    private fun processJson(
        jsonContent: String,
        env: CPPEnvironment
    ): CoverageData {
        val root = Klaxon().parse<Root>(jsonContent) ?: return CoverageData(emptyMap())

        return CoverageData(root.files.filter { it.lines.isNotEmpty() || it.functions.isNotEmpty() }.map { file ->
            CoverageFileData(env.toLocalPath(file.file).replace('\\', '/'), file.functions.map { function ->
                val lines = file.lines.filter {
                    it.functionName == function.name
                }
                CoverageFunctionData(
                    function.startLine,
                    function.endLine,
                    function.demangledName,
                    FunctionLineData(lines.associate { it.lineNumber to it.count }),
                    lines.map { line ->
                        line.branches.chunked(2).filter { !it.any { branch -> branch.throwing } }
                            .fold(emptyList<CoverageBranchData>()) { result, list ->
                                val steppedIn = if (list[0].fallthrough) list[0] else list[1]
                                val skipped = if (list[0].fallthrough) list[1] else list[0]
                                result + CoverageBranchData(
                                    line.lineNumber to Int.MAX_VALUE,
                                    steppedIn.count,
                                    skipped.count
                                )
                            }
                    }.flatten()
                )
            }.associateBy { it.functionName })
        }.associateBy { it.filePath })
    }

//    private fun findStatementsForBranches(
//        branches: List<Branch>,
//        line: Int,
//        file: String,
//        project: Project
//    ): List<CoverageBranchData> {
//        if (branches.isEmpty()) {
//            return emptyList()
//        }
//        return ApplicationManager.getApplication().runReadAction<List<CoverageBranchData>> {
//            val result = mutableListOf<CoverageBranchData>()
//            val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadAction emptyList()
//            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadAction emptyList()
//            val document =
//                PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction emptyList()
//            branches.chunked(2).reversed().forEachIndexed { index, chuncked ->
//                val range = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
//                var pos: Pair<Int, Int>? = null
//                var counter = 0
//                object : OCVisitor() {
//
//                    override fun visitElement(element: PsiElement?) {
//                        element ?: return
//
//                        var var2: PsiElement? = element.firstChild
//                        while (var2 != null && pos == null) {
//                            if (range.intersects(OCElementUtil.getRangeWithMacros(var2))) {
//                                var2.accept(this)
//                            }
//                            var2 = var2.nextSibling
//                        }
//                    }
//
//                    override fun visitIfStatement(stmt: OCIfStatement?) {
//                        super.visitIfStatement(stmt)
//                        if (stmt != null && range.contains(stmt.firstChild.textOffset)) {
//                            when {
//                                pos == null && counter == index -> {
//                                    val offset = stmt.lParenth?.startOffset ?: return
//                                    val lineNumber = document.getLineNumber(offset)
//                                    pos = 1 + lineNumber to offset - document.getLineStartOffset(lineNumber) + 1
//                                }
//                                else -> counter++
//                            }
//                        }
//                    }
//
//                    override fun visitLoopStatement(loop: OCLoopStatement?) {
//                        super.visitLoopStatement(loop)
//                        if (loop != null && range.contains(if (loop is OCDoWhileStatement) loop.textRange.endOffset else loop.textOffset)) {
//                            when {
//                                pos == null && counter == index -> {
//                                    val offset = loop.lParenth?.startOffset ?: return
//                                    val lineNumber = document.getLineNumber(offset)
//                                    pos = 1 + lineNumber to offset - document.getLineStartOffset(lineNumber) + 1
//                                }
//                                else -> counter++
//                            }
//                        }
//                    }
//                }.visitElement(psiFile)
//                val endPos = pos ?: return@forEachIndexed
//                val steppedIn = if (chuncked[0].fallthrough) chuncked[0] else chuncked[1]
//                val skipped = if (chuncked[0].fallthrough) chuncked[1] else chuncked[0]
//                result += CoverageBranchData(endPos, steppedIn.count, skipped.count)
//            }
//            result
//        }
//    }

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

        return processJson(lines.joinToString(), environment)
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

fun getGeneratorFor(executable: String, maybeOptionalLLVMProf: String?): Pair<CoverageGenerator?, String?> {
    if (executable.isBlank()) {
        return null to "No executable specified"
    }
    if (!Paths.get(executable).exists()) {
        return null to "Executable doe not exist"
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
            return LLVMCoverageGenerator(executable, maybeOptionalLLVMProf) to null
        }
        lines[0].contains("gcov", true) -> {
            val version = extractVersion(lines[0])
            return if (version.first >= 9) {
                GCCJSONCoverageGenerator(executable) to null
            } else {
                GCCGCDACoverageGenerator(executable) to null
            }
        }
        else ->
            return null to "Executable identified as neither gcov nor llvm-cov"
    }
}