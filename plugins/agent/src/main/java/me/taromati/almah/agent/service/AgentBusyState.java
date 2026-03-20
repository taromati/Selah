package me.taromati.almah.agent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AgentBusyState {
    private final AtomicBoolean chatBusy = new AtomicBoolean();
    private final AtomicBoolean routineBusy = new AtomicBoolean();

    public boolean isChatBusy() { return chatBusy.get(); }
    public void setChatBusy(boolean busy) { chatBusy.set(busy); }
    public boolean isRoutineBusy() { return routineBusy.get(); }
    public void setRoutineBusy(boolean busy) { routineBusy.set(busy); }
    public boolean isAnyBusy() { return chatBusy.get() || routineBusy.get(); }
}
