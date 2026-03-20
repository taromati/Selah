package me.taromati.almah.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.event.PluginEvent;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.config.PluginsConfigProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginEventDispatcher {

    private final MessengerGatewayRegistry registry;
    private final PluginsConfigProperties pluginsConfig;
    private final Environment environment;

    public void dispatch(PluginEvent event) {
        dispatch(event.pluginName(), event.message());
    }

    public void dispatch(String pluginName, String message) {
        String channelName = resolveChannel(pluginName);
        registry.broadcastText(channelName, message);
    }

    private String resolveChannel(String pluginName) {
        if (pluginName == null) {
            return pluginsConfig.getNotificationChannel();
        }
        return environment.getProperty(
                "plugins." + pluginName + ".channel-name",
                pluginsConfig.getNotificationChannel());
    }
}
