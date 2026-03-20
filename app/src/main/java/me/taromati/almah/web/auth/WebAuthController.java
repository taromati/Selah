package me.taromati.almah.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import me.taromati.almah.core.response.RootResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "web.auth.enabled", havingValue = "true")
public class WebAuthController {

    private final WebAuthService webAuthService;
    private final WebAuthConfigProperties config;

    @GetMapping("/api/auth/check")
    public RootResponse<Map<String, Object>> checkSession(HttpServletRequest request, HttpSession session) {
        String sessionId = (String) session.getAttribute("authSessionId");
        if (sessionId == null) {
            sessionId = getCookieValue(request, "AUTH_TOKEN");
            if (sessionId != null && webAuthService.isSessionValid(sessionId)) {
                session.setAttribute("authSessionId", sessionId);
                session.setMaxInactiveInterval(config.getSessionTimeoutHours() * 3600);
            }
        }
        if (webAuthService.isSessionValid(sessionId)) {
            return RootResponse.ok(Map.of("authenticated", true));
        }
        return RootResponse.ok(Map.of("authenticated", false));
    }

    @GetMapping("/api/auth/challenge")
    public RootResponse<Map<String, String>> challenge() {
        String token = webAuthService.generateChallengeToken();
        return RootResponse.ok(Map.of("token", token));
    }

    @PostMapping("/api/auth/request")
    public RootResponse<?> requestLogin(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String ip = resolveClientIp(request);
        var info = new WebAuthService.RequestInfo(
                ip,
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("User-Agent"),
                request.getHeader("Accept-Language"),
                getString(body, "canvasFingerprint"),
                getString(body, "screenResolution"),
                getString(body, "timezone"),
                getString(body, "platform"),
                getString(body, "webglRenderer"),
                Boolean.TRUE.equals(body.get("touchCapable")),
                getString(body, "challengeToken"),
                getString(body, "honeypotValue"),
                getLong(body, "pageLoadTimestamp")
        );

        var result = webAuthService.requestLogin(info);
        if (result.success()) {
            return RootResponse.ok(Map.of("token", result.token()));
        }
        return RootResponse.fail(result.error());
    }

    @GetMapping("/api/auth/status")
    public RootResponse<Map<String, String>> checkStatus(@RequestParam String token,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response,
                                                          HttpSession session) {
        var status = webAuthService.getStatus(token);
        if (status == WebAuthService.StatusResult.APPROVED) {
            String sessionId = webAuthService.createSession(token, resolveClientIp(request));
            if (sessionId != null) {
                session.setAttribute("authSessionId", sessionId);
                session.setMaxInactiveInterval(config.getSessionTimeoutHours() * 3600);
                addAuthCookie(response, sessionId);
                return RootResponse.ok(Map.of("status", "approved"));
            }
        }
        return RootResponse.ok(Map.of("status", status.name().toLowerCase()));
    }

    @PostMapping("/api/auth/logout")
    public RootResponse<Void> logout(HttpSession session, HttpServletResponse response) {
        String sessionId = (String) session.getAttribute("authSessionId");
        webAuthService.invalidateSession(sessionId);
        session.invalidate();
        clearAuthCookie(response);
        return RootResponse.ok();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String getString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString() : null;
    }

    private long getLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.longValue();
        return 0;
    }

    private void addAuthCookie(HttpServletResponse response, String sessionId) {
        var cookie = new jakarta.servlet.http.Cookie("AUTH_TOKEN", sessionId);
        cookie.setMaxAge(config.getSessionTimeoutHours() * 3600);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    private void clearAuthCookie(HttpServletResponse response) {
        var cookie = new jakarta.servlet.http.Cookie("AUTH_TOKEN", "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
