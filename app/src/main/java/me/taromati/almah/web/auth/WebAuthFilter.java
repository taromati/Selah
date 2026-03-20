package me.taromati.almah.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebAuthFilter extends OncePerRequestFilter {

    private final WebAuthConfigProperties config;
    private final ObjectProvider<WebAuthService> webAuthServiceProvider;

    public WebAuthFilter(WebAuthConfigProperties config,
                          ObjectProvider<WebAuthService> webAuthServiceProvider) {
        this.config = config;
        this.webAuthServiceProvider = webAuthServiceProvider;
    }

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/login",
            "/api/auth/",
            "/api/system/health",
            "/webhook/",
            "/favicon",
            "/static/",
            "/assets/",
            "/robots.txt"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 보안 헤더 (모든 응답)
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");

        // 인증 비활성화 시 통과 (auth API는 항상 authenticated=true 반환)
        if (!config.isEnabled()) {
            if (request.getRequestURI().equals("/api/auth/check")) {
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"OK\",\"data\":{\"authenticated\":true}}");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // Public 경로 통과
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 세션 체크: HttpSession → AUTH_TOKEN 쿠키 순서로 확인
        HttpSession session = request.getSession(false);
        String sessionId = session != null ? (String) session.getAttribute("authSessionId") : null;

        // HttpSession에 없으면 AUTH_TOKEN 쿠키에서 복원
        if (sessionId == null) {
            sessionId = getCookieValue(request, "AUTH_TOKEN");
            if (sessionId != null && getAuthService().isSessionValid(sessionId)) {
                HttpSession newSession = request.getSession(true);
                newSession.setAttribute("authSessionId", sessionId);
                newSession.setMaxInactiveInterval(config.getSessionTimeoutHours() * 3600);
            }
        }

        if (getAuthService().isSessionValid(sessionId)) {
            chain.doFilter(request, response);
            return;
        }

        // 미인증 → 무효한 AUTH_TOKEN 쿠키 정리
        clearAuthCookie(response);

        // 미인증 → API는 401 JSON, 페이지는 SPA index.html 서빙 (Vue Router가 /login 라우팅)
        if (isApiRequest(path)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
        } else {
            serveSpaIndex(response);
        }
    }

    private boolean isPublicPath(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isApiRequest(String path) {
        return path.contains("/api/");
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private void serveSpaIndex(HttpServletResponse response) throws IOException {
        var indexHtml = new ClassPathResource("/static/index.html");
        if (indexHtml.isReadable()) {
            response.setContentType("text/html");
            response.setCharacterEncoding("UTF-8");
            try (InputStream in = indexHtml.getInputStream()) {
                in.transferTo(response.getOutputStream());
            }
        } else {
            // SPA 빌드 결과물이 없으면 기존 로그인 페이지로 리다이렉트
            response.sendRedirect("/login");
        }
    }

    private WebAuthService getAuthService() {
        return webAuthServiceProvider.getIfAvailable();
    }

    private void clearAuthCookie(HttpServletResponse response) {
        var cookie = new jakarta.servlet.http.Cookie("AUTH_TOKEN", "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
