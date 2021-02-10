package net.zero9178.cov.window

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsListener
import com.jetbrains.cidr.cpp.toolchains.WSL
import net.zero9178.cov.settings.CoverageGeneratorSettings
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.nio.file.Paths
import javax.swing.DefaultComboBoxModel

class SettingsWindowImpl : SettingsWindow() {

    init {
        myLLVMProfdataBrowser.isVisible = false
        myLLVMProfLabel.isVisible = false
        myDemanglerBrowser.isVisible = false
        myDemanglerLabel.isVisible = false

        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(CPPToolchainsListener.TOPIC, object : CPPToolchainsListener {
                override fun toolchainsRenamed(renamed: MutableMap<String, String>) {
                    for (renames in renamed) {
                        val value = myTempToolchainState[renames.key] ?: continue
                        myTempToolchainState.remove(renames.key)
                        myTempToolchainState[renames.value] = value
                    }
                    updateToolChainComboBox()
                    updateUIAfterItemChange()
                }

                override fun toolchainCMakeEnvironmentChanged(toolchains: MutableSet<CPPToolchains.Toolchain>) {
                    updateToolChainComboBox()
                    toolchains.groupBy {
                        CPPToolchains.getInstance().toolchains.contains(it)
                    }.forEach { group ->
                        if (group.key) {
                            group.value.forEach {
                                //I am not sure at all yet if one can assume the order of the notification delivery. For now lets
                                //just be happy if the order was correct (CoverageGeneratorPaths.kt was called first) and if not
                                //do an empty string
                                myTempToolchainState[it.name] =
                                    CoverageGeneratorSettings.getInstance().paths.getOrDefault(
                                        it.name,
                                        CoverageGeneratorSettings.GeneratorInfo()
                                    )
                            }
                        } else {
                            group.value.forEach {
                                myTempToolchainState.remove(it.name)
                            }
                        }
                    }
                }
            })
        updateToolChainComboBox()

        class MyTextBrowseFolderListener(
            textFieldWithBrowseButton: TextFieldWithBrowseButton,
            val runnable: (String, CoverageGeneratorSettings.GeneratorInfo, String) -> Unit
        ) :
            TextBrowseFolderListener(
                FileChooserDescriptor(true, false, false, false, false, false)
            ), KeyListener {

            init {
                textFieldWithBrowseButton.addBrowseFolderListener(this)
                textFieldWithBrowseButton.textField.addKeyListener(this)
            }

            override fun onFileChosen(chosenFile: VirtualFile) {
                super.onFileChosen(chosenFile)
                textChanged()
            }

            private fun textChanged() {
                val selectedItem = myComboBox.selectedItem as? String ?: return
                val info = myTempToolchainState[selectedItem] ?: return
                runnable(componentText, info, selectedItem)
            }

            override fun keyReleased(e: KeyEvent?) {
                textChanged()
            }

            override fun keyTyped(e: KeyEvent?) {}

            override fun keyPressed(e: KeyEvent?) {}
        }

        MyTextBrowseFolderListener(myGcovOrllvmCovBrowser) { text, info, selectedItem ->
            info.gcovOrllvmCovPath = text
            updateLLVMFields(CPPToolchains.getInstance().toolchains.find { it.name == selectedItem }?.wsl)
        }

        MyTextBrowseFolderListener(myLLVMProfdataBrowser) { text, info, _ ->
            info.llvmProfDataPath = text
        }

        MyTextBrowseFolderListener(myDemanglerBrowser) { text, info, _ ->
            info.demangler = text
        }

        myComboBox.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                updateUIAfterItemChange()
            }
        }

        myDocHyperlink.setHyperlinkTarget("https://github.com/zero9178/C-Cpp-Coverage-for-CLion")
        myDocHyperlink.setTextWithHyperlink("For more information see: <hyperlink>https://github.com/zero9178/C-Cpp-Coverage-for-CLion</hyperlink>")
        myIfBranchCoverage.isSelected = CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
        myLoopBranchCoverage.isSelected = CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
        myCondBranchCoverage.isSelected = CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled
        myBooleanOpBranchCoverage.isSelected = CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
        myUseRunner.isSelected = CoverageGeneratorSettings.getInstance().useCoverageAction
        myDoBranchCoverage.isSelected = CoverageGeneratorSettings.getInstance().branchCoverageEnabled
        myCalculateExternal.isSelected = CoverageGeneratorSettings.getInstance().calculateExternalSources
    }

    private fun updateToolChainComboBox() {
        myComboBox.model = DefaultComboBoxModel(CPPToolchains.getInstance().toolchains.map { it.name }.toTypedArray())
    }

    private fun updateUIAfterItemChange() {
        val toolchainName = myComboBox.selectedItem as? String ?: return
        val wsl = CPPToolchains.getInstance().toolchains.find {
            it.name == toolchainName
        }?.wsl
        myGcovOrllvmCovBrowser.text = myTempToolchainState[toolchainName]?.gcovOrllvmCovPath ?: ""
        myLLVMProfdataBrowser.text = myTempToolchainState[toolchainName]?.llvmProfDataPath ?: ""
        myDemanglerBrowser.text = myTempToolchainState[toolchainName]?.demangler ?: ""
        updateLLVMFields(wsl)
    }

    private fun updateLLVMFields(wsl: WSL?) {
        if (myGcovOrllvmCovBrowser.text.isBlank()) {
            myErrors.text = "No executable specified"
            myErrors.icon = AllIcons.General.Warning
            myLLVMProfLabel.isVisible = false
            myLLVMProfdataBrowser.isVisible = false
            myDemanglerLabel.isVisible = false
            myDemanglerBrowser.isVisible = false
            myGcovOrLLVMCovLabel.text = "gcov or llvm-cov:"
            return
        }
        if (if (wsl == null) !Paths.get(myGcovOrllvmCovBrowser.text).exists() else false) {
            myErrors.text = "'${myGcovOrllvmCovBrowser.text}' is not a valid path to an executable"
            myErrors.icon = AllIcons.General.Warning
            myLLVMProfLabel.isVisible = false
            myLLVMProfdataBrowser.isVisible = false
            myDemanglerLabel.isVisible = false
            myDemanglerBrowser.isVisible = false
            myGcovOrLLVMCovLabel.text = "gcov or llvm-cov:"
            return
        }
        if (!myGcovOrllvmCovBrowser.text.contains("(llvm-cov|gcov)".toRegex(RegexOption.IGNORE_CASE))) {
            myErrors.text = "'${myGcovOrllvmCovBrowser.text}' is neither gcov nor llvm-cov"
            myErrors.icon = AllIcons.General.Warning
            myLLVMProfLabel.isVisible = false
            myLLVMProfdataBrowser.isVisible = false
            myDemanglerLabel.isVisible = false
            myDemanglerBrowser.isVisible = false
            myGcovOrLLVMCovLabel.text = "gcov or llvm-cov:"
            return
        }
        myErrors.text = ""
        myErrors.icon = null
        myLLVMProfLabel.isVisible = myGcovOrllvmCovBrowser.text.contains("llvm-cov", true)
        myLLVMProfdataBrowser.isVisible = myLLVMProfLabel.isVisible
        myDemanglerLabel.isVisible = myLLVMProfLabel.isVisible
        myDemanglerBrowser.isVisible = myLLVMProfLabel.isVisible
        myGcovOrLLVMCovLabel.text = if (myLLVMProfLabel.isVisible) "llvm-cov:" else "gcov:"
    }

    private val myTempToolchainState: MutableMap<String, CoverageGeneratorSettings.GeneratorInfo> =
        CoverageGeneratorSettings.getInstance().paths.mapValues { it.value.copy() }.toMutableMap()

    //toMutableMap creates a copy of the map instead of copying the reference

    init {
        updateUIAfterItemChange()
    }

    override fun isModified() =
        CoverageGeneratorSettings.getInstance().paths != myTempToolchainState || myIfBranchCoverage.isSelected != CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
                || myLoopBranchCoverage.isSelected != CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
                || myCondBranchCoverage.isSelected != CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled
                || myBooleanOpBranchCoverage.isSelected != CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
                || myDoBranchCoverage.isSelected != CoverageGeneratorSettings.getInstance().branchCoverageEnabled
                || myUseRunner.isSelected != CoverageGeneratorSettings.getInstance().useCoverageAction
                || myCalculateExternal.isSelected != CoverageGeneratorSettings.getInstance().calculateExternalSources

    override fun getDisplayName() = "C/C++ Coverage"

    override fun apply() {
        CoverageGeneratorSettings.getInstance().paths =
            myTempToolchainState.mapValues { it.value.copy() }.toMutableMap()
        CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled = myIfBranchCoverage.isSelected
        CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled = myLoopBranchCoverage.isSelected
        CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled = myCondBranchCoverage.isSelected
        CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled = myBooleanOpBranchCoverage.isSelected
        CoverageGeneratorSettings.getInstance().useCoverageAction = myUseRunner.isSelected
        CoverageGeneratorSettings.getInstance().branchCoverageEnabled = myDoBranchCoverage.isSelected
        CoverageGeneratorSettings.getInstance().calculateExternalSources = myCalculateExternal.isSelected
    }
}