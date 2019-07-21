package net.zero9178.cov.data

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import net.zero9178.cov.notification.CoverageNotification
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

    private fun processJson(jsonContent: String): CoverageData {
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
            }).parse<Root>(jsonContent)

        return CoverageData(emptyMap())
    }

    override fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return null

        val llvmProf = ProcessBuilder().command(
            llvmProf,
            "merge",
            "-output=${config.target.name}.profdata",
            "${config.target.name}-*.profraw"
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

        val llvmCov = ProcessBuilder().command(
            executable,
            "export",
            "-instr-profile",
            "${config.target.name}.profdata",
            config.productFile?.absolutePath ?: ""
        ).directory(config.configurationGenerationDir).redirectErrorStream(true).start()
        lines = llvmCov.inputStream.bufferedReader().readLines()
        retCode = llvmCov.waitFor()
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "llvm-cov returned error code $retCode with error output:\n${lines.joinToString(
                    "\n"
                )}", NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        return processJson(lines.joinToString())
    }
}

private class GCCGCDACoverageGenerator(override val executable: String) : CoverageGenerator {

}

private class GCCJSONCoverageGenerator(override val executable: String) : CoverageGenerator {

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