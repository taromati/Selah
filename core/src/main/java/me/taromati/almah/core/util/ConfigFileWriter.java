package me.taromati.almah.core.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * config.yml 파일의 특정 값을 업데이트하는 유틸리티
 * YAML 파일의 구조와 주석을 보존하면서 특정 키의 값만 변경
 */
@Slf4j
public final class ConfigFileWriter {

    private static final String CONFIG_FILE = System.getProperty("almah.config-file", "config.yml");

    private ConfigFileWriter() {
    }

    /**
     * YAML 파일의 특정 경로에 있는 값을 업데이트
     *
     * @param path  점(.)으로 구분된 YAML 경로 (예: "plugins.recorder.chzzk.NID_AUT")
     * @param value 새로운 값
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public static void updateYamlValue(String path, String value) throws IOException {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + CONFIG_FILE);
        }

        List<String> lines = Files.readAllLines(configPath);
        List<String> updatedLines = updateLines(lines, path, value);
        Files.write(configPath, updatedLines);
        log.info("Config updated: {} = {}", path, maskValue(value));
    }

    /**
     * YAML 파일의 여러 값을 한 번에 업데이트
     *
     * @param updates 경로-값 쌍 목록
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public static void updateYamlValues(List<PathValue> updates) throws IOException {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + CONFIG_FILE);
        }

        List<String> lines = Files.readAllLines(configPath);
        for (PathValue update : updates) {
            lines = updateLines(lines, update.path(), update.value());
        }
        Files.write(configPath, lines);
        log.info("Config updated: {} values", updates.size());
    }

    private static List<String> updateLines(List<String> lines, String path, String value) {
        String[] pathParts = path.split("\\.");
        List<String> result = new ArrayList<>(lines);

        int[] indentStack = new int[pathParts.length];
        int currentDepth = 0;
        boolean[] foundPath = new boolean[pathParts.length];

        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);
            String trimmed = line.trim();

            // 빈 줄이나 주석은 스킵
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int indent = getIndent(line);

            // 들여쓰기가 줄어들면 깊이 조정
            while (currentDepth > 0 && indent <= indentStack[currentDepth - 1]) {
                foundPath[currentDepth] = false;
                currentDepth--;
            }

            // 현재 키 확인
            String currentKey = getKey(trimmed);
            if (currentKey == null) {
                continue;
            }

            // 경로 매칭 확인
            if (currentDepth < pathParts.length && currentKey.equals(pathParts[currentDepth])) {
                foundPath[currentDepth] = true;
                indentStack[currentDepth] = indent;

                // 마지막 경로 요소면 값 업데이트
                if (currentDepth == pathParts.length - 1) {
                    // 기존 멀티라인 연속 줄 제거 (block scalar, multiline quoted)
                    removeMultilineContinuation(result, i, indent);

                    if (value != null && value.contains("\n")) {
                        // YAML block scalar (|) 형식으로 멀티라인 값 작성
                        result.set(i, " ".repeat(indent) + currentKey + ": |");
                        String[] valueLines = value.split("\n", -1);
                        int end = valueLines.length;
                        while (end > 0 && valueLines[end - 1].isEmpty()) {
                            end--;
                        }
                        int childIndent = indent + 2;
                        String childIndentStr = " ".repeat(childIndent);
                        for (int j = end - 1; j >= 0; j--) {
                            result.add(i + 1, valueLines[j].isEmpty() ? "" : childIndentStr + valueLines[j]);
                        }
                    } else {
                        result.set(i, formatLine(indent, currentKey, value));
                    }
                    return result;
                }

                currentDepth++;
            }
        }

        log.warn("Path not found in config: {}", path);
        return result;
    }

    /**
     * 멀티라인 YAML 값의 연속 줄을 제거.
     * block scalar (|, >) 또는 멀티라인 따옴표 문자열 감지.
     */
    private static void removeMultilineContinuation(List<String> lines, int keyLineIndex, int keyIndent) {
        String keyLine = lines.get(keyLineIndex);
        String trimmed = keyLine.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx < 0) return;

        String valuePart = trimmed.substring(colonIdx + 1).trim();

        boolean isBlockScalar = valuePart.matches("[|>][0-9+-]*\\s*(#.*)?");
        boolean isMultilineQuoted = valuePart.startsWith("\"") && !valuePart.endsWith("\"");

        if (!isBlockScalar && !isMultilineQuoted) {
            return;
        }

        int removeFrom = keyLineIndex + 1;
        int removeTo = removeFrom;

        if (isBlockScalar) {
            while (removeTo < lines.size()) {
                String line = lines.get(removeTo);
                if (line.trim().isEmpty()) {
                    removeTo++;
                    continue;
                }
                if (getIndent(line) > keyIndent) {
                    removeTo++;
                } else {
                    break;
                }
            }
            // 섹션 사이 빈 줄은 유지
            while (removeTo > removeFrom && lines.get(removeTo - 1).trim().isEmpty()) {
                removeTo--;
            }
        } else {
            // 멀티라인 따옴표: 닫는 따옴표까지 제거
            while (removeTo < lines.size()) {
                String line = lines.get(removeTo);
                removeTo++;
                if (line.trim().endsWith("\"")) {
                    break;
                }
            }
        }

        for (int i = removeTo - 1; i >= removeFrom; i--) {
            lines.remove(i);
        }
    }

    private static int getIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static String getKey(String trimmedLine) {
        int colonIndex = trimmedLine.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }
        return trimmedLine.substring(0, colonIndex).trim();
    }

    private static String formatLine(int indent, String key, String value) {
        String indentStr = " ".repeat(indent);
        // 값에 특수문자가 있으면 따옴표로 감싸기
        String formattedValue = needsQuotes(value) ? "\"" + value + "\"" : value;
        return indentStr + key + ": " + formattedValue;
    }

    private static final Set<String> YAML_BOOL_KEYWORDS = Set.of(
            "true", "false", "yes", "no", "on", "off",
            "True", "False", "Yes", "No", "On", "Off",
            "TRUE", "FALSE", "YES", "NO", "ON", "OFF"
    );

    private static boolean needsQuotes(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        // YAML boolean 예약어는 따옴표 필요
        if (YAML_BOOL_KEYWORDS.contains(value)) {
            return true;
        }
        // YAML 인라인 리스트는 따옴표 불필요
        if (value.startsWith("[")) {
            return false;
        }
        // 특수문자나 공백이 있으면 따옴표 필요
        return value.contains(" ") || value.contains(":") || value.contains("#") ||
                value.contains("\"") || value.contains("'");
    }

    private static String maskValue(String value) {
        if (value == null || value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 3) + "..." + value.substring(value.length() - 3);
    }

    /**
     * YAML 리스트 값 갱신 (인라인 형식)
     * 예: deny: [] → deny: [bash, file_write]
     *
     * @param path   점(.)으로 구분된 YAML 경로
     * @param values 리스트 값
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public static void updateYamlList(String path, List<String> values) throws IOException {
        String yamlValue = values.isEmpty() ? "[]" : "[" + String.join(", ", values) + "]";
        updateYamlValue(path, yamlValue);
    }

    /**
     * YAML 맵 엔트리 갱신 또는 추가.
     * 기존 키가 있으면 값 갱신, 없으면 부모 섹션 끝에 새 줄 삽입.
     *
     * @param path  점(.)으로 구분된 YAML 경로 (예: "plugins.agent.tools.ask.exec")
     * @param value 새로운 값
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public static void updateOrAddYamlValue(String path, String value) throws IOException {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + CONFIG_FILE);
        }

        List<String> lines = Files.readAllLines(configPath);
        List<String> updatedLines = updateOrAddLines(lines, path, value);
        Files.write(configPath, updatedLines);
        log.info("Config updated (add/update): {} = {}", path, value);
    }

    /**
     * YAML 파일의 여러 값을 한 번에 업데이트하거나 추가.
     * 기존 키가 있으면 값 갱신, 없으면 부모 섹션 끝에 새 줄 삽입.
     *
     * @param updates 경로-값 쌍 목록
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public static void updateOrAddYamlValues(List<PathValue> updates) throws IOException {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + CONFIG_FILE);
        }

        List<String> lines = Files.readAllLines(configPath);
        for (PathValue update : updates) {
            lines = updateOrAddLines(lines, update.path(), update.value());
        }
        Files.write(configPath, lines);
        log.info("Config updated (add/update): {} values", updates.size());
    }

    /**
     * YAML 키 제거.
     * 지정된 경로의 키-값 줄을 삭제.
     *
     * @param path 점(.)으로 구분된 YAML 경로 (예: "plugins.agent.tools.ask.exec")
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public static void removeYamlKey(String path) throws IOException {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + CONFIG_FILE);
        }

        List<String> lines = Files.readAllLines(configPath);
        List<String> updatedLines = removeKeyLines(lines, path);
        Files.write(configPath, updatedLines);
        log.info("Config key removed: {}", path);
    }

    private static List<String> updateOrAddLines(List<String> lines, String path, String value) {
        String[] pathParts = path.split("\\.");
        List<String> result = new ArrayList<>(lines);

        // 먼저 기존 키 갱신 시도
        List<String> updated = updateLines(result, path, value);
        // updateLines가 값을 변경했는지 확인 (같은 참조면 변경 없음)
        if (!updated.equals(result) || containsKey(result, path)) {
            return updated;
        }

        // 키가 없으면 부모 섹션을 찾아서 끝에 삽입
        String parentPath = path.substring(0, path.lastIndexOf('.'));
        String leafKey = pathParts[pathParts.length - 1];

        int parentEnd = findSectionEnd(result, parentPath);
        if (parentEnd < 0) {
            log.warn("Parent path not found in config: {}", parentPath);
            return result;
        }

        // 부모의 자식 들여쓰기 수준 결정
        int parentIndent = findKeyIndent(result, parentPath);
        int childIndent = parentIndent + 2;

        if (value != null && value.contains("\n")) {
            result.add(parentEnd, " ".repeat(childIndent) + leafKey + ": |");
            String[] valueLines = value.split("\n", -1);
            int end = valueLines.length;
            while (end > 0 && valueLines[end - 1].isEmpty()) {
                end--;
            }
            int grandChildIndent = childIndent + 2;
            String indentStr = " ".repeat(grandChildIndent);
            for (int j = 0; j < end; j++) {
                result.add(parentEnd + 1 + j, valueLines[j].isEmpty() ? "" : indentStr + valueLines[j]);
            }
        } else {
            result.add(parentEnd, formatLine(childIndent, leafKey, value));
        }
        return result;
    }

    private static boolean containsKey(List<String> lines, String path) {
        String[] pathParts = path.split("\\.");
        int currentDepth = 0;
        int[] indentStack = new int[pathParts.length];

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = getIndent(line);
            while (currentDepth > 0 && indent <= indentStack[currentDepth - 1]) {
                currentDepth--;
            }

            String key = getKey(trimmed);
            if (key != null && currentDepth < pathParts.length && key.equals(pathParts[currentDepth])) {
                indentStack[currentDepth] = indent;
                if (currentDepth == pathParts.length - 1) return true;
                currentDepth++;
            }
        }
        return false;
    }

    /**
     * 특정 YAML 섹션의 끝 위치 (삽입 지점) 반환.
     * 섹션의 마지막 자식 뒤 줄 인덱스.
     */
    private static int findSectionEnd(List<String> lines, String path) {
        String[] pathParts = path.split("\\.");
        int currentDepth = 0;
        int[] indentStack = new int[pathParts.length];
        int sectionStart = -1;
        int sectionIndent = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = getIndent(line);

            if (sectionStart >= 0) {
                // 섹션 시작 후, 같은 레벨 이하로 돌아오면 섹션 끝
                if (indent <= sectionIndent) {
                    return i;
                }
                continue;
            }

            while (currentDepth > 0 && indent <= indentStack[currentDepth - 1]) {
                currentDepth--;
            }

            String key = getKey(trimmed);
            if (key != null && currentDepth < pathParts.length && key.equals(pathParts[currentDepth])) {
                indentStack[currentDepth] = indent;
                if (currentDepth == pathParts.length - 1) {
                    sectionStart = i;
                    sectionIndent = indent;
                    continue;
                }
                currentDepth++;
            }
        }

        // 파일 끝까지 섹션이 계속되면 파일 끝 반환
        if (sectionStart >= 0) {
            return lines.size();
        }
        return -1;
    }

    private static int findKeyIndent(List<String> lines, String path) {
        String[] pathParts = path.split("\\.");
        int currentDepth = 0;
        int[] indentStack = new int[pathParts.length];

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = getIndent(line);
            while (currentDepth > 0 && indent <= indentStack[currentDepth - 1]) {
                currentDepth--;
            }

            String key = getKey(trimmed);
            if (key != null && currentDepth < pathParts.length && key.equals(pathParts[currentDepth])) {
                indentStack[currentDepth] = indent;
                if (currentDepth == pathParts.length - 1) return indent;
                currentDepth++;
            }
        }
        return -1;
    }

    private static List<String> removeKeyLines(List<String> lines, String path) {
        String[] pathParts = path.split("\\.");
        List<String> result = new ArrayList<>(lines);
        int currentDepth = 0;
        int[] indentStack = new int[pathParts.length];

        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = getIndent(line);
            while (currentDepth > 0 && indent <= indentStack[currentDepth - 1]) {
                currentDepth--;
            }

            String key = getKey(trimmed);
            if (key != null && currentDepth < pathParts.length && key.equals(pathParts[currentDepth])) {
                indentStack[currentDepth] = indent;
                if (currentDepth == pathParts.length - 1) {
                    removeMultilineContinuation(result, i, indent);
                    result.remove(i);
                    return result;
                }
                currentDepth++;
            }
        }

        log.warn("Key not found for removal: {}", path);
        return result;
    }

    /**
     * 경로-값 쌍을 나타내는 레코드
     */
    public record PathValue(String path, String value) {
    }
}
