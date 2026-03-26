package me.taromati.almah.agent.suggest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.core.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ConversationCollector implements StimulusCollector {

    private final AgentSessionService sessionService;
    private final AgentConfigProperties config;

    @Override
    public StimulusCategory category() { return StimulusCategory.CONVERSATION; }

    @Override
    public StimulusResult collect() {
        var session = sessionService.findActiveSession(config.getChannelName());
        if (session == null) return new StimulusResult(category(), List.of(), "");

        var messages = sessionService.getMessages(session.getId());
        var recent = messages.stream()
                .filter(m -> m.getCreatedAt().isAfter(LocalDateTime.now().minusHours(24)))
                .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                .toList();

        if (recent.isEmpty()) return new StimulusResult(category(), List.of(), "");

        StringBuilder context = new StringBuilder("최근 24시간 대화 요약:\n");
        List<String> topics = new ArrayList<>();
        for (var msg : recent) {
            String content = StringUtils.truncate(msg.getContent(), 200);
            context.append("- [").append(msg.getRole()).append("] ").append(content).append("\n");
            if ("user".equals(msg.getRole())) {
                topics.add(StringUtils.truncate(msg.getContent(), 50));
            }
        }

        return new StimulusResult(category(), topics, context.toString());
    }
}
