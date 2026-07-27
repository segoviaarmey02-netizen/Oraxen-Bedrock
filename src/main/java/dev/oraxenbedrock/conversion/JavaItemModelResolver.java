package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the representative Java model from the modern 1.21.4+
 * assets/&lt;namespace&gt;/items definitions. Bedrock/Geyser cannot reproduce every
 * Java predicate, so conditional definitions deliberately use their first
 * concrete branch and report that approximation.
 */
final class JavaItemModelResolver {
    record Result(String model, List<String> warnings) {}

    private final PackSource pack;
    private final String defaultNamespace;

    JavaItemModelResolver(PackSource pack, String defaultNamespace) {
        this.pack = pack;
        this.defaultNamespace = defaultNamespace;
    }

    Result resolve(String reference) throws IOException {
        if (reference == null || reference.isBlank()) return new Result(null, List.of());
        String normalized = normalize(reference);
        int colon = normalized.indexOf(':');
        Path definition = pack.root().resolve("assets")
                .resolve(normalized.substring(0, colon))
                .resolve("items")
                .resolve(normalized.substring(colon + 1) + ".json");
        if (!Files.isRegularFile(definition)) return new Result(null, List.of());

        JsonObject root = JsonSupport.readObject(definition);
        JsonElement node = root.has("model") ? root.get("model") : root;
        List<String> warnings = new ArrayList<>();
        String model = findModel(node, warnings, 0);
        if (model == null)
            warnings.add("Modern item definition has no convertible model: " + normalized);
        return new Result(model, List.copyOf(warnings));
    }

    private String findModel(JsonElement node, List<String> warnings, int depth) {
        if (node == null || node.isJsonNull() || depth > 32) return null;
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                String found = findModel(child, warnings, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (!node.isJsonObject()) return null;

        JsonObject object = node.getAsJsonObject();
        String type = primitive(object.get("type"));
        String shortType = type == null ? "" : type.substring(type.indexOf(':') + 1)
                .toLowerCase(Locale.ROOT);
        if (shortType.equals("model")) {
            String model = primitive(object.get("model"));
            if (model != null) return normalize(model);
        }

        List<String> preferred = switch (shortType) {
            case "condition" -> List.of("on_true", "on_false");
            case "select", "range_dispatch" -> List.of("cases", "entries", "fallback");
            case "composite" -> List.of("models");
            case "special" -> List.of("base");
            default -> List.of("model", "models", "on_true", "on_false",
                    "cases", "entries", "fallback", "base");
        };
        if (List.of("condition", "select", "range_dispatch").contains(shortType))
            warnings.add("Conditional Java item model '" + shortType
                    + "' approximated with its first concrete branch");

        for (String key : preferred) {
            JsonElement child = object.get(key);
            if (child == null) continue;
            if (child.isJsonPrimitive() && key.equals("model")) {
                String value = primitive(child);
                if (value != null && value.contains(":")) return normalize(value);
                continue;
            }
            String found = findModel(child, warnings, depth + 1);
            if (found != null) return found;
        }
        for (var entry : object.entrySet()) {
            if (preferred.contains(entry.getKey()) || entry.getKey().equals("type")) continue;
            String found = findModel(entry.getValue(), warnings, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private String normalize(String value) {
        String normalized = value.replace('\\', '/').replaceFirst("\\.json$", "")
                .replaceFirst("^assets/", "").replaceFirst("^items/", "")
                .replaceFirst("^models/", "");
        return normalized.contains(":") ? normalized : defaultNamespace + ":" + normalized;
    }

    private String primitive(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
