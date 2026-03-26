package me.taromati.almah.core.condition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * 플러그인 활성화 조건.
 * worker.enabled=true이면 플러그인을 비활성화합니다.
 */
public class PluginEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // worker.enabled가 true이면 플러그인 비활성화
        Boolean workerEnabled = context.getEnvironment().getProperty("plugins.worker.enabled", Boolean.class, false);
        if (Boolean.TRUE.equals(workerEnabled)) {
            return false;
        }

        // @ConditionalOnPluginEnabled 어노테이션에서 플러그인 이름 가져오기
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnPluginEnabled.class.getName());
        if (attrs == null) {
            return false;
        }

        String pluginName = (String) attrs.get("value");
        String propertyName = "plugins." + pluginName + ".enabled";

        return context.getEnvironment().getProperty(propertyName, Boolean.class, false);
    }
}
