package gcov.messaging

import com.intellij.util.messages.Topic
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains

interface CoverageProcessEnded {

    fun ended(buildDirectory: String, toolchain: CPPToolchains.Toolchain)

    companion object {
        val GCOVERAGE_RUN_ENDED_TOPIC = Topic.create("CoverageProcessEnded", CoverageProcessEnded::class.java)
    }
}
