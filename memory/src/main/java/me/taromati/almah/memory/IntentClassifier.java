package me.taromati.almah.memory;

import java.util.EnumSet;

/**
 * Rule-based 쿼리 의도 분류기.
 * LLM 호출 없이 키워드 매칭으로 분류하여 레이턴시 0.
 */
public class IntentClassifier {

    private IntentClassifier() {}

    public enum Intent { FACTUAL, TEMPORAL, CAUSAL, EXPLORATORY }

    public static EnumSet<Intent> classifyAll(String query) {
        if (query == null || query.isBlank()) return EnumSet.of(Intent.FACTUAL);
        String lower = query.toLowerCase();
        EnumSet<Intent> intents = EnumSet.noneOf(Intent.class);

        if (containsAny(lower, "언제", "when", "몇시", "날짜", "지난", "최근", "이전", "이후",
                "며칠", "yesterday", "today", "last", "ago", "before", "after",
                "시작", "끝나", "기간", "동안", "이번주", "지난주", "지난달", "올해", "작년")) {
            intents.add(Intent.TEMPORAL);
        }
        if (containsAny(lower, "왜", "why", "원인", "이유", "때문", "because", "결과",
                "영향", "덕분", "탓", "how come", "어째서", "까닭")) {
            intents.add(Intent.CAUSAL);
        }
        if (containsAny(lower, "어떤", "무엇", "관련", "연결", "연관", "탐색",
                "관계", "사이", "관련된", "연결된", "네트워크", "related", "connected",
                "그래프", "주변", "함께")) {
            intents.add(Intent.EXPLORATORY);
        }
        if (intents.isEmpty()) intents.add(Intent.FACTUAL);
        return intents;
    }

    public static Intent classify(String query) {
        return classifyAll(query).iterator().next();
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
