package GCov.Messaging;

import com.intellij.util.messages.Topic;

public interface CoverageProcessEnded {
    Topic<CoverageProcessEnded> GCOVERAGE_RUN_ENDED_TOPIC = Topic.create("CoverageProcessEnded", CoverageProcessEnded.class);

    void ended(String buildDirectory);
}
