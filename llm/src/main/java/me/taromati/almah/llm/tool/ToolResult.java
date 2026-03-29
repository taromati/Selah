package me.taromati.almah.llm.tool;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Tool 실행 결과
 */
@Getter
@Builder
public class ToolResult {
    private final String text;
    private final byte[] image;

    /** Follow-up LLM 호출 시 추가 컨텍스트. 도구 핸들러가 설정, ToolCallingService가 사용. */
    @Builder.Default
    private final String followUpHint = null;

    /** 동적 도구 로딩 요청. ToolCallingService가 다음 라운드부터 지정 도구를 tools 배열에 추가. */
    @Builder.Default
    private final List<String> loadTools = null;

    /** 도구 실행 실패 여부. true이면 LLM에 오류 결과로 전달. */
    @Builder.Default
    private final boolean failure = false;

    public static ToolResult text(String text) {
        return ToolResult.builder().text(text).build();
    }

    public static ToolResult withImage(String text, byte[] image) {
        return ToolResult.builder().text(text).image(image).build();
    }

    public static ToolResult withLoadTools(String text, List<String> loadTools) {
        return ToolResult.builder().text(text).loadTools(loadTools).build();
    }

    public static ToolResult failure(String text) {
        return ToolResult.builder().text(text).failure(true).build();
    }

    public boolean hasImage() {
        return image != null && image.length > 0;
    }
}
