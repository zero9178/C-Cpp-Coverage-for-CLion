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
class CoverageGeneratorPaths : PersistentStateComponent<CoverageGeneratorPaths.State> {

    data class GeneratorInfo(var gcovOrllvmCovPath: String = "", var llvmProfDataPath: String? = null) {
        fun copy() = GeneratorInfo(gcovOrllvmCovPath, llvmProfDataPath)
    }

    data class State(var paths: MutableMap<String, GeneratorInfo> = mutableMapOf())

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

    fun getGeneratorFor(toolchain: String) = myGenerators[toolchain]

    override fun getState() = myState

    override fun loadState(state: State) {
        myState = state
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
        myGenerators[name] = getGeneratorFor(info.gcovOrllvmCovPath, info.llvmProfDataPath)
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
        fun getInstance() = ApplicationManager.getApplication().getComponent(CoverageGeneratorPaths::class.java)!!
    }
}

private fun guessCoverageGeneratorForToolchain(toolchain: CPPToolchains.Toolchain): CoverageGeneratorPaths.GeneratorInfo {
    val toolset = toolchain.toolSet ?: return CoverageGeneratorPaths.GeneratorInfo()
    //Lets not deal with WSL yet
    return if (toolset is MinGW) {
        if (toolchain.customCCompilerPath != null || toolchain.customCXXCompilerPath != null) {
            val compiler = toolchain.customCCompilerPath ?: toolchain.customCXXCompilerPath
            if (compiler != null && compiler.contains("clang", true)) {
                //We are using clang so we need to look for llvm-cov. We are first going to check if
                //llvm-cov is next to the compiler. If not we looking for it on PATH

                val findExe = { name: String, extraPath: Path ->
                    val executable = if (OSType.getCurrent() == OSType.WIN) "$name.exe" else name
                    val path = extraPath.resolve(executable)
                    if (path.exists()) {
                        path.toString()
                    } else {
                        System.getenv("PATH").splitToSequence(File.pathSeparatorChar).find { directory ->
                            Paths.get(directory).resolve(executable).exists()
                        }?.plus(File.separator + executable)
                    }
                }

                val covPath = findExe("llvm-cov", Paths.get(compiler).parent)

                val profPath = findExe("llvm-profdata", Paths.get(compiler).parent)

                return if (profPath == null || covPath == null) {
                    CoverageGeneratorPaths.GeneratorInfo()
                } else {
                    CoverageGeneratorPaths.GeneratorInfo(covPath, profPath)
                }
            }
        }
        val path = toolset.home.toPath().resolve("bin")
            .resolve(if (OSType.getCurrent() == OSType.WIN) "gcov.exe" else "gcov")
        if (path.exists()) {
            CoverageGeneratorPaths.GeneratorInfo(path.toString())
        } else {
            CoverageGeneratorPaths.GeneratorInfo()
        }
    } else {
        CoverageGeneratorPaths.GeneratorInfo()
    }
}