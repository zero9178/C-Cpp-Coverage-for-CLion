package gcov.messaging

import com.intellij.util.messages.Topic

interface CoverageProcessEnded {

    fun ended(buildDirectory: String)

    companion object {
        val GCOVERAGE_RUN_ENDED_TOPIC = Topic.create("CoverageProcessEnded", CoverageProcessEnded::class.java)
    }
}
