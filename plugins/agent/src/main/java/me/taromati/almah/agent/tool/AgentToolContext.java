package me.taromati.almah.agent.tool;

import me.taromati.almah.llm.client.LlmClient;

/**
 * Agent 도구 실행 시 ThreadLocal 컨텍스트.
 * Tool Calling 흐름에서 채널 정보 등을 직접 접근할 수 없으므로
 * AgentListener/SubagentToolHandler/AgentJobExecutor가 설정하고 도구 핸들러가 참조합니다.
 *
 * @param channelId Discord 채널 ID
 * @param isSubagent 서브에이전트 실행 여부 (재귀 방지)
 * @param isCronJob cron 잡 실행 여부
 * @param client LLM 클라이언트 (서브에이전트 상속용, nullable)
 * @param model LLM 모델 (서브에이전트 상속용, nullable)
 * @param executionContext 실행 컨텍스트 (CHAT, ROUTINE, SUGGEST, CRON, SUBAGENT)
 */
public record AgentToolContext(String channelId, boolean isSubagent, boolean isCronJob,
                                LlmClient client, String model, ExecutionContext executionContext) {

    public enum ExecutionContext {
        CHAT, ROUTINE, SUGGEST, CRON, SUBAGENT
    }

    private static final ThreadLocal<AgentToolContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_MESSAGE = new ThreadLocal<>();

    /** 하위호환: selfGeneration → SUGGEST */
    public boolean selfGeneration() {
        return executionContext == ExecutionContext.SUGGEST;
    }

    /** 기존 호환: client/model 없이 설정 */
    public static void set(String channelId, boolean isSubagent) {
        CURRENT.set(new AgentToolContext(channelId, isSubagent, false, null, null,
                isSubagent ? ExecutionContext.SUBAGENT : ExecutionContext.CHAT));
    }

    /** 기존 호환: client/model 없이 설정 (cron 포함) */
    public static void set(String channelId, boolean isSubagent, boolean isCronJob) {
        CURRENT.set(new AgentToolContext(channelId, isSubagent, isCronJob, null, null,
                isCronJob ? ExecutionContext.CRON : isSubagent ? ExecutionContext.SUBAGENT : ExecutionContext.CHAT));
    }

    /** 멀티 프로바이더: client + model 포함 */
    public static void set(String channelId, boolean isSubagent, boolean isCronJob,
                            LlmClient client, String model) {
        CURRENT.set(new AgentToolContext(channelId, isSubagent, isCronJob, client, model,
                isCronJob ? ExecutionContext.CRON : isSubagent ? ExecutionContext.SUBAGENT : ExecutionContext.CHAT));
    }

    /** 하위호환: selfGeneration boolean → ExecutionContext 변환 */
    public static void set(String channelId, boolean isSubagent, boolean isCronJob,
                            LlmClient client, String model, boolean selfGeneration) {
        ExecutionContext ctx = selfGeneration ? ExecutionContext.SUGGEST
                : isCronJob ? ExecutionContext.CRON
                : isSubagent ? ExecutionContext.SUBAGENT
                : ExecutionContext.CHAT;
        CURRENT.set(new AgentToolContext(channelId, isSubagent, isCronJob, client, model, ctx));
    }

    /** 신규: ExecutionContext 직접 지정 */
    public static void set(String channelId, boolean isSubagent, boolean isCronJob,
                            LlmClient client, String model, ExecutionContext executionContext) {
        CURRENT.set(new AgentToolContext(channelId, isSubagent, isCronJob, client, model, executionContext));
    }

    public static AgentToolContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void setCurrentUserMessage(String msg) {
        CURRENT_USER_MESSAGE.set(msg);
    }

    public static String getCurrentUserMessage() {
        return CURRENT_USER_MESSAGE.get();
    }

    public static void clearCurrentUserMessage() {
        CURRENT_USER_MESSAGE.remove();
    }
}
