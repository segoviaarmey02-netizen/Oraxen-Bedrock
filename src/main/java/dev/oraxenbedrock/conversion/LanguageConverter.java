package dev.oraxenbedrock.conversion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Converts Java language JSON files into Bedrock .lang files. */
final class LanguageConverter {
    record Result(int languages, int entries) {}

    Result convert(PackSource source, Path bedrock, List<String> warnings) {
        Map<String, SortedMap<String, String>> languages = new TreeMap<>();
        Path assets = source.root().resolve("assets");
        if (!Files.isDirectory(assets)) return new Result(0, 0);
        try (Stream<Path> paths = Files.walk(assets)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String normalized = file.toString().replace('\\', '/');
                if (!normalized.contains("/lang/")) continue;
                String name = file.getFileName().toString();
                String locale;
                if (name.endsWith(".json")) {
                    locale = bedrockLocale(name.substring(0, name.length() - 5));
                    JsonObject json = JsonSupport.readObject(file);
                    SortedMap<String, String> values =
                            languages.computeIfAbsent(locale, ignored -> new TreeMap<>());
                    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                        if (entry.getValue().isJsonPrimitive())
                            values.put(entry.getKey(), entry.getValue().getAsString());
                    }
                } else if (name.endsWith(".lang")) {
                    locale = bedrockLocale(name.substring(0, name.length() - 5));
                    SortedMap<String, String> values =
                            languages.computeIfAbsent(locale, ignored -> new TreeMap<>());
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                        int split = line.indexOf('=');
                        if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            warnings.add("Could not convert language files: " + ex.getMessage());
        }

        int entries = 0;
        JsonArrayBuilder available = new JsonArrayBuilder();
        for (Map.Entry<String, SortedMap<String, String>> language : languages.entrySet()) {
            if (language.getValue().isEmpty()) continue;
            StringBuilder output = new StringBuilder();
            language.getValue().forEach((key, value) -> output.append(escape(key)).append('=')
                    .append(escape(value)).append('\n'));
            try {
                Path target = bedrock.resolve("texts").resolve(language.getKey() + ".lang");
                Files.createDirectories(target.getParent());
                Files.writeString(target, output, StandardCharsets.UTF_8);
                available.add(language.getKey());
                entries += language.getValue().size();
            } catch (IOException ex) {
                warnings.add("Could not write language " + language.getKey() + ": " + ex.getMessage());
            }
        }
        try {
            if (!available.isEmpty())
                JsonSupport.write(bedrock.resolve("texts/languages.json"), available.array());
        } catch (IOException ex) {
            warnings.add("Could not write texts/languages.json: " + ex.getMessage());
        }
        return new Result(available.size(), entries);
    }

    private String bedrockLocale(String locale) {
        String[] parts = locale.replace('-', '_').split("_", 3);
        if (parts.length < 2) return locale;
        return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT)
                + (parts.length == 3 ? "_" + parts[2] : "");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "")
                .replace("\n", "\\n").replace("=", "\\=");
    }

    private static final class JsonArrayBuilder {
        private final com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        void add(String value) { array.add(value); }
        boolean isEmpty() { return array.isEmpty(); }
        int size() { return array.size(); }
        com.google.gson.JsonArray array() { return array; }
    }
}
