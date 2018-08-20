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
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class CoverageRunner extends AnAction {

    static class ProcessEndHandler implements ExecutionListener {

        private long m_executionID;
        private Set<Project> m_connected = new HashSet<>();

        void setExecutionID(long executionID) {
            m_executionID = executionID;
        }

        private void disposeProjects() {
            m_connected.removeIf(ComponentManager::isDisposed);
        }

        void connect(@NotNull Project project) {
            if (!m_connected.contains(project)) {
                project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, this);
                m_connected.add(project);
            }
            disposeProjects();
        }

        @Override
        public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
            if (env.getExecutionId() != m_executionID || exitCode != 0) {
                return;
            }
            String target = env.getExecutionTarget().getDisplayName();
            GCoverageRunEnded publisher = env.getProject().getMessageBus().syncPublisher(GCoverageRunEnded.GCOVERAGE_RUN_ENDED_TOPIC);
            publisher.ended("cmake-build-" + target.toLowerCase());
        }
    }

    static private final ProcessEndHandler m_handler = new ProcessEndHandler();

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
                    m_handler.setExecutionID(runContentDescriptor.getExecutionId()
                            + (runContentDescriptor.getHelpId().equals("reference.runToolWindow.testResultsTab") ? 0 : 0));
                });

    }
}
