package net.zero9178.cov.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsListener
import com.jetbrains.cidr.cpp.toolchains.MinGW
import com.jetbrains.cidr.cpp.toolchains.NativeUnixToolSet
import com.jetbrains.cidr.toolchains.OSType
import net.zero9178.cov.data.CoverageGenerator
import net.zero9178.cov.data.getGeneratorFor
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

@State(
    name = "net.zero9178.coverage.settings",
    storages = [Storage("zero9178.coverage.xml", roamingType = RoamingType.DISABLED)]
)
class CoverageGeneratorSettings : PersistentStateComponent<CoverageGeneratorSettings.State> {

    data class GeneratorInfo(
        var gcovOrllvmCovPath: String = "",
        var llvmProfDataPath: String? = null,
        var demangler: String? = null
    ) {
        fun copy() = GeneratorInfo(gcovOrllvmCovPath, llvmProfDataPath, demangler)
    }

    data class State(
        var paths: MutableMap<String, GeneratorInfo> = mutableMapOf(),
        var ifBranchCoverageEnabled: Boolean = true,
        var loopBranchCoverageEnabled: Boolean = true,
        var booleanOpBranchCoverageEnabled: Boolean = false
    )

    private var myState: State = State()

    private var myGenerators: MutableMap<String, Pair<CoverageGenerator?, String?>> = mutableMapOf()

    var paths: Map<String, GeneratorInfo>
        get() = myState.paths
        set(value) {
            myState.paths = value.toMutableMap()
            myGenerators.clear()
            myState.paths.forEach {
                generateGeneratorFor(it.key, it.value)
            }
        }

    var ifBranchCoverageEnabled: Boolean
        get() = myState.ifBranchCoverageEnabled
        set(value) {
            myState.ifBranchCoverageEnabled = value
        }

    var loopBranchCoverageEnabled: Boolean
        get() = myState.loopBranchCoverageEnabled
        set(value) {
            myState.loopBranchCoverageEnabled = value
        }

    var booleanOpBranchCoverageEnabled: Boolean
        get() = myState.booleanOpBranchCoverageEnabled
        set(value) {
            myState.booleanOpBranchCoverageEnabled = value
        }

    fun getGeneratorFor(toolchain: String) = myGenerators[toolchain]

    override fun getState() = myState

    override fun loadState(state: State) {
        state.paths.forEach {
            if (myState.paths.contains(it.key)) {
                myState.paths[it.key] = it.value
            }
        }
        myState.ifBranchCoverageEnabled = state.ifBranchCoverageEnabled
        myState.booleanOpBranchCoverageEnabled = state.booleanOpBranchCoverageEnabled
        myState.loopBranchCoverageEnabled = state.loopBranchCoverageEnabled
        ensurePopulatedPaths()
    }

    private fun ensurePopulatedPaths() {
        if (paths.isEmpty()) {
            paths = CPPToolchains.getInstance().toolchains.associateBy({ it.name }, {
                guessCoverageGeneratorForToolchain(it)
            }).toMutableMap()
        } else {
            paths = paths.mapValues {
                if (it.value.gcovOrllvmCovPath.isNotEmpty()) {
                    it.value
                } else {
                    val toolchain =
                        CPPToolchains.getInstance().toolchains.find { toolchain -> toolchain.name == it.key }
                            ?: return@mapValues GeneratorInfo()
                    guessCoverageGeneratorForToolchain(toolchain)
                }
            }
        }
    }

    private fun generateGeneratorFor(name: String, info: GeneratorInfo) {
        myGenerators[name] = getGeneratorFor(info.gcovOrllvmCovPath, info.llvmProfDataPath, info.demangler)
    }

    init {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(CPPToolchainsListener.TOPIC, object : CPPToolchainsListener {
                override fun toolchainsRenamed(renamed: MutableMap<String, String>) {
                    for (renames in renamed) {
                        val value = myState.paths.remove(renames.key)
                        if (value != null) {
                            myState.paths[renames.value] = value
                        }
                        val generator = myGenerators.remove(renames.key)
                        if (generator != null) {
                            myGenerators[renames.value] = generator
                        }
                    }
                }

                override fun toolchainCMakeEnvironmentChanged(toolchains: MutableSet<CPPToolchains.Toolchain>) {
                    toolchains.groupBy {
                        CPPToolchains.getInstance().toolchains.contains(it)
                    }.forEach { group ->
                        if (group.key) {
                            group.value.forEach {
                                val path = guessCoverageGeneratorForToolchain(it)
                                myState.paths[it.name] = path
                                generateGeneratorFor(it.name, path)
                            }
                        } else {
                            group.value.forEach {
                                myState.paths.remove(it.name)
                                myGenerators.remove(it.name)
                            }
                        }
                    }
                }
            })
        ensurePopulatedPaths()
    }

    companion object {
        fun getInstance() = ApplicationManager.getApplication().getComponent(CoverageGeneratorSettings::class.java)!!
    }
}

private fun guessCoverageGeneratorForToolchain(toolchain: CPPToolchains.Toolchain): CoverageGeneratorSettings.GeneratorInfo {
    val toolset = toolchain.toolSet
    var compiler =
        toolchain.customCXXCompilerPath ?: System.getenv("CXX")?.ifBlank { System.getenv("CC") }
    //Lets not deal with WSL yet
    return if (toolset is MinGW || toolset is NativeUnixToolSet) {

        val findExe = { prefix: String, name: String, suffix: String, extraPath: Path ->
            val insideSameDir = extraPath.toFile().listFiles()?.asSequence()?.map {
                "($prefix)?$name($suffix)?".toRegex().matchEntire(it.name)
            }?.filterNotNull()?.maxBy {
                it.value.length
            }?.value
            if (insideSameDir != null) {
                extraPath.resolve(insideSameDir).toString()
            } else {
                val pair = System.getenv("PATH").splitToSequence(File.pathSeparatorChar).asSequence().map {
                    Paths.get(it).toFile()
                }.map { path ->
                    val result = path.listFiles()?.asSequence()?.map {
                        it to "($prefix)?$name($suffix)?".toRegex().matchEntire(it.name)
                    }?.filter { it.second != null }?.map { it.first to it.second!! }?.maxBy { it.second.value.length }
                    result
                }.filterNotNull().maxBy { it.second.value.length }
                pair?.first?.absolutePath
            }
        }

        if (compiler != null && compiler.contains("clang", true)) {
            //We are using clang so we need to look for llvm-cov. We are first going to check if
            //llvm-cov is next to the compiler. If not we looking for it on PATH

            val compilerName = Paths.get(compiler).fileName.toString()

            val clangName = if (compilerName.contains("clang++")) "clang++" else "clang"

            val prefix = compilerName.substringBefore(clangName)

            val suffix = compilerName.substringAfter(clangName)

            val covPath = findExe(prefix, "llvm-cov", suffix, Paths.get(compiler).parent)

            val profPath = findExe(prefix, "llvm-profdata", suffix, Paths.get(compiler).parent)

            val llvmFilt = findExe(prefix, "llvm-cxxfilt", suffix, Paths.get(compiler).parent)

            return if (profPath == null || covPath == null) {
                CoverageGeneratorSettings.GeneratorInfo()
            } else {
                CoverageGeneratorSettings.GeneratorInfo(
                    covPath,
                    profPath,
                    llvmFilt ?: findExe(prefix, "c++filt", suffix, Paths.get(compiler).parent)
                )
            }
        } else if (compiler == null) {
            if (toolset is MinGW) {
                val path = toolset.home.toPath().resolve("bin")
                    .resolve(if (OSType.getCurrent() == OSType.WIN) "gcov.exe" else "gcov")
                return if (path.exists()) {
                    CoverageGeneratorSettings.GeneratorInfo(path.toString())
                } else {
                    CoverageGeneratorSettings.GeneratorInfo()
                }
            }
            compiler = "/usr/bin/gcc"
        }

        val compilerName = Paths.get(compiler).fileName.toString()

        val gccName = if (compilerName.contains("g++")) "g++" else "gcc"

        val prefix = compilerName.substringBefore(gccName)

        val suffix = compilerName.substringAfter(gccName)

        val gcovPath = findExe(prefix, "gcov", suffix, Paths.get(compiler).parent)
        if (gcovPath != null) {
            CoverageGeneratorSettings.GeneratorInfo(gcovPath)
        } else {
            CoverageGeneratorSettings.GeneratorInfo()
        }
    } else {
        CoverageGeneratorSettings.GeneratorInfo()
    }
}