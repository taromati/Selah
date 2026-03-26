package me.taromati.almah.core.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 한국어 문장 분리 유틸리티 — TTS 문장 단위 스트리밍에 사용.
 * <p>
 * 규칙: 문장 종결 부호(.?!), 줄바꿈, 한국어 종결어미(~다, ~요, ~죠 등) 기준 분리.
 * 최소 5자, 최대 100자 강제 분할.
 */
public final class SentenceSplitter {

    private static final int MIN_SENTENCE_LENGTH = 5;
    private static final int MAX_SENTENCE_LENGTH = 100;

    /** 문장 종결 패턴: 구두점 + 선택적 따옴표/괄호 + 공백/끝 */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?<=[.?!。？！])[\"'）)」』]*(?=\\s|$)" +
            "|(?<=\\n)"
    );

    /** 한국어 종결어미 패턴 (공백 또는 문장 끝이 뒤따르는 경우) */
    private static final Pattern KOREAN_ENDING = Pattern.compile(
            "(?<=(?:다|요|죠|네|세요|습니다|합니다|입니다|됩니다|겠습니다|하세요|드려요|볼게요|할게요|했어요|거예요|는데요|잖아요|까요|래요|대요))(?=\\s|,|$)"
    );

    private SentenceSplitter() {}

    /**
     * 텍스트를 문장 단위로 분리.
     * 2문장 이하 짧은 응답은 분할하지 않고 통째로 반환 (TTS 음질 우선).
     */
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> raw = splitRaw(text);

        // 2문장 이하: 분할하지 않음 (음질 우선)
        if (raw.size() <= 2) {
            String joined = String.join(" ", raw).strip();
            return joined.isEmpty() ? List.of() : List.of(joined);
        }

        return raw;
    }

    /**
     * 증분 모드용 — 완성된 문장과 잔여 버퍼를 분리.
     * Phase 3 SSE 스트리밍에서 토큰 축적 중 완성 문장을 추출할 때 사용.
     *
     * @return [0]: 완성된 문장 리스트, [1]: 잔여 버퍼 (아직 문장 미완성)
     */
    public static SplitResult splitIncremental(String buffer) {
        if (buffer == null || buffer.isBlank()) {
            return new SplitResult(List.of(), "");
        }

        List<String> raw = splitRaw(buffer);
        if (raw.size() <= 1) {
            // 문장 분리점 없음 → 전부 잔여 버퍼
            // 단, MAX_SENTENCE_LENGTH 초과 시 강제 분할
            if (buffer.strip().length() > MAX_SENTENCE_LENGTH) {
                String forced = buffer.strip().substring(0, MAX_SENTENCE_LENGTH);
                String remainder = buffer.strip().substring(MAX_SENTENCE_LENGTH);
                return new SplitResult(List.of(forced), remainder);
            }
            return new SplitResult(List.of(), buffer);
        }

        // 마지막 조각은 잔여 버퍼 (아직 문장 미완성일 수 있음)
        List<String> completed = new ArrayList<>(raw.subList(0, raw.size() - 1));
        String remainder = raw.getLast();

        return new SplitResult(completed, remainder);
    }

    /**
     * 실제 분리 로직 — 구두점 → 한국어 종결어미 → 강제 분할 순서
     */
    private static List<String> splitRaw(String text) {
        // 1차: 구두점/줄바꿈 기준 분리
        String[] parts = SENTENCE_END.split(text);
        List<String> sentences = new ArrayList<>();

        for (String part : parts) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;

            // 2차: 한국어 종결어미 기준 세분화
            String[] subParts = KOREAN_ENDING.split(trimmed);
            StringBuilder current = new StringBuilder();

            for (String sub : subParts) {
                String subTrimmed = sub.strip();
                if (subTrimmed.isEmpty()) continue;

                current.append(current.isEmpty() ? "" : " ").append(subTrimmed);

                if (current.length() >= MIN_SENTENCE_LENGTH) {
                    // 최대 길이 강제 분할
                    String sentence = current.toString();
                    while (sentence.length() > MAX_SENTENCE_LENGTH) {
                        int cut = findWordBreak(sentence, MAX_SENTENCE_LENGTH);
                        sentences.add(sentence.substring(0, cut).strip());
                        sentence = sentence.substring(cut).strip();
                    }
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    current.setLength(0);
                }
            }

            // 잔여 텍스트 처리
            if (!current.isEmpty()) {
                String remaining = current.toString().strip();
                if (!remaining.isEmpty()) {
                    // 이전 문장에 병합 가능하면 병합
                    if (!sentences.isEmpty() && remaining.length() < MIN_SENTENCE_LENGTH) {
                        String prev = sentences.removeLast();
                        sentences.add(prev + " " + remaining);
                    } else {
                        sentences.add(remaining);
                    }
                }
            }
        }

        return sentences;
    }

    /** 단어 경계에서 분할 위치 탐색 (공백 기준) */
    private static int findWordBreak(String text, int maxPos) {
        int cut = text.lastIndexOf(' ', maxPos);
        if (cut < maxPos / 2) cut = maxPos; // 공백 없으면 강제 분할
        return cut;
    }

    public record SplitResult(List<String> sentences, String remainder) {}
}
