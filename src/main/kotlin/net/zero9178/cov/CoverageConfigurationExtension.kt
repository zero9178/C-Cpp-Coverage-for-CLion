package net.zero9178.cov

import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.wm.ToolWindowManager
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.execution.coverage.CMakeCoverageBuildOptionsInstallerFactory
import com.jetbrains.cidr.cpp.execution.testing.CMakeTestRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.execution.CidrBuildTarget
import com.jetbrains.cidr.execution.CidrRunConfiguration
import com.jetbrains.cidr.execution.CidrRunConfigurationExtensionBase
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.execution.coverage.CidrCoverageProgramRunner
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import net.zero9178.cov.actions.STARTED_BY_COVERAGE_BUTTON
import net.zero9178.cov.data.CoverageFileData
import net.zero9178.cov.data.CoverageFunctionData
import net.zero9178.cov.data.CoverageGenerator
import net.zero9178.cov.editor.CoverageHighlighter
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.window.CoverageView
import java.nio.file.Paths
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode

class CoverageConfigurationExtension : CidrRunConfigurationExtensionBase() {

    override fun isApplicableFor(configuration: CidrRunConfiguration<*, *>): Boolean {
        return if (CoverageGeneratorSettings.getInstance().useCoverageAction) {
            val startedByCoverageButton: Boolean? = configuration.getUserData(STARTED_BY_COVERAGE_BUTTON)
            !(startedByCoverageButton == null || startedByCoverageButton == false)
        } else {
            true
        }
    }

    override fun isEnabledFor(
        applicableConfiguration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?
    ): Boolean {
        if (environment !is CPPEnvironment) {
            return false
        }
        // Don't check for flags if the user explicitly requested it
        if (explicitlyRequested(applicableConfiguration)) {
            return true
        }
        return (applicableConfiguration as? CMakeAppRunConfiguration)?.let {
            hasCompilerFlags(it)
        } ?: false
    }

    override fun patchCommandLine(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        runnerSettings: RunnerSettings?,
        environment: CidrToolEnvironment,
        cmdLine: GeneralCommandLine,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        if (environment !is CPPEnvironment || configuration !is CMakeAppRunConfiguration || ProgramRunner.findRunnerById(
                runnerId
            ) is CidrCoverageProgramRunner
        ) {
            return
        }
        getCoverageGenerator(environment, configuration.project)?.patchEnvironment(configuration, environment, cmdLine)
    }

    override fun attachToProcess(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        handler: ProcessHandler,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        if (environment !is CPPEnvironment || configuration !is CMakeAppRunConfiguration || ProgramRunner.findRunnerById(
                runnerId
            ) is CidrCoverageProgramRunner
        ) {
            return
        }
        val wasExplicitlyRequested = explicitlyRequested(configuration)
        val executionTarget = ExecutionTargetManager.getInstance(configuration.project).activeTarget
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                if (!hasCompilerFlags(configuration) && wasExplicitlyRequested) {
                    val factory = CMakeCoverageBuildOptionsInstallerFactory()
                    val installer = factory.getInstaller(configuration)
                    if (installer != null && installer.canInstall(configuration, configuration.project)) {
                        NotificationGroupManager.getInstance().getNotificationGroup("C/C++ Coverage Notification")
                            .createNotification(
                                "Missing compilation flags",
                                "Compiler flags for generating coverage are missing.\n"
                                        + "Would you like to create a new profile with them now?\n<a href=\"\"" +
                                        ">Create</a>", NotificationType.ERROR
                            ) { _, hyperlinkEvent ->
                                if (hyperlinkEvent.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                                    if (!installer.install(
                                            {},
                                            configuration,
                                            configuration.project
                                        )
                                    ) {
                                        NotificationGroupManager.getInstance()
                                            .getNotificationGroup("C/C++ Coverage Notification")
                                            .createNotification(
                                                "Failed to add compiler flags", NotificationType.ERROR
                                            ).notify(configuration.project)
                                    }
                                }
                            }.notify(configuration.project)
                    }
                } else {
                    ProgressManager.getInstance()
                        .run(object : Task.Backgroundable(
                            configuration.project, "Gathering coverage...", false,
                            PerformInBackgroundOption.DEAF
                        ) {
                            override fun run(indicator: ProgressIndicator) {
                                indicator.isIndeterminate = true
                                val data =
                                    getCoverageGenerator(environment, configuration.project)?.generateCoverage(
                                        configuration,
                                        environment,
                                        executionTarget
                                    )
                                val root = DefaultMutableTreeNode("invisible-root")
                                invokeLater {
                                    CoverageHighlighter.getInstance(configuration.project).setCoverageData(data)
                                }
                                if (data != null) {
                                    for ((_, value) in data.files) {
                                        val fileNode = object : DefaultMutableTreeNode(value) {
                                            override fun toString(): String {
                                                val filePath =
                                                    (userObject as? CoverageFileData)?.filePath?.replace('\\', '/')
                                                filePath ?: return userObject.toString()
                                                val projectPath =
                                                    configuration.project.basePath?.replace('\\', '/')
                                                projectPath ?: return filePath
                                                return if (filePath.startsWith(projectPath)) {
                                                    Paths.get(projectPath).relativize(Paths.get(filePath)).toString()
                                                } else {
                                                    filePath
                                                }
                                            }
                                        }

                                        root.add(fileNode)
                                        for (function in value.functions.values) {
                                            fileNode.add(object : DefaultMutableTreeNode(function) {
                                                override fun toString() =
                                                    (userObject as? CoverageFunctionData)?.functionName
                                                        ?: userObject.toString()
                                            })
                                        }
                                    }
                                }
                                invokeLater {
                                    CoverageView.getInstance(configuration.project)
                                        .setRoot(
                                            root,
                                            data?.hasBranchCoverage ?: true,
                                            data?.containsExternalSources ?: false
                                        )
                                    ToolWindowManager.getInstance(configuration.project).getToolWindow("C/C++ Coverage")
                                        ?.show(null)
                                }
                            }
                        })
                }
            }
        })
    }

    private fun getCoverageGenerator(
        environment: CPPEnvironment,
        project: Project
    ): CoverageGenerator? {
        val generator = CoverageGeneratorSettings.getInstance().getGeneratorFor(environment.toolchain.name)
        if (generator == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("C/C++ Coverage Notification")
                .createNotification(
                    "Neither gcov nor llvm-cov specified for ${environment.toolchain.name}",
                    NotificationType.ERROR
                ).notify(project)
            return null
        }
        val coverageGenerator = generator.first
        if (coverageGenerator == null) {
            if (generator.second != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("C/C++ Coverage Notification")
                    .createNotification(
                        "Coverage could not be generated due to following error: ${generator.second}",
                        NotificationType.ERROR
                    ).notify(project)
            }
            return null
        }
        return coverageGenerator
    }

    private fun hasCompilerFlags(configuration: CMakeAppRunConfiguration): Boolean {
        val executionTarget = ExecutionTargetManager.getInstance(configuration.project).activeTarget
        // I am in a bit of dilemma here. The problem is that I don't want the plugin to depend on CTest
        // just to check whether the runConfiguration is a CTest run. Those have no runConfiguration and therefore
        // there's no way for me to check whether it has compiler flags or not because CLion seems to pick
        // whatever random target (that mustn't even be a library or executable CMake target) as target
        // when running all CTests
        val runConfiguration = configuration.getBuildAndRunConfigurations(executionTarget)?.runConfiguration
            ?: return configuration is CMakeTestRunConfiguration
        return CLanguageKind.values().any { kind ->
            val list = runConfiguration.getCombinedCompilerFlags(kind, null)
            list.contains("--coverage") || list.containsAll(
                listOf(
                    "-fprofile-arcs",
                    "-ftest-coverage"
                )
            ) || list.containsAll(
                listOf("-fprofile-instr-generate", "-fcoverage-mapping")
            )
        }
    }

    private fun explicitlyRequested(configuration: UserDataHolder): Boolean {
        return CoverageGeneratorSettings.getInstance().useCoverageAction && configuration.getUserData(
            STARTED_BY_COVERAGE_BUTTON
        ) == true
    }
}