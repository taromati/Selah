package me.taromati.almah.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.memory.config.MemoryConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
public class ChunkingService {

    private static final Pattern USER_PATTERN = Pattern.compile(
            "^(USER:|\\[사용자\\])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSISTANT_PATTERN = Pattern.compile(
            "^(ASSISTANT:|\\[AI\\])", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper objectMapper;

    private final int maxTokens;
    private final int temporalGapMinutes;

    @Autowired
    public ChunkingService(MemoryConfigProperties config, ObjectMapper objectMapper) {
        this(config.getChunk(), objectMapper);
    }

    ChunkingService(MemoryConfigProperties.ChunkConfig config, ObjectMapper objectMapper) {
        this.maxTokens = config.getMaxTokens();
        this.temporalGapMinutes = config.getTemporalGapMinutes();
        this.objectMapper = objectMapper;
    }

    public List<ChunkData> chunk(String content, String metadataJson) {
        if (content == null || content.isBlank()) return List.of();

        // 원본 metadata를 파싱하여 각 청크에 전파
        Map<String, String> baseMetadata = parseMetadata(metadataJson);

        // Step 1: Extract timestamps from metadata
        List<Long> timestamps = extractTimestamps(metadataJson);

        // Step 2: Parse turn pairs
        List<TurnPair> turnPairs = parseTurnPairs(content, timestamps);

        // Step 3: Group by temporal gap
        List<List<TurnPair>> groups = groupByTemporalGap(turnPairs);

        // Step 4: Enforce size constraints + metadata 전파
        List<ChunkData> chunks = enforceSize(groups);

        // 빈 metadata인 청크에 원본 metadata 전파
        if (!baseMetadata.isEmpty()) {
            chunks = chunks.stream()
                    .map(c -> c.metadata().isEmpty()
                            ? new ChunkData(c.content(), baseMetadata, c.tokenCount())
                            : c)
                    .toList();
        }

        return chunks;
    }

    // ── Internal types ────────────────────────────────────────────────

    private enum Role { USER, ASSISTANT }

    record TurnPair(String content, LocalDateTime timestamp) {
        int estimatedTokens() {
            return content.length() / 2;
        }
    }

    // ── Step 1: Turn-Pair Parsing ─────────────────────────────────────

    List<TurnPair> parseTurnPairs(String content, List<Long> timestamps) {
        String[] lines = content.split("\n");

        // Check if content has role markers
        boolean hasMarkers = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (USER_PATTERN.matcher(trimmed).find() || ASSISTANT_PATTERN.matcher(trimmed).find()) {
                hasMarkers = true;
                break;
            }
        }

        if (!hasMarkers) {
            return List.of(new TurnPair(content, null));
        }

        // Parse into role segments
        record Segment(Role role, StringBuilder text) {}
        List<Segment> segments = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (USER_PATTERN.matcher(trimmed).find()) {
                segments.add(new Segment(Role.USER, new StringBuilder(trimmed)));
            } else if (ASSISTANT_PATTERN.matcher(trimmed).find()) {
                segments.add(new Segment(Role.ASSISTANT, new StringBuilder(trimmed)));
            } else if (!segments.isEmpty()) {
                segments.get(segments.size() - 1).text().append("\n").append(trimmed);
            }
        }

        // Pair user + following assistant
        List<TurnPair> pairs = new ArrayList<>();
        int msgIdx = 0;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);

            if (seg.role() == Role.USER) {
                StringBuilder pairContent = new StringBuilder(seg.text());
                Long userTs = msgIdx < timestamps.size() ? timestamps.get(msgIdx) : null;
                msgIdx++;

                if (i + 1 < segments.size() && segments.get(i + 1).role() == Role.ASSISTANT) {
                    i++;
                    pairContent.append("\n").append(segments.get(i).text());
                    msgIdx++;
                }

                LocalDateTime ts = userTs != null
                        ? LocalDateTime.ofInstant(Instant.ofEpochMilli(userTs), ZoneId.systemDefault())
                        : null;
                pairs.add(new TurnPair(pairContent.toString(), ts));
            } else if (seg.role() == Role.ASSISTANT) {
                Long ts = msgIdx < timestamps.size() ? timestamps.get(msgIdx) : null;
                msgIdx++;
                LocalDateTime ldt = ts != null
                        ? LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
                        : null;
                pairs.add(new TurnPair(seg.text().toString(), ldt));
            }
        }

        return pairs.isEmpty() ? List.of(new TurnPair(content, null)) : pairs;
    }

    // ── Step 2: Temporal Gap Detection ────────────────────────────────

    List<List<TurnPair>> groupByTemporalGap(List<TurnPair> turnPairs) {
        if (turnPairs.size() <= 1) return List.of(new ArrayList<>(turnPairs));

        List<List<TurnPair>> groups = new ArrayList<>();
        List<TurnPair> currentGroup = new ArrayList<>();
        currentGroup.add(turnPairs.get(0));

        for (int i = 1; i < turnPairs.size(); i++) {
            TurnPair prev = turnPairs.get(i - 1);
            TurnPair curr = turnPairs.get(i);

            if (prev.timestamp() != null && curr.timestamp() != null) {
                long minutesGap = ChronoUnit.MINUTES.between(prev.timestamp(), curr.timestamp());
                if (minutesGap >= temporalGapMinutes) {
                    groups.add(currentGroup);
                    currentGroup = new ArrayList<>();
                }
            }
            currentGroup.add(curr);
        }
        groups.add(currentGroup);

        return groups;
    }

    // ── Step 3: Size Constraint ──────────────────────────────────────

    private List<ChunkData> enforceSize(List<List<TurnPair>> groups) {
        List<ChunkData> result = new ArrayList<>();

        for (List<TurnPair> group : groups) {
            List<ChunkData> groupChunks = new ArrayList<>();
            int totalTokens = group.stream().mapToInt(TurnPair::estimatedTokens).sum();

            if (totalTokens <= maxTokens) {
                String content = group.stream()
                        .map(TurnPair::content)
                        .collect(Collectors.joining("\n"));
                groupChunks.add(new ChunkData(content, Map.of(), content.length() / 2));
            } else if (group.size() == 1) {
                splitAtSentenceBoundary(group.get(0).content(), groupChunks);
            } else {
                splitAtTurnPairBoundary(group, groupChunks);
            }

            // mergeSmallChunks 제거 (I-T-9): minTokens 미만 청크를 무조건 병합하면
            // 문서/노트 기반 메모리에서 의미 있는 짧은 섹션이 인접 섹션에 강제 합쳐져
            // 검색 정밀도가 저하된다. 대화형 턴쌍도 Turn-Pair Bundling이 이미
            // 적절한 크기를 보장하므로 추가 병합이 불필요하다.
            result.addAll(groupChunks);
        }

        return result;
    }

    private void splitAtTurnPairBoundary(List<TurnPair> group, List<ChunkData> result) {
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (TurnPair pair : group) {
            int pairTokens = pair.estimatedTokens();

            if (currentTokens + pairTokens > maxTokens && currentTokens > 0) {
                String content = current.toString();
                result.add(new ChunkData(content, Map.of(), content.length() / 2));
                current = new StringBuilder();
                currentTokens = 0;
            }

            if (!current.isEmpty()) current.append("\n");
            current.append(pair.content());
            currentTokens += pairTokens;
        }

        if (!current.isEmpty()) {
            String content = current.toString();
            result.add(new ChunkData(content, Map.of(), content.length() / 2));
        }
    }

    private void splitAtSentenceBoundary(String text, List<ChunkData> result) {
        String[] sentences = text.split("(?<=[.?!。？！])\\s*");

        if (sentences.length > 1) {
            StringBuilder current = new StringBuilder();
            int currentTokens = 0;

            for (String sentence : sentences) {
                int sentenceTokens = sentence.length() / 2;

                if (currentTokens + sentenceTokens > maxTokens && currentTokens > 0) {
                    String content = current.toString();
                    result.add(new ChunkData(content, Map.of(), content.length() / 2));
                    current = new StringBuilder();
                    currentTokens = 0;
                }

                current.append(sentence);
                currentTokens += sentenceTokens;
            }

            if (!current.isEmpty()) {
                String content = current.toString();
                result.add(new ChunkData(content, Map.of(), content.length() / 2));
            }
        } else {
            // No sentence boundary → hard cut
            int maxChars = maxTokens * 2;
            for (int i = 0; i < text.length(); i += maxChars) {
                String sub = text.substring(i, Math.min(i + maxChars, text.length()));
                result.add(new ChunkData(sub, Map.of(), sub.length() / 2));
            }
        }
    }

    // mergeSmallChunks 삭제 (I-T-9) — 구조적 분할 파괴 방지

    // ── Helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(metadataJson, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (entry.getValue() != null && !"timestamps".equals(entry.getKey())) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return result.isEmpty() ? Map.of() : result;
        } catch (Exception e) {
            log.debug("[ChunkingService] Failed to parse metadata: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<Long> extractTimestamps(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            if (node.has("timestamps") && node.get("timestamps").isArray()) {
                List<Long> timestamps = new ArrayList<>();
                for (JsonNode ts : node.get("timestamps")) {
                    timestamps.add(ts.asLong());
                }
                return timestamps;
            }
        } catch (Exception e) {
            log.debug("[ChunkingService] Failed to parse timestamps: {}", e.getMessage());
        }
        return List.of();
    }
}
