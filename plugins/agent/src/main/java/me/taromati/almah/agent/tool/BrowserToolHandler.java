package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * browser 도구: Playwright 기반 헤드리스 브라우저 제어.
 * SPA, 로그인 필요 페이지, 상호작용(클릭/입력/스크린샷)에 사용.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class BrowserToolHandler {

    private static final int MAX_INTERACTIVE_ELEMENTS = 50;

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("browser")
                            .description("브라우저 제어 (SPA/동적 페이지용, 정적은 web_fetch)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "action", Map.of(
                                                    "type", "string",
                                                    "enum", List.of("navigate", "read", "click", "type", "screenshot", "evaluate", "close"),
                                                    "description", "작업"
                                            ),
                                            "url", Map.of("type", "string", "description", "URL (navigate)"),
                                            "text", Map.of("type", "string", "description", "클릭 대상 텍스트 (click)"),
                                            "selector", Map.of("type", "string", "description", "CSS 셀렉터 (click/type)"),
                                            "value", Map.of("type", "string", "description", "입력값 (type)"),
                                            "submit", Map.of("type", "boolean", "description", "Enter 전송 (type)"),
                                            "expression", Map.of("type", "string", "description", "JS 표현식 (evaluate)")
                                    ),
                                    "required", List.of("action")
                            ))
                            .build())
                    .build();

    private final ToolRegistry toolRegistry;
    private final BrowserManager browserManager;
    private final AgentConfigProperties.BrowserConfig config;
    private final ObjectMapper objectMapper;

    public BrowserToolHandler(ToolRegistry toolRegistry, BrowserManager browserManager,
                              AgentConfigProperties agentConfig, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.browserManager = browserManager;
        this.config = agentConfig.getBrowser();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("browser", DEFINITION, this::execute, true, "웹");
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String action = (String) args.get("action");

            if (action == null || action.isBlank()) {
                return ToolResult.text("action이 필요합니다 (navigate/read/click/type/screenshot/evaluate/close)");
            }

            return switch (action) {
                case "navigate" -> handleNavigate(args);
                case "read" -> handleRead();
                case "click" -> handleClick(args);
                case "type" -> handleType(args);
                case "screenshot" -> handleScreenshot();
                case "evaluate" -> handleEvaluate(args);
                case "close" -> handleClose();
                default -> ToolResult.text("알 수 없는 action: " + action);
            };
        } catch (PlaywrightException e) {
            log.error("[BrowserToolHandler] Playwright error: {}", e.getMessage());
            return ToolResult.text("브라우저 오류: " + e.getMessage());
        } catch (Exception e) {
            log.error("[BrowserToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("browser 도구 오류: " + e.getMessage());
        }
    }

    private ToolResult handleNavigate(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.text("url이 필요합니다.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        log.info("[BrowserToolHandler] navigate: {}", StringUtils.truncate(url, 100));
        Page page = browserManager.getOrCreatePage();
        int timeoutMs = config.getTimeoutSeconds() * 1000;

        page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(timeoutMs));

        browserManager.touch();
        return buildPageSnapshot(page);
    }

    private ToolResult handleRead() {
        if (!browserManager.isActive()) {
            return ToolResult.text("브라우저가 열려있지 않습니다. navigate로 먼저 페이지를 열어주세요.");
        }
        Page page = browserManager.getOrCreatePage();
        browserManager.touch();
        return buildPageSnapshot(page);
    }

    private ToolResult handleClick(Map<String, Object> args) {
        if (!browserManager.isActive()) {
            return ToolResult.text("브라우저가 열려있지 않습니다. navigate로 먼저 페이지를 열어주세요.");
        }
        String text = (String) args.get("text");
        String selector = (String) args.get("selector");

        if ((text == null || text.isBlank()) && (selector == null || selector.isBlank())) {
            return ToolResult.text("text 또는 selector가 필요합니다.");
        }

        Page page = browserManager.getOrCreatePage();
        int timeoutMs = config.getTimeoutSeconds() * 1000;

        if (selector != null && !selector.isBlank()) {
            page.locator(selector).first().click(new Locator.ClickOptions().setTimeout(timeoutMs));
        } else {
            // text 기반 클릭: link → button → getByText 순서
            Locator link = page.getByRole(com.microsoft.playwright.options.AriaRole.LINK,
                    new Page.GetByRoleOptions().setName(text).setExact(false));
            if (link.count() > 0) {
                link.first().click(new Locator.ClickOptions().setTimeout(timeoutMs));
            } else {
                Locator button = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(text).setExact(false));
                if (button.count() > 0) {
                    button.first().click(new Locator.ClickOptions().setTimeout(timeoutMs));
                } else {
                    page.getByText(text).first().click(new Locator.ClickOptions().setTimeout(timeoutMs));
                }
            }
        }

        // 클릭 후 네비게이션 대기
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (PlaywrightException e) {
            // SPA 클릭은 navigation 없이 DOM만 변경할 수 있음
        }

        browserManager.touch();
        log.info("[BrowserToolHandler] click: text='{}', selector='{}'", text, selector);
        return buildPageSnapshot(page);
    }

    private ToolResult handleType(Map<String, Object> args) {
        if (!browserManager.isActive()) {
            return ToolResult.text("브라우저가 열려있지 않습니다. navigate로 먼저 페이지를 열어주세요.");
        }
        String selector = (String) args.get("selector");
        String value = (String) args.get("value");
        boolean submit = Boolean.TRUE.equals(args.get("submit"));

        if (selector == null || selector.isBlank()) {
            return ToolResult.text("selector가 필요합니다.");
        }
        if (value == null) {
            return ToolResult.text("value가 필요합니다.");
        }

        Page page = browserManager.getOrCreatePage();
        Locator locator = page.locator(selector).first();
        locator.fill(value);

        if (submit) {
            locator.press("Enter");
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(5000));
            } catch (PlaywrightException e) {
                // SPA는 navigation 없을 수 있음
            }
        }

        browserManager.touch();
        log.info("[BrowserToolHandler] type: selector='{}', value length={}, submit={}", selector, value.length(), submit);
        return ToolResult.text("입력 완료: selector='" + selector + "', submit=" + submit);
    }

    private ToolResult handleScreenshot() {
        if (!browserManager.isActive()) {
            return ToolResult.text("브라우저가 열려있지 않습니다. navigate로 먼저 페이지를 열어주세요.");
        }

        Page page = browserManager.getOrCreatePage();
        byte[] screenshot = page.screenshot(new Page.ScreenshotOptions()
                .setFullPage(false));

        browserManager.touch();
        log.info("[BrowserToolHandler] screenshot: {} bytes", screenshot.length);
        return ToolResult.withImage("스크린샷 (현재 뷰포트)", screenshot);
    }

    private ToolResult handleEvaluate(Map<String, Object> args) {
        if (!browserManager.isActive()) {
            return ToolResult.text("브라우저가 열려있지 않습니다. navigate로 먼저 페이지를 열어주세요.");
        }
        String expression = (String) args.get("expression");
        if (expression == null || expression.isBlank()) {
            return ToolResult.text("expression이 필요합니다.");
        }

        Page page = browserManager.getOrCreatePage();
        Object result = page.evaluate(expression);

        browserManager.touch();
        log.info("[BrowserToolHandler] evaluate: {}", StringUtils.truncate(expression, 80));
        String resultStr = result != null ? result.toString() : "undefined";
        return ToolResult.text(StringUtils.truncateRaw(resultStr, config.getMaxContentLength()));
    }

    private ToolResult handleClose() {
        if (!browserManager.isActive()) {
            return ToolResult.text("브라우저가 이미 닫혀있습니다.");
        }
        browserManager.closeBrowser();
        return ToolResult.text("브라우저를 닫았습니다.");
    }

    private ToolResult buildPageSnapshot(Page page) {
        String title = page.title();
        String url = page.url();
        int maxLength = config.getMaxContentLength();

        // JS로 페이지 텍스트 + 상호작용 요소 추출
        String content = (String) page.evaluate("""
                () => {
                    const text = document.body.innerText || '';
                    const elements = [];
                    const seen = new Set();

                    // 링크
                    for (const a of document.querySelectorAll('a[href]')) {
                        const label = (a.innerText || '').trim().substring(0, 80);
                        const href = a.href;
                        if (!label || seen.has(label + href)) continue;
                        seen.add(label + href);
                        elements.push('[link] ' + label + ' → ' + href);
                        if (elements.length >= %d) break;
                    }

                    // 버튼
                    if (elements.length < %d) {
                        for (const btn of document.querySelectorAll('button, input[type="submit"], input[type="button"]')) {
                            const label = (btn.innerText || btn.value || btn.getAttribute('aria-label') || '').trim().substring(0, 80);
                            if (!label || seen.has('btn:' + label)) continue;
                            seen.add('btn:' + label);
                            elements.push('[button] ' + label);
                            if (elements.length >= %d) break;
                        }
                    }

                    // 입력 필드
                    if (elements.length < %d) {
                        for (const input of document.querySelectorAll('input[type="text"], input[type="search"], input[type="email"], input[type="password"], textarea')) {
                            const name = input.name || input.id || input.placeholder || input.getAttribute('aria-label') || '';
                            const sel = input.id ? '#' + input.id : (input.name ? 'input[name="' + input.name + '"]' : '');
                            if (!sel) continue;
                            elements.push('[input] ' + name.substring(0, 40) + ' (selector: ' + sel + ')');
                            if (elements.length >= %d) break;
                        }
                    }

                    return JSON.stringify({ text: text, elements: elements });
                }
                """.formatted(MAX_INTERACTIVE_ELEMENTS, MAX_INTERACTIVE_ELEMENTS,
                MAX_INTERACTIVE_ELEMENTS, MAX_INTERACTIVE_ELEMENTS, MAX_INTERACTIVE_ELEMENTS));

        try {
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {});
            String pageText = (String) parsed.get("text");
            @SuppressWarnings("unchecked")
            List<String> elements = (List<String>) parsed.get("elements");

            StringBuilder result = new StringBuilder();
            result.append("## ").append(title).append("\n");
            result.append("URL: ").append(url).append("\n\n");

            if (pageText != null && !pageText.isBlank()) {
                result.append("### Content\n");
                result.append(StringUtils.truncateRaw(pageText, maxLength));
                result.append("\n\n");
            }

            if (elements != null && !elements.isEmpty()) {
                result.append("### Interactive Elements\n");
                for (String el : elements) {
                    result.append("- ").append(el).append("\n");
                }
            }

            return ToolResult.text(result.toString());
        } catch (Exception e) {
            // JSON 파싱 실패 시 raw 반환
            return ToolResult.text("## " + title + "\nURL: " + url + "\n\n"
                    + StringUtils.truncateRaw(content, maxLength));
        }
    }
}
