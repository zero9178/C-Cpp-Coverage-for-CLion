package net.zero9178.cov.window

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsListener
import net.zero9178.cov.settings.CoverageGeneratorPaths
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Paths
import javax.swing.DefaultComboBoxModel

class SettingsWindowImpl : SettingsWindow() {

    init {
        m_llvmProfdataBrowser.isVisible = false
        m_llvmProfLabel.isVisible = false
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(CPPToolchainsListener.TOPIC, object : CPPToolchainsListener {
                override fun toolchainsRenamed(renamed: MutableMap<String, String>) {
                    for (renames in renamed) {
                        val value = myEditorState[renames.key] ?: continue
                        myEditorState.remove(renames.key)
                        myEditorState[renames.value] = value
                    }
                    updateToolChainComboBox()
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
                                myEditorState[it.name] = CoverageGeneratorPaths.getInstance().paths.getOrDefault(
                                    it.name,
                                    CoverageGeneratorPaths.GeneratorInfo()
                                )
                            }
                        } else {
                            group.value.forEach {
                                myEditorState.remove(it.name)
                            }
                        }
                    }
                }
            })
        updateToolChainComboBox()

        m_gcovOrllvmCovBrowser.addBrowseFolderListener(
            object : TextBrowseFolderListener(
                FileChooserDescriptor(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false
                )
            ) {
                override fun onFileChosen(chosenFile: VirtualFile) {
                    super.onFileChosen(chosenFile)
                    val selectedItem = m_comboBox.selectedItem as? String ?: return
                    val info = myEditorState[selectedItem]
                    if (info != null) {
                        info.gcovOrllvmCovPath = m_gcovOrllvmCovBrowser.text
                        updateLLVMFields()
                    }
                }
            }
        )
        m_gcovOrllvmCovBrowser.textField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                val selectedItem = m_comboBox.selectedItem as? String ?: return
                val info = myEditorState[selectedItem]
                if (info != null) {
                    info.gcovOrllvmCovPath = m_gcovOrllvmCovBrowser.text
                    updateLLVMFields()
                }
            }
        })

        m_llvmProfdataBrowser.addBrowseFolderListener(
            object : TextBrowseFolderListener(
                FileChooserDescriptor(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false
                )
            ) {
                override fun onFileChosen(chosenFile: VirtualFile) {
                    super.onFileChosen(chosenFile)
                    val selectedItem = m_comboBox.selectedItem as? String ?: return
                    val info = myEditorState[selectedItem]
                    if (info != null) {
                        info.llvmProfDataPath = m_llvmProfdataBrowser.text
                    }
                }
            }
        )
        m_llvmProfdataBrowser.textField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                val selectedItem = m_comboBox.selectedItem as? String ?: return
                val info = myEditorState[selectedItem]
                if (info != null) {
                    info.llvmProfDataPath = m_llvmProfdataBrowser.text
                }
            }
        })

        m_comboBox.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                updateUIAfterItemChange()
            }
        }
    }

    private fun updateToolChainComboBox() {
        m_comboBox.model = DefaultComboBoxModel(CPPToolchains.getInstance().toolchains.map { it.name }.toTypedArray())
    }

    private fun updateUIAfterItemChange() {
        val toolchainName = m_comboBox.selectedItem as? String ?: return
        m_gcovOrllvmCovBrowser.text = myEditorState[toolchainName]?.gcovOrllvmCovPath ?: ""
        m_llvmProfdataBrowser.text = myEditorState[toolchainName]?.llvmProfDataPath ?: ""
        updateLLVMFields()
    }

    private fun updateLLVMFields() {
        if (m_gcovOrllvmCovBrowser.text.isBlank()) {
            m_errors.text = "No executable specified"
            m_errors.icon = AllIcons.General.Warning
            m_llvmProfLabel.isVisible = false
            m_llvmProfdataBrowser.isVisible = false
            return
        }
        if (!Paths.get(m_gcovOrllvmCovBrowser.text).exists()) {
            m_errors.text = "'${m_gcovOrllvmCovBrowser.text}' is not a valid path to an executable"
            m_errors.icon = AllIcons.General.Warning
            m_llvmProfLabel.isVisible = false
            m_llvmProfdataBrowser.isVisible = false
            return
        }
        if (!m_gcovOrllvmCovBrowser.text.contains("(llvm-cov|gcov)".toRegex(RegexOption.IGNORE_CASE))) {
            m_errors.text = "'${m_gcovOrllvmCovBrowser.text}' is neither gcov nor llvm-cov"
            m_errors.icon = AllIcons.General.Warning
            m_llvmProfLabel.isVisible = false
            m_llvmProfdataBrowser.isVisible = false
            return
        }
        m_errors.text = ""
        m_errors.icon = null
        m_llvmProfLabel.isVisible = m_gcovOrllvmCovBrowser.text.contains("llvm-cov", true)
        m_llvmProfdataBrowser.isVisible = m_llvmProfLabel.isVisible
        m_gcovOrLLVMCovLabel.text = if (m_llvmProfLabel.isVisible) "llvm-cov:" else "gcov:"
    }

    private val myEditorState: MutableMap<String, CoverageGeneratorPaths.GeneratorInfo> =
        CoverageGeneratorPaths.getInstance().paths.mapValues { it.value.copy() }.toMutableMap()
    //toMutableMap creates a copy of the map instead of copying the reference

    init {
        updateUIAfterItemChange()
    }

    override fun isModified() = CoverageGeneratorPaths.getInstance().paths != myEditorState

    override fun getDisplayName() = "C/C++ Coverage"

    override fun apply() {
        CoverageGeneratorPaths.getInstance().paths = myEditorState.mapValues { it.value.copy() }.toMutableMap()
    }
}