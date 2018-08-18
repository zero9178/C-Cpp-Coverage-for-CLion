package GCov.Action;

import GCov.Messaging.GCoverageRunEnded;
import com.intellij.execution.*;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class CoverageRunner extends AnAction {

    static class ProcessEndHandler implements ExecutionListener {

        private long m_executionID;

        ProcessEndHandler(long executionID) {
            m_executionID = executionID;
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
                runContentDescriptor -> project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC,
                        new ProcessEndHandler(runContentDescriptor.getExecutionId())));

    }
}
