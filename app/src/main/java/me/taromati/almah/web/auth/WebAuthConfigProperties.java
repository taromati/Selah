package me.taromati.almah.web.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "web.auth")
public class WebAuthConfigProperties {
    private boolean enabled = false;
    private int sessionTimeoutHours = 168;          // 7일
    private int requestExpirySeconds = 120;         // 요청 유효시간 2분
    private int requestRateLimitSeconds = 30;       // 요청 간격
    // Per-IP 방어
    private int ipBlockThreshold = 3;               // 3회 → 차단
    private int ipBlockHours = 1;                   // 1시간 차단
    // Global 방어
    private int globalLimitCount = 10;              // 10회/10분
    private int globalLimitWindowMinutes = 10;
    private int globalLockdownMinutes = 30;         // 전체 잠금
}
