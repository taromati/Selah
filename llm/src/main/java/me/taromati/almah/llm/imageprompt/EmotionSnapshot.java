package me.taromati.almah.llm.imageprompt;

/**
 * 이미지 프롬프트 강화용 감정 스냅샷 DTO.
 * aichat 모듈의 EmotionalState를 llm 모듈에서 참조 불가하므로 별도 record.
 */
public record EmotionSnapshot(double mood, double energy, double confidence, double longing) {}
