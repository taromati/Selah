package me.taromati.almah.agent.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.chat.ChatTerminationClassifier.ChatExitReason;
import me.taromati.almah.agent.chat.ChatTerminationClassifier.Classification;
import me.taromati.almah.agent.permission.ActionScopeFactory;
import me.taromati.almah.agent.task.TaskSource;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 예산 소진 -> 분류 기반 할 일 등록.
 *
 * <p>핵심 규칙:
 * <ul>
 *   <li>TOOL_FAILURE → HANDOFF 생성하지 않음 (백그라운드 재시도 무의미)</li>
 *   <li>PROGRESS_STALLED → maxRetries 낮게 (2회)</li>
 *   <li>WORK_INCOMPLETE → maxRetries 기본값 (5회)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AutoHandoffHandler {

    private final TaskStoreService taskStoreService;
    private final ActionScopeFactory actionScopeFactory;

    /**
     * 분류 기반 핸드오프 등록.
     *
     * @return 생성된 task ID, 또는 TOOL_FAILURE/CANCELLED 등으로 생성하지 않은 경우 null
     */
    public String handleClassified(String userMessage, String progress, Classification classification) {
        ChatExitReason reason = classification.reason();

        // TOOL_FAILURE, COMPLETED, CANCELLED → HANDOFF 생성 안 함
        if (reason == ChatExitReason.TOOL_FAILURE
                || reason == ChatExitReason.COMPLETED
                || reason == ChatExitReason.CANCELLED) {
            log.info("[AutoHandoff] HANDOFF 생성 안 함: reason={}", reason);
            return null;
        }

        String title = StringUtils.truncate(userMessage, 100);
        int maxRetries = classification.suggestedMaxRetries();

        String description = "예산 소진으로 자동 등록됨 (" + reason + ").\n분류: " + classification.detail()
                + "\n\n원본 요청:\n" + userMessage
                + (progress != null ? "\n\n진행 상황:\n" + progress : "");

        var task = taskStoreService.create(title, description, userMessage, TaskSource.HANDOFF, maxRetries);

        // 대화 모드 scope 설정
        String scope = actionScopeFactory.createChatScope();
        taskStoreService.updateActionScope(task.getId(), scope);

        log.info("[AutoHandoff] HANDOFF 등록: {} reason={} maxRetries={}", task.getId(), reason, maxRetries);
        return task.getId();
    }

    /**
     * @deprecated 하위 호환용 — 신규 코드는 {@link #handleClassified}를 사용하세요.
     */
    @Deprecated
    public void handleRoundsExhausted(String userMessage, String progress) {
        var classification = new Classification(ChatExitReason.WORK_INCOMPLETE, 5, "레거시 호출");
        handleClassified(userMessage, progress, classification);
    }
}
