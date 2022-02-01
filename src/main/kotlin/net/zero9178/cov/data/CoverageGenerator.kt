package net.zero9178.cov.data

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.cpp.toolchains.WSL
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import java.nio.file.Paths

interface CoverageGenerator {
    fun patchEnvironment(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: CMakeBuildProfileExecutionTarget,
        cmdLine: GeneralCommandLine,
        context: ConfigurationExtensionContext
    ) {
    }

    fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: CMakeBuildProfileExecutionTarget,
        indicator: ProgressIndicator,
        context: ConfigurationExtensionContext
    ): CoverageData? {
        return null
    }
}

private fun extractVersion(line: String): Triple<Int, Int, Int> {
    val result = "(\\d+)\\.(\\d+)\\.(\\d+)".toRegex().findAll(line).lastOrNull() ?: return Triple(0, 0, 0)
    val (first, second, value) = result.destructured
    return Triple(first.toInt(), second.toInt(), value.toInt())
}

fun createGeneratorFor(
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

    val commandLine = GeneralCommandLine(executable, "--version")
    if (wsl != null) {
        wsl.wslDistribution?.patchCommandLine(commandLine, null, WSLCommandLineOptions())
    }

    val output = CapturingProcessHandler(commandLine).runProcess(5000)
    if (output.isTimeout) {
        return null to "Executable timed out"
    }

    val retCode = output.exitCode
    if (retCode != 0) {
        val stderrOutput = output.getStderrLines(false)
        return null to "Executable returned with error code $retCode and error output:\n ${stderrOutput.joinToString("\n")}"
    }
    val lines = output.getStdoutLines(false)
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
                GCCJSONCoverageGenerator(executable, version.first) to null
            } else {
                GCCGCDACoverageGenerator(executable, version.first) to null
            }
        }
        else ->
            return null to "Executable identified as neither gcov nor llvm-cov"
    }
}