package net.zero9178.cov.data

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.system.RemoteUtil
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

interface CoverageGenerator {
    fun patchEnvironment(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        cmdLine: GeneralCommandLine
    ) {
    }

    fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        return null
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
    optionalDemangler: String?,
    toolchain: CPPToolchains.Toolchain
): CompletableFuture<Pair<CoverageGenerator?, String?>> {
    return CompletableFuture.supplyAsync {
        if (executable.isBlank()) {
            return@supplyAsync null to "No executable specified"
        }
        val environment = CPPEnvironment(toolchain)
        if (if (environment.hostMachine.isRemote) !RemoteUtil.fileExists(
                toolchain.remoteCredentials!!,
                executable
            ) else !Paths.get(executable).exists()
        ) {
            return@supplyAsync null to "Executable does not exist"
        }
        val p = environment.hostMachine.runProcess(GeneralCommandLine(executable, "--version"), null, 5000)
        if (p.isTimeout) {
            return@supplyAsync null to "Executable timed out"
        }
        if (p.exitCode != 0) {
            return@supplyAsync null to "Executable returned with error code ${p.exitCode} and error output:\n ${p.stderrLines.joinToString(
                "\n"
            )}"
        }
        val firstLine = p.stdoutLines[0]
        when {
            firstLine.contains("LLVM", true) -> {
                if (maybeOptionalLLVMProf == null) {
                    return@supplyAsync null to "No llvm-profdata specified to accompany llvm-cov"
                }
                return@supplyAsync LLVMCoverageGenerator(executable, maybeOptionalLLVMProf, optionalDemangler) to null
            }
            firstLine.contains("gcov", true) -> {
                val version = extractVersion(firstLine)
                return@supplyAsync if (version.first >= 9) {
                    GCCJSONCoverageGenerator(executable) to null
                } else {
                    GCCGCDACoverageGenerator(executable, version.first) to null
                }
            }
            else ->
                return@supplyAsync null to "Executable identified as neither gcov nor llvm-cov"
        }
    }
}