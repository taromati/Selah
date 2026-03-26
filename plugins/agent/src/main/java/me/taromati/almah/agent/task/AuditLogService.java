package me.taromati.almah.agent.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.db.entity.AgentAuditLogEntity;
import me.taromati.almah.agent.db.repository.AgentAuditLogRepository;
import me.taromati.almah.agent.service.SkillEnvInjector;
import me.taromati.almah.agent.service.SkillManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class AuditLogService {

    private final AgentAuditLogRepository auditLogRepository;

    @Autowired(required = false)
    private SkillEnvInjector skillEnvInjector;

    @Autowired(required = false)
    private SkillManager skillManager;

    @Transactional("agentTransactionManager")
    public AgentAuditLogEntity log(String taskItemId, String toolName, String arguments,
                                    String resultSummary, String scopeVerdict) {
        return log(taskItemId, null, toolName, arguments, resultSummary, scopeVerdict);
    }

    @Transactional("agentTransactionManager")
    public AgentAuditLogEntity log(String taskItemId, String sessionId, String toolName,
                                    String arguments, String resultSummary, String scopeVerdict) {
        AgentAuditLogEntity entry = AgentAuditLogEntity.builder()
                .taskItemId(taskItemId)
                .sessionId(sessionId)
                .toolName(toolName)
                .arguments(truncate(maskEnvValues(arguments), 2000))
                .resultSummary(truncate(maskEnvValues(resultSummary), 2000))
                .scopeVerdict(scopeVerdict)
                .build();
        return auditLogRepository.save(entry);
    }

    public List<AgentAuditLogEntity> findByTaskItem(String taskItemId) {
        return auditLogRepository.findByTaskItemIdOrderByCreatedAtDesc(taskItemId);
    }

    public List<AgentAuditLogEntity> findRecent() {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc();
    }

    private String maskEnvValues(String text) {
        if (text == null || skillEnvInjector == null || skillManager == null) return text;
        try {
            Map<String, String> envValues = skillEnvInjector.resolveEnv(skillManager.getActiveSkills());
            String masked = text;
            for (String value : envValues.values()) {
                if (value != null && value.length() >= 4) {
                    masked = masked.replace(value, "***");
                }
            }
            return masked;
        } catch (Exception e) {
            log.debug("[AuditLogService] env 마스킹 실패: {}", e.getMessage());
            return text;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
