package net.zero9178.cov.settings

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.toolchains.*
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
        var conditionalExpBranchCoverageEnabled: Boolean = true,
        var booleanOpBranchCoverageEnabled: Boolean = true,
        var branchCoverageEnabled: Boolean = true,
        var useCoverageAction: Boolean = true,
        var calculateExternalSources: Boolean = false
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

    var conditionalExpCoverageEnabled: Boolean
        get() = myState.conditionalExpBranchCoverageEnabled
        set(value) {
            myState.conditionalExpBranchCoverageEnabled = value
        }

    var branchCoverageEnabled: Boolean
        get() = myState.branchCoverageEnabled
        set(value) {
            myState.branchCoverageEnabled = value
        }

    var useCoverageAction: Boolean
        get() = myState.useCoverageAction
        set(value) {
            myState.useCoverageAction = value
        }

    var calculateExternalSources: Boolean
        get() = myState.calculateExternalSources
        set(value) {
            myState.calculateExternalSources = value
        }

    fun getGeneratorFor(toolchain: String) = myGenerators[toolchain]

    override fun getState() = myState

    override fun loadState(state: State) {
        val paths = myState.paths
        myState = state
        myState.paths.forEach {
            if (paths.contains(it.key)) {
                paths[it.key] = it.value
            }
        }
        myState.paths = paths
        ensurePopulatedPaths()
    }

    private fun ensurePopulatedPaths() {
        ApplicationManager.getApplication().executeOnPooledThread {
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
    }

    private fun generateGeneratorFor(name: String, info: GeneratorInfo) {
        myGenerators[name] = getGeneratorFor(
            info.gcovOrllvmCovPath,
            info.llvmProfDataPath,
            info.demangler,
            CPPToolchains.getInstance().toolchains.find { it.name == name }?.wsl
        )
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
                    try {
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
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e)
                    }
                }
            })
        ensurePopulatedPaths()
    }

    companion object {
        fun getInstance() = service<CoverageGeneratorSettings>()

        val log = Logger.getInstance(CoverageGeneratorSettings::class.java)
    }

    class Registrator : AppLifecycleListener {
        override fun appFrameCreated(commandLineArgs: MutableList<String>) {
            getInstance()
        }
    }
}

private fun guessCoverageGeneratorForToolchain(toolchain: CPPToolchains.Toolchain): CoverageGeneratorSettings.GeneratorInfo {
    val toolset = toolchain.toolSet
    var compiler =
        toolchain.customCXXCompilerPath
            ?: if (toolset !is WSL) System.getenv("CXX")?.ifBlank { System.getenv("CC") } else null
    val findExe = { prefix: String, name: String, suffix: String, extraPath: Path? ->
        val insideSameDir = extraPath?.toFile()?.listFiles()?.asSequence()?.map {
            "($prefix)?$name($suffix)?".toRegex().matchEntire(it.name)
        }?.filterNotNull()?.maxByOrNull {
            it.value.length
        }?.value
        when {
            insideSameDir != null -> extraPath.resolve(insideSameDir).toString()
            toolset !is WSL -> {
                val pair = System.getenv("PATH").splitToSequence(File.pathSeparatorChar).asSequence().map {
                    Paths.get(it).toFile()
                }.map { path ->
                    val result = path.listFiles()?.asSequence()?.map {
                        it to "($prefix)?$name($suffix)?".toRegex().matchEntire(it.name)
                    }?.filter { it.second != null }?.map { it.first to it.second!! }
                        ?.maxByOrNull { it.second.value.length }
                    result
                }.filterNotNull().maxByOrNull { it.second.value.length }
                pair?.first?.absolutePath
            }
            else -> null
        }
    }

    if (compiler != null && compiler.contains("clang", true)) {
        //We are using clang so we need to look for llvm-cov. We are first going to check if
        //llvm-cov is next to the compiler. If not we looking for it on PATH

        val compilerName = Paths.get(compiler).fileName.toString()

        val clangName = when {
            compilerName.contains("clang++") -> "clang++"
            compilerName.contains("clang-cl") -> "clang-cl"
            else -> "clang"
        }

        val prefix = compilerName.substringBefore(clangName)

        val suffix = compilerName.substringAfter(clangName)

        val covPath = findExe(
            prefix,
            "llvm-cov",
            suffix,
            Paths.get(if (toolset is WSL) toolset.toLocalPath(null, compiler) else compiler).parent
        )

        val profPath = findExe(
            prefix,
            "llvm-profdata",
            suffix,
            Paths.get(if (toolset is WSL) toolset.toLocalPath(null, compiler) else compiler).parent
        )

        val finalFilt = if (toolset !is MSVC) {
            val llvmFilt = findExe(
                prefix,
                "llvm-cxxfilt",
                suffix,
                Paths.get(if (toolset is WSL) toolset.toLocalPath(null, compiler) else compiler).parent
            )

            llvmFilt ?: findExe(
                prefix,
                "c++filt",
                suffix,
                Paths.get(if (toolset is WSL) toolset.toLocalPath(null, compiler) else compiler).parent
            )
        } else {
            findExe(
                prefix,
                "llvm-undname",
                suffix,
                Paths.get(if (toolset is WSL) toolset.toLocalPath(null, compiler) else compiler).parent
            )
        }

        return if (profPath == null || covPath == null) {
            CoverageGeneratorSettings.GeneratorInfo()
        } else {
            CoverageGeneratorSettings.GeneratorInfo(
                if (toolset is WSL) toolset.toEnvPath(covPath) else covPath,
                if (toolset is WSL) toolset.toEnvPath(profPath) else profPath,
                if (finalFilt != null) {
                    if (toolset is WSL) toolset.toEnvPath(finalFilt) else finalFilt
                } else null
            )
        }
    } else if (compiler == null) {
        if (toolset is MSVC) {
            // If the toolset is MSVC but the compiler isn't clang we have no chance. We are done here
            return CoverageGeneratorSettings.GeneratorInfo()
        }
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

    val gcovPath = findExe(
        prefix,
        "gcov",
        suffix,
        Paths.get(if (toolset is WSL) toolset.toLocalPath(null, compiler) else compiler).parent
    )
    return if (gcovPath != null) {
        CoverageGeneratorSettings.GeneratorInfo(if (toolset is WSL) toolset.toEnvPath(gcovPath) else gcovPath)
    } else {
        CoverageGeneratorSettings.GeneratorInfo()
    }
}