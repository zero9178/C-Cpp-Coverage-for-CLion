package net.zero9178.cov.actions

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.ide.macro.MacroManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import net.zero9178.cov.settings.CoverageGeneratorSettings
import org.jdom.Element

class RunCoverageSettings(val executionTarget: CMakeBuildProfileExecutionTarget) : RunnerSettings {
    override fun readExternal(element: Element?) {

    }

    override fun writeExternal(element: Element?) {

    }
}

class CoverageButton : DumbAwareAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        e.presentation.isEnabled = false
        val manager = RunManager.getInstance(project)
        val settings = manager.selectedConfiguration ?: return
        e.presentation.text = "Run '${settings.name}' with C/C++ Coverage Plugin"

        if (ExecutionTargetManager.getActiveTarget(project) !is CMakeBuildProfileExecutionTarget) {
            return
        }

        val runConfig = CMakeAppRunConfiguration.getSelectedRunConfiguration(project) ?: return
        val cmakeConfig = CMakeWorkspace.getInstance(project).getCMakeConfigurationFor(
            runConfig.getResolveConfiguration(
                ExecutionTargetManager.getInstance(project).activeTarget
            )
        ) ?: return
        val info = CMakeWorkspace.getInstance(project).getProfileInfoFor(cmakeConfig)
        val toolchain = info.profile.toolchainName ?: CPPToolchains.getInstance().defaultToolchain?.name ?: return
        val gen = CoverageGeneratorSettings.getInstance()
            .getGeneratorFor(toolchain)
        e.presentation.isEnabled = gen?.first != null
    }

    override fun actionPerformed(anActionEvent: AnActionEvent) {
        val executor = DefaultRunExecutor.getRunExecutorInstance() ?: return
        val project = anActionEvent.project ?: return
        val manager = RunManager.getInstance(project)
        val settings = manager.selectedConfiguration ?: return

        val config = settings.configuration as? CMakeAppRunConfiguration ?: return

        MacroManager.getInstance().cacheMacrosPreview(anActionEvent.dataContext)
        val envBuilder = ExecutionEnvironmentBuilder.create(executor, settings)

        val executionTarget =
            ExecutionTargetManager.getActiveTarget(project) as? CMakeBuildProfileExecutionTarget ?: return
        val environment = envBuilder.run {
            dataContext(anActionEvent.dataContext)
            activeTarget()
            runnerSettings(RunCoverageSettings(executionTarget))
        }.build()

        ExecutionManager.getInstance(config.project).restartRunProfile(environment)
    }
}