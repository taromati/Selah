package me.taromati.almah.core.dto.worker;

import lombok.Getter;
import lombok.Setter;

/**
 * Chzzk 인증 쿠키 업데이트 요청 DTO.
 */
@Getter
@Setter
public class ChzzkAuthRequest {
    private String nidAut;
    private String nidSes;
}
