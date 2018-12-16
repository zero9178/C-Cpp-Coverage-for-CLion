package gcov.window;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import gcov.state.GCovSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class GCovSettingsWindow implements Configurable {
    @NotNull
    private Project m_project;
    private JPanel m_panel;
    private TextFieldWithBrowseButton m_pathBrowser;
    private JLabel m_versionOrError;

    public GCovSettingsWindow(@NotNull Project project) {
        m_project = project;
    }

    @Override
    public @Nullable JComponent createComponent() {
        GCovSettings instance = GCovSettings.Companion.getInstance(m_project);
        m_pathBrowser.setText(instance.getGcovPath());
        if(instance.getValid()) {
            m_versionOrError.setText("Version: " + instance.getVersion());
        } else {
            m_versionOrError.setText(instance.getErrorString());
            m_versionOrError.setForeground(JBColor.RED);
        }
        m_pathBrowser.addBrowseFolderListener(new TextBrowseFolderListener(new FileChooserDescriptor(true,false,false,false,false,false),m_project));
        return m_panel;
    }

    @Override
    public boolean isModified() {
        GCovSettings instance = GCovSettings.Companion.getInstance(m_project);
        return instance.getValid() && !m_pathBrowser.getText().replace('\\','/').equals(instance.getGcovPath());
    }

    @Override
    public void apply() {
        GCovSettings instance = GCovSettings.Companion.getInstance(m_project);
        instance.setGcovPath(m_pathBrowser.getText());
        if(instance.getValid()) {
            m_versionOrError.setText("Version: " + instance.getVersion());
        } else {
            m_versionOrError.setText(instance.getErrorString());
            m_versionOrError.setForeground(JBColor.RED);
        }
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "GCoverage";
    }
}
