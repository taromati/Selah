package me.taromati.almah.event;

import lombok.RequiredArgsConstructor;
import me.taromati.almah.core.event.PluginEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PluginEventListener {

    private final PluginEventDispatcher dispatcher;

    @EventListener
    public void handle(PluginEvent event) {
        dispatcher.dispatch(event);
    }
}
