package GCov.Data;

import GCov.Notification.GCovNotification;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class CoverageThread extends Thread {

    private static final Logger log = Logger.getInstance(CoverageData.class);
    private final Runnable m_runner;
    private final String m_buildDirectory;
    private final Project m_project;
    private final CoverageData m_data;

    public CoverageThread(@NotNull Project project, String buildDirectory, Runnable runner) {
        m_runner = runner;
        m_buildDirectory = buildDirectory;
        m_project = project;
        m_data = CoverageData.getInstance(m_project);
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
                Notification notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                        .createNotification("Process timed out", NotificationType.ERROR);
                Notifications.Bus.notify(notification,m_project);
            }
            if (retCode != 0) {
                Notification notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                        .createNotification("\"gcov\" returned with error code " + retCode, NotificationType.ERROR);
                Notifications.Bus.notify(notification,m_project);
            }
        } catch (IOException e) {
            Notification notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                    .createNotification("\"gcov\" was not found in system path", NotificationType.ERROR);
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
                    currentFile = m_data.getData().get(substring);
                    if (currentFile == null) {
                        currentFile = new CoverageFileData(substring,
                                m_project.getBasePath() != null && substring.startsWith(m_project.getBasePath()));
                        m_data.getData().put(substring, currentFile);
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

    @Override
    public void run() {
        m_data.clearCoverage();
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
            ApplicationManager.getApplication().invokeLater(m_runner);
        }
    }
}
