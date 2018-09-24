package GCov.Data;

import GCov.Notification.GCovNotification;
import GCov.State.ShowNonProjectSourcesState;
import GCov.Window.CoverageTree;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;


public class GCovCoverageGatherer implements ProjectComponent {

    private static final Logger log = Logger.getInstance(GCovCoverageGatherer.class);

    private final Project m_project;
    private String m_buildDirectory;
    private final Map<String, CoverageFileData> m_data;

    public static GCovCoverageGatherer getInstance(@NotNull Project project) {
        return project.getComponent(GCovCoverageGatherer.class);
    }

    private GCovCoverageGatherer(Project project) {
        m_project = project;
        m_data = new TreeMap<>();
    }

    public class CoverageFileData {

        private boolean m_projectSource;
        private final String m_filePath;
        private final Map<String, CoverageFunctionData> m_data = new TreeMap<>();
        private final Map<Integer,CoverageLineData> m_lineDataMap = new HashMap<>();

        CoverageFileData(String filePath,boolean projectSource) {
            m_filePath = filePath;
            m_projectSource = projectSource;
        }

        void emplaceFunction(int startLine, int endLine, int executionCount, String functionName) {
            this.emplaceFunction(new CoverageFunctionData(startLine, endLine, executionCount, functionName));
        }

        void emplaceFunction(CoverageFunctionData functionData) {
            CoverageFunctionData data = m_data.get(functionData.getFunctionName());
            if (data == null) {
                functionData.setFileData(this);
                m_data.put(functionData.getFunctionName(), functionData);
            } else {
                data.setExecutionCount(data.getExecutionCount() + functionData.getExecutionCount());
            }
        }

        @NotNull CoverageFunctionData functionFromLine(int line) {
            for (Map.Entry<String, CoverageFunctionData> entry : m_data.entrySet()) {
                if (entry.getValue().lineIsInFunction(line)) {
                    return entry.getValue();
                }
            }
            CoverageFunctionData unknown = m_data.get("<unknown function>");
            if (unknown == null) {
                unknown = new CoverageFunctionData(-1, -1, 0, "<unknown function>");
                unknown.setFileData(this);
                m_data.put("<unkown function>", unknown);
            }
            return unknown;
        }

        public Map<String, CoverageFunctionData> getData() {
            return m_data;
        }

        @Override
        public String toString() {
            if(m_projectSource && !ShowNonProjectSourcesState.getInstance(m_project).showNonProjectSources && m_project.getBasePath() != null) {
                return m_filePath.substring(1+m_project.getBasePath().length());
            } else {
                return m_filePath;
            }
        }

        public String getFilePath() {
            return m_filePath;
        }

        @Nullable
        public CoverageLineData getLineData(int line) {
            return m_lineDataMap.get(line);
        }
    }

    public static class CoverageFunctionData {

        private final Map<Integer, CoverageLineData> m_lines = new TreeMap<>();
        private final int m_startLine;
        private final int m_endLine;
        private int m_executionCount;
        private final String m_functionName;
        private CoverageFileData m_fileData;

        public CoverageFileData getFileData() {
            return m_fileData;
        }

        void setFileData(CoverageFileData fileData) {
            m_fileData = fileData;
        }

        CoverageFunctionData(int startLine, int endLine, int executionCount, String functionName) {
            m_startLine = startLine;
            m_endLine = endLine;
            m_executionCount = executionCount;
            m_functionName = functionName;
        }

        void emplaceLine(int lineNumber, int executionCount, boolean unexecutedBlock) {
            this.emplaceLine(new CoverageLineData(lineNumber, executionCount, unexecutedBlock));
        }

        void emplaceLine(CoverageLineData lineData) {
            CoverageLineData line = m_lines.get(lineData.getLineNumber());
            if (line == null) {
                m_lines.put(lineData.getLineNumber(), lineData);
                m_fileData.m_lineDataMap.put(lineData.getLineNumber(),lineData);
            } else {
                line.setExecutionCount(line.getExecutionCount() + lineData.getExecutionCount());
            }
        }

        boolean lineIsInFunction(int line) {
            return line >= m_startLine && line <= m_endLine;
        }

        public int getStartLine() {
            return m_startLine;
        }

        int getExecutionCount() {
            return m_executionCount;
        }

        void setExecutionCount(int executionCount) {
            m_executionCount = executionCount;
        }

        String getFunctionName() {
            return m_functionName;
        }

        public int getCoverage() {
            int coverage = 0;
            for(Map.Entry<Integer,CoverageLineData> entry : m_lines.entrySet()) {
                if(entry.getValue().getExecutionCount() > 0) {
                    coverage++;
                }
            }
            return coverage;
        }

        public Map<Integer, CoverageLineData> getLines() {
            return m_lines;
        }

        @Override
        public String toString() {
            return m_functionName;
        }
    }

    public static class CoverageLineData {
        private final int m_lineNumber;
        private int m_executionCount;

        CoverageLineData(int lineNumber, int executionCount, boolean unexecutedBlock) {
            m_lineNumber = lineNumber;
            m_executionCount = executionCount;
        }

        int getLineNumber() {
            return m_lineNumber;
        }

        public int getExecutionCount() {
            return m_executionCount;
        }

        void setExecutionCount(int executionCount) {
            m_executionCount = executionCount;
        }

        public boolean isCovered() {
            return m_executionCount != 0;
        }
    }

    @Nullable
    public CoverageFileData getCoverageFromPath(String path) {
        return m_data.get(path);
    }

    public void setBuildDirectory(String buildDirectory) {
        m_buildDirectory = buildDirectory;
    }

    private boolean generateGCDA(@NotNull Path gcda) {
        int retCode = -1;
        try {
            String nullFile;
            if (System.getProperty("os.name").startsWith("Windows")) {
                nullFile = "NUL";
            } else {
                nullFile = "/dev/zero";
            }
            ProcessBuilder builder = new ProcessBuilder("gcov", "-i", "-m", "-b", gcda.toString())
                    .directory(gcda.toFile().getParentFile())
                    .redirectOutput(new File(nullFile)).redirectErrorStream(true);
            Process p = builder.start();
            try {
                retCode = p.waitFor();
            } catch (InterruptedException e) {
                Notification notification = GCovNotification.GROUP_DISPLAY_ID_INFO.createNotification("Process timed out", NotificationType.ERROR);
                Notifications.Bus.notify(notification,m_project);
            }
            if (retCode != 0) {
                String command = String.join(" ",builder.command());
                log.warn("gcov finished with error code " + retCode + " using following arguments\n" + command);
            }
        } catch (IOException e) {
            Notification notification = GCovNotification.GROUP_DISPLAY_ID_INFO.createNotification("\"gcov\" was not found in system path", NotificationType.ERROR);
            Notifications.Bus.notify(notification,m_project);
        }
        return retCode == 0;
    }

    private void parseGCov(@NotNull List<String> lines) {
        CoverageFileData currentFile = null;
        for (String line : lines) {
            String substring = line.substring(line.indexOf(':') + 1);
            switch (line.substring(0, line.indexOf(':'))) {
                case "file": {
                    currentFile = m_data.get(substring);
                    if (currentFile == null) {
                        currentFile = new CoverageFileData(substring,
                                m_project.getBasePath() != null && substring.startsWith(m_project.getBasePath()));
                        m_data.put(substring, currentFile);
                    }
                }
                break;
                case "function": {
                    log.assertTrue(currentFile != null, "\"function\" statement found before a file statement");
                    String data = substring;
                    int startLine = Integer.parseInt(data.substring(0, data.indexOf(',')));
                    data = data.substring(data.indexOf(',') + 1);
                    int endLine = Integer.parseInt(data.substring(0, data.indexOf(',')));
                    data = data.substring(data.indexOf(',') + 1);
                    int executionCount = Integer.parseInt(data.substring(0, data.indexOf(',')));
                    data = data.substring(data.indexOf(',') + 1);
                    currentFile.emplaceFunction(startLine, endLine, executionCount, data);
                }
                break;
                case "lcount": {
                    log.assertTrue(currentFile != null, "\"lcount\" statement found before a file statement");
                    String data = substring;
                    int lineNumber = Integer.parseInt(data.substring(0, data.indexOf(',')));
                    data = data.substring(data.indexOf(',') + 1);
                    int executionCount = Integer.parseInt(data.substring(0, data.indexOf(',')));
                    data = data.substring(data.indexOf(',') + 1);
                    boolean unexecutedBlock = Integer.parseInt(data) == 1;
                    CoverageFunctionData functionData = currentFile.functionFromLine(lineNumber);
                    functionData.emplaceLine(lineNumber, executionCount, unexecutedBlock);
                }
                break;
                case "branch": {

                }
                break;
                default:
                    break;
            }
        }
    }

    private class GatherRunner extends Thread {

        final Runnable m_runner;

        GatherRunner(@Nullable Runnable runner) {
            m_runner = runner;
        }

        @Override
        public void run() {
            m_data.clear();
            List<Path> gcdaFiles = new ArrayList<>();
            try {
                Files.find(Paths.get(m_buildDirectory), Integer.MAX_VALUE,
                        ((path, basicFileAttributes) -> basicFileAttributes.isRegularFile() && path.toString().endsWith("gcda")))
                        .forEach(gcdaFiles::add);
            } catch (IOException e) {
                e.printStackTrace();
            }

            for (Path gcda : gcdaFiles) {
                if (!generateGCDA(gcda) || !Paths.get(gcda.toString() + ".gcov").toFile().exists()) {
                    continue;
                }

                List<String> lines = new ArrayList<>();
                try (Stream<String> stream = Files.lines(Paths.get(gcda.toString() + ".gcov"))) {
                    stream.forEach(lines::add);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (!Paths.get(gcda.toString() + ".gcov").toFile().delete() || lines.isEmpty()) {
                    continue;
                }
                parseGCov(lines);
            }

            for(Path gcda : gcdaFiles) {
                if(!gcda.toFile().delete()) {
                    log.warn(gcda.toString() + " could not be deleted");
                }
            }

            if(m_runner != null) {
                ApplicationManager.getApplication().invokeLater(m_runner::run);
            }
        }
    }

    public void gather(@Nullable Runnable runnable){
        GatherRunner gatherer = new GatherRunner(runnable);
        gatherer.start();
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
        for (Map.Entry<String,GCovCoverageGatherer.CoverageFileData> entry : m_data.entrySet()) {
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

            DefaultMutableTreeNode file = new DefaultMutableTreeNode(entry.getValue());
            root.add(file);
            for(Map.Entry<String,CoverageFunctionData> functionDataEntry : entry.getValue().getData().entrySet()) {
                DefaultMutableTreeNode function = new DefaultMutableTreeNode(functionDataEntry.getValue());
                file.add(function);
            }
        }

        tree.setModel(new ListTreeTableModelOnColumns(root,CoverageTree.getColumnInfo()));
        tree.setRootVisible(false);
        updateEditor();
    }
}
