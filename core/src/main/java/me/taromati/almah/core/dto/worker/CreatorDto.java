package me.taromati.almah.core.dto.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Patreon 크리에이터 정보 DTO.
 * Worker API에서 크리에이터 CRUD 작업에 사용합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorDto {
    private String id;

    @JsonProperty("creator_id")
    private String creatorId;

    private String name;

    @JsonProperty("use_yn")
    private String useYn;
}
