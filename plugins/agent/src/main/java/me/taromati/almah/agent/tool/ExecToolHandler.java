package me.taromati.almah.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * exec 도구: 셸 명령 실행 (3단계 보안 레벨)
 *
 * <ul>
 *   <li>blockedPatterns: 위험 명령 패턴 차단 (모든 보안 레벨)</li>
 *   <li>체이닝/치환 문자 차단: ;, &&, ||, `, $()</li>
 *   <li>파이프(|)는 허용: 각 segment별 allowlist 검증</li>
 *   <li>security 레벨: deny / allowlist / full</li>
 * </ul>
 *
 * 승인 정책은 ToolPolicyResolver에서 처리. 여기서는 defense-in-depth로 한 번 더 검증.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ExecToolHandler {

    private static final ChatCompletionRequest.ToolDefinition DEFINITION =
            ChatCompletionRequest.ToolDefinition.builder()
                    .type("function")
                    .function(ChatCompletionRequest.ToolDefinition.Function.builder()
                            .name("exec")
                            .description("셸 명령 실행 (파이프 허용)")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "command", Map.of("type", "string", "description", "명령")
                                    ),
                                    "required", List.of("command")
                            ))
                            .build())
                    .build();

    private final AgentConfigProperties config;
    private final ToolRegistry toolRegistry;
    private final LoginShellProcess loginShellProcess;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SkillEnvInjector skillEnvInjector;

    @Autowired(required = false)
    private SkillManager skillManager;

    public ExecToolHandler(AgentConfigProperties config, ToolRegistry toolRegistry,
                           LoginShellProcess loginShellProcess, ObjectMapper objectMapper) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.loginShellProcess = loginShellProcess;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void register() {
        toolRegistry.register("exec", DEFINITION, this::execute);
    }

    private ToolResult execute(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String command = (String) args.get("command");

            if (command == null || command.isBlank()) {
                return ToolResult.text("명령어가 비어있습니다.");
            }

            var execConfig = config.getExec();

            // 1. blockedPatterns 체크 (모든 보안 레벨에서 적용)
            for (String pattern : execConfig.getBlockedPatterns()) {
                if (command.contains(pattern)) {
                    return ToolResult.text("차단된 명령 패턴: " + pattern);
                }
            }

            // 2. 체이닝/치환 문자 차단 (파이프는 허용)
            if (containsChaining(command)) {
                return ToolResult.text("체이닝(;, &&, ||) 및 명령 치환(`, $())은 허용되지 않습니다.");
            }

            // 3. security 레벨 검증
            String security = execConfig.getSecurity();
            if ("deny".equals(security)) {
                return ToolResult.text("exec 도구가 비활성화되어 있습니다 (security: deny)");
            }

            if ("allowlist".equals(security)) {
                List<String> segments = parseCommandSegments(command);
                List<String> allowlist = execConfig.getAllowlist();
                for (String cmd : segments) {
                    if (!allowlist.contains(cmd)) {
                        return ToolResult.text("허용되지 않은 명령: " + cmd + " (allowlist에 없음)");
                    }
                }
            }
            // security == "full": blockedPatterns만 적용 (위에서 이미 체크됨)

            // 4. curl 타임아웃 자동 주입
            command = injectCurlTimeout(command);

            // 5. 실행
            int outputLimit = execConfig.getOutputLimitKb() * 1024;
            log.info("[ExecToolHandler] Executing: {}", StringUtils.truncate(command, 100));

            ProcessBuilder pb = loginShellProcess.create("bash", "-c", command);
            pb.redirectErrorStream(true);
            injectSkillEnv(pb);

            Process process = pb.start();
            boolean finished = process.waitFor(execConfig.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.text("명령 실행 시간 초과 (" + execConfig.getTimeoutSeconds() + "초)");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > outputLimit) {
                        output.append("\n... (출력 제한 초과: ").append(execConfig.getOutputLimitKb()).append("KB)");
                        break;
                    }
                }
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();

            if (result.isEmpty()) {
                result = "(출력 없음)";
            }

            if (exitCode != 0) {
                return ToolResult.text("exit code: " + exitCode + "\n" + result);
            }

            return ToolResult.text(result);

        } catch (Exception e) {
            log.error("[ExecToolHandler] Error: {}", e.getMessage());
            return ToolResult.text("명령 실행 오류: " + e.getMessage());
        }
    }

    /**
     * 체이닝/치환 문자 포함 여부 확인.
     * 파이프(|)는 허용, ||는 차단.
     */
    public static boolean containsChaining(String command) {
        // $( ) 명령 치환
        if (command.contains("$(")) return true;
        // 백틱 명령 치환
        if (command.contains("`")) return true;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            // 세미콜론
            if (c == ';') return true;
            // && 또는 ||
            if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') return true;
            if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') return true;
        }
        return false;
    }

    /**
     * 파이프로 분리된 각 segment의 첫 번째 토큰(명령 이름) 추출
     */
    public static List<String> parseCommandSegments(String command) {
        String[] segments = command.split("\\|");
        return java.util.Arrays.stream(segments)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    String firstToken = s.split("\\s+")[0];
                    // 경로 포함 명령에서 파일명만 추출 (e.g. /usr/bin/ls → ls)
                    return java.nio.file.Path.of(firstToken).getFileName().toString();
                })
                .toList();
    }

    /**
     * curl 명령에 --connect-timeout / --max-time 미지정 시 자동 주입.
     * 외부 서비스 무응답 시 전체 agent-turn 타임아웃 소진 방지.
     */
    static String injectCurlTimeout(String command) {
        String trimmed = command.stripLeading();
        if (!trimmed.startsWith("curl ") && !trimmed.startsWith("curl\t")) return command;
        if (command.contains("--connect-timeout") || command.contains("-m ") || command.contains("--max-time")) {
            return command;
        }
        // curl 다음에 타임아웃 옵션 삽입
        int curlEnd = command.indexOf("curl") + 4;
        return command.substring(0, curlEnd) + " --connect-timeout 5 --max-time 15" + command.substring(curlEnd);
    }

    private void injectSkillEnv(ProcessBuilder pb) {
        if (skillEnvInjector != null && skillManager != null) {
            try {
                var envMap = skillEnvInjector.resolveEnv(skillManager.getActiveSkills());
                if (!envMap.isEmpty()) {
                    pb.environment().putAll(envMap);
                }
            } catch (Exception e) {
                log.debug("[ExecToolHandler] 스킬 환경변수 주입 실패: {}", e.getMessage());
            }
        }
    }
}
