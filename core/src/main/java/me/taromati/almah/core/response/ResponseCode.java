package me.taromati.almah.core.response;

import lombok.Getter;

@Getter
public enum ResponseCode {
    SUCCESS("OK", "성공."),
    FAIL("FAIL", "예기치 못한 오류가 발생했습니다. 나중에 다시 시도해 주세요."),

    // 400
    INVALID_PARAMETER("INVALID_PARAMETER", "필수 파라미터 누락 혹은 잘못된 파라미터가 존재 합니다."),
    ALREADY_EXISTS("ALREADY_EXISTS", "이미 등록된 항목입니다."),
    NOT_FOUND("NOT_FOUND", "해당 항목을 찾을 수 없습니다."),

    // Recorder 관련
    STREAMER_NOT_FOUND("STREAMER_NOT_FOUND", "해당 스트리머를 찾을 수 없습니다."),

    // Patreon 관련
    CREATOR_NOT_FOUND("CREATOR_NOT_FOUND", "해당 크리에이터를 찾을 수 없습니다."),

    // AI Chat 관련
    LLM_CONNECTION_FAILED("LLM_CONNECTION_FAILED", "LLM 서버 연결에 실패했습니다."),
    LLM_RESPONSE_TIMEOUT("LLM_RESPONSE_TIMEOUT", "LLM 응답 시간이 초과되었습니다."),
    IMAGE_GENERATION_FAILED("IMAGE_GENERATION_FAILED", "이미지 생성에 실패했습니다."),
    ZIMAGE_CONNECTION_FAILED("ZIMAGE_CONNECTION_FAILED", "Z-Image 서버 연결에 실패했습니다."),
    ZIMAGE_TIMEOUT("ZIMAGE_TIMEOUT", "이미지 생성 시간이 초과되었습니다."),
    EMBEDDING_FAILED("EMBEDDING_FAILED", "임베딩 생성에 실패했습니다."),
    RAG_SEARCH_FAILED("RAG_SEARCH_FAILED", "RAG 검색에 실패했습니다."),
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", "해당 대화를 찾을 수 없습니다."),

    // 500
    INVALID_DATABASE("INVALID_DATABASE", "예기치 못한 오류가 발생했습니다. 나중에 다시 시도해 주세요."),
    ;

    private final String code;
    private final String message;

    ResponseCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public static ResponseCode fromCode(String code) {
        for (ResponseCode responseCode : values()) {
            if (responseCode.getCode().equals(code)) {
                return responseCode;
            }
        }
        return null;
    }
}
