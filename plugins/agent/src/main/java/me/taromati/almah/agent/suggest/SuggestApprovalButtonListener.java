package me.taromati.almah.agent.suggest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.AgentSessionService;
import me.taromati.almah.agent.suggest.SuggestHistory;
import me.taromati.almah.agent.task.TaskSource;
import me.taromati.almah.agent.task.TaskStoreService;
import me.taromati.almah.core.messenger.ActionEvent;
import me.taromati.almah.core.messenger.InteractionHandler;
import me.taromati.almah.core.messenger.InteractionResponder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 자율 제안 승인/거부 버튼 이벤트 리스너.
 * 버튼 ID 형식:
 *   agent-suggest-approve:{suggestId8}:{actionIndex}
 *   agent-suggest-approve-all:{suggestId8}
 *   agent-suggest-deny:{suggestId8}
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SuggestApprovalButtonListener implements InteractionHandler {

    private static final String ACTION_PREFIX = "agent-suggest-";
    private static final String APPROVE_PREFIX = "agent-suggest-approve:";
    private static final String APPROVE_ALL_PREFIX = "agent-suggest-approve-all:";
    private static final String DENY_PREFIX = "agent-suggest-deny:";

    private final SuggestHistory suggestHistory;
    private final TaskStoreService taskStoreService;
    private final AgentSessionService sessionService;
    private final AgentConfigProperties config;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SuggestApprovalButtonListener(SuggestHistory suggestHistory,
                                          TaskStoreService taskStoreService,
                                          AgentSessionService sessionService,
                                          AgentConfigProperties config) {
        this.suggestHistory = suggestHistory;
        this.taskStoreService = taskStoreService;
        this.sessionService = sessionService;
        this.config = config;
    }

    @Override
    public String getActionIdPrefix() {
        return ACTION_PREFIX;
    }

    @Override
    public void handle(ActionEvent event, InteractionResponder responder) {
        String actionId = event.actionId();

        if (actionId.startsWith(APPROVE_ALL_PREFIX)) {
            handleApproveAll(actionId, responder);
        } else if (actionId.startsWith(APPROVE_PREFIX)) {
            handleApprove(actionId, responder);
        } else if (actionId.startsWith(DENY_PREFIX)) {
            handleDeny(actionId, responder);
        }
    }

    private void handleApprove(String actionId, InteractionResponder responder) {
        // 파싱: agent-suggest-approve:{idPrefix}:{actionIndex}
        String payload = actionId.substring(APPROVE_PREFIX.length());
        int lastColon = payload.lastIndexOf(':');
        if (lastColon < 0) {
            responder.replyEphemeral("잘못된 버튼 형식입니다.");
            return;
        }

        String idPrefix = payload.substring(0, lastColon);
        int actionIndex;
        try {
            actionIndex = Integer.parseInt(payload.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            responder.replyEphemeral("잘못된 버튼 형식입니다.");
            return;
        }

        var suggestOpt = suggestHistory.findByIdPrefix(idPrefix);
        if (suggestOpt.isEmpty()) {
            responder.replyEphemeral("만료되었거나 존재하지 않는 제안입니다.");
            return;
        }

        var suggest = suggestOpt.get();
        if (!"PENDING".equals(suggest.getResponse())) {
            responder.replyEphemeral("이미 처리된 제안입니다.");
            return;
        }

        List<Map<String, String>> actions = parsePassedActions(suggest.getPassedActions());
        if (actionIndex < 0 || actionIndex >= actions.size()) {
            responder.replyEphemeral("잘못된 작업 인덱스입니다.");
            return;
        }

        var action = actions.get(actionIndex);
        String title = action.get("title");
        String description = action.get("description");

        if (taskStoreService.existsActiveByTitle(title)) {
            responder.replyEphemeral("이미 진행 중인 작업입니다: " + title);
            return;
        }

        try {
            taskStoreService.create(title, description, null, TaskSource.SUGGEST,
                    getTaskMaxRetries());
        } catch (Exception e) {
            log.warn("[SuggestApproval] Task 생성 실패: {}", e.getMessage());
            responder.replyEphemeral("Task 생성 실패: " + e.getMessage());
            return;
        }

        // 단일 ACTION이면 APPROVED, 다중이면 PENDING 유지 (나머지 ACTION 대기)
        if (actions.size() == 1) {
            suggestHistory.recordResponse(suggest.getId(), "APPROVED");
        }
        responder.editMessage("\u2705 승인됨 — Task 생성: " + title);
        saveToSession("(사용자가 '" + title + "' 제안을 승인했습니다)");
        log.info("[SuggestApproval] 승인: {} (suggest={})", title, idPrefix);
    }

    private void handleApproveAll(String actionId, InteractionResponder responder) {
        String idPrefix = actionId.substring(APPROVE_ALL_PREFIX.length());

        var suggestOpt = suggestHistory.findByIdPrefix(idPrefix);
        if (suggestOpt.isEmpty()) {
            responder.replyEphemeral("만료되었거나 존재하지 않는 제안입니다.");
            return;
        }

        var suggest = suggestOpt.get();
        if (!"PENDING".equals(suggest.getResponse())) {
            responder.replyEphemeral("이미 처리된 제안입니다.");
            return;
        }

        List<Map<String, String>> actions = parsePassedActions(suggest.getPassedActions());
        int created = 0;

        for (var action : actions) {
            String title = action.get("title");
            String description = action.get("description");

            if (taskStoreService.existsActiveByTitle(title)) continue;

            try {
                taskStoreService.create(title, description, null, TaskSource.SUGGEST,
                        getTaskMaxRetries());
                created++;
            } catch (Exception e) {
                log.warn("[SuggestApproval] Task 생성 실패: {} - {}", title, e.getMessage());
            }
        }

        suggestHistory.recordResponse(suggest.getId(), "APPROVED");
        responder.editMessage("\u2705 전체 승인됨 — Task " + created + "건 생성");
        saveToSession("(사용자가 제안 전체를 승인했습니다 — Task " + created + "건 생성)");
        log.info("[SuggestApproval] 전체 승인: {} tasks (suggest={})", created, idPrefix);
    }

    private void handleDeny(String actionId, InteractionResponder responder) {
        String idPrefix = actionId.substring(DENY_PREFIX.length());

        var suggestOpt = suggestHistory.findByIdPrefix(idPrefix);
        if (suggestOpt.isEmpty()) {
            responder.replyEphemeral("만료되었거나 존재하지 않는 제안입니다.");
            return;
        }

        var suggest = suggestOpt.get();
        if (!"PENDING".equals(suggest.getResponse())) {
            responder.replyEphemeral("이미 처리된 제안입니다.");
            return;
        }

        suggestHistory.recordResponse(suggest.getId(), "DENIED");
        responder.editMessage("\u274C 거부됨");
        saveToSession("(사용자가 제안을 거부했습니다)");
        log.info("[SuggestApproval] 거부 (suggest={})", idPrefix);
    }

    private void saveToSession(String content) {
        try {
            String channelName = config != null ? config.getChannelName() : null;
            var session = sessionService.getOrCreateActiveSession(channelName);
            String sessionId = session != null ? session.getId() : null;
            sessionService.saveMessage(sessionId, "assistant", content, null, null);
        } catch (Exception e) {
            log.warn("[SuggestApproval] 세션 기록 실패: {}", e.getMessage());
        }
    }

    private int getTaskMaxRetries() {
        var suggestConfig = config != null ? config.getSuggest() : null;
        return suggestConfig != null && suggestConfig.getTaskMaxRetries() != null
                ? suggestConfig.getTaskMaxRetries() : 3;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parsePassedActions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[SuggestApproval] passedActions 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }
}
