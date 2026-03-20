package me.taromati.almah.core.dto.worker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Worker 전체 상태 응답 DTO.
 * Main App에서 Worker의 플러그인 상태를 조회할 때 사용합니다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerStatusResponse {
    private RecorderStatus recorder;
    private PatreonStatus patreon;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecorderStatus {
        private boolean enabled;
        private int activeRecordings;
        private int totalStreamers;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatreonStatus {
        private boolean enabled;
        private boolean crawling;
        private int totalCreators;
    }
}
