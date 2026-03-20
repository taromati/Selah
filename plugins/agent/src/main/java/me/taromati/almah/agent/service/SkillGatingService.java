package me.taromati.almah.agent.service;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.mcp.McpClientManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 스킬 게이팅 파이프라인.
 * active → os → bins → env → mcp-server 순서로 조건을 평가합니다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SkillGatingService {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    @Autowired(required = false)
    private McpClientManager mcpClientManager;

    @Autowired(required = false)
    private SkillEnvInjector skillEnvInjector;

    /**
     * 스킬의 게이팅 조건을 순차 평가합니다.
     */
    public GatingResult evaluate(SkillFile skill) {
        // 1. active 체크
        if (!skill.active()) {
            return new GatingResult(GatingResult.GatingStatus.SKIP, "active: false");
        }

        // 2. OS 체크
        if (!skill.os().isEmpty()) {
            String currentOs = detectOs();
            boolean match = skill.os().stream()
                    .anyMatch(os -> os.equalsIgnoreCase(currentOs));
            if (!match) {
                return new GatingResult(GatingResult.GatingStatus.INACTIVE,
                        "os_mismatch: current=" + currentOs + ", required=" + skill.os());
            }
        }

        // 3. bins 체크
        if (skill.requires() != null && !skill.requires().bins().isEmpty()) {
            for (String bin : skill.requires().bins()) {
                if (!isBinaryAvailable(bin)) {
                    // install 스펙이 있으면 INSTALL_REQUIRED
                    if (!skill.install().isEmpty()) {
                        return new GatingResult(GatingResult.GatingStatus.INSTALL_REQUIRED,
                                "bin_missing: " + bin, skill.install());
                    }
                    return new GatingResult(GatingResult.GatingStatus.INACTIVE,
                            "bin_missing: " + bin);
                }
            }
        }

        // 4. env 체크 (System.getenv 또는 스킬 env 매핑의 config 해석값)
        if (skill.requires() != null && !skill.requires().env().isEmpty()) {
            for (String envKey : skill.requires().env()) {
                if (System.getenv(envKey) == null
                        && (skillEnvInjector == null || !skillEnvInjector.canResolveEnv(skill, envKey))) {
                    return new GatingResult(GatingResult.GatingStatus.INACTIVE,
                            "env_missing: " + envKey);
                }
            }
        }

        // 5. MCP 서버 체크
        if (skill.mcpServer() != null && !skill.mcpServer().isEmpty()) {
            if (mcpClientManager == null || !mcpClientManager.isConnected(skill.mcpServer())) {
                return new GatingResult(GatingResult.GatingStatus.INACTIVE,
                        "mcp_disconnected: " + skill.mcpServer());
            }
        }

        return new GatingResult(GatingResult.GatingStatus.ACTIVE, null);
    }

    /**
     * 현재 OS를 Almah 스킬 규격 이름으로 반환.
     */
    static String detectOs() {
        if (OS_NAME.contains("mac") || OS_NAME.contains("darwin")) return "darwin";
        if (OS_NAME.contains("win")) return "windows";
        if (OS_NAME.contains("linux")) return "linux";
        return OS_NAME;
    }

    /**
     * which(macOS/Linux) 또는 where(Windows) 명령으로 바이너리 존재 여부 확인.
     */
    private boolean isBinaryAvailable(String binName) {
        try {
            String cmd = OS_NAME.contains("win") ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(cmd, binName)
                    .redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("[SkillGating] Failed to check binary '{}': {}", binName, e.getMessage());
            return false;
        }
    }
}
