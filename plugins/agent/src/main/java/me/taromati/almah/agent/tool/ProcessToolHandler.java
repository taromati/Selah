package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import me.taromati.almah.agent.service.SkillEnvInjector;
import me.taromati.almah.agent.service.SkillManager;
import me.taromati.almah.core.util.LoginShellProcess;
import me.taromati.almah.core.util.StringUtils;
import me.taromati.almah.llm.client.dto.ChatCompletionRequest;
import me.taromati.almah.llm.tool.ToolRegistry;
import me.taromati.almah.llm.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * process 도구: 백그라운드 프로세스 관리 (start/poll/kill)
 *
 * <p>장시간 명령(build, test 등)을 백그라운드로 실행하고,
 * 출력을 확인하거나 종료할 수 있습니다.</p>
 *
 * <ul>
 *   <li>start: 명령을 백그라운드로 실행 (exec 보안 정책 동일 적용)</li>
 *   <li>poll: 실행 중인 프로세스의 출력 확인</li>
 *   <li>kill: 프로세스 종료</li>
 *   <li>list: 실행 중인 프로세스 목록</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ProcessToolHandler {

    private static final int MAX_PROCESSES = 5;
    private static final int MAX_OUTPUT_BUFFER = 32 * 1024; // 32KB

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("process")
                            .description("백그라운드 프로세스 관리")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "action", Map.of(
                                                    "type", "string",
                                                    "enum", List.of("start", "poll", "kill", "list"),
                                                    "description", "작업"
                                            ),
                                            "command", Map.of("type", "string", "description", "명령 (start)"),
                                            "pid", Map.of("type", "string", "description", "PID (poll/kill)")
                                    ),
                                    "required", List.of("action")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final LoginShellProcess loginShellProcess;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private SkillEnvInjector skillEnvInjector;

    @Autowired(required = false)
    private SkillManager skillManager;

    private final ConcurrentHashMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    private int nextPid = 1;

    public ProcessToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry,
                              LoginShellProcess loginShellProcess) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.loginShellProcess = loginShellProcess;
    }

    @PostConstruct
    void register() {
        toolRegistry.register("process", DEFINITION, this::execute);
    }

    @PreDestroy
    void cleanup() {
        processes.values().forEach(mp -> {
            if (mp.process.isAlive()) {
                mp.process.destroyForcibly();
            }
        });
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String action = (String) args.get("action");

            if (action == null) {
                return ToolResult.text("action이 필요합니다 (start/poll/kill/list)");
            }

            return switch (action) {
                case "start" -> handleStart((String) args.get("command"));
                case "poll" -> handlePoll((String) args.get("pid"));
                case "kill" -> handleKill((String) args.get("pid"));
                case "list" -> handleList();
                default -> ToolResult.text("알 수 없는 action: " + action);
            };
        } catch (Exception e) {
            log.error("[ProcessToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("프로세스 관리 오류: " + e.getMessage());
        }
    }

    private ToolResult handleStart(String command) {
        if (command == null || command.isBlank()) {
            return ToolResult.text("command가 필요합니다.");
        }

        // 최대 프로세스 수 제한
        long alive = processes.values().stream().filter(mp -> mp.process.isAlive()).count();
        if (alive >= MAX_PROCESSES) {
            return ToolResult.text("실행 중인 프로세스가 최대 " + MAX_PROCESSES + "개입니다. 종료 후 다시 시도하세요.");
        }

        var execConfig = config.getExec();

        // exec 보안 정책 동일 적용
        for (String pattern : execConfig.getBlockedPatterns()) {
            if (command.contains(pattern)) {
                return ToolResult.text("차단된 명령 패턴: " + pattern);
            }
        }

        if (ExecToolHandler.containsChaining(command)) {
            return ToolResult.text("체이닝(;, &&, ||) 및 명령 치환(`, $())은 허용되지 않습니다.");
        }

        String security = execConfig.getSecurity();
        if ("deny".equals(security)) {
            return ToolResult.text("exec가 비활성화되어 있습니다 (security: deny)");
        }

        if ("allowlist".equals(security)) {
            List<String> segments = ExecToolHandler.parseCommandSegments(command);
            List<String> allowlist = execConfig.getAllowlist();
            for (String cmd : segments) {
                if (!allowlist.contains(cmd)) {
                    return ToolResult.text("허용되지 않은 명령: " + cmd + " (allowlist에 없음)");
                }
            }
        }

        try {
            ProcessBuilder pb = loginShellProcess.create("bash", "-c", command);
            pb.redirectErrorStream(true);
            injectSkillEnv(pb);
            Process process = pb.start();

            String pid = String.valueOf(nextPid++);
            var managed = new ManagedProcess(process, command, Instant.now());

            // 출력 수집 스레드
            Thread outputReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        managed.appendOutput(line + "\n");
                    }
                } catch (Exception e) {
                    managed.appendOutput("\n[출력 읽기 오류: " + e.getMessage() + "]");
                }
            });
            managed.outputReader = outputReader;

            processes.put(pid, managed);
            log.info("[ProcessToolHandler] Started process pid={}: {}", pid, StringUtils.truncate(command, 100));

            return ToolResult.text("프로세스 시작됨 (pid: " + pid + ")\n명령: " + StringUtils.truncate(command, 200));

        } catch (Exception e) {
            return ToolResult.text("프로세스 시작 실패: " + e.getMessage());
        }
    }

    private ToolResult handlePoll(String pid) {
        if (pid == null || pid.isBlank()) {
            return ToolResult.text("pid가 필요합니다.");
        }

        ManagedProcess mp = processes.get(pid);
        if (mp == null) {
            return ToolResult.text("프로세스를 찾을 수 없습니다: " + pid);
        }

        String output = mp.getAndClearOutput();
        boolean alive = mp.process.isAlive();
        Duration elapsed = Duration.between(mp.startTime, Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("pid: ").append(pid);
        sb.append(" | 상태: ").append(alive ? "실행 중" : "종료 (exit: " + mp.process.exitValue() + ")");
        sb.append(" | 경과: ").append(formatDuration(elapsed));
        sb.append(" | 명령: ").append(StringUtils.truncate(mp.command, 80));
        sb.append("\n");

        if (output.isEmpty()) {
            sb.append("(새 출력 없음)");
        } else {
            sb.append(StringUtils.truncateRaw(output, config.getExec().getOutputLimitKb() * 1024));
        }

        // 종료된 프로세스는 정리
        if (!alive) {
            processes.remove(pid);
        }

        return ToolResult.text(sb.toString());
    }

    private ToolResult handleKill(String pid) {
        if (pid == null || pid.isBlank()) {
            return ToolResult.text("pid가 필요합니다.");
        }

        ManagedProcess mp = processes.get(pid);
        if (mp == null) {
            return ToolResult.text("프로세스를 찾을 수 없습니다: " + pid);
        }

        if (!mp.process.isAlive()) {
            processes.remove(pid);
            return ToolResult.text("이미 종료된 프로세스입니다 (exit: " + mp.process.exitValue() + ")");
        }

        mp.process.destroyForcibly();
        processes.remove(pid);
        log.info("[ProcessToolHandler] Killed process pid={}", pid);

        return ToolResult.text("프로세스를 종료했습니다 (pid: " + pid + ")");
    }

    private ToolResult handleList() {
        if (processes.isEmpty()) {
            return ToolResult.text("실행 중인 프로세스가 없습니다.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("프로세스 목록:\n");
        processes.forEach((pid, mp) -> {
            boolean alive = mp.process.isAlive();
            Duration elapsed = Duration.between(mp.startTime, Instant.now());
            sb.append(String.format("  pid: %s | %s | %s | %s\n",
                    pid,
                    alive ? "실행 중" : "종료",
                    formatDuration(elapsed),
                    StringUtils.truncate(mp.command, 60)));
        });

        // 종료된 프로세스 정리
        processes.entrySet().removeIf(e -> !e.getValue().process.isAlive());

        return ToolResult.text(sb.toString().trim());
    }

    private static String formatDuration(Duration d) {
        long seconds = d.getSeconds();
        if (seconds < 60) return seconds + "초";
        if (seconds < 3600) return (seconds / 60) + "분 " + (seconds % 60) + "초";
        return (seconds / 3600) + "시간 " + ((seconds % 3600) / 60) + "분";
    }

    private void injectSkillEnv(ProcessBuilder pb) {
        if (skillEnvInjector != null && skillManager != null) {
            try {
                var envMap = skillEnvInjector.resolveEnv(skillManager.getActiveSkills());
                if (!envMap.isEmpty()) {
                    pb.environment().putAll(envMap);
                }
            } catch (Exception e) {
                log.debug("[ProcessToolHandler] 스킬 환경변수 주입 실패: {}", e.getMessage());
            }
        }
    }

    private static class ManagedProcess {
        final Process process;
        final String command;
        final Instant startTime;
        Thread outputReader;
        private final StringBuilder outputBuffer = new StringBuilder();

        ManagedProcess(Process process, String command, Instant startTime) {
            this.process = process;
            this.command = command;
            this.startTime = startTime;
        }

        synchronized void appendOutput(String text) {
            if (outputBuffer.length() + text.length() > MAX_OUTPUT_BUFFER) {
                // 오래된 출력 잘라내기
                int excess = outputBuffer.length() + text.length() - MAX_OUTPUT_BUFFER;
                outputBuffer.delete(0, excess);
            }
            outputBuffer.append(text);
        }

        synchronized String getAndClearOutput() {
            String result = outputBuffer.toString();
            outputBuffer.setLength(0);
            return result;
        }
    }
}
