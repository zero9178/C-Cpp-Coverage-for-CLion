package gcov.state

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import com.jetbrains.cidr.cpp.toolchains.CPPToolSet
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@State(name = "GCoveragePath",storages = [Storage("gcov.xml",roamingType = RoamingType.DISABLED)])
class GCovSettings : PersistentStateComponent<GCovSettings> {

    @com.intellij.util.xmlb.annotations.Property
    private val myGcovs = mutableMapOf<String, GCov>()

    class GCov {

        var toolchain = ""
            internal set(value) {
                field = value
                setVersion()
            }

        var gcovPath = ""
            set(value) {
                field = value
                setVersion()
            }

        @com.intellij.util.xmlb.annotations.Transient
        var version: Int = 0
            private set

        @com.intellij.util.xmlb.annotations.Transient
        var valid: Boolean = true
            private set

        @com.intellij.util.xmlb.annotations.Transient
        var errorString: String = ""
            private set

        private fun setVersion() {
            valid = true
            errorString = ""
            val toolChain = CPPToolchains.getInstance().getToolchainByNameOrDefault(toolchain)
            if(toolChain == null) {
                valid = false
                errorString = "Could not determine associated toolchain"
                return
            }
            val kind = toolChain.toolSetKind
            if (kind == null) {
                valid = false
                errorString = "Could not determine kind of toolchain"
                return
            }

            if (kind != CPPToolSet.Kind.WSL && !File(gcovPath).exists()) {
                return
            }
            val lines = mutableListOf<String>()
            val builder = if (kind == CPPToolSet.Kind.WSL) {
                ProcessBuilder(toolChain.toolSetPath,"run",gcovPath,"--version").run {
                    redirectErrorStream(false)
                }
            } else {
                ProcessBuilder(gcovPath, "--version").run {
                    redirectErrorStream(false)
                }
            }
            val p = builder.start()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            do {
                val line = reader.readLine() ?: break
                lines.add(line)
            } while (true)
            val retCode = p.waitFor()
            if (retCode != 0) {
                valid = false
                errorString = "gcov returned with error code $retCode"
                return
            }
            if (lines.isEmpty()) {
                valid = false
                errorString = "Could not determine version of gcov"
                return
            }
            var versionLine = lines.first()
            versionLine = versionLine.replace("""\(.*\)""".toRegex(), "")
            val match = """(\d\.?){1,3}""".toRegex().findAll(versionLine)
            val value = match.first().value
            version = value.split('.').first().toInt()
            if (System.getProperty("os.name").startsWith("Mac") && lines.first().contains("LLVM")) {
                version = 7
            }
        }
    }

    fun getGCovPathForToolchain(toolchain: String): GCov? = myGcovs[toolchain]

    fun getGCovPathForToolchain(toolchain: CPPToolchains.Toolchain): GCov? = myGcovs[toolchain.name]

    fun putGCovPathForToolchain(toolchain: String, path: String): GCov {
        val gcov = myGcovs.getOrPut(toolchain) {
            GCov().also {
                it.toolchain = toolchain
            }
        }
        gcov.gcovPath = path
        return gcov
    }

    fun putGCovPathForToolchain(toolchain: CPPToolchains.Toolchain, path: String): GCov = putGCovPathForToolchain(toolchain.name, path)

    override fun getState(): GCovSettings = this

    override fun loadState(settings: GCovSettings) {
        XmlSerializerUtil.copyBean(settings, this)
    }

    companion object {

        fun getInstance(): GCovSettings = ServiceManager.getService(GCovSettings::class.java)!!

    }

}