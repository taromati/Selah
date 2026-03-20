package me.taromati.almah.llm.agent.listener;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.core.messenger.PluginCommandHandler;
import me.taromati.almah.llm.client.codex.CodexTokenManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Codex 인증 관련 명령어 핸들러.
 *
 * <p>처리 명령어:</p>
 * <ul>
 *   <li>{@code !codex [status|import|logout]} — OpenAI Codex 토큰</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class CodexCommandListener implements PluginCommandHandler {

    private final CodexTokenManager codexTokenManager;
    private final MessengerGatewayRegistry messengerRegistry;

    public CodexCommandListener(
            @org.springframework.beans.factory.annotation.Autowired(required = false) CodexTokenManager codexTokenManager,
            MessengerGatewayRegistry messengerRegistry
    ) {
        this.codexTokenManager = codexTokenManager;
        this.messengerRegistry = messengerRegistry;
    }

    @Override
    public boolean canHandle(String content) {
        return content.startsWith("!codex");
    }

    @Override
    public void handle(ChannelRef channel, String content, String channelId) {
        handleCodexCommand(channel, content);
    }

    private void handleCodexCommand(ChannelRef channel, String content) {
        String[] parts = content.split("\\s+", 2);
        String subCommand = parts.length > 1 ? parts[1].trim() : "";

        switch (subCommand) {
            case "status" -> handleCodexStatus(channel);
            case "import" -> handleCodexImport(channel);
            case "logout" -> handleCodexLogout(channel);
            default -> messengerRegistry.sendText(channel, """
                    **OpenAI Codex OAuth 토큰 관리**
                    `!codex status` — 토큰 상태 확인
                    `!codex import` — Codex CLI 토큰 import (`codex login` 후)
                    `!codex logout` — 저장된 토큰 삭제""");
        }
    }

    private void handleCodexStatus(ChannelRef channel) {
        if (codexTokenManager == null) {
            messengerRegistry.sendText(channel,
                    "**Codex 상태**: 비활성 (`llm.providers.openai-codex.enabled: true` 필요)");
            return;
        }

        String source = codexTokenManager.getTokenSource();
        boolean hasToken = codexTokenManager.hasTokens();
        String status = hasToken ? "\u2705 토큰 있음" : "\u274C 토큰 없음";
        messengerRegistry.sendText(channel,
                "**Codex 상태**\n토큰: " + status + "\n소스: " + source);
    }

    private void handleCodexImport(ChannelRef channel) {
        if (codexTokenManager == null) {
            messengerRegistry.sendText(channel, "\u274C Codex가 활성화되지 않았습니다.");
            return;
        }

        if (codexTokenManager.hasTokens()) {
            messengerRegistry.sendText(channel,
                    "\u2705 ~/.codex/auth.json 토큰이 이미 사용 가능합니다. `!모델 변경 openai-codex/gpt-4o`로 전환하세요.");
        } else {
            messengerRegistry.sendText(channel,
                    "\u274C ~/.codex/auth.json에 토큰이 없습니다. `codex login`으로 먼저 인증하세요.");
        }
    }

    private void handleCodexLogout(ChannelRef channel) {
        if (codexTokenManager == null) {
            messengerRegistry.sendText(channel, "\u274C Codex가 활성화되지 않았습니다.");
            return;
        }

        boolean deleted = codexTokenManager.deleteTokens();
        if (deleted) {
            messengerRegistry.sendText(channel, "\u2705 저장된 Codex 토큰이 삭제되었습니다.");
        } else {
            messengerRegistry.sendText(channel, "저장된 토큰 파일이 없습니다.");
        }
    }
}
