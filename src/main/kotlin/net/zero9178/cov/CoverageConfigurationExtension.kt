package net.zero9178.cov

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.jetbrains.cidr.cpp.cmake.model.CMakeConfiguration
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import com.jetbrains.cidr.cpp.execution.coverage.CMakeCoverageBuildOptionsInstallerFactory
import com.jetbrains.cidr.cpp.execution.testing.CMakeTestRunConfiguration
import com.jetbrains.cidr.cpp.execution.testing.ctest.CidrCTestRunConfigurationData
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.execution.CidrBuildTarget
import com.jetbrains.cidr.execution.CidrRunConfiguration
import com.jetbrains.cidr.execution.CidrRunConfigurationExtensionBase
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.execution.coverage.CidrCoverageProgramRunner
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import net.zero9178.cov.actions.STARTED_BY_COVERAGE_BUTTON
import net.zero9178.cov.data.*
import net.zero9178.cov.editor.CoverageFileAccessProtector
import net.zero9178.cov.editor.CoverageHighlighter
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.isCTestInstalled
import net.zero9178.cov.window.CoverageView
import java.io.File
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode

private val EXECUTION_TARGET_USED = Key<ExecutionTarget>("EXECUTION_TARGET_USED")
private val GENERAL_COMMAND_LINE = Key<GeneralCommandLine>("GENERAL_COMMAND_LINE")

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
        val executionTarget =
            ExecutionTargetManager.getActiveTarget(applicableConfiguration.project) as? CMakeBuildProfileExecutionTarget
                ?: return false
        return (applicableConfiguration as? CMakeAppRunConfiguration)?.let {
            hasCompilerFlags(it, executionTarget)
        } ?: false
    }

    override fun patchCommandLineState(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        runnerSettings: RunnerSettings?,
        environment: CidrToolEnvironment,
        projectBaseDir: File?,
        state: CommandLineState,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        if (environment !is CPPEnvironment || configuration !is CMakeAppRunConfiguration || ProgramRunner.findRunnerById(
                runnerId
            ) is CidrCoverageProgramRunner
        ) {
            return
        }
        context.putUserData(EXECUTION_TARGET_USED, state.executionTarget)
        val commandLine = context.getUserData(GENERAL_COMMAND_LINE) ?: return
        getCoverageGenerator(environment, configuration.project)?.patchEnvironment(
            configuration,
            environment,
            state.executionTarget,
            commandLine
        )
    }

    override fun patchCommandLine(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        runnerSettings: RunnerSettings?,
        environment: CidrToolEnvironment,
        cmdLine: GeneralCommandLine,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        // Have to be super careful about this in the future possibly. Problem is that currently (2020.3) patchCommandLine
        // gets called before patchCommandLineState. patchCommandLine needs to know the execution target used
        // to determine which generate needs to be used, to call the generator's patchEnvironment function.
        // We only get the execution target once in patchCommandLineState though. So instead we put the
        // GeneralCommandLine into the context and patch everything once in patchCommandLineState. This is fragile
        // however and depends on 1) the order of the two calls 2) cmdLine still being allowed to be modified
        // from within patchCommandLineState. I don't know of a better solution as of this moment however
        context.putUserData(GENERAL_COMMAND_LINE, cmdLine)
    }

    override fun attachToProcess(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        handler: ProcessHandler,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        val executionTarget = context.getUserData(EXECUTION_TARGET_USED) as? CMakeBuildProfileExecutionTarget
        if (executionTarget == null || environment !is CPPEnvironment || configuration !is CMakeAppRunConfiguration || ProgramRunner.findRunnerById(
                runnerId
            ) is CidrCoverageProgramRunner
        ) {
            return
        }
        val wasExplicitlyRequested = explicitlyRequested(configuration)
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                if (!hasCompilerFlags(configuration, executionTarget) && wasExplicitlyRequested) {
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
                            configuration.project, "Gathering coverage...", true,
                            PerformInBackgroundOption.DEAF
                        ) {
                            override fun run(indicator: ProgressIndicator) {
                                indicator.isIndeterminate = true

                                if (!ProjectSemaphore.getInstance(project).semaphore.tryAcquire()) {
                                    indicator.text = "Waiting for other coverage gathering to finish"
                                    ProjectSemaphore.getInstance(project).semaphore.acquire()
                                    indicator.text = ""
                                }

                                val coverageGenerator = getCoverageGenerator(environment, configuration.project)
                                val needsSourcefiles = when (coverageGenerator) {
                                    is LLVMCoverageGenerator -> coverageGenerator.majorVersion < 12
                                    is GCCJSONCoverageGenerator -> true
                                    is GCCGCDACoverageGenerator -> false
                                    else -> false
                                }
                                if (needsSourcefiles) {
                                    synchronized(CoverageHighlighter.getInstance(project)) {
                                        val sources = getSourceFiles(project)
                                        invokeLater {
                                            CoverageFileAccessProtector.currentlyInCoverage[configuration.project] =
                                                sources
                                        }
                                    }
                                }
                                val data =
                                    coverageGenerator?.generateCoverage(
                                        configuration,
                                        environment,
                                        executionTarget,
                                        indicator
                                    )
                                val root = DefaultMutableTreeNode("invisible-root")
                                invokeLater {
                                    CoverageHighlighter.getInstance(configuration.project).setCoverageData(data)
                                }
                                if (data != null) {
                                    createCoverageViewTree(root, data)
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

                            override fun onFinished() {
                                invokeAndWaitIfNeeded {
                                    CoverageFileAccessProtector.currentlyInCoverage.remove(configuration.project)
                                }
                                ProjectSemaphore.getInstance(project).semaphore.release()
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

    private fun getCMakeConfigurations(
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

    private fun hasCompilerFlags(
        configuration: CMakeAppRunConfiguration,
        executionTarget: CMakeBuildProfileExecutionTarget
    ) = getCMakeConfigurations(configuration, executionTarget).any {
        CLanguageKind.values().any { kind ->
            val list = it.getCombinedCompilerFlags(kind, null)
            list.contains("--coverage") || list.containsAll(
                listOf(
                    "-fprofile-arcs",
                    "-ftest-coverage"
                )
            ) || (list.contains("-fcoverage-mapping") && list.any {
                it.matches("-fprofile-instr-generate(=.*)?".toRegex())
            })
        }
    }

    private fun getSourceFiles(
        project: Project
    ): Set<VirtualFile> {
        return CMakeWorkspace.getInstance(project).module?.let { module ->
            ModuleRootManager.getInstance(module).contentEntries.map {
                it.sourceFolderFiles.toSet()
            }.fold(emptySet()) { result, curr ->
                result.union(curr)
            }
        } ?: emptySet()
    }

    private fun explicitlyRequested(configuration: UserDataHolder): Boolean {
        return CoverageGeneratorSettings.getInstance().useCoverageAction && configuration.getUserData(
            STARTED_BY_COVERAGE_BUTTON
        ) == true
    }

    private fun createCoverageViewTree(root: DefaultMutableTreeNode, data: CoverageData) {

        data class ChosenName(val filepath: String, var count: Int, val data: CoverageFileData) {
            fun getFilename(): String {
                return filepath.split('/').takeLast(count).joinToString("/")
            }
        }

        val map = mutableMapOf<String, ChosenName>()
        for ((_, value) in data.files) {
            var new = ChosenName(value.filePath.replace('\\', '/'), 1, value)
            val filename = new.getFilename()
            val existing = map[filename]
            if (existing == null) {
                map[filename] = new
                continue
            }
            map.remove(filename)
            var nonNull: ChosenName = existing
            while (new.getFilename() == nonNull.getFilename()) {
                new = new.copy(count = new.count + 1)
                nonNull = nonNull.copy(count = nonNull.count + 1)
            }
            map[new.getFilename()] = new
            map[nonNull.getFilename()] = nonNull
        }

        val fileDataToName = map.map {
            it.value.data to it.key
        }.toMap()

        for ((_, value) in data.files) {
            val fileNode = object : DefaultMutableTreeNode(value) {
                override fun toString(): String {
                    val coverageFileData = userObject as? CoverageFileData ?: return super.toString()
                    return fileDataToName[coverageFileData] ?: return super.toString()
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
}