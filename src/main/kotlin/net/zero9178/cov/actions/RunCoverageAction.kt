package net.zero9178.cov.actions

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.Key
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import net.zero9178.cov.settings.CoverageGeneratorSettings

val STARTED_BY_COVERAGE_BUTTON = Key<Boolean>("STARTED_BY_COVERAGE_BUTTON")

class CoverageButton : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = CoverageGeneratorSettings.getInstance().useCoverageAction
        if (!e.presentation.isEnabledAndVisible) {
            return
        }
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val manager = RunManager.getInstance(project)
        val settings = manager.selectedConfiguration
        if (settings == null) {
            e.presentation.isEnabled = false
            return
        }
        e.presentation.isEnabled = settings.type.id.startsWith("CMake")

        e.presentation.text = "Run '${settings.name}' with coverage"
    }

    override fun actionPerformed(anActionEvent: AnActionEvent) {
        val executor = DefaultRunExecutor.getRunExecutorInstance() ?: return
        val project = anActionEvent.project ?: return
        val manager = RunManager.getInstance(project)
        val settings = manager.selectedConfiguration ?: return

        /**
         * Feels a bit wrong and not what UserData was probably intended for but it works, is simple and follows the DRY
         * Principle so lets do that for now lol
         */
        val config = settings.configuration as? CMakeAppRunConfiguration ?: return
        config.putUserData(STARTED_BY_COVERAGE_BUTTON, true)

        val envBuilder = ExecutionEnvironmentBuilder.create(executor, settings)

        val environment = envBuilder.run {
            contentToReuse(null)
            dataContext(null)
            activeTarget()
        }.build()

        ProgramRunnerUtil.executeConfigurationAsync(environment, true, true) {
            config.replace(STARTED_BY_COVERAGE_BUTTON, true, null)
        }
    }
}