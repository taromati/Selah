package me.taromati.almah.agent.listener;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.chat.EmbedResponseParser;
import me.taromati.almah.core.messenger.ChannelRef;
import me.taromati.almah.core.messenger.MessengerGatewayRegistry;
import me.taromati.almah.llm.tool.StreamingListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 스트리밍 LLM 응답을 Discord 메시지로 실시간 전달하는 핸들러.
 *
 * <p>도구 상태 메시지와 텍스트 응답 메시지를 별도 메시지로 분리한다.
 * 도구 호출 중에는 도구 메시지에 🔍/✅ 상태를 누적하고,
 * 텍스트 응답 시작 시 도구 메시지를 삭제하고 새 텍스트 메시지를 생성한다.</p>
 *
 * <p>설계 참조: streaming-message-split/scenarios.md S01~S11</p>
 */
@Slf4j
public class StreamingMessageHandler implements StreamingListener {

    private static final int SEND_TEXT_FALLBACK_THRESHOLD = 500;

    private final MessengerGatewayRegistry messengerRegistry;
    private final ChannelRef channel;
    private final int debounceMs;
    private final int splitThreshold;

    private String placeholderMessageId;
    private String toolMessageId;
    private String textMessageId;
    private final StringBuilder tokenBuffer = new StringBuilder();
    private final List<String> toolHistory = new ArrayList<>();
    private long lastEditNanos;

    public StreamingMessageHandler(MessengerGatewayRegistry messengerRegistry, ChannelRef channel) {
        this(messengerRegistry, channel, 200, 2000);
    }

    public StreamingMessageHandler(MessengerGatewayRegistry messengerRegistry, ChannelRef channel,
                                   int debounceMs, int splitThreshold) {
        this.messengerRegistry = messengerRegistry;
        this.channel = channel;
        this.debounceMs = debounceMs;
        this.splitThreshold = splitThreshold;

        this.placeholderMessageId = messengerRegistry.sendTextAndGetId(channel, "\uD83E\uDD14 생각 중...");
    }

    // ─── StreamingListener 구현 ───

    @Override
    public synchronized void onToken(String token) {
        if (token.isEmpty()) return;

        tokenBuffer.append(token);

        // 플레이스홀더도 없는 경우 → sendText fallback
        if (placeholderMessageId == null && textMessageId == null && toolMessageId == null) {
            if (tokenBuffer.length() >= SEND_TEXT_FALLBACK_THRESHOLD) {
                messengerRegistry.sendText(channel, tokenBuffer.toString());
                tokenBuffer.setLength(0);
            }
            return;
        }

        // 도구 메시지 활성 → 도구 삭제 + 새 텍스트 메시지
        if (toolMessageId != null) {
            tryDeleteMessage(toolMessageId);
            toolMessageId = null;
            textMessageId = messengerRegistry.sendTextAndGetId(channel, tokenBuffer.toString());
            lastEditNanos = System.nanoTime();
            return;
        }

        // 텍스트 메시지가 아직 없으면 → 플레이스홀더를 텍스트로 전용
        if (textMessageId == null) {
            textMessageId = placeholderMessageId;
            if (textMessageId != null) {
                if (!tryEditMessage(textMessageId, tokenBuffer.toString())) {
                    switchToNewTextMessage(tokenBuffer.toString());
                }
            }
            lastEditNanos = System.nanoTime();
            return;
        }

        // 이미 텍스트 모드: 분할 체크 + debounce
        if (tokenBuffer.length() >= splitThreshold) {
            splitMessage();
            return;
        }

        long now = System.nanoTime();
        long elapsedMs = (now - lastEditNanos) / 1_000_000;
        if (elapsedMs >= debounceMs) {
            if (!tryEditMessage(textMessageId, tokenBuffer.toString())) {
                switchToNewTextMessage(tokenBuffer.toString());
            }
            lastEditNanos = System.nanoTime();
        }
    }

    @Override
    public synchronized void onFlush() {
        if (tokenBuffer.isEmpty()) return;

        if (textMessageId != null) {
            tryEditMessage(textMessageId, tokenBuffer.toString());
        } else if (placeholderMessageId == null && toolMessageId == null) {
            messengerRegistry.sendText(channel, tokenBuffer.toString());
        }
    }

    @Override
    public synchronized void onToolStart(String name, String argsPreview) {
        if (toolMessageId == null) {
            if (textMessageId != null) {
                // S04: 텍스트 후 도구 → 새 도구 메시지 생성
                toolMessageId = messengerRegistry.sendTextAndGetId(channel,
                        "\uD83D\uDD0D " + name + "(" + argsPreview + ") 실행 중...");
                toolHistory.clear();
                tokenBuffer.setLength(0);
            } else {
                // 첫 도구: 플레이스홀더를 도구 메시지로 전용
                toolMessageId = placeholderMessageId;
            }
        }

        toolHistory.add("\uD83D\uDD0D " + name + "(" + argsPreview + ") 실행 중...");

        if (toolMessageId != null) {
            String content = String.join("\n", toolHistory);
            if (!tryEditMessage(toolMessageId, content)) {
                switchToNewToolMessage(content);
            }
        }
    }

    @Override
    public synchronized void onToolDone(String name, String argsPreview, String resultSummary) {
        if (!toolHistory.isEmpty()) {
            int lastIdx = toolHistory.size() - 1;
            toolHistory.set(lastIdx, "\u2705 " + name + "(" + argsPreview + ") \u2014 " + resultSummary);
        }

        if (toolMessageId != null) {
            String content = String.join("\n", toolHistory);
            if (!tryEditMessage(toolMessageId, content)) {
                switchToNewToolMessage(content);
            }
        }
    }

    // ─── 라이프사이클 메서드 ───

    /** 잔여 버퍼를 embed→인용 변환 후 확정. 도구 메시지는 삭제. */
    public synchronized void finalizeMessage() {
        // 도구 메시지가 남아있으면 삭제
        if (toolMessageId != null) {
            tryDeleteMessage(toolMessageId);
            toolMessageId = null;
        }

        if (textMessageId != null) {
            // 텍스트 메시지 확정: embed 변환
            String converted = EmbedResponseParser.convertToQuoteBlocks(tokenBuffer.toString());
            tokenBuffer.setLength(0);
            tokenBuffer.append(converted);

            String content = tokenBuffer.toString();
            if (content.isBlank()) {
                content = "(빈 응답)";
            }
            tryEditMessage(textMessageId, content);
        } else if (!toolHistory.isEmpty()) {
            // 도구만 있었고 텍스트 없음 → "(빈 응답)" 새 메시지
            textMessageId = messengerRegistry.sendTextAndGetId(channel, "(빈 응답)");
        } else {
            // 텍스트도 도구도 없음 → "(빈 응답)"
            if (placeholderMessageId != null) {
                tryEditMessage(placeholderMessageId, "(빈 응답)");
            }
        }

        toolHistory.clear();
    }

    /** 취소 시 도구 메시지 삭제 + 중단 표시 */
    public synchronized void cancel() {
        if (toolMessageId != null) {
            tryDeleteMessage(toolMessageId);
            toolMessageId = null;
        }

        String content = tokenBuffer.toString();
        if (!content.isEmpty()) {
            content += "\n\n\u23F9\uFE0F 중단됨";
        } else {
            content = "\u23F9\uFE0F 중단됨";
        }

        String targetId = textMessageId != null ? textMessageId : placeholderMessageId;
        if (targetId != null) {
            tryEditMessage(targetId, content);
        } else {
            messengerRegistry.sendText(channel, content);
        }
    }

    /** 에러 시 도구 메시지 삭제 + 에러 메시지 표시 */
    public synchronized void forceEnd(String message) {
        if (toolMessageId != null) {
            tryDeleteMessage(toolMessageId);
            toolMessageId = null;
        }

        String content = tokenBuffer.toString();
        if (!content.isEmpty()) {
            content += "\n\n" + message;
        } else {
            content = message;
        }

        String targetId = textMessageId != null ? textMessageId : placeholderMessageId;
        if (targetId != null) {
            tryEditMessage(targetId, content);
        } else {
            messengerRegistry.sendText(channel, content);
        }
    }

    /** 새 라운드 시작 — 버퍼/상태 초기화 + 새 플레이스홀더 */
    public synchronized void newRound() {
        tokenBuffer.setLength(0);
        toolHistory.clear();
        toolMessageId = null;
        textMessageId = null;
        lastEditNanos = 0;

        placeholderMessageId = messengerRegistry.sendTextAndGetId(channel, "\uD83E\uDD14 이어서 진행 중...");
    }

    /** 현재 활성 messageId 반환 */
    public String getMessageId() {
        if (textMessageId != null) return textMessageId;
        if (toolMessageId != null) return toolMessageId;
        return placeholderMessageId;
    }

    // ─── 내부 메서드 ───

    private boolean tryEditMessage(String messageId, String content) {
        try {
            messengerRegistry.editMessage(channel, messageId, content);
            return true;
        } catch (Exception e) {
            log.warn("editMessage 실패 (messageId={}): {}", messageId, e.getMessage());
            return false;
        }
    }

    private void tryDeleteMessage(String messageId) {
        try {
            messengerRegistry.deleteMessage(channel, messageId);
        } catch (Exception e) {
            log.warn("deleteMessage 실패 (messageId={}): {}", messageId, e.getMessage());
        }
    }

    private void switchToNewTextMessage(String content) {
        String newId = messengerRegistry.sendTextAndGetId(channel, content);
        if (newId != null) {
            textMessageId = newId;
        }
    }

    private void switchToNewToolMessage(String content) {
        String newId = messengerRegistry.sendTextAndGetId(channel, content);
        if (newId != null) {
            toolMessageId = newId;
        }
    }

    /**
     * splitThreshold 초과 시 메시지를 분할한다.
     * embed 블록이 잘리지 않도록 분할 위치를 조정한다.
     */
    private void splitMessage() {
        String buffer = tokenBuffer.toString();
        int splitPos = splitThreshold;

        int openEmbedStart = EmbedResponseParser.findOpenEmbedStart(buffer);
        if (openEmbedStart >= 0 && openEmbedStart < splitPos) {
            splitPos = openEmbedStart;
        }

        if (splitPos <= 0) {
            splitPos = splitThreshold;
        }

        String confirmed = buffer.substring(0, splitPos);
        String remaining = buffer.substring(splitPos);

        String converted = EmbedResponseParser.convertToQuoteBlocks(confirmed);
        if (!tryEditMessage(textMessageId, converted)) {
            messengerRegistry.sendText(channel, converted);
        }

        tokenBuffer.setLength(0);
        if (!remaining.isEmpty()) {
            tokenBuffer.append(remaining);
            String newId = messengerRegistry.sendTextAndGetId(channel, remaining);
            if (newId != null) {
                textMessageId = newId;
            }
        }

        lastEditNanos = System.nanoTime();
    }
}
