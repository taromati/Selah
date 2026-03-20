package me.taromati.almah.memory;

import java.util.Map;

public record ChunkData(String content, Map<String, String> metadata, int tokenCount) {}
