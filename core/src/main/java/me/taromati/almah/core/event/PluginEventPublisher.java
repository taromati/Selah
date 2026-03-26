package me.taromati.almah.core.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 플러그인 이벤트 발행 서비스.
 * 플러그인은 이 컴포넌트를 통해 이벤트를 발행하고,
 * 처리 방식(Discord 전송, webhook 전달 등)은 모드별 리스너가 자동 처리합니다.
 */
@Component
@RequiredArgsConstructor
public class PluginEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(String pluginName, String message) {
        eventPublisher.publishEvent(new PluginEvent(pluginName, message));
    }

    public void publish(String pluginName, String format, Object... args) {
        publish(pluginName, String.format(format, args));
    }
}
