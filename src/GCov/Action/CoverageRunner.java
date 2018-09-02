package GCov.Action;

import GCov.Messaging.GCoverageRunEnded;
import com.intellij.execution.*;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.project.Project;
import com.jetbrains.cidr.cpp.cmake.CMakeSettings;
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration;
import com.jetbrains.cidr.execution.BuildTargetAndConfigurationData;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Coverage button and menu option
 *
 * Class representing the option in the run menu as well as the button in the top right for running coverage
 */
public class CoverageRunner extends AnAction {

    /**
     * Class handling termination events
     *
     * Checks that connections to projects and processes in projects are unique and working.
     * Sends a GCoverageRunEnded event on the project bus upon termination
     */
    static class ProcessEndHandler implements ExecutionListener {

        private long m_executionID;
        private Set<Project> m_connected = new HashSet<>();

        /**
         * Sets the executionID of the process to listen to
         *
         * @param executionID ExecutionID of the process
         */
        void setExecutionID(long executionID) {
            m_executionID = executionID;
        }

        /**
         * Removes all projects from the set that are already disposed
         */
        private void disposeProjects() {
            m_connected.removeIf(ComponentManager::isDisposed);
        }

        /**
         * Connects to the message bus of the project if it doesn't have a connection already
         *
         * @param project Project to connect to
         */
        void connect(@NotNull Project project) {
            if (!m_connected.contains(project)) {
                project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, this);
                m_connected.add(project);
            }
            disposeProjects();
        }

        /**
         * Called when a process terminates
         *
         * Checks if the process that terminated has the same executionId as this and publishes a GCoverageRunEnded event
         * if it does
         * @param executorId executorID not to be confused with executionId
         * @param env Environment
         * @param handler ProcessHandler
         * @param exitCode exit code of the process
         */
        @Override
        public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
            if (env.getExecutionId() != m_executionID || exitCode != 0) {
                return;
            }
            String target = env.getExecutionTarget().getDisplayName();
            CMakeSettings settings = env.getProject().getComponent(CMakeSettings.class);
            String buildDirectory = null;
            for (CMakeSettings.Profile profile : settings.getProfiles()) {
                if (profile.getName().equals(target)) {
                    if (profile.getGenerationDir() == null) {
                        buildDirectory = "cmake-build-" + target.toLowerCase();
                    } else {
                        buildDirectory = profile.getGenerationDir().toString();
                    }
                    break;
                }
            }
            if (buildDirectory == null) {
                return;
            }
            GCoverageRunEnded publisher = env.getProject().getMessageBus().syncPublisher(GCoverageRunEnded.GCOVERAGE_RUN_ENDED_TOPIC);
            publisher.ended(buildDirectory);
        }
    }

    static private final ProcessEndHandler m_handler = new ProcessEndHandler();

    /**
     *
     * Called every few seconds to update state
     *
     * Checks if the current selected runner is compatible with
     * coverage and disables the button if it's not
     * @param e Event sent by intellij
     */
    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabled(false);
            return;
        }
        RunManager manager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = manager.getSelectedConfiguration();
        if (settings == null) {
            e.getPresentation().setEnabled(false);
            return;
        }
        e.getPresentation().setEnabled(settings.getType().getId().startsWith("CMake"));
    }

    /**
     * Called when the user presses the button
     *
     * Gets the current run configuration, adds a termination listener in the form of the ProcessEndHandler
     * and executes the configuration
     * @param anActionEvent Event sent by intellij
     */
    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        Executor defaultExecutor = DefaultRunExecutor.getRunExecutorInstance();
        Project project = anActionEvent.getProject();
        if (project == null) {
            return;
        }
        RunManager manager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = manager.getSelectedConfiguration();
        if (settings == null || !settings.getType().getId().startsWith("CMake")) {
            return;
        }
        ExecutionEnvironmentBuilder envBuilder;
        try {
            envBuilder = ExecutionEnvironmentBuilder.create(defaultExecutor, settings);
        } catch (ExecutionException var4) {
            return;
        }
        ProgramRunnerUtil.executeConfigurationAsync(envBuilder.contentToReuse(null).dataContext(null).activeTarget().build(),
                true, true,
                runContentDescriptor -> {
                    m_handler.connect(project);
                    m_handler.setExecutionID(runContentDescriptor.getExecutionId());
                });

    }
}
