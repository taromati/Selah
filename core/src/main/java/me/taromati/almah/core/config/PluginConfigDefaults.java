package me.taromati.almah.core.config;

import java.util.Map;

/**
 * 플러그인이 자기 기본 config를 제공하는 인터페이스.
 * ConfigGenerator가 이 기본값을 수집하여 config.yml을 생성한다.
 * Java 코드가 단일 소스 — yml은 결과물.
 */
public interface PluginConfigDefaults {

    /**
     * config.yml에서 이 플러그인이 차지하는 기본 설정 구조를 반환한다.
     * 반환값은 YAML 직렬화 가능한 중첩 Map이다.
     *
     * <p>예: Agent 플러그인이면 {@code plugins.agent} 하위의 전체 기본 구조를 반환.</p>
     *
     * @return 기본 설정 Map (키: YAML 키, 값: 문자열/숫자/boolean/List/Map)
     */
    Map<String, Object> getDefaultConfig();
}
