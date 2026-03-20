package me.taromati.almah.core.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 플러그인 로그 파일에서 최근 줄을 읽어 JSON 포맷으로 반환하는 공용 유틸리티.
 */
public final class LogFileReader {

    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[[^]]*]\\s+(\\w+)\\s+(\\S+)\\s+-\\s+(.*)$");

    private LogFileReader() {
    }

    /**
     * 플러그인 로그 파일에서 최근 N줄을 읽어 JSON 포맷 리스트로 반환.
     *
     * @param pluginName 플러그인 이름 (로그 파일명: ./logs/{pluginName}.log)
     * @param maxLines   최대 줄 수
     * @return JSON 형식의 로그 줄 리스트
     */
    public static List<String> readRecent(String pluginName, int maxLines) {
        Path logFile = Path.of("./logs/" + pluginName + ".log");
        if (!logFile.toFile().exists()) return List.of();

        List<String> allLines = readTailLines(logFile, maxLines * 2);

        List<String> result = new ArrayList<>();
        for (String line : allLines) {
            Matcher m = LOG_PATTERN.matcher(line);
            if (m.matches()) {
                String time = m.group(1);
                String level = m.group(2);
                String logger = m.group(3);
                String message = m.group(4);
                result.add("{\"time\":\"" + escapeJson(time)
                        + "\",\"level\":\"" + escapeJson(level)
                        + "\",\"logger\":\"" + escapeJson(logger)
                        + "\",\"message\":\"" + escapeJson(message) + "\"}");
            }
            if (result.size() >= maxLines) break;
        }
        return result;
    }

    static List<String> readTailLines(Path logFile, int targetLines) {
        List<String> allLines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) return List.of();

            int blockSize = 8192;
            long pos = fileLength;
            byte[] buf = new byte[blockSize];
            StringBuilder tail = new StringBuilder();

            while (pos > 0 && allLines.size() < targetLines) {
                int readSize = (int) Math.min(blockSize, pos);
                pos -= readSize;
                raf.seek(pos);
                raf.readFully(buf, 0, readSize);

                String chunk = new String(buf, 0, readSize, StandardCharsets.UTF_8) + tail;
                tail.setLength(0);

                String[] lines = chunk.split("\n", -1);
                tail.append(lines[0]);
                for (int i = lines.length - 1; i >= 1; i--) {
                    if (!lines[i].isEmpty()) {
                        allLines.addFirst(lines[i]);
                    }
                }
            }
            if (!tail.isEmpty()) {
                allLines.addFirst(tail.toString());
            }
        } catch (IOException e) {
            return List.of();
        }
        return allLines;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
