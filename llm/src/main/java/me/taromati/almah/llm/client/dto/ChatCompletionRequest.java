package me.taromati.almah.llm.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {
    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<ChatMessage> messages;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("min_p")
    private Double minP;

    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    @JsonProperty("repetition_penalty")
    private Double repetitionPenalty;

    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    @JsonProperty("tools")
    private List<ToolDefinition> tools;

    @JsonProperty("tool_choice")
    private String toolChoice;

    @JsonProperty("stream")
    private Boolean stream;

    @JsonProperty("chat_template_kwargs")
    private Map<String, Object> chatTemplateKwargs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolDefinition {
        @JsonProperty("type")
        @Builder.Default
        private String type = "function";

        @JsonProperty("function")
        private Function function;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Function {
            @JsonProperty("name")
            private String name;

            @JsonProperty("description")
            private String description;

            @JsonProperty("parameters")
            private Map<String, Object> parameters;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolConfig {
        private List<ToolDefinition> tools;
        private String toolChoice;
    }
}
