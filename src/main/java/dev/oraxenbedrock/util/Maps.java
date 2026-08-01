package dev.oraxenbedrock.util;

import java.util.*;

public final class Maps {
    private Maps() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(Map<?, ?> map, String key) {
        Object value = get(map, key);
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    public static Object get(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).equalsIgnoreCase(key)) return entry.getValue();
        }
        String normalized = normalizeKey(key);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (normalizeKey(String.valueOf(entry.getKey())).equals(normalized))
                return entry.getValue();
        }
        return null;
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "_");
    }

    public static String string(Map<?, ?> map, String key) {
        Object value = get(map, key);
        return value == null ? null : String.valueOf(value);
    }

    public static Integer integer(Map<?, ?> map, String key) {
        Object value = get(map, key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Double decimal(Map<?, ?> map, String key) {
        Object value = get(map, key);
        if (value instanceof Number n) return n.doubleValue();
        if (value != null) {
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static List<String> strings(Map<?, ?> map, String key) {
        Object value = get(map, key);
        if (value instanceof Collection<?> values)
            return values.stream().map(String::valueOf).toList();
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
}
