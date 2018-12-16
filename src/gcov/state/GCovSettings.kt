package gcov.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@State(name = "GCoveragePath")
class GCovSettings : PersistentStateComponent<GCovSettings> {

    var gcovPath = ""
        set(value) {
            field = value
            setVersion()
        }

    init {
        if(gcovPath.isEmpty()) {
            handleEmptyPath()
        }
    }

    @Transient
    var version: Int = 0
        private set

    @Transient
    var valid: Boolean = true
        private set

    @Transient
    var errorString:String = ""
        private set

    override fun getState(): GCovSettings = this

    override fun loadState(settings: GCovSettings) {
        XmlSerializerUtil.copyBean(settings,this)
        if(gcovPath.isEmpty()) {
            handleEmptyPath()
        }
    }

    private fun handleEmptyPath() {
        val path = System.getenv("PATH")!!
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        val list = if(isWindows) {
            path.split(';')
        } else {
            path.split(':')
        }
        val name = if(isWindows) {
            "gcov.exe"
        } else {
            "gcov"
        }
        gcovPath = list.find { File("$it/$name").exists() }?.plus("/$name")?.replace('\\','/') ?: "Not found"
        if(gcovPath == "Not found") {
            valid = false
            errorString = "No $name found on PATH"
        }
    }

    private fun setVersion() {
        if(!File(gcovPath).exists()) {
            return
        }
        val lines = mutableListOf<String>()
        val builder = ProcessBuilder(gcovPath,"--version").run {
            redirectErrorStream(false)
        }
        val p = builder.start()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        do {
            val line = reader.readLine() ?: break
            lines.add(line)
        }while (true)
        val retCode = p.waitFor()
        if(retCode != 0) {
            valid = false
            errorString = "gcov returned with error code $retCode"
            return
        }
        if (lines.isEmpty()) {
            valid = false
            errorString = "Could not determine version of gcov"
            return
        }
        val versionLine = lines.first()
        version = versionLine.substring(versionLine.lastIndexOf(')')+1).trim().split('.').first().toInt()
    }

    companion object {

        fun getInstance(project: Project):GCovSettings = ServiceManager.getService(project,GCovSettings::class.java)!!

    }

}