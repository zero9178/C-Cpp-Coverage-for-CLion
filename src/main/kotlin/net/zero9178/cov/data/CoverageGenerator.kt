package net.zero9178.cov.data

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.cpp.toolchains.WSL
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

interface CoverageGenerator {
    fun patchEnvironment(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget,
        cmdLine: GeneralCommandLine
    ) {
    }

    fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget,
        indicator: ProgressIndicator
    ): CoverageData? {
        return null
    }
}

private fun extractVersion(line: String): Triple<Int, Int, Int> {
    val result = "(\\d+)\\.(\\d+)\\.(\\d+)".toRegex().findAll(line).lastOrNull() ?: return Triple(0, 0, 0)
    val (first, second, value) = result.destructured
    return Triple(first.toInt(), second.toInt(), value.toInt())
}

fun getGeneratorFor(
    executable: String,
    maybeOptionalLLVMProf: String?,
    optionalDemangler: String?,
    wsl: WSL?
): Pair<CoverageGenerator?, String?> {
    if (executable.isBlank()) {
        return null to "No executable specified"
    }
    if (if (wsl == null) !Paths.get(executable).exists() else false) {
        return null to "Executable does not exist"
    }
    val p = ProcessBuilder(
        (if (wsl != null) listOf(wsl.homePath, "run") else emptyList()) + listOf(
            executable,
            "--version"
        )
    ).start()
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
            val majorVersion = extractVersion(lines[1]).first
            return LLVMCoverageGenerator(majorVersion, executable, maybeOptionalLLVMProf, optionalDemangler) to null
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