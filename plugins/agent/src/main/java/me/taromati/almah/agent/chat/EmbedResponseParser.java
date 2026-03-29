package me.taromati.almah.agent.chat;

import me.taromati.almah.core.messenger.EmbedData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답에서 [embed]...[/embed] 마커를 파싱하여 일반 텍스트와 Embed 데이터를 분리한다.
 */
public final class EmbedResponseParser {

    private static final Pattern EMBED_PATTERN = Pattern.compile(
            "\\[embed]\\s*(.*?)\\s*\\[/embed]",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private EmbedResponseParser() {}

    /**
     * 파싱 결과. text는 embed 블록 제거 후 남은 일반 텍스트, embeds는 추출된 embed 목록.
     */
    public record ParseResult(String text, List<EmbedData> embeds) {

        public boolean hasEmbeds() {
            return embeds != null && !embeds.isEmpty();
        }
    }

    /**
     * LLM 응답을 파싱하여 일반 텍스트와 embed를 분리한다.
     * [embed]...[/embed] 블록이 없으면 전체가 text로 반환된다.
     */
    public static ParseResult parse(String response) {
        if (response == null || response.isBlank()) {
            return new ParseResult(response, List.of());
        }

        Matcher matcher = EMBED_PATTERN.matcher(response);
        List<EmbedData> embeds = new ArrayList<>();

        while (matcher.find()) {
            String content = matcher.group(1).trim();
            if (!content.isEmpty()) {
                embeds.add(new EmbedData(content));
            }
        }

        if (embeds.isEmpty()) {
            return new ParseResult(response, List.of());
        }

        // embed 블록을 제거한 나머지 텍스트
        String text = matcher.reset().replaceAll("").trim();
        return new ParseResult(text.isEmpty() ? null : text, embeds);
    }

    /**
     * [embed]...[/embed] 마커를 인용 블록(> )으로 변환한다.
     * embed 카드 대신 인라인 인용으로 표시하여 Discord에서 위치 자유도 확보.
     */
    public static String convertToQuoteBlocks(String response) {
        if (response == null || response.isBlank()) return response;

        Matcher matcher = EMBED_PATTERN.matcher(response);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            // embed 앞 텍스트
            result.append(response, lastEnd, matcher.start());

            // embed 내용을 인용 블록으로 변환
            String content = matcher.group(1).trim();
            if (!content.isEmpty()) {
                for (String line : content.split("\n")) {
                    result.append("> ").append(line).append("\n");
                }
            }
            lastEnd = matcher.end();
        }

        // 남은 텍스트
        result.append(response.substring(lastEnd));
        return result.toString().trim();
    }

    /**
     * 열린 embed 블록이 있는지 확인한다 (2000자 분할 보호용).
     * [embed]가 있는데 [/embed]로 닫히지 않았으면 true.
     */
    public static boolean hasOpenEmbedBlock(String text) {
        if (text == null) return false;
        int lastOpen = text.lastIndexOf("[embed]");
        int lastClose = text.lastIndexOf("[/embed]");
        return lastOpen >= 0 && lastOpen > lastClose;
    }

    /**
     * 열린 embed 블록 시작 위치를 반환한다 (분할 위치 조정용).
     * 열린 블록이 없으면 -1.
     */
    public static int findOpenEmbedStart(String text) {
        if (!hasOpenEmbedBlock(text)) return -1;
        return text.lastIndexOf("[embed]");
    }
}
