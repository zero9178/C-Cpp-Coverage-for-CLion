package gcov.action

import com.intellij.execution.*
import gcov.messaging.CoverageProcessEnded
import gcov.notification.GCovNotification
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.cmake.CMakeSettings

import java.nio.file.Files
import java.nio.file.Paths
import java.util.HashSet

/**
 * Coverage button and menu option
 *
 * Class representing the option in the run menu as well as the button in the top right for running coverage
 */
class CoverageButton : AnAction() {

    /**
     * Class handling termination events
     *
     * Checks that connections to projects and processes in projects are unique and working.
     * Sends a CoverageProcessEnded event on the project bus upon termination
     */
    internal class ProcessEndHandler : ExecutionListener {

        var executionID: Long = 0
        private val myConnected = HashSet<Project>()

        /**
         * Removes all projects from the set that are already disposed
         */
        private fun disposeProjects() = myConnected.removeIf{ it.isDisposed }

        /**
         * Connects to the message bus of the project if it doesn't have a connection already
         *
         * @param project Project to connect to
         */
        fun connect(project: Project) {
            if (!myConnected.contains(project)) {
                project.messageBus.connect().subscribe(ExecutionManager.EXECUTION_TOPIC, this)
                myConnected.add(project)
            }
            disposeProjects()
        }

        /**
         * Called when a process terminates
         *
         * Checks if the process that terminated has the same executionId as this and publishes a CoverageProcessEnded event
         * if it does
         * @param executorId executorID not to be confused with executionId
         * @param env Environment
         * @param handler ProcessHandler
         * @param exitCode exit code of the process
         */
        override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
            if (env.executionId != executionID) {
                return
            }
            val target = env.executionTarget.displayName
            val settings = env.project.getComponent(CMakeSettings::class.java) ?: return
            val profile = settings.profiles.find { it.name == target }
            var buildDirectory: String? = if (profile != null) {
                if (profile.generationDir == null) {
                    "cmake-build-" + target.toLowerCase()
                } else {
                    profile.generationDir.toString()
                }
            } else {
                null
            }

            if (buildDirectory == null) {
                val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                        .createNotification("Error getting build directory for cmake profile $target", NotificationType.ERROR)
                Notifications.Bus.notify(notification, env.project)
                return
            }

            if (!Files.exists(Paths.get(buildDirectory))) {
                buildDirectory =  "${env.project.basePath}/$buildDirectory"
            }
            val publisher = env.project.messageBus.syncPublisher(CoverageProcessEnded.GCOVERAGE_RUN_ENDED_TOPIC)
            publisher.ended(buildDirectory)
        }
    }

    /**
     *
     * Called every few seconds to update state
     *
     * Checks if the current selected runner is compatible with
     * coverage and disables the button if it's not
     * @param e Event sent by intellij
     */
    override fun update(e: AnActionEvent) {
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
    }

    /**
     * Called when the user presses the button
     *
     * Gets the current run configuration, adds a termination listener in the form of the ProcessEndHandler
     * and executes the configuration
     * @param anActionEvent Event sent by intellij
     */
    override fun actionPerformed(anActionEvent: AnActionEvent) {
        val defaultExecutor = DefaultRunExecutor.getRunExecutorInstance() ?: return
        val project = anActionEvent.project ?: return
        val manager = RunManager.getInstance(project)
        val settings = manager.selectedConfiguration
        if (settings == null || !settings.type.id.startsWith("CMake")) {
            return
        }
        val envBuilder: ExecutionEnvironmentBuilder
        try {
            envBuilder = ExecutionEnvironmentBuilder.create(defaultExecutor, settings)
        } catch (var4: ExecutionException) {
            return
        }

        val environment = envBuilder.run {
            contentToReuse(null)
            dataContext(null)
            activeTarget()
        }.build()

        ProgramRunnerUtil.executeConfigurationAsync(environment, true, true) {
            myHandler.connect(project)
            myHandler.executionID = it.executionId
        }
    }

    companion object {

        private val myHandler = ProcessEndHandler()
    }
}
