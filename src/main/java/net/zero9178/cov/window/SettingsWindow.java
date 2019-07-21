package net.zero9178.cov.window;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public abstract class SettingsWindow implements Configurable {

    protected ComboBox<String> m_comboBox;
    protected TextFieldWithBrowseButton m_gcovOrllvmCovBrowser;
    protected TextFieldWithBrowseButton m_llvmProfdataBrowser;
    protected com.intellij.ui.components.JBLabel m_gcovOrLLVMCovLabel;
    protected com.intellij.ui.components.JBLabel m_llvmProfLabel;
    protected JBLabel m_errors;
    private JPanel m_panel;
    private JBLabel m_loading;

    protected void setLoading(boolean loading) {
        m_loading.setIcon(loading ? new AnimatedIcon.Default() : null);
    }

    @Override
    public @NotNull
    JComponent createComponent() {
        return m_panel;
    }
}
