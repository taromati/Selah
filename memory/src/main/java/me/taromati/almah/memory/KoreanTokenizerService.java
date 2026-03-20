package me.taromati.almah.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class KoreanTokenizerService {

    private Analyzer analyzer;

    @PostConstruct
    public void init() {
        this.analyzer = new KoreanAnalyzer(
                null,
                KoreanTokenizer.DecompoundMode.MIXED,
                Set.of(),
                false
        );
        log.info("[KoreanTokenizerService] Initialized with Nori MIXED mode");
    }

    public String tokenize(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            List<String> tokens = extractTokens(text);
            if (tokens.isEmpty()) return text;
            return String.join(" ", tokens);
        } catch (Exception e) {
            log.warn("[KoreanTokenizerService] Tokenization failed, returning original: {}", e.getMessage());
            return text;
        }
    }

    public String tokenizeForQuery(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            List<String> tokens = extractTokens(text);
            if (tokens.isEmpty()) return "\"" + text + "\"";
            return tokens.stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(" "));
        } catch (Exception e) {
            log.warn("[KoreanTokenizerService] Query tokenization failed: {}", e.getMessage());
            return "\"" + text + "\"";
        }
    }

    private List<String> extractTokens(String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("content", text)) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(attr.toString());
            }
            stream.end();
        }
        return tokens;
    }

    @PreDestroy
    public void destroy() {
        if (analyzer != null) {
            analyzer.close();
            log.info("[KoreanTokenizerService] Analyzer closed");
        }
    }
}
