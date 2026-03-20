package me.taromati.almah.core.util;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CLI 명령을 시스템 로그인 셸을 통해 실행하는 ProcessBuilder 팩토리.
 * <p>
 * launchd/systemd 환경에서 PATH가 최소(/usr/bin:/bin)인 문제를 해결합니다.
 * 로그인 셸(-l)이 프로필 파일을 소싱하여 사용자 PATH가 완전히 적용됩니다.
 * </p>
 */
@Component
public class LoginShellProcess {

    /** CLI 명령을 로그인 셸을 통해 실행하는 ProcessBuilder 생성 */
    public ProcessBuilder create(String... command) {
        String joined = shellJoin(command);
        if (isWindows()) {
            return new ProcessBuilder("cmd", "/c", joined);
        }
        String shell = resolveUnixShell();
        return new ProcessBuilder(shell, "-lc", joined);
    }

    /** List<String> 오버로드 */
    public ProcessBuilder create(List<String> command) {
        return create(command.toArray(String[]::new));
    }

    private static String resolveUnixShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) return shell;
        return "/bin/sh";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** 명령어 배열을 셸 안전한 문자열로 결합 */
    private static String shellJoin(String[] command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.length; i++) {
            if (i > 0) sb.append(' ');
            String arg = command[i];
            if (arg.contains(" ") || arg.contains("'") || arg.contains("\"")
                    || arg.contains("$") || arg.contains("`")) {
                sb.append("'").append(arg.replace("'", "'\\''")).append("'");
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }
}
