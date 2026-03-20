package me.taromati.almah.llm.client;

/**
 * LLM Sampling 파라미터 (per-call 전달)
 * null 값은 서버 기본값을 사용합니다.
 */
public record SamplingParams(
        Integer maxTokens,
        Double temperature,
        Double topP,
        Double minP,
        Double frequencyPenalty,
        Double repetitionPenalty,
        Double presencePenalty
) {
    /**
     * temperature만 지정하는 편의 생성자
     */
    public static SamplingParams withTemperature(Double temperature) {
        return new SamplingParams(null, temperature, null, null, null, null, null);
    }

    /**
     * maxTokens만 변경한 새 인스턴스 반환
     */
    public SamplingParams withMaxTokens(Integer maxTokens) {
        return new SamplingParams(maxTokens, temperature, topP, minP, frequencyPenalty, repetitionPenalty, presencePenalty);
    }
}
