package GCov.Data;

import GCov.State.ShowNonProjectSourcesState;
import GCov.Window.CoverageTree;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@State(name = "GCoverageCoverageData")
public class CoverageData implements PersistentStateComponent<CoverageData> {

    @Nullable
    @Override
    public CoverageData getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull CoverageData coverageData) {
        XmlSerializerUtil.copyBean(coverageData,this);
    }

    private Project m_project;
    private final Map<String, CoverageFileData> m_data = new TreeMap<>();

    public static CoverageData getInstance(@NotNull Project project) {
        CoverageData instance = ServiceManager.getService(project,CoverageData.class);
        if(instance != null) {
            instance.m_project = project;
        }
        return instance;
    }

    @Contract(pure = true)
    public Map<String, CoverageFileData> getData() {
        return m_data;
    }

    @Nullable
    public CoverageFileData getCoverageFromPath(String path) {
        return m_data.get(path);
    }

    private void restartDaemonForFile(String file) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByIoFile(Paths.get(file).toFile());
        if(virtualFile == null) {
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(m_project).findFile(virtualFile);
        if(psiFile == null) {
            return;
        }
        DaemonCodeAnalyzer.getInstance(m_project).restart(psiFile);
    }

    public void clearCoverage() {
        List<String> files = new ArrayList<>(m_data.keySet());
        m_data.clear();
        for(String file : files) {
            if(m_project.getBasePath() == null || !file.startsWith(m_project.getBasePath()))
            {
                continue;
            }
            restartDaemonForFile(file);
        }
    }

    public void updateEditor() {
        for (Map.Entry<String,CoverageFileData> entry : m_data.entrySet()) {
            if(m_project.getBasePath() == null || !entry.getKey().startsWith(m_project.getBasePath()))
            {
                continue;
            }
            restartDaemonForFile(entry.getKey());
        }
    }

    public void display(CoverageTree tree) {
        if (m_data.isEmpty()) {
            tree.getEmptyText().setText("No coverage data found. Did you compile with \"--coverage\"?");
            return;
        }

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("invisibile-root");
        for(Map.Entry<String,CoverageFileData> entry : m_data.entrySet()) {
            if(!ShowNonProjectSourcesState.getInstance(m_project).showNonProjectSources && (m_project.getBasePath() == null || !entry.getKey().startsWith(m_project.getBasePath()))) {
                continue;
            }

            DefaultMutableTreeNode file = new DefaultMutableTreeNode(entry.getValue())
            {
                @Override
                public String toString() {
                    if(userObject == null) {
                        return null;
                    }
                    if(!(userObject instanceof CoverageFileData)) {
                        return userObject.toString();
                    } else {
                        if(ShowNonProjectSourcesState.getInstance(m_project).showNonProjectSources || m_project.getBasePath() == null) {
                            return ((CoverageFileData) userObject).getFilePath();
                        } else {
                            return ((CoverageFileData)userObject).getFilePath().substring(m_project.getBasePath().length()+1);
                        }
                    }
                }
            };
            root.add(file);
            for(Map.Entry<String,CoverageFunctionData> functionDataEntry : entry.getValue().getFunctionData().entrySet()) {
                DefaultMutableTreeNode function = new DefaultMutableTreeNode(functionDataEntry.getValue());
                file.add(function);
            }
        }

        tree.setModel(new ListTreeTableModelOnColumns(root,CoverageTree.getColumnInfo()));
        tree.setRootVisible(false);
        updateEditor();
    }
}
