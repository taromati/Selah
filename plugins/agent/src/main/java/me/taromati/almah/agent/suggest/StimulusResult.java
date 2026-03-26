package me.taromati.almah.agent.suggest;

import java.util.List;

public record StimulusResult(
        StimulusCategory category,
        List<String> topics,
        String context
) {
    public boolean isEmpty() {
        return topics == null || topics.isEmpty();
    }
}
