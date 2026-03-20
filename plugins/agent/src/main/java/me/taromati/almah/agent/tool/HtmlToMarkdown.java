package me.taromati.almah.agent.tool;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.Set;

/**
 * Jsoup 기반 HTML → Markdown 변환 유틸리티.
 * LLM이 소비할 수 있는 수준의 구조 정보를 보존한다.
 */
public final class HtmlToMarkdown {

    private static final Set<String> SKIP_TAGS = Set.of(
            "script", "style", "noscript", "svg", "nav", "footer", "header"
    );

    private HtmlToMarkdown() {}

    public static String convert(Element body, String baseUrl) {
        return convert(body, baseUrl, Integer.MAX_VALUE);
    }

    public static String convert(Element body, String baseUrl, int maxLength) {
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new MarkdownVisitor(sb, baseUrl, maxLength), body);
        return normalizeBlankLines(sb.toString().trim());
    }

    private static String normalizeBlankLines(String text) {
        return text.replaceAll("\\n{3,}", "\n\n");
    }

    private static class MarkdownVisitor implements NodeVisitor {
        private final StringBuilder sb;
        private final String baseUrl;
        private final int maxLength;
        private boolean truncated = false;
        private int skipDepth = 0;
        private int listDepth = 0;
        private int orderedIndex = 0;
        private boolean inPre = false;
        private boolean inTable = false;
        private boolean firstCell = false;
        private int columnCount = 0;

        MarkdownVisitor(StringBuilder sb, String baseUrl, int maxLength) {
            this.sb = sb;
            this.baseUrl = baseUrl;
            this.maxLength = maxLength;
        }

        @Override
        public void head(Node node, int depth) {
            if (truncated) return;

            if (node instanceof Element el && SKIP_TAGS.contains(el.tagName())) {
                skipDepth++;
                return;
            }

            if (skipDepth > 0) return;

            if (node instanceof TextNode textNode) {
                String text = inPre ? textNode.getWholeText() : textNode.text();
                if (!text.isEmpty()) {
                    append(text);
                }
                return;
            }

            if (!(node instanceof Element el)) return;
            String tag = el.tagName();

            switch (tag) {
                case "h1" -> appendBlock("\n\n# ");
                case "h2" -> appendBlock("\n\n## ");
                case "h3" -> appendBlock("\n\n### ");
                case "h4" -> appendBlock("\n\n#### ");
                case "h5" -> appendBlock("\n\n##### ");
                case "h6" -> appendBlock("\n\n###### ");
                case "p", "div", "section", "article" -> ensureNewline();
                case "br" -> append("\n");
                case "strong", "b" -> append("**");
                case "em", "i" -> append("*");
                case "code" -> {
                    if (!inPre) append("`");
                }
                case "pre" -> {
                    inPre = true;
                    appendBlock("\n\n```\n");
                }
                case "a" -> {
                    // 링크 시작은 tail에서 처리
                }
                case "img" -> {
                    String alt = el.attr("alt");
                    String src = resolveUrl(el.attr("src"));
                    if (!alt.isEmpty() && !src.isEmpty()) {
                        append("![" + alt + "](" + src + ")");
                    }
                }
                case "ul" -> {
                    listDepth++;
                    ensureNewline();
                }
                case "ol" -> {
                    listDepth++;
                    orderedIndex = 0;
                    ensureNewline();
                }
                case "li" -> {
                    String indent = "  ".repeat(Math.max(0, listDepth - 1));
                    Element parent = el.parent();
                    if (parent != null && "ol".equals(parent.tagName())) {
                        orderedIndex++;
                        append(indent + orderedIndex + ". ");
                    } else {
                        append(indent + "- ");
                    }
                }
                case "table" -> {
                    inTable = true;
                    ensureNewline();
                }
                case "tr" -> {
                    firstCell = true;
                    columnCount = 0;
                }
                case "th", "td" -> {
                    if (!firstCell) {
                        append(" | ");
                    } else {
                        append("| ");
                        firstCell = false;
                    }
                    columnCount++;
                }
                case "blockquote" -> appendBlock("\n\n> ");
                case "hr" -> appendBlock("\n\n---\n\n");
                default -> {
                    // 기타 태그는 텍스트만 추출
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (truncated) return;
            if (!(node instanceof Element el)) return;
            String tag = el.tagName();

            if (SKIP_TAGS.contains(tag)) {
                skipDepth = Math.max(0, skipDepth - 1);
                return;
            }
            if (skipDepth > 0) return;

            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> append("\n\n");
                case "p", "div", "section", "article" -> ensureNewline();
                case "strong", "b" -> append("**");
                case "em", "i" -> append("*");
                case "code" -> {
                    if (!inPre) append("`");
                }
                case "pre" -> {
                    inPre = false;
                    append("\n```\n\n");
                }
                case "a" -> {
                    String href = resolveUrl(el.attr("href"));
                    String text = el.text();
                    if (!href.isEmpty() && !text.isEmpty()
                            && !href.startsWith("javascript:") && !href.startsWith("#")) {
                        // 이미 추가된 텍스트를 링크로 감싸기 위해
                        // tail에서 href만 추가
                        int textStart = sb.lastIndexOf(text);
                        if (textStart >= 0 && textStart == sb.length() - text.length()) {
                            sb.delete(textStart, sb.length());
                            append("[" + text + "](" + href + ")");
                        }
                    }
                }
                case "ul", "ol" -> {
                    listDepth = Math.max(0, listDepth - 1);
                    if (listDepth == 0) ensureNewline();
                }
                case "li" -> ensureNewline();
                case "tr" -> {
                    append(" |\n");
                    // 헤더 행 다음에 구분선 추가
                    if (el.parent() != null && "thead".equals(el.parent().tagName())) {
                        append("|" + " --- |".repeat(columnCount) + "\n");
                    }
                    // thead 없이 첫 번째 tr이 th를 포함하는 경우
                    else if (!el.select("th").isEmpty()) {
                        append("|" + " --- |".repeat(columnCount) + "\n");
                    }
                }
                case "table" -> {
                    inTable = false;
                    ensureNewline();
                }
                default -> {
                    // 기타 태그: 아무것도 안 함
                }
            }
        }

        private void append(String text) {
            if (truncated) return;
            if (sb.length() + text.length() > maxLength) {
                sb.append(text, 0, maxLength - sb.length());
                sb.append("\n\n... (truncated)");
                truncated = true;
                return;
            }
            sb.append(text);
        }

        private void appendBlock(String text) {
            ensureNewline();
            append(text);
        }

        private void ensureNewline() {
            if (sb.isEmpty()) return;
            if (sb.charAt(sb.length() - 1) != '\n') {
                append("\n");
            }
        }

        private String resolveUrl(String url) {
            if (url == null || url.isEmpty()) return "";
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) {
                return url;
            }
            if (baseUrl != null && !baseUrl.isEmpty()) {
                if (url.startsWith("/")) {
                    // 절대 경로
                    try {
                        java.net.URI base = java.net.URI.create(baseUrl);
                        return base.getScheme() + "://" + base.getHost()
                                + (base.getPort() > 0 ? ":" + base.getPort() : "") + url;
                    } catch (Exception e) {
                        return url;
                    }
                }
                // 상대 경로
                String basePath = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
                return basePath + url;
            }
            return url;
        }
    }
}
