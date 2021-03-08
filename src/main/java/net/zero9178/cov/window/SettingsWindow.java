package net.zero9178.cov.window;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public abstract class SettingsWindow implements Configurable {

    protected ComboBox<String> myComboBox;
    protected TextFieldWithBrowseButton myGcovOrllvmCovBrowser;
    protected TextFieldWithBrowseButton myLLVMProfdataBrowser;
    protected com.intellij.ui.components.JBLabel myGcovOrLLVMCovLabel;
    protected com.intellij.ui.components.JBLabel myLLVMProfLabel;
    protected JBLabel myErrors;
    protected JBCheckBox myIfBranchCoverage;
    protected JBCheckBox myLoopBranchCoverage;
    protected JBCheckBox myBooleanOpBranchCoverage;
    protected JLabel myDemanglerLabel;
    protected TextFieldWithBrowseButton myDemanglerBrowser;
    private JPanel myPanel;
    private JBLabel myLoading;
    protected JCheckBox myDoBranchCoverage;
    protected JBCheckBox myCondBranchCoverage;
    protected JCheckBox myCalculateExternal;
    protected HyperlinkLabel myDocHyperlink;

    protected void setLoading(boolean loading) {
        myLoading.setIcon(loading ? new AnimatedIcon.Default() : null);
    }

    @Override
    public @NotNull
    JComponent createComponent() {
        return myPanel;
    }
}
