package net.zero9178.cov.util

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.jetbrains.cidr.cpp.cmake.model.CMakeConfiguration
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import com.jetbrains.cidr.cpp.execution.testing.CMakeTestRunConfiguration
import com.jetbrains.cidr.cpp.execution.testing.ctest.CidrCTestRunConfigurationData

fun isCTestInstalled() =
    PluginManager.getInstance().findEnabledPlugin(PluginId.getId("org.jetbrains.plugins.clion.ctest")) != null

fun getCMakeConfigurations(
    configuration: CMakeAppRunConfiguration,
    executionTarget: CMakeBuildProfileExecutionTarget
): Sequence<CMakeConfiguration> {
    return if (configuration is CMakeTestRunConfiguration && isCTestInstalled() && configuration.testData is CidrCTestRunConfigurationData) {
        val testData = configuration.testData as CidrCTestRunConfigurationData
        testData.infos?.mapNotNull {
            it?.command?.exePath
        }?.distinct()?.asSequence()?.mapNotNull { executable ->
            CMakeWorkspace.getInstance(configuration.project).modelTargets.asSequence().mapNotNull { target ->
                target.buildConfigurations.find { it.profileName == executionTarget.profileName }
            }.find {
                it.productFile?.name == executable.substringAfterLast(
                    '/',
                    if (SystemInfo.isWindows) executable.substringAfterLast('\\') else executable
                )
            }
        } ?: emptySequence()
    } else {
        val runConfiguration =
            configuration.getBuildAndRunConfigurations(executionTarget)?.buildConfiguration
                ?: return emptySequence()
        sequenceOf(runConfiguration)
    }
}