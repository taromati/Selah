package me.taromati.almah.web;

import me.taromati.almah.core.response.RootResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final boolean agentEnabled;

    public SystemController(@Value("${plugins.agent.enabled:false}") boolean agentEnabled) {
        this.agentEnabled = agentEnabled;
    }

    @GetMapping("/health")
    public RootResponse<Void> health() {
        return RootResponse.ok();
    }

    @GetMapping("/plugins")
    public RootResponse<Map<String, Boolean>> pluginStatus() {
        return RootResponse.ok(Map.of("agent", agentEnabled));
    }
}
