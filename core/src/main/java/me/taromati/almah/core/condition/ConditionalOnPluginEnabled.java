package me.taromati.almah.core.condition;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * 플러그인 활성화 조건 어노테이션.
 * plugins.{value}.enabled=true이고 worker.enabled=false일 때만 빈이 등록됩니다.
 *
 * 사용 예:
 * @ConditionalOnPluginEnabled("recorder")
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(PluginEnabledCondition.class)
public @interface ConditionalOnPluginEnabled {
    /**
     * 플러그인 이름 (예: "recorder", "patreon")
     */
    String value();
}
