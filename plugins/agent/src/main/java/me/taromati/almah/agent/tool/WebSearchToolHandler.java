package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * web_search 도구: DuckDuckGo / SearXNG 멀티 프로바이더 웹 검색
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class WebSearchToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("web_search")
                            .description("간단한 웹 검색 (단일 쿼리 → 결과 목록). 심층 조사는 gemini를 사용하세요.")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "query", Map.of("type", "string", "description", "쿼리"),
                                            "provider", Map.of(
                                                    "type", "string",
                                                    "description", "검색 엔진 (searxng | duckduckgo). 기본: searxng (Google 포함)",
                                                    "enum", List.of("duckduckgo", "searxng")
                                            ),
                                            "count", Map.of("type", "integer", "description", "결과 수 (기본5)")
                                    ),
                                    "required", List.of("query")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    public WebSearchToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("web_search", DEFINITION, this::execute);
        if (config.getWebSearch().getSearxngUrl() == null || config.getWebSearch().getSearxngUrl().isBlank()) {
            log.warn("[WebSearchToolHandler] SearXNG URL이 설정되지 않았습니다 (plugins.agent.web-search.searxng-url). " +
                    "SearXNG 검색을 사용하려면 URL을 설정하세요.");
        }
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String query = (String) args.get("query");
            String provider = args.containsKey("provider") ? (String) args.get("provider") : "searxng";
            int count = args.containsKey("count") ? ((Number) args.get("count")).intValue() : 5;

            if (query == null || query.isBlank()) {
                return ToolResult.text("검색 쿼리가 비어있습니다.");
            }

            log.info("[WebSearch] provider={}, query={}", provider, StringUtils.truncate(query, 80));

            return switch (provider) {
                case "searxng" -> searchSearXNG(query, count);
                case "duckduckgo" -> searchDuckDuckGo(query, count);
                default -> searchSearXNG(query, count);
            };

        } catch (Exception e) {
            log.error("[WebSearch] Error: {}", e.getMessage());
            return ToolResult.text("웹 검색 오류: " + e.getMessage());
        }
    }

    // ── DuckDuckGo (HTML 스크래핑) ──────────────────────────────

    private ToolResult searchDuckDuckGo(String query, int count) {
        try {
            String url = "https://html.duckduckgo.com/html/?q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .timeout(10_000)
                    .get();

            Elements results = doc.select(".result");
            if (results.isEmpty()) {
                return ToolResult.text("검색 결과가 없습니다.");
            }

            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (Element result : results) {
                if (idx >= count) break;

                Element linkEl = result.selectFirst(".result__a");
                Element snippetEl = result.selectFirst(".result__snippet");

                if (linkEl == null) continue;

                String title = linkEl.text();
                String href = linkEl.attr("href");
                String snippet = snippetEl != null ? snippetEl.text() : "";

                // DuckDuckGo redirect URL에서 실제 URL 추출
                if (href.contains("uddg=")) {
                    try {
                        String decoded = java.net.URLDecoder.decode(
                                href.substring(href.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                        // 추가 파라미터 제거
                        int ampIdx = decoded.indexOf('&');
                        if (ampIdx > 0) decoded = decoded.substring(0, ampIdx);
                        href = decoded;
                    } catch (Exception ignored) {
                        // fallback: 원본 href 사용
                    }
                }

                idx++;
                sb.append(String.format("[%d] %s\n", idx, title));
                sb.append(String.format("    URL: %s\n", href));
                if (!snippet.isEmpty()) {
                    sb.append(String.format("    %s\n", StringUtils.truncateRaw(snippet, 200)));
                }
                sb.append("\n");
            }

            return ToolResult.text(idx > 0 ? sb.toString().trim() : "검색 결과가 없습니다.");

        } catch (Exception e) {
            log.error("[WebSearch] DuckDuckGo error: {}", e.getMessage());
            return ToolResult.text("DuckDuckGo 검색 오류: " + e.getMessage());
        }
    }

    // ── SearXNG (JSON API) ──────────────────────────────────────

    private ToolResult searchSearXNG(String query, int count) {
        String searxngUrl = config.getWebSearch().getSearxngUrl();
        if (searxngUrl == null || searxngUrl.isBlank()) {
            return ToolResult.text("SearXNG URL이 설정되지 않았습니다. (plugins.agent.web-search.searxng-url)");
        }

        try {
            // 끝 슬래시 제거
            String baseUrl = searxngUrl.endsWith("/") ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
            String url = baseUrl + "/search?q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8) +
                    "&format=json&pageno=1";

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getBody() == null) {
                return ToolResult.text("SearXNG 응답이 비어있습니다.");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode resultsNode = root.path("results");

            if (!resultsNode.isArray() || resultsNode.isEmpty()) {
                return ToolResult.text("검색 결과가 없습니다.");
            }

            StringBuilder sb = new StringBuilder();
            int limit = Math.min(count, resultsNode.size());
            for (int i = 0; i < limit; i++) {
                JsonNode r = resultsNode.get(i);
                String title = r.path("title").asText("");
                String resultUrl = r.path("url").asText("");
                String content = r.path("content").asText("");

                sb.append(String.format("[%d] %s\n", i + 1, title));
                sb.append(String.format("    URL: %s\n", resultUrl));
                if (!content.isEmpty()) {
                    sb.append(String.format("    %s\n", StringUtils.truncateRaw(content, 200)));
                }
                sb.append("\n");
            }

            return ToolResult.text(sb.toString().trim());

        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("[WebSearch] SearXNG 연결 실패: {}", e.getMessage());
            return ToolResult.text("SearXNG에 연결할 수 없습니다. 서비스 상태를 확인하세요. (" + searxngUrl + ")");
        } catch (Exception e) {
            log.error("[WebSearch] SearXNG error: {}", e.getMessage());
            return ToolResult.text("SearXNG 검색 오류: " + e.getMessage());
        }
    }
}
