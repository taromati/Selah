package me.taromati.almah.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 연결 정보 설정 (공유 인프라)
 * sampling 파라미터는 각 feature config에서 독립 관리
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfigProperties {
    /** 프로바이더별 설정. key = 프로바이더 이름 (사용자 정의) */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    // ─── Tool Calling 글로벌 설정 ───

    /** Tool Calling 절대 라운드 상한 (극단적 안전장치) */
    private int toolCallingRoundCap = 100;
    /** 컨텍스트 예산 하한 (토큰). 이 미만이면 루프 중단 */
    private int toolCallingMinTokenBuffer = 500;
    /** maxTokens 미지정 시 기본 출력 토큰 수 */
    private int toolCallingDefaultMaxOutputTokens = 4096;
    /** 예산 경고 주입 임계치 (0.0~1.0). 남은 예산이 이 비율 이하일 때 경고 */
    private double toolCallingBudgetWarningRatio = 0.2;
    /** 토큰 추정 안전계수 (문자→토큰 변환 시 보정 배율) */
    private double toolCallingTokenEstimationMultiplier = 1.2;
    /** S05: 같은 도구 연속 실패 허용 횟수. 초과 시 해당 도구 비활성화 */
    private int toolCallingConsecutiveFailureLimit = 3;
    /** S07: 도구 결과 최대 문자 수. 초과 시 절삭 */
    private int toolResultMaxChars = 10000;
    /** S16: 개별 도구 실행 타임아웃 (초) */
    private int toolExecutionTimeoutSeconds = 120;
    /** S08: LLM API 오류 재시도 횟수 */
    private int apiRetryCount = 2;

    // ─── 토큰 갱신 설정 ───

    /** 토큰 만료 전 갱신 여유 시간 (초). 기본 300 (5분) */
    private int tokenRefreshMarginSeconds = 300;

    @Getter
    @Setter
    public static class ProviderConfig {
        /** 프로바이더 타입: vllm, openai, gemini, codex */
        private String type;

        /** 프로바이더 활성화 여부 (providers Map에 등록 = 활성화 의사이므로 기본 true) */
        private boolean enabled = true;

        // ─── 공통 연결 설정 ───
        private String baseUrl;
        /** 모델명. 필수 — 미설정 시 시작 실패 */
        private String model;
        private String apiKey = "";

        // ─── 프로바이더 능력치 (Agent 등에서 조회) ───
        private Integer contextWindow;
        private Integer maxTokens;
        private Integer charsPerToken;
        private Integer recentKeep;

        /** HTTP 연결 타임아웃 (초). 기본 10초 */
        private int connectTimeoutSeconds = 10;
        /** HTTP 읽기 타임아웃 (초). 기본 120초 — vLLM 행 시 스레드 영구 블로킹 방지 */
        private int timeoutSeconds = 120;

        // ─── CLI 기반 프로바이더 (gemini 등) ───
        /** CLI 실행 경로 (Gemini CLI 등 CLI 기반 프로바이더용) */
        private String cliPath;

        // ─── openai-codex 전용 ───
        private String accessToken;   // OAuth access token (수동 지정용)
        private String refreshToken;  // OAuth refresh token (수동 지정용)
        private String accountId;     // ChatGPT Account ID

        // ─── vLLM thinking ───
        /** vLLM: thinking 활성화. null이면 true (현재 동작 유지) */
        private Boolean enableThinking;

        // ─── openai-codex reasoning ───
        /** OpenAI Codex: reasoning effort ("low"/"medium"/"high"). null이면 "medium" */
        private String reasoningEffort;

        // ─── Rate Limiting ───
        /** 분당 최대 호출 횟수. 설정 시 고정 간격 Rate Limiter 활성화 */
        private Integer rateLimitPerMinute;

    }
}
