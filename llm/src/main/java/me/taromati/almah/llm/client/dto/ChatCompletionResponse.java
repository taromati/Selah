package me.taromati.almah.llm.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionResponse {
    @JsonProperty("id")
    private String id;

    @JsonProperty("object")
    private String object;

    @JsonProperty("created")
    private Long created;

    @JsonProperty("model")
    private String model;

    @JsonProperty("choices")
    private List<Choice> choices;

    @JsonProperty("usage")
    private Usage usage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        @JsonProperty("index")
        private Integer index;

        @JsonProperty("message")
        private ResponseMessage message;

        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseMessage {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private String content;

        @JsonProperty("tool_calls")
        private java.util.List<ToolCall> toolCalls;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("function")
        private FunctionCall function;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class FunctionCall {
            @JsonProperty("name")
            private String name;

            @JsonProperty("arguments")
            private String arguments;
        }
    }

    /**
     * tool_calls가 있는지 확인
     */
    public boolean hasToolCalls() {
        return choices != null && !choices.isEmpty()
                && choices.getFirst().getMessage() != null
                && choices.getFirst().getMessage().getToolCalls() != null
                && !choices.getFirst().getMessage().getToolCalls().isEmpty();
    }

    /**
     * 첫 번째 선택지의 tool_calls 반환
     */
    public java.util.List<ToolCall> getToolCalls() {
        if (hasToolCalls()) {
            return choices.getFirst().getMessage().getToolCalls();
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 컨텐츠 반환
     */
    public String getContent() {
        if (choices != null && !choices.isEmpty() && choices.getFirst().getMessage() != null) {
            return choices.getFirst().getMessage().getContent();
        }
        return null;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
