package me.taromati.almah.agent.routine;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.messenger.PluginCommandHandler;
import me.taromati.almah.core.messenger.TypingHandle;
import me.taromati.almah.core.util.PluginMdc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code !점검실행} / {@code !routine} 명령어 핸들러.
 * 에이전트 정기 점검을 수동으로 실행합니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class RoutineCommandListener implements PluginCommandHandler {

    private final RoutineScheduler routineScheduler;
    private final MessengerGatewayRegistry messengerRegistry;

    public RoutineCommandListener(
            @org.springframework.beans.factory.annotation.Autowired(required = false) RoutineScheduler routineScheduler,
            MessengerGatewayRegistry messengerRegistry
    ) {
        this.routineScheduler = routineScheduler;
        this.messengerRegistry = messengerRegistry;
    }

    @Override
    public boolean canHandle(String content) {
        return content.equals("!점검실행") || content.equals("!routine");
    }

    @Override
    public void handle(ChannelRef channel, String content, String channelId) {
        try (TypingHandle ignored = messengerRegistry.startTyping(channel)) {
            PluginMdc.set("agent");
            try {
                if (routineScheduler == null) {
                    messengerRegistry.sendText(channel, "\u274C 루틴이 비활성화되어 있습니다.");
                    return;
                }
                messengerRegistry.sendText(channel, "\uD83E\uDEC0 루틴 수동 실행 중...");
                routineScheduler.runManual();
                messengerRegistry.sendText(channel, "\u2705 루틴 완료");
            } catch (Exception e) {
                log.error("[RoutineCommandListener] Routine execution error", e);
                messengerRegistry.sendText(channel, "\u274C 루틴 실행 오류: " + e.getMessage());
            } finally {
                PluginMdc.clear();
            }
        }
    }
}
