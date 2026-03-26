package me.taromati.almah.core.dto.worker;

import lombok.Getter;
import lombok.Setter;

/**
 * Patreon 크리에이터 추가 요청 DTO.
 */
@Getter
@Setter
public class CreatorRequest {
    private String creatorId;
    private String name;
}
