package me.taromati.almah.llm.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {
    @JsonProperty("role")
    private String role;

    // String (텍스트만) 또는 List<ContentPart> (멀티모달)
    @JsonProperty("content")
    private Object content;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    @JsonProperty("tool_calls")
    private List<ChatCompletionResponse.ToolCall> toolCalls;

    /**
     * content에서 텍스트 문자열 추출
     * - String이면 그대로 반환
     * - List<ContentPart>이면 text 타입의 내용만 합쳐서 반환
     */
    @SuppressWarnings("unchecked")
    public String getContentAsString() {
        if (content == null) {
            return null;
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof ContentPart cp && "text".equals(cp.getType())) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(cp.getText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /**
     * 이미지를 포함한 사용자 메시지 생성
     */
    public static ChatMessage userWithImages(String text, List<byte[]> images) {
        List<ContentPart> parts = new ArrayList<>();

        // 텍스트 파트
        parts.add(ContentPart.builder()
                .type("text")
                .text(text)
                .build());

        // 이미지 파트들
        for (byte[] imageData : images) {
            String base64 = Base64.getEncoder().encodeToString(imageData);
            String dataUrl = "data:image/png;base64," + base64;
            parts.add(ContentPart.builder()
                    .type("image_url")
                    .imageUrl(new ImageUrl(dataUrl))
                    .build());
        }

        return ChatMessage.builder()
                .role("user")
                .content(parts)
                .build();
    }

    /**
     * 도구 응답 메시지 생성
     */
    public static ChatMessage toolResponse(String toolCallId, String content) {
        return ChatMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .content(content)
                .build();
    }

    /**
     * 어시스턴트 메시지 (tool_calls 포함) 생성
     */
    public static ChatMessage assistantWithToolCalls(List<ChatCompletionResponse.ToolCall> toolCalls) {
        return ChatMessage.builder()
                .role("assistant")
                .toolCalls(toolCalls)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentPart {
        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("image_url")
        private ImageUrl imageUrl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageUrl {
        @JsonProperty("url")
        private String url;
    }
}
