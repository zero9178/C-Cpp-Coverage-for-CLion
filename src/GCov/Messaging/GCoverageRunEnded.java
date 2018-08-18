package GCov.Messaging;

import com.intellij.util.messages.Topic;

public interface GCoverageRunEnded {
    Topic<GCoverageRunEnded> GCOVERAGE_RUN_ENDED_TOPIC = Topic.create("GCoverageRunEnded", GCoverageRunEnded.class);

    void ended(String buildDirectory);
}
