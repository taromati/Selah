package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * web_fetch 도구: 웹 페이지를 Markdown으로 변환하여 가져오기 (Jsoup)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class WebFetchToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("web_fetch")
                            .description("웹 페이지를 마크다운으로 가져오기 (정적 페이지용)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "url", Map.of("type", "string", "description", "URL"),
                                            "selector", Map.of("type", "string", "description", "CSS 셀렉터"),
                                            "extract_links", Map.of("type", "boolean", "description", "링크만 추출"),
                                            "raw", Map.of("type", "boolean", "description", "텍스트만 반환")
                                    ),
                                    "required", List.of("url")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebFetchToolHandler(ToolRegistry toolRegistry, AgentConfigProperties config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("web_fetch", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String url = (String) args.get("url");
            String selector = (String) args.get("selector");
            boolean extractLinks = Boolean.TRUE.equals(args.get("extract_links"));
            boolean raw = Boolean.TRUE.equals(args.get("raw"));

            if (url == null || url.isBlank()) {
                return ToolResult.text("URL이 비어있습니다.");
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            log.info("[WebFetchToolHandler] Fetching: {}", StringUtils.truncate(url, 100));

            AgentConfigProperties.WebFetchConfig webFetchConfig = config.getWebFetch();
            int timeoutMs = webFetchConfig.getTimeoutSeconds() * 1000;
            int maxLength = webFetchConfig.getMaxContentLength();

            Document doc = Jsoup.connect(url)
                    .timeout(timeoutMs)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .get();

            String title = doc.title();

            // extract_links 모드
            if (extractLinks) {
                return extractLinksResult(doc, url, title);
            }

            // 대상 요소 결정
            Element target;
            if (selector != null && !selector.isBlank()) {
                Elements selected = doc.select(selector);
                if (selected.isEmpty()) {
                    return ToolResult.text("셀렉터 '" + selector + "'에 해당하는 요소가 없습니다.");
                }
                target = selected.first();
            } else {
                target = doc.body();
            }

            if (target == null) {
                return ToolResult.text("페이지 본문이 비어있습니다.");
            }

            StringBuilder result = new StringBuilder();
            if (!title.isEmpty()) {
                result.append("# ").append(title).append("\n\n");
            }

            if (raw) {
                result.append(StringUtils.truncateRaw(target.text(), maxLength));
            } else {
                String markdown = HtmlToMarkdown.convert(target, url, maxLength);
                result.append(markdown);
            }

            return ToolResult.text(result.toString());

        } catch (Exception e) {
            log.error("[WebFetchToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("웹 페이지 가져오기 오류: " + e.getMessage());
        }
    }

    private ToolResult extractLinksResult(Document doc, String baseUrl, String title) {
        Elements links = doc.select("a[href]");
        StringBuilder result = new StringBuilder();
        if (!title.isEmpty()) {
            result.append("# ").append(title).append("\n\n");
        }
        result.append("Links (").append(links.size()).append("):\n\n");

        int count = 0;
        for (Element link : links) {
            String href = link.attr("abs:href");
            String text = link.text().trim();
            if (href.isEmpty() || href.startsWith("javascript:")) continue;
            if (text.isEmpty()) text = href;

            result.append("- [").append(text).append("](").append(href).append(")\n");
            count++;
            if (count >= 200) {
                result.append("\n... (").append(links.size() - count).append(" more links)");
                break;
            }
        }

        return ToolResult.text(result.toString());
    }
}
