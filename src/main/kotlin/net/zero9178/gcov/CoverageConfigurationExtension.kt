package net.zero9178.gcov

import com.intellij.coverage.CoverageDataManager
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessHandler
import com.jetbrains.cidr.execution.CidrBuildTarget
import com.jetbrains.cidr.execution.CidrRunConfiguration
import com.jetbrains.cidr.execution.CidrRunConfigurationExtensionBase
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.toolchains.CidrCompilerSwitches
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment

class CoverageConfigurationExtension : CidrRunConfigurationExtensionBase() {
    override fun isApplicableFor(configuration: CidrRunConfiguration<*, *>) = true

    override fun isEnabledFor(
        applicableConfiguration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?
    ): Boolean {
        val executionTarget = ExecutionTargetManager.getInstance(applicableConfiguration.getProject()).activeTarget
        val resolver = applicableConfiguration.getResolveConfiguration(executionTarget) ?: return false
        for (i in CLanguageKind.values()) {
            val switches = resolver.getCompilerSettings(i, null).compilerSwitches ?: continue
            val list = switches.getList(CidrCompilerSwitches.Format.RAW)
            return list.contains("--coverage") || list.containsAll(listOf("-fprofile-arcs", "-ftest-coverage"))
        }
        return false
    }

    override fun attachToProcess(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        handler: ProcessHandler,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) = CoverageDataManager.getInstance(configuration.getProject()).attachToProcess(
        handler,
        configuration,
        runnerSettings
    )
}