package me.taromati.almah.llm.client.codex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class CodexTokenManager {

    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";

    private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String configAccessToken;
    private final String configRefreshToken;
    private final String configAccountId;
    private final long refreshMarginMs;
    private final RestTemplate restTemplate;

    private String cachedAccessToken;
    private String cachedAccountId;
    private long expiresAtMs;

    public CodexTokenManager(String configAccessToken, String configRefreshToken, String configAccountId,
                             int refreshMarginSeconds) {
        this.configAccessToken = configAccessToken;
        this.configRefreshToken = configRefreshToken;
        this.configAccountId = configAccountId;
        this.refreshMarginMs = refreshMarginSeconds * 1000L;
        this.restTemplate = new RestTemplate();
    }

    public synchronized String getAccessToken() {
        if (cachedAccessToken != null && System.currentTimeMillis() < expiresAtMs - refreshMarginMs) {
            return cachedAccessToken;
        }
        refreshAccessToken();
        return cachedAccessToken;
    }

    public synchronized String getAccountId() {
        if (cachedAccountId != null) return cachedAccountId;

        // 1. 파일
        CodexAuthFile auth = loadToken();
        if (auth != null && auth.tokens != null && auth.tokens.accountId != null) {
            cachedAccountId = auth.tokens.accountId;
            return cachedAccountId;
        }

        // 2. config
        cachedAccountId = configAccountId;
        return cachedAccountId;
    }

    public synchronized void invalidateCache() {
        cachedAccessToken = null;
        cachedAccountId = null;
        expiresAtMs = 0;
    }

    public boolean hasTokens() {
        return resolveRefreshToken() != null;
    }

    public String getTokenSource() {
        if (Files.exists(TOKEN_FILE)) return "파일 (" + TOKEN_FILE + ")";
        if (configRefreshToken != null && !configRefreshToken.isBlank()) return "config.yml";
        return "없음";
    }

    public boolean deleteTokens() {
        try {
            boolean deleted = Files.deleteIfExists(TOKEN_FILE);
            if (deleted) {
                invalidateCache();
                log.info("[CodexTokenManager] 토큰 파일 삭제: {}", TOKEN_FILE);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("[CodexTokenManager] 토큰 파일 삭제 실패: {}", e.getMessage());
            return false;
        }
    }

    private void refreshAccessToken() {
        String refreshToken = resolveRefreshToken();
        if (refreshToken == null) {
            throw new RuntimeException(
                    "Codex OAuth 토큰이 없습니다. `codex login`을 실행하거나 config.yml에 설정하세요.");
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        body.add("client_id", CLIENT_ID);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        log.debug("[CodexTokenManager] 토큰 갱신 요청");

        try {
            ResponseEntity<TokenResponse> response = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, entity, TokenResponse.class);

            TokenResponse resp = response.getBody();
            if (resp == null || resp.accessToken == null) {
                throw new RuntimeException("Codex 토큰 갱신 실패: 빈 응답");
            }

            cachedAccessToken = resp.accessToken;
            expiresAtMs = System.currentTimeMillis() + (resp.expiresIn * 1000L);

            // 갱신된 토큰 저장
            CodexAuthFile auth = loadToken();
            if (auth == null) auth = new CodexAuthFile();
            if (auth.tokens == null) auth.tokens = new CodexAuthTokens();
            auth.tokens.accessToken = resp.accessToken;
            if (resp.refreshToken != null) {
                auth.tokens.refreshToken = resp.refreshToken;
            }
            if (cachedAccountId == null) {
                getAccountId();
            }
            auth.tokens.accountId = cachedAccountId;

            saveToken(auth);

            log.info("[CodexTokenManager] 토큰 갱신 완료. expiresIn={}분",
                    resp.expiresIn / 60);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Codex 토큰 갱신 실패: " + e.getMessage(), e);
        }
    }

    /**
     * refresh token 해석 (파일 → config 우선순위)
     */
    private String resolveRefreshToken() {
        // 1. ~/.codex/auth.json
        CodexAuthFile auth = loadToken();
        if (auth != null && auth.tokens != null
                && auth.tokens.refreshToken != null && !auth.tokens.refreshToken.isBlank()) {
            // 캐시된 access token도 로드 (파일에 잔여 유효 토큰이 있을 수 있음)
            if (cachedAccessToken == null && auth.tokens.accessToken != null) {
                cachedAccessToken = auth.tokens.accessToken;
                // expiresAt는 파일에 없으므로 즉시 갱신됨 (expiresAtMs = 0)
            }
            return auth.tokens.refreshToken;
        }

        // 2. config.yml
        if (configRefreshToken != null && !configRefreshToken.isBlank()) {
            return configRefreshToken;
        }

        return null;
    }

    private CodexAuthFile loadToken() {
        if (!Files.exists(TOKEN_FILE)) return null;
        try {
            return objectMapper.readValue(Files.readString(TOKEN_FILE), CodexAuthFile.class);
        } catch (IOException e) {
            log.warn("[CodexTokenManager] 토큰 파일 읽기 실패: {}", e.getMessage());
            return null;
        }
    }

    private void saveToken(CodexAuthFile token) throws IOException {
        Files.createDirectories(TOKEN_FILE.getParent());
        Files.writeString(TOKEN_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(token));
    }

    // ─── Jackson DTOs (Codex CLI 호환 포맷) ───

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CodexAuthFile {
        @JsonProperty("tokens") public CodexAuthTokens tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CodexAuthTokens {
        @JsonProperty("access_token") public String accessToken;
        @JsonProperty("refresh_token") public String refreshToken;
        @JsonProperty("account_id") public String accountId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn
    ) {}
}
