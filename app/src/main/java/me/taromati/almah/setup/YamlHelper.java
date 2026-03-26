package me.taromati.almah.setup;

import java.util.Map;

/**
 * SnakeYAML로 파싱된 Map 트리에서 중첩 값을 안전하게 추출하는 헬퍼.
 */
class YamlHelper {

    static Object getPath(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else {
                return null;
            }
        }
        return current;
    }

    static String getString(Map<String, Object> root, String... keys) {
        Object val = getPath(root, keys);
        return val != null ? val.toString() : null;
    }

    static int getInt(Map<String, Object> root, int defaultValue, String... keys) {
        Object val = getPath(root, keys);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static boolean getBool(Map<String, Object> root, boolean defaultValue, String... keys) {
        Object val = getPath(root, keys);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equals(s);
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getMap(Map<String, Object> root, String... keys) {
        Object val = getPath(root, keys);
        if (val instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return Map.of();
    }
}
