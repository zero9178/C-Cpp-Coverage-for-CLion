package gcov.window;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains;
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsListener;
import gcov.state.GCovSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class GCovSettingsWindow implements Configurable {
    @NotNull
    private Project m_project;
    private JPanel m_panel;
    private TextFieldWithBrowseButton m_pathBrowser;
    private JLabel m_versionOrError;
    private JComboBox<String> m_comboBox;
    private HashMap<String,String> m_paths = new HashMap<>();

    public GCovSettingsWindow(@NotNull Project project) {
        m_project = project;
    }

    private void updateToolChain() {
        m_paths.clear();
        List<CPPToolchains.Toolchain> list = CPPToolchains.getInstance().getToolchains();
        Vector<String> items = new Vector<>();
        GCovSettings instance = GCovSettings.Companion.getInstance();
        for (CPPToolchains.Toolchain toolChain : list) {
            items.add(toolChain.getName());
            GCovSettings.GCov gcov = instance.getGCovPathForToolchain(toolChain);
            if(gcov != null) {
                m_paths.put(toolChain.getName(),gcov.getGcovPath());
            } else {
                m_paths.put(toolChain.getName(),"");
            }
        }
        m_comboBox.setModel(new DefaultComboBoxModel<>(items));
    }

    @Override
    public @Nullable JComponent createComponent() {
        updateToolChain();
        m_project.getMessageBus().connect().subscribe(CPPToolchainsListener.TOPIC, new CPPToolchainsListener() {
            @Override
            public void toolchainsRenamed(@NotNull Map<String, String> renamed) {
                GCovSettings instance = GCovSettings.Companion.getInstance();
                for (Map.Entry<String,String> renames : renamed.entrySet()) {
                    GCovSettings.GCov previousValue = instance.removeGCovPathForToolchain(renames.getKey());
                    if(previousValue != null) {
                        instance.putGCovPathForToolchain(renames.getValue(),previousValue.getGcovPath());
                    }
                }
                updateToolChain();
            }

            @Override
            public void toolchainCMakeEnvironmentChanged(@NotNull Set<CPPToolchains.Toolchain> toolchains) {
                updateToolChain();
            }
        });
        m_comboBox.addActionListener(e -> {
            if (!(e.getSource() instanceof JComboBox)) {
                return;
            }
            JComboBox cb = (JComboBox)e.getSource();
            if(cb.getSelectedItem() == null) {
                return;
            }
            m_pathBrowser.setText(m_paths.getOrDefault(cb.getSelectedItem(),""));
            m_versionOrError.setText("");
        });
        m_pathBrowser.setText(m_paths.getOrDefault(m_comboBox.getSelectedItem(),""));
        m_pathBrowser.addBrowseFolderListener(new TextBrowseFolderListener(new FileChooserDescriptor(true,false,false,false,false,false),m_project));
        return m_panel;
    }

    @Override
    public boolean isModified() {
        return !m_pathBrowser.getText().equals(m_paths.getOrDefault(m_comboBox.getSelectedItem(),""));
    }

    @Override
    public void apply() {
        GCovSettings instance = GCovSettings.Companion.getInstance();
        Object item = m_comboBox.getSelectedItem();
        if (!(item instanceof String)) {
            return;
        }

        GCovSettings.GCov gcov = instance.putGCovPathForToolchain((String)item,m_pathBrowser.getText());
        if (gcov.getValid()) {
            m_versionOrError.setText("Version: " + gcov.getVersion());
        } else {
            m_versionOrError.setText(gcov.getErrorString());
            m_versionOrError.setForeground(JBColor.RED);
        }
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "GCoverage";
    }
}
