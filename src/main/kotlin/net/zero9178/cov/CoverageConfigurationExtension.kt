package net.zero9178.cov

import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.execution.CidrBuildTarget
import com.jetbrains.cidr.execution.CidrRunConfiguration
import com.jetbrains.cidr.execution.CidrRunConfigurationExtensionBase
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.toolchains.CidrCompilerSwitches
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import net.zero9178.cov.data.CoverageFileData
import net.zero9178.cov.data.CoverageFunctionData
import net.zero9178.cov.data.CoverageGenerator
import net.zero9178.cov.editor.CoverageHighlighter
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorPaths
import net.zero9178.cov.window.CoverageView
import java.nio.file.Paths
import javax.swing.tree.DefaultMutableTreeNode

class CoverageConfigurationExtension : CidrRunConfigurationExtensionBase() {
    override fun isApplicableFor(configuration: CidrRunConfiguration<*, *>) = true

    override fun isEnabledFor(
        applicableConfiguration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?
    ): Boolean {
        if (environment !is CPPEnvironment || applicableConfiguration !is CMakeAppRunConfiguration) {
            return false
        }
        val executionTarget = ExecutionTargetManager.getInstance(applicableConfiguration.project).activeTarget
        val resolver = applicableConfiguration.getResolveConfiguration(executionTarget) ?: return false
        CLanguageKind.values().forEach {
            val switches = resolver.getCompilerSettings(it, null).compilerSwitches ?: return@forEach
            val list = switches.getList(CidrCompilerSwitches.Format.RAW)
            return list.contains("--coverage") || list.containsAll(
                listOf(
                    "-fprofile-arcs",
                    "-ftest-coverage"
                )
            ) || list.containsAll(
                listOf("-fprofile-instr-generate", "-fcoverage-mapping")
            )
        }
        return false
    }

    override fun patchCommandLine(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        runnerSettings: RunnerSettings?,
        environment: CidrToolEnvironment,
        cmdLine: GeneralCommandLine,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        if (environment !is CPPEnvironment || configuration !is CMakeAppRunConfiguration) {
            return
        }
        getCoverageGenerator(environment, configuration)?.patchEnvironment(configuration, environment, cmdLine)
    }

    override fun attachToProcess(
        configuration: CidrRunConfiguration<*, out CidrBuildTarget<*>>,
        handler: ProcessHandler,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?,
        runnerId: String,
        context: ConfigurationExtensionContext
    ) {
        if (environment !is CPPEnvironment || configuration !is CMakeAppRunConfiguration) {
            return
        }
        val executionTarget = ExecutionTargetManager.getInstance(configuration.project).activeTarget
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}

            override fun processTerminated(event: ProcessEvent) {
                val data =
                    getCoverageGenerator(environment, configuration)?.generateCoverage(
                        configuration,
                        environment,
                        executionTarget
                    )
                        ?: return

                val resolver = configuration.getResolveConfiguration(executionTarget)
                val root = DefaultMutableTreeNode("invisible-root")
                for ((_, value) in data.files) {
                    val fileNode = object : DefaultMutableTreeNode(value) {
                        override fun toString(): String {
                            val filePath = (userObject as? CoverageFileData)?.filePath ?: return userObject.toString()
                            val basePath = configuration.project.basePath ?: return filePath
                            return if (resolver?.sources?.contains(
                                    LocalFileSystem.getInstance().findFileByPath(
                                        environment.toLocalPath(filePath)
                                    )
                                ) == true
                            ) {
                                Paths.get(basePath).relativize(Paths.get(filePath)).toString()
                            } else {
                                filePath
                            }
                        }
                    }

                    root.add(fileNode)
                    for (function in value.functions.values) {
                        fileNode.add(object : DefaultMutableTreeNode(function) {
                            override fun toString() =
                                (userObject as? CoverageFunctionData)?.functionName ?: userObject.toString()
                        })
                    }
                }
                CoverageHighlighter.getInstance(configuration.project).setCoverageData(data)
                ApplicationManager.getApplication().invokeLater {
                    CoverageView.getInstance(configuration.project).setRoot(root)
                }
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}

            override fun startNotified(event: ProcessEvent) {}
        })
    }

    private fun getCoverageGenerator(
        environment: CPPEnvironment,
        configuration: CMakeAppRunConfiguration
    ): CoverageGenerator? {
        val generator = CoverageGeneratorPaths.getInstance().getGeneratorFor(environment.toolchain.name)
        if (generator == null) {
            val notification =
                CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                    "Neither gcov nor llvm-cov specified for ${environment.toolchain.name}",
                    NotificationType.ERROR
                )
            Notifications.Bus.notify(notification, configuration.getProject())
            return null
        }
        val coverageGenerator = generator.first
        if (coverageGenerator == null) {
            if (generator.second != null) {
                val notification =
                    CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                        "Coverage could not be generated due to following error: ${generator.second}",
                        NotificationType.ERROR
                    )
                Notifications.Bus.notify(notification, configuration.getProject())
            }
            return null
        }
        return coverageGenerator
    }
}