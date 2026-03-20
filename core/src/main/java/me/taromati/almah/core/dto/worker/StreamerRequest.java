package me.taromati.almah.core.dto.worker;

import lombok.Getter;
import lombok.Setter;

/**
 * 스트리머 추가/수정 요청 DTO.
 */
@Getter
@Setter
public class StreamerRequest {
    private String platform;
    private String accountId;
    private String userName;
    private String useYn;
}
