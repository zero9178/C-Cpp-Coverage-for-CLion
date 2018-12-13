package gcov.data

import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import gcov.notification.GCovNotification
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.toList

class CoverageThread(private val m_project: Project, private val m_buildDirectory: String, private val myRunner: Runnable?) : Thread() {
    private val myData = CoverageData.getInstance(m_project)

    private fun generateGCDA(gcda: File) {
        try {
            val nullFile = if (System.getProperty("os.name").startsWith("Windows")) {
                "NUL"
            } else {
                "/dev/zero"
            }
            val builder = ProcessBuilder("gcov", "-i", "-m", "-b", gcda.toString()).run {
                directory(gcda.parentFile)
                redirectOutput(File(nullFile))
                redirectErrorStream(true)
            }
            val p = builder.start()
            val retCode = p.waitFor()
            if (retCode != 0) {
                log.warn("\"gcov\" returned with error code $retCode")
            }
        } catch (e: IOException) {
            val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                    .createNotification("\"gcov\" was not found in system path", NotificationType.ERROR)
            Notifications.Bus.notify(notification, m_project)
        } catch (e: InterruptedException) {
            val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                    .createNotification("Process timed out", NotificationType.ERROR)
            Notifications.Bus.notify(notification, m_project)
        }

    }

    private fun parseGCov(lines: List<String>) {
        var currentFile: CoverageFileData? = null
        loop@ for (line in lines) {
            val substring = line.substring(line.indexOf(':') + 1)
            when (line.substring(0, line.indexOf(':'))) {
                "file" -> {
                    currentFile = myData.getCoverageFromPath(substring)
                    if (currentFile == null) {
                        currentFile = CoverageFileData(substring)
                        myData.setCoverageForPath(substring, currentFile)
                    }
                }
                "function" -> {
                    if (currentFile == null) {
                        log.assertTrue(false, "\"function\" statement found before a file statement")
                        break@loop
                    }
                    val list = substring.split(',')
                    val startLine = Integer.parseInt(list[0])
                    val endLine = Integer.parseInt(list[1])
                    val executionCount = Integer.parseInt(list[2])
                    val function = list.drop(3).fold("") { lhs, rhs -> lhs + rhs }
                    currentFile.emplaceFunction(startLine, endLine, executionCount, function)
                }
                "lcount" -> {
                    if (currentFile == null) {
                        log.assertTrue(false, "\"lcount\" statement found before a file statement")
                        break@loop
                    }
                    val list = substring.split(',')
                    val lineNumber = Integer.parseInt(list[0])
                    val executionCount = Integer.parseInt(list[1])
                    val functionData = currentFile.functionFromLine(lineNumber)
                    functionData.emplaceLine(lineNumber, executionCount, Integer.parseInt(list[2]) == 1)
                }
                "branch" -> {

                }
                "version" -> {

                }
                else -> {
                    log.warn("File is not complete, found symbol " + line.substring(0, line.indexOf(':')))
                    break@loop
                }
            }
        }
    }

    override fun run() {
        myData.clearCoverage()
        File(m_buildDirectory).walkTopDown().asSequence().filter {
            it.isFile && it.name.endsWith(".gcda")
        }.forEach {
            generateGCDA(it)
            val gcov = it.toString() + ".gcov"
            if (!Paths.get(gcov).toFile().exists()) {
                return
            }

            val lines = Files.lines(Paths.get(gcov)).toList()
            if (!Paths.get(gcov).toFile().delete() || lines.isEmpty()) {
                return
            }
            parseGCov(lines)
        }

        if (myRunner != null) {
            ApplicationManager.getApplication().invokeLater(myRunner)
        }
    }

    companion object {

        private val log = Logger.getInstance(CoverageData::class.java)
    }
}
