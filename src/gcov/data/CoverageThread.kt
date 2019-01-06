package gcov.data

import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.toolchains.CPPToolSet
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import gcov.notification.GCovNotification
import gcov.state.GCovSettings
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import kotlin.streams.toList

class CoverageThread(private val myProject: Project, private val myBuildDirectory: String,private val toolchain: CPPToolchains.Toolchain, private val myRunner: Runnable?) : Thread() {
    private val myData = CoverageData.getInstance(myProject)
    private val myLinesSaid = HashSet<List<String>>()

    init {
        name = "CoverageThread"
    }

    private fun generateGCDA(gcda: File):Boolean {
        try {
            var lines = mutableListOf<String>()
            val nullFile = if (System.getProperty("os.name").startsWith("Windows")) {
                "NUL"
            } else {
                "/dev/zero"
            }
            val path = GCovSettings.getInstance().getGCovPathForToolchain(toolchain)?.gcovPath
            if(path == null) {
                val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                        .createNotification("No GCov specified for toolchain $toolchain", NotificationType.ERROR)
                Notifications.Bus.notify(notification, myProject)
                return false
            }
            val builder = if (toolchain.toolSetKind == CPPToolSet.Kind.WSL){
                ProcessBuilder(toolchain.toolSetPath,"run",path, "-i", "-m", "-b", gcda.name.toString())
            }else{
                ProcessBuilder(path, "-i", "-m", "-b", gcda.name.toString())
            }.run {
                directory(gcda.parentFile)
                redirectOutput(File(nullFile))
                redirectErrorStream(false)
            }
            val p = builder.start()
            val reader = BufferedReader(InputStreamReader(p.errorStream))
            do {
                val line = reader.readLine() ?: break
                lines.add(line)
            }while (true)
            val retCode = p.waitFor()
            if (retCode != 0) {
                log.warn("\"gcov\" returned with error code $retCode")
            }
            if(lines.isNotEmpty()) {
                lines = lines.map {
                    val pathConverted = it.replace('\\','/')
                    pathConverted.substring(1 + pathConverted.indexOf(':',pathConverted.indexOf('/')))
                }.toMutableList()
                if(myLinesSaid.add(lines)) {
                    val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                            .createNotification("gcov returned following warning:\n" + lines.joinToString("\n"),NotificationType.WARNING)
                    Notifications.Bus.notify(notification,myProject)
                }
            }
        } catch (e: IOException) {
            val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                    .createNotification("\"gcov\" was not found in system path", NotificationType.ERROR)
            Notifications.Bus.notify(notification, myProject)
        } catch (e: InterruptedException) {
            val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                    .createNotification("Process timed out", NotificationType.ERROR)
            Notifications.Bus.notify(notification, myProject)
        }
        return true
    }

    private fun parseGCov(lines: List<String>) {
        var functionsSet = false
        val version = GCovSettings.getInstance().getGCovPathForToolchain(toolchain)?.version
        if(version == null) {
            val notification = GCovNotification.GROUP_DISPLAY_ID_INFO
                    .createNotification("No GCov specified for toolchain $toolchain", NotificationType.ERROR)
            Notifications.Bus.notify(notification, myProject)
            return
        }
        val wsl = toolchain.wsl
        var currentFile: CoverageFileData? = null
        loop@ for (line in lines) {
            val substring = line.substring(line.indexOf(':') + 1)
            when (line.substring(0, line.indexOf(':'))) {
                "file" -> {
                    currentFile = myData.getCoverageFromPath(wsl?.toLocalPath(null,substring)?.replace('\\','/')
                            ?: substring)
                    if (currentFile == null) {
                        currentFile = if (wsl == null) {
                            CoverageFileData(substring)
                        } else {
                            CoverageFileData(wsl.toLocalPath(null,substring).replace('\\','/'))
                        }
                        myData.setCoverageForPath(currentFile.filePath, currentFile)
                    }
                }
                "function" -> {
                    if (currentFile == null) {
                        log.assertTrue(false, "\"function\" statement found before a file statement")
                        break@loop
                    }
                    val list = substring.split(',')
                    //We are currently skipping execution count when reading
                    functionsSet = false
                    if(version == 8) {
                        val startLine = Integer.parseInt(list[0])
                        val endLine = Integer.parseInt(list[1])
                        val function = list.drop(3).fold("") {lhs,rhs -> if(lhs.isEmpty())rhs else "$lhs,$rhs" }
                        currentFile.emplaceFunction(startLine, endLine, function)
                    } else {
                        val startLine = Integer.parseInt(list[0])
                        val function = list.drop(2).fold("") {lhs,rhs -> if(lhs.isEmpty())rhs else "$lhs,$rhs" }
                        currentFile.emplaceFunction(startLine,-1,function)
                    }
                }
                "lcount" -> {
                    if (currentFile == null) {
                        log.assertTrue(false, "\"lcount\" statement found before a file statement")
                        break@loop
                    }
                    if(!functionsSet) {
                        functionsSet = true
                        if(version < 8) {
                            val list = currentFile.functionData.values.sortedBy { it.startLine }.toList()
                            list.mapIndexed{index,data ->
                                data.endLine = list.getOrNull(index+1)?.startLine?.minus(1) ?: Int.MAX_VALUE
                            }
                        }
                    }
                    val list = substring.split(',')

                    val lineNumber = Integer.parseInt(list[0])
                    val executionCount = Integer.parseInt(list[1])
                    val functionData = currentFile.functionFromLine(lineNumber)
                    functionData.emplaceLine(lineNumber, executionCount)
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
        synchronized(myData) {
            myData.clearCoverage()
            File(myBuildDirectory).walkTopDown().asSequence().filter {
                it.isFile && when {
                    it.name.endsWith(".gcda") -> true
                    it.name.endsWith(".gcov") -> {
                        it.delete()
                        false
                    }
                    else -> false
                }
            }.toList().forEach{
                if(!generateGCDA(it)) {
                    return@run
                }
            }

            File(myBuildDirectory).walkTopDown().asSequence().filter {
                it.isFile && it.name.endsWith(".gcov")
            }.forEach forEach@{
                val lines = Files.lines(it.toPath()).use { stream ->
                    stream.toList()
                }
                if (!it.delete() || lines.isEmpty()) {
                    return@forEach
                }
                parseGCov(lines)
            }
        }

        if (myRunner != null) {
            ApplicationManager.getApplication().invokeLater(myRunner)
        }
    }

    companion object {

        private val log = Logger.getInstance(CoverageThread::class.java)
    }
}
