package me.taromati.almah.core.messenger;

import java.util.ArrayList;
import java.util.List;

public class MessageSplitter {

    private MessageSplitter() {}

    public static List<String> split(String message, int maxLength) {
        if (message == null || message.isEmpty()) return List.of();
        if (message.length() <= maxLength) return List.of(message);

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < message.length()) {
            int end = Math.min(start + maxLength, message.length());
            if (end < message.length()) {
                int lastNewline = message.lastIndexOf('\n', end);
                if (lastNewline > start) {
                    end = lastNewline + 1;
                }
            }
            chunks.add(message.substring(start, end));
            start = end;
        }
        return chunks;
    }
}
