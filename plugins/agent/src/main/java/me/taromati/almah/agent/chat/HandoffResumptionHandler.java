package me.taromati.almah.agent.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentTaskItemEntity;
import me.taromati.almah.agent.task.TaskSource;
import me.taromati.almah.agent.task.TaskStatus;
import me.taromati.almah.agent.task.TaskStoreService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * "이어해", "이어서", "계속해" 등의 메시지를 감지하여
 * 최근 PENDING HANDOFF task를 채팅 컨텍스트에 연결합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class HandoffResumptionHandler {

    private static final Pattern RESUME_PATTERN = Pattern.compile(
            "^(이어해|이어서|계속해|이어줘|계속|이어서 해줘|계속 해줘|이어서해줘|계속해줘)$");

    private final TaskStoreService taskStoreService;

    /**
     * 사용자 메시지가 재개 요청인지 판별합니다.
     */
    public boolean isResumeRequest(String message) {
        if (message == null) return false;
        return RESUME_PATTERN.matcher(message.trim()).matches();
    }

    /**
     * 최근 PENDING HANDOFF task를 찾아 재개용 프롬프트를 구성합니다.
     *
     * @return 재개 프롬프트, 또는 HANDOFF가 없으면 empty
     */
    public Optional<ResumptionContext> findResumable() {
        List<AgentTaskItemEntity> pending = taskStoreService.findPending();

        // 최근 HANDOFF 중 PENDING인 것 (최신 순)
        var handoff = pending.stream()
                .filter(t -> TaskSource.HANDOFF.equals(t.getSource()))
                .reduce((first, second) -> second); // 마지막 (가장 최근 createdAt)

        if (handoff.isEmpty()) return Optional.empty();

        var task = handoff.get();
        return Optional.of(new ResumptionContext(task.getId(), task.getTitle(), task.getOriginalRequest(), task.getProgress()));
    }

    /**
     * task를 채팅으로 실행하기 위한 프롬프트를 구성합니다.
     */
    public String buildResumptionPrompt(ResumptionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[이전 작업 재개] 다음 작업을 이어서 처리해주세요.\n\n");
        sb.append("## 원본 요청\n").append(ctx.originalRequest() != null ? ctx.originalRequest() : ctx.title()).append("\n\n");
        if (ctx.progress() != null && !ctx.progress().isBlank()) {
            sb.append("## 이전 진행 상황\n").append(ctx.progress()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * ID 접두사로 HANDOFF task를 찾아 ResumptionContext를 반환합니다.
     */
    public Optional<ResumptionContext> findByIdPrefix(String idPrefix) {
        return taskStoreService.findByIdPrefix(idPrefix)
                .filter(t -> TaskSource.HANDOFF.equals(t.getSource()))
                .map(t -> new ResumptionContext(t.getId(), t.getTitle(), t.getOriginalRequest(), t.getProgress()));
    }

    /**
     * 재개 완료 후 HANDOFF task를 COMPLETED로 전환합니다.
     */
    public void markCompleted(String taskId) {
        try {
            var task = taskStoreService.findById(taskId).orElse(null);
            if (task != null && !TaskStatus.isTerminal(task.getStatus())) {
                taskStoreService.transition(taskId, TaskStatus.COMPLETED);
                log.info("[HandoffResumption] HANDOFF task 완료 처리: {}", taskId);
            }
        } catch (Exception e) {
            log.warn("[HandoffResumption] HANDOFF task 완료 처리 실패: {}", e.getMessage());
        }
    }

    public record ResumptionContext(String taskId, String title, String originalRequest, String progress) {}
}
