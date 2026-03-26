package me.taromati.almah.agent.service;

import java.util.List;

/**
 * 스킬 게이팅 평가 결과.
 *
 * @param status       게이팅 상태
 * @param reason       상태 사유 (예: "bin_missing: op", "os_mismatch: linux")
 * @param installSpecs INSTALL_REQUIRED 상태일 때 설치 가능한 스펙
 */
public record GatingResult(GatingStatus status, String reason, List<SkillFile.InstallSpec> installSpecs) {

    public GatingResult(GatingStatus status, String reason) {
        this(status, reason, List.of());
    }

    public enum GatingStatus {
        /** 모든 조건 충족 — 활성 */
        ACTIVE,
        /** 조건 미충족 — 비활성 */
        INACTIVE,
        /** bins 미충족이지만 install 스펙 있음 */
        INSTALL_REQUIRED,
        /** active: false로 명시적 비활성 */
        SKIP
    }
}
