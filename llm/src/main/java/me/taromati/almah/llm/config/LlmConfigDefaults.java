package me.taromati.almah.llm.config;

import me.taromati.almah.core.config.PluginConfigDefaults;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 공유 인프라 기본 설정.
 * ConfigGenerator가 이 값을 수집하여 config.yml을 생성한다.
 *
 * <p>{@link #defaults()}는 {@code llm} 하위 구조를 반환한다.</p>
 */
public class LlmConfigDefaults implements PluginConfigDefaults {

    @Override
    public Map<String, Object> getDefaultConfig() {
        return defaults();
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> cfg = new LinkedHashMap<>();

        cfg.put("tool-calling-round-cap", 100);
        cfg.put("tool-calling-min-token-buffer", 500);
        cfg.put("tool-calling-default-max-output-tokens", 4096);
        cfg.put("tool-calling-budget-warning-ratio", 0.2);
        cfg.put("tool-calling-token-estimation-multiplier", 1.2);
        cfg.put("token-refresh-margin-seconds", 300);

        return cfg;
    }
}
