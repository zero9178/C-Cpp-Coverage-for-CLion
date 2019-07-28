package net.zero9178.cov.window;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.dualView.TreeTableView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

public abstract class CoverageView {

    protected TreeTableView myTreeTableView;
    protected JButton myClear;
    protected JBCheckBox myIncludeNonProjectSources;
    private JPanel myPanel;

    @NotNull
    public static CoverageView getInstance(@NotNull Project project) {
        return project.getComponent(CoverageView.class);
    }

    public JPanel getPanel() {
        return myPanel;
    }

    protected abstract void createUIComponents();

    public abstract void setRoot(@Nullable DefaultMutableTreeNode treeNode);
}
