package dev.oraxenbedrock.conversion;

import com.google.gson.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class JsonSupport {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonSupport() {}

    static JsonObject readObject(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) throw new IOException("Expected JSON object: " + path);
            return element.getAsJsonObject();
        } catch (JsonParseException ex) {
            throw new IOException("Invalid JSON " + path + ": " + ex.getMessage(), ex);
        }
    }

    static void write(Path path, JsonElement json) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }

    static void deepMerge(JsonObject target, JsonObject source) {
        source.entrySet().forEach(entry -> {
            JsonElement old = target.get(entry.getKey());
            if (old != null && old.isJsonObject() && entry.getValue().isJsonObject())
                deepMerge(old.getAsJsonObject(), entry.getValue().getAsJsonObject());
            else target.add(entry.getKey(), entry.getValue().deepCopy());
        });
    }
}
