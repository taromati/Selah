package me.taromati.almah.core.dto.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 스트리머 정보 DTO.
 * Worker API에서 스트리머 CRUD 작업에 사용합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamerDto {
    private String id;
    private String platform;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("use_yn")
    private String useYn;

    private boolean recording;
}
