package me.taromati.almah.agent.task;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.routine.RoutineOrchestrator;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.messenger.PluginCommandHandler;
import me.taromati.almah.core.messenger.TypingHandle;
import me.taromati.almah.core.util.PluginMdc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * {@code !작업} / {@code !work} 명령어 핸들러.
 * 작업을 TaskStore에 등록하고 즉시 실행합니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class TaskCommandListener implements PluginCommandHandler {

    private final TaskStoreService taskStoreService;
    private final RoutineOrchestrator routineOrchestrator;
    private final MessengerGatewayRegistry messengerRegistry;

    public TaskCommandListener(
            TaskStoreService taskStoreService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RoutineOrchestrator routineOrchestrator,
            MessengerGatewayRegistry messengerRegistry
    ) {
        this.taskStoreService = taskStoreService;
        this.routineOrchestrator = routineOrchestrator;
        this.messengerRegistry = messengerRegistry;
    }

    @Override
    public boolean canHandle(String content) {
        return content.startsWith("!작업 ") || content.startsWith("!work ");
    }

    @Override
    public void handle(ChannelRef channel, String content, String channelId) {
        String taskDesc = content.substring(content.indexOf(' ') + 1).trim();
        if (taskDesc.isEmpty()) return;

        var task = taskStoreService.create(taskDesc, null, taskDesc, TaskSource.CHAT);
        messengerRegistry.sendText(channel,
                "\uD83D\uDCCB 할 일 등록: " + taskDesc + " (ID: " + task.getId().substring(0, 8) + ")");

        // 즉시 실행
        if (routineOrchestrator != null) {
            try (TypingHandle ignored = messengerRegistry.startTyping(channel)) {
                PluginMdc.set("agent");
                try {
                    routineOrchestrator.executeSingleTask(task.getId());
                } catch (Exception e) {
                    log.error("[TaskCommandListener] Task execution error", e);
                    messengerRegistry.sendText(channel, "\u274C 작업 실행 오류: " + e.getMessage());
                } finally {
                    PluginMdc.clear();
                }
            }
        }
    }
}
