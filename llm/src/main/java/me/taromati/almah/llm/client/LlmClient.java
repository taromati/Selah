package me.taromati.almah.llm.client;

import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.client.dto.ChatCompletionResponse;
import me.taromati.almah.llm.client.dto.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 프로바이더 공통 인터페이스.
 * 각 프로바이더(vLLM, OpenAI, Gemini 등)가 이를 구현합니다.
 */
public interface LlmClient {

    /**
     * Chat Completion API 호출
     *
     * @param messages 메시지 목록
     * @param params Sampling 파라미터
     * @param toolConfig 도구 설정 (nullable)
     * @param model 사용할 모델 (nullable → 프로바이더 기본 모델)
     * @return API 응답
     */
    ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params,
                                           ChatCompletionRequest.ToolConfig toolConfig, String model);

    /**
     * 프로바이더 이름 반환
     */
    String getProviderName();

    /**
     * 프로바이더의 기본 모델명.
     * model 파라미터가 null일 때 실제 사용되는 모델 이름을 반환합니다.
     */
    String getDefaultModel();

    /**
     * temperature만 지정하는 편의 오버로드 (요약, 타이밍 판단 등)
     */
    default ChatCompletionResponse chatCompletion(List<ChatMessage> messages, Double temperature) {
        return chatCompletion(messages, SamplingParams.withTemperature(temperature), null, null);
    }

    /**
     * SamplingParams 기반 호출 (도구/모델 없음)
     */
    default ChatCompletionResponse chatCompletion(List<ChatMessage> messages, SamplingParams params) {
        return chatCompletion(messages, params, null, null);
    }

    /**
     * 프로바이더 특화 시스템 프롬프트 힌트.
     * 모델의 행동 특성에 따른 보정 규칙을 반환합니다.
     * 기본: null (추가 규칙 없음).
     */
    default String getSystemPromptHints() {
        return null;
    }

    /**
     * 프로바이더의 능력치 (contextWindow, maxTokens 등).
     * Agent 등 상위 모듈이 provider-agnostic하게 동작할 수 있도록 합니다.
     */
    default ProviderCapabilities getCapabilities() {
        return ProviderCapabilities.empty();
    }

    /**
     * 도구 호출 결과를 바탕으로 최종 응답을 재생성.
     * 기본: null 반환 (=재생성 안 함).
     */
    default String rewriteResponse(List<ChatMessage> context, String draft, SamplingParams params) {
        return null;
    }

    /**
     * Chat Completion API SSE 스트리밍 호출.
     * 토큰 단위로 콜백을 호출하며, 완료 시 전체 응답을 ChatCompletionResponse로 반환.
     * default: 동기 호출 후 전체 content를 단일 콜백으로 전달.
     *
     * @param messages 메시지 목록
     * @param params Sampling 파라미터
     * @param toolConfig 도구 설정 (nullable)
     * @param model 사용할 모델 (nullable → 프로바이더 기본 모델)
     * @param tokenCallback 토큰(delta.content) 수신 콜백
     * @return 전체 응답 (model, usage 등 메타데이터 포함)
     */
    default ChatCompletionResponse chatCompletionStream(List<ChatMessage> messages, SamplingParams params,
                                                          ChatCompletionRequest.ToolConfig toolConfig, String model,
                                                          Consumer<String> tokenCallback) {
        ChatCompletionResponse response = chatCompletion(messages, params, toolConfig, model);
        String content = response.getContent();
        if (content != null && !content.isEmpty()) {
            tokenCallback.accept(content);
        }
        return response;
    }
}
