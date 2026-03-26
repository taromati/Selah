package me.taromati.almah.agent.suggest;

public interface StimulusCollector {
    StimulusCategory category();
    StimulusResult collect();
}
