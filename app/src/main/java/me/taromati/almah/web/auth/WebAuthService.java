package me.taromati.almah.web.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.*;
import me.taromati.almah.config.PluginsConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@ConditionalOnProperty(name = "web.auth.enabled", havingValue = "true")
public class WebAuthService {

    private static final Logger AUDIT = LoggerFactory.getLogger("auth-audit");
    private static final Path STATE_FILE = Path.of("./app-data/auth-state.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final WebAuthConfigProperties config;
    private final MessengerGatewayRegistry messengerRegistry;
    private final PluginsConfigProperties pluginsConfig;

    // 로그인 요청 (단기 — 영속화 불필요)
    private final ConcurrentHashMap<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    // 인증된 세션
    private final ConcurrentHashMap<String, AuthSession> authenticatedSessions = new ConcurrentHashMap<>();
    // IP 차단
    private final ConcurrentHashMap<String, IpRecord> ipRecords = new ConcurrentHashMap<>();
    // Global 제한
    private final List<Instant> globalRequestLog = new CopyOnWriteArrayList<>();
    private volatile Instant globalLockdownUntil;
    // Canvas FP → 차단된 IP 매핑
    private final ConcurrentHashMap<String, String> blockedFingerprints = new ConcurrentHashMap<>();
    // Challenge Token (단기 — 영속화 불필요)
    private final ConcurrentHashMap<String, Instant> challengeTokens = new ConcurrentHashMap<>();
    // Token → SessionId 매핑 (승인 취소 시 세션 무효화용)
    private final ConcurrentHashMap<String, String> tokenToSession = new ConcurrentHashMap<>();

    public WebAuthService(WebAuthConfigProperties config, MessengerGatewayRegistry messengerRegistry,
                          PluginsConfigProperties pluginsConfig) {
        this.config = config;
        this.messengerRegistry = messengerRegistry;
        this.pluginsConfig = pluginsConfig;
    }

    // ── Records ──

    record PendingLogin(String token, Instant createdAt, Instant expiresAt, String ip,
                        String userAgent, boolean approved, boolean denied) {
        PendingLogin withApproved() {
            return new PendingLogin(token, createdAt, expiresAt, ip, userAgent, true, false);
        }
        PendingLogin withDenied() {
            return new PendingLogin(token, createdAt, expiresAt, ip, userAgent, false, true);
        }
    }

    record IpRecord(AtomicInteger failCount, Instant blockedUntil, String fingerprint) {}

    record AuthSession(String sessionId, Instant createdAt, Instant expiresAt, String ip) {}

    record RequestInfo(
            String ip, String remoteAddr, String xff,
            String userAgent, String acceptLang,
            String canvasFingerprint,
            String screenResolution, String timezone, String platform,
            String webglRenderer, boolean touchCapable,
            String challengeToken,
            String honeypotValue,
            long pageLoadTimestamp
    ) {}

    record LoginResult(boolean success, String token, String error) {
        static LoginResult ok(String token) { return new LoginResult(true, token, null); }
        static LoginResult fail(String error) { return new LoginResult(false, null, error); }
    }

    enum StatusResult { PENDING, APPROVED, DENIED, EXPIRED, NOT_FOUND }

    // ── 파일 영속화 ──

    record PersistedIpRecord(int failCount, String blockedUntil, String fingerprint) {}
    record PersistedSession(String sessionId, String createdAt, String expiresAt, String ip) {}
    record PersistedState(
            Map<String, PersistedIpRecord> ipRecords,
            Map<String, String> blockedFingerprints,
            Map<String, PersistedSession> sessions,
            String globalLockdownUntil
    ) {}

    @PostConstruct
    void loadState() {
        if (!Files.exists(STATE_FILE)) return;
        try {
            var state = MAPPER.readValue(STATE_FILE.toFile(),
                    new TypeReference<PersistedState>() {});
            Instant now = Instant.now();

            if (state.ipRecords() != null) {
                state.ipRecords().forEach((ip, r) -> {
                    Instant until = r.blockedUntil() != null ? Instant.parse(r.blockedUntil()) : null;
                    if (until != null && now.isAfter(until)) return;
                    ipRecords.put(ip, new IpRecord(new AtomicInteger(r.failCount()), until, r.fingerprint()));
                });
            }
            if (state.blockedFingerprints() != null) {
                blockedFingerprints.putAll(state.blockedFingerprints());
            }
            if (state.sessions() != null) {
                state.sessions().forEach((id, s) -> {
                    Instant expiresAt = Instant.parse(s.expiresAt());
                    if (now.isAfter(expiresAt)) return;
                    authenticatedSessions.put(id, new AuthSession(
                            s.sessionId(), Instant.parse(s.createdAt()), expiresAt, s.ip()));
                });
            }
            if (state.globalLockdownUntil() != null) {
                Instant lockdown = Instant.parse(state.globalLockdownUntil());
                if (now.isBefore(lockdown)) globalLockdownUntil = lockdown;
            }

            log.info("[WebAuth] 상태 로드 완료: IP차단 {}건, 세션 {}건",
                    ipRecords.size(), authenticatedSessions.size());
        } catch (IOException e) {
            log.warn("[WebAuth] 상태 파일 로드 실패: {}", e.getMessage());
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(STATE_FILE.getParent());

            Map<String, PersistedIpRecord> ipMap = new LinkedHashMap<>();
            ipRecords.forEach((ip, r) -> ipMap.put(ip, new PersistedIpRecord(
                    r.failCount().get(),
                    r.blockedUntil() != null ? r.blockedUntil().toString() : null,
                    r.fingerprint())));

            Map<String, PersistedSession> sessionMap = new LinkedHashMap<>();
            authenticatedSessions.forEach((id, s) -> sessionMap.put(id, new PersistedSession(
                    s.sessionId(), s.createdAt().toString(), s.expiresAt().toString(), s.ip())));

            var state = new PersistedState(
                    ipMap,
                    new LinkedHashMap<>(blockedFingerprints),
                    sessionMap,
                    globalLockdownUntil != null ? globalLockdownUntil.toString() : null);

            MAPPER.writeValue(STATE_FILE.toFile(), state);
        } catch (IOException e) {
            log.warn("[WebAuth] 상태 파일 저장 실패: {}", e.getMessage());
        }
    }

    // ── Challenge Token ──

    public String generateChallengeToken() {
        String token = UUID.randomUUID().toString();
        challengeTokens.put(token, Instant.now().plusSeconds(300));
        return token;
    }

    private boolean validateChallengeToken(String token) {
        if (token == null) return false;
        Instant expiry = challengeTokens.remove(token);
        return expiry != null && Instant.now().isBefore(expiry);
    }

    // ── 로그인 요청 ──

    public LoginResult requestLogin(RequestInfo info) {
        if (info.honeypotValue() != null && !info.honeypotValue().isBlank()) {
            AUDIT.info("HONEYPOT_TRIGGERED ip={} ua={}", info.ip(), info.userAgent());
            blockIp(info.ip(), info.canvasFingerprint(), "honeypot");
            return delayedFail();
        }

        if (!validateChallengeToken(info.challengeToken())) {
            AUDIT.info("JS_CHALLENGE_FAILED ip={} ua={}", info.ip(), info.userAgent());
            return LoginResult.fail("요청을 처리할 수 없습니다.");
        }

        long elapsed = System.currentTimeMillis() - info.pageLoadTimestamp();
        if (elapsed < 2000) {
            AUDIT.info("BOT_SUSPECTED ip={} ua={} elapsed={}ms", info.ip(), info.userAgent(), elapsed);
        }

        if (isGlobalLocked()) {
            AUDIT.info("GLOBAL_LOCKED ip={}", info.ip());
            return delayedFail();
        }

        if (isIpBlocked(info.ip())) {
            AUDIT.info("IP_BLOCKED_REQUEST ip={}", info.ip());
            return delayedFail();
        }

        if (info.canvasFingerprint() != null && !info.canvasFingerprint().isBlank()) {
            String blockedIp = blockedFingerprints.get(info.canvasFingerprint());
            if (blockedIp != null && !blockedIp.equals(info.ip())) {
                AUDIT.info("CANVAS_FP_MATCH ip={} matched_blocked_ip={} canvas={}",
                        info.ip(), blockedIp, info.canvasFingerprint());
                blockIp(info.ip(), info.canvasFingerprint(), "canvas_fp_match");
                return delayedFail();
            }
        }

        boolean recentRequest = pendingLogins.values().stream()
                .anyMatch(p -> p.ip().equals(info.ip())
                        && p.createdAt().plusSeconds(config.getRequestRateLimitSeconds()).isAfter(Instant.now()));
        if (recentRequest) {
            return LoginResult.fail("잠시 후 다시 시도해주세요.");
        }

        Instant windowStart = Instant.now().minusSeconds(config.getGlobalLimitWindowMinutes() * 60L);
        globalRequestLog.removeIf(t -> t.isBefore(windowStart));
        globalRequestLog.add(Instant.now());
        if (globalRequestLog.size() > config.getGlobalLimitCount()) {
            globalLockdownUntil = Instant.now().plusSeconds(config.getGlobalLockdownMinutes() * 60L);
            AUDIT.info("GLOBAL_LOCKDOWN trigger_ip={} total_requests={}", info.ip(), globalRequestLog.size());
            sendAlert("전체 잠금 활성화 (요청 " + globalRequestLog.size() + "회/"
                    + config.getGlobalLimitWindowMinutes() + "분). 해제: " + config.getGlobalLockdownMinutes() + "분 후");
            saveState();
            return delayedFail();
        }

        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        pendingLogins.put(token, new PendingLogin(token, now,
                now.plusSeconds(config.getRequestExpirySeconds()), info.ip(), info.userAgent(), false, false));

        AUDIT.info("LOGIN_REQUEST ip={} remoteAddr={} ua={} lang={} xff={} canvas={} screen={} tz={} platform={} webgl={} touch={}",
                info.ip(), info.remoteAddr(), info.userAgent(), info.acceptLang(), info.xff(),
                info.canvasFingerprint(), info.screenResolution(), info.timezone(),
                info.platform(), info.webglRenderer(), info.touchCapable());

        sendLoginRequest(token, info);
        return LoginResult.ok(token);
    }

    // ── 승인 / 거부 ──

    public boolean approve(String token) {
        PendingLogin login = pendingLogins.get(token);
        if (login == null || login.approved() || login.denied()) return false;
        if (Instant.now().isAfter(login.expiresAt())) return false;

        pendingLogins.put(token, login.withApproved());
        AUDIT.info("APPROVED token={} ip={}", token, login.ip());
        return true;
    }

    public String revokeApproval(String token) {
        PendingLogin login = pendingLogins.get(token);
        if (login != null && login.approved()) {
            pendingLogins.put(token, login.withDenied());
            AUDIT.info("REVOKED token={} ip={}", token, login.ip());
            return "승인이 취소되었습니다.";
        }

        String sessionId = tokenToSession.remove(token);
        if (sessionId != null) {
            authenticatedSessions.remove(sessionId);
            AUDIT.info("SESSION_REVOKED session={} token={}", sessionId, token);
            saveState();
            return "세션이 강제 종료되었습니다.";
        }

        return null;
    }

    public boolean deny(String token) {
        PendingLogin login = pendingLogins.get(token);
        if (login == null || login.approved() || login.denied()) return false;

        pendingLogins.put(token, login.withDenied());
        AUDIT.info("DENIED token={} ip={}", token, login.ip());

        IpRecord record = ipRecords.computeIfAbsent(login.ip(),
                k -> new IpRecord(new AtomicInteger(0), null, null));
        int count = record.failCount().incrementAndGet();

        if (count >= config.getIpBlockThreshold()) {
            blockIp(login.ip(), null, count + "_failures");
        }
        return true;
    }

    // ── 상태 조회 ──

    public StatusResult getStatus(String token) {
        PendingLogin login = pendingLogins.get(token);
        if (login == null) return StatusResult.NOT_FOUND;
        if (Instant.now().isAfter(login.expiresAt())) return StatusResult.EXPIRED;
        if (login.approved()) return StatusResult.APPROVED;
        if (login.denied()) return StatusResult.DENIED;
        return StatusResult.PENDING;
    }

    // ── 세션 관리 ──

    public String createSession(String token, String ip) {
        PendingLogin login = pendingLogins.remove(token);
        if (login == null || !login.approved()) return null;

        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        authenticatedSessions.put(sessionId, new AuthSession(sessionId, now,
                now.plusSeconds(config.getSessionTimeoutHours() * 3600L), ip));
        tokenToSession.put(token, sessionId);

        AUDIT.info("SESSION_CREATED session={} token={} ip={}", sessionId, token, ip);
        saveState();
        return sessionId;
    }

    public boolean isSessionValid(String sessionId) {
        if (sessionId == null) return false;
        AuthSession session = authenticatedSessions.get(sessionId);
        if (session == null) return false;
        if (Instant.now().isAfter(session.expiresAt())) {
            authenticatedSessions.remove(sessionId);
            saveState();
            return false;
        }
        return true;
    }

    public void invalidateSession(String sessionId) {
        if (sessionId != null) {
            authenticatedSessions.remove(sessionId);
            AUDIT.info("SESSION_INVALIDATED session={}", sessionId);
            saveState();
        }
    }

    // ── IP 관리 ──

    public void unblockIp(String ip) {
        ipRecords.remove(ip);
        blockedFingerprints.entrySet().removeIf(e -> e.getValue().equals(ip));
        AUDIT.info("IP_UNBLOCKED ip={}", ip);
        saveState();
    }

    public void resumeGlobal() {
        globalLockdownUntil = null;
        AUDIT.info("GLOBAL_LOCKDOWN_LIFTED");
        saveState();
    }

    // ── 내부 메서드 ──

    private boolean isIpBlocked(String ip) {
        IpRecord record = ipRecords.get(ip);
        if (record == null || record.blockedUntil() == null) return false;
        if (Instant.now().isAfter(record.blockedUntil())) {
            ipRecords.remove(ip);
            return false;
        }
        return true;
    }

    private boolean isGlobalLocked() {
        return globalLockdownUntil != null && Instant.now().isBefore(globalLockdownUntil);
    }

    private void blockIp(String ip, String fingerprint, String reason) {
        Instant until = Instant.now().plusSeconds(config.getIpBlockHours() * 3600L);
        ipRecords.put(ip, new IpRecord(new AtomicInteger(config.getIpBlockThreshold()), until, fingerprint));
        if (fingerprint != null && !fingerprint.isBlank()) {
            blockedFingerprints.put(fingerprint, ip);
        }
        AUDIT.info("IP_BLOCKED ip={} reason={} canvas={}", ip, reason, fingerprint);
        saveState();

        // 모든 메신저에 IP 해제 버튼 알림
        String channelName = pluginsConfig.getNotificationChannel();
        for (MessengerGateway gw : messengerRegistry.getAllGateways()) {
            ChannelRef ch = gw.resolveChannel(channelName);
            if (ch != null) {
                gw.sendInteractive(ch, new InteractiveMessage(
                        "IP 차단됨: " + ip + " (사유: " + reason + ")",
                        List.of(new InteractiveMessage.Action(
                                "auth-unblock-" + ip, "IP 해제: " + ip,
                                InteractiveMessage.ActionStyle.DANGER))
                ));
            }
        }
    }

    private LoginResult delayedFail() {
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return LoginResult.fail("요청을 처리할 수 없습니다.");
    }

    private void sendAlert(String message) {
        String channelName = pluginsConfig.getNotificationChannel();
        messengerRegistry.broadcastText(channelName, "[WebAuth] " + message);
    }

    private void sendLoginRequest(String token, RequestInfo info) {
        String channelName = pluginsConfig.getNotificationChannel();
        String uaSummary = summarizeUserAgent(info.userAgent());
        String ipInfo = "IP: " + info.ip();
        if (info.remoteAddr() != null && !info.remoteAddr().equals(info.ip())) {
            ipInfo += " (remote: " + info.remoteAddr() + ")";
        }
        if (info.xff() != null && !info.xff().isBlank()) {
            ipInfo += " | XFF: " + info.xff();
        }
        String content = "웹 로그인 요청\n" + ipInfo + " | TZ: " + nvl(info.timezone())
                + "\nUA: " + uaSummary + " | " + nvl(info.screenResolution());

        for (MessengerGateway gw : messengerRegistry.getAllGateways()) {
            ChannelRef ch = gw.resolveChannel(channelName);
            if (ch != null) {
                gw.sendInteractive(ch, new InteractiveMessage(content, List.of(
                        new InteractiveMessage.Action("auth-approve-" + token, "승인",
                                InteractiveMessage.ActionStyle.SUCCESS),
                        new InteractiveMessage.Action("auth-deny-" + token, "거부",
                                InteractiveMessage.ActionStyle.DANGER)
                )));
            }
        }
    }

    private String summarizeUserAgent(String ua) {
        if (ua == null) return "unknown";
        if (ua.contains("Chrome") && !ua.contains("Edg")) return "Chrome (" + extractOs(ua) + ")";
        if (ua.contains("Edg")) return "Edge (" + extractOs(ua) + ")";
        if (ua.contains("Firefox")) return "Firefox (" + extractOs(ua) + ")";
        if (ua.contains("Safari") && !ua.contains("Chrome")) return "Safari (" + extractOs(ua) + ")";
        if (ua.length() > 50) return ua.substring(0, 50) + "...";
        return ua;
    }

    private String extractOs(String ua) {
        if (ua.contains("Mac")) return "Mac";
        if (ua.contains("Windows")) return "Win";
        if (ua.contains("Linux")) return "Linux";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        return "?";
    }

    private String nvl(String s) { return s != null ? s : "-"; }

    // ── 만료 정리 (5분마다) ──

    @Scheduled(fixedRate = 300_000)
    public void cleanupExpired() {
        Instant now = Instant.now();
        pendingLogins.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        challengeTokens.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        tokenToSession.entrySet().removeIf(e -> !authenticatedSessions.containsKey(e.getValue()));

        int removedSessions = 0;
        var sessionIt = authenticatedSessions.entrySet().iterator();
        while (sessionIt.hasNext()) {
            if (now.isAfter(sessionIt.next().getValue().expiresAt())) {
                sessionIt.remove();
                removedSessions++;
            }
        }

        int removedIps = 0;
        var ipIt = ipRecords.entrySet().iterator();
        while (ipIt.hasNext()) {
            var entry = ipIt.next();
            if (entry.getValue().blockedUntil() != null && now.isAfter(entry.getValue().blockedUntil())) {
                ipIt.remove();
                removedIps++;
            }
        }

        if (removedSessions > 0 || removedIps > 0) {
            saveState();
        }
    }
}
