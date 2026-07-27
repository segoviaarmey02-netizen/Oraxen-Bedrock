package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Performs a final referential-integrity pass before the pack is installed. */
final class PackValidator {
    record Result(int checkedReferences, List<String> warnings) {}

    Result validate(Path bedrock, JsonObject itemMappings,
                    JsonObject blockMappings) throws IOException {
        for (String required : List.of("manifest.json", "textures/item_texture.json",
                "textures/terrain_texture.json")) {
            if (!Files.isRegularFile(bedrock.resolve(required)))
                throw new IOException("Generated Bedrock pack is missing " + required);
        }

        List<String> warnings = new ArrayList<>();
        int checked = 0;
        Set<String> ids = new HashSet<>();
        JsonObject mapped = itemMappings.getAsJsonObject("items");
        if (mapped != null) for (JsonElement definitions : mapped.asMap().values()) {
            if (!definitions.isJsonArray()) continue;
            for (JsonElement value : definitions.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject definition = value.getAsJsonObject();
                if (!definition.has("bedrock_identifier")) continue;
                String id = definition.get("bedrock_identifier").getAsString();
                if (!ids.add(id)) throw new IOException(
                        "Duplicate generated Bedrock item identifier: " + id);
                checked++;
            }
        }

        checked += validateAtlas(bedrock, "textures/item_texture.json", warnings);
        checked += validateAtlas(bedrock, "textures/terrain_texture.json", warnings);
        checked += validateGeometries(bedrock, blockMappings, warnings);
        checked += validateFlipbooks(bedrock, warnings);
        checked += validateSounds(bedrock, warnings);
        return new Result(checked, List.copyOf(warnings));
    }

    private int validateAtlas(Path bedrock, String file, List<String> warnings) throws IOException {
        JsonObject root = JsonSupport.readObject(bedrock.resolve(file));
        JsonObject data = root.getAsJsonObject("texture_data");
        if (data == null) {
            warnings.add(file + " has no texture_data object");
            return 0;
        }
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
            for (String texture : strings(textures)) {
                checked++;
                if (texture.startsWith("textures/") && !textureExists(bedrock, texture))
                    warnings.add("Missing texture referenced by " + file + ": " + texture);
            }
        }
        return checked;
    }

    private int validateGeometries(Path bedrock, JsonObject blockMappings,
                                   List<String> warnings) throws IOException {
        Set<String> available = new HashSet<>();
        Path models = bedrock.resolve("models");
        if (Files.isDirectory(models)) try (Stream<Path> paths = Files.walk(models)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    JsonArray definitions = JsonSupport.readObject(file)
                            .getAsJsonArray("minecraft:geometry");
                    if (definitions == null) continue;
                    for (JsonElement value : definitions) {
                        JsonObject description = value.getAsJsonObject()
                                .getAsJsonObject("description");
                        if (description != null && description.has("identifier"))
                            available.add(description.get("identifier").getAsString());
                    }
                } catch (RuntimeException ex) {
                    warnings.add("Could not validate geometry file " + file.getFileName());
                }
            }
        }
        int[] checked = {0};
        walk(blockMappings, value -> {
            if (!value.isJsonObject()) return;
            JsonObject object = value.getAsJsonObject();
            if (!object.has("geometry") || !object.get("geometry").isJsonPrimitive()) return;
            String geometry = object.get("geometry").getAsString();
            checked[0]++;
            if (geometry.startsWith("geometry.oraxen.") && !available.contains(geometry))
                warnings.add("Missing generated geometry: " + geometry);
        });
        return checked[0];
    }

    private int validateFlipbooks(Path bedrock, List<String> warnings) throws IOException {
        Path file = bedrock.resolve("textures/flipbook_textures.json");
        if (!Files.isRegularFile(file)) return 0;
        JsonElement parsed;
        try (var reader = Files.newBufferedReader(file)) {
            parsed = com.google.gson.JsonParser.parseReader(reader);
        }
        if (!parsed.isJsonArray()) {
            warnings.add("textures/flipbook_textures.json is not an array");
            return 0;
        }
        int checked = 0;
        for (JsonElement value : parsed.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject flipbook = value.getAsJsonObject();
            if (!flipbook.has("flipbook_texture")) continue;
            String texture = flipbook.get("flipbook_texture").getAsString();
            checked++;
            if (!textureExists(bedrock, texture))
                warnings.add("Missing flipbook texture: " + texture);
        }
        return checked;
    }

    private int validateSounds(Path bedrock, List<String> warnings) throws IOException {
        Path file = bedrock.resolve("sounds/sound_definitions.json");
        if (!Files.isRegularFile(file)) return 0;
        JsonObject definitions = JsonSupport.readObject(file).getAsJsonObject("sound_definitions");
        if (definitions == null) return 0;
        int checked = 0;
        for (JsonElement definition : definitions.asMap().values()) {
            if (!definition.isJsonObject()) continue;
            JsonArray sounds = definition.getAsJsonObject().getAsJsonArray("sounds");
            if (sounds == null) continue;
            for (JsonElement sound : sounds) {
                String name = sound.isJsonObject()
                        ? string(sound.getAsJsonObject().get("name")) : string(sound);
                if (name == null || !name.startsWith("sounds/")) continue;
                checked++;
                if (!Files.isRegularFile(bedrock.resolve(name + ".ogg"))
                        && !Files.isRegularFile(bedrock.resolve(name + ".wav")))
                    warnings.add("Missing audio referenced by sound definitions: " + name);
            }
        }
        return checked;
    }

    private boolean textureExists(Path bedrock, String texture) {
        String normalized = texture.replace('\\', '/').replaceFirst("\\.png$", "");
        return Files.isRegularFile(bedrock.resolve(normalized + ".png"));
    }

    private List<String> strings(JsonElement value) {
        if (value == null) return List.of();
        if (value.isJsonArray()) {
            List<String> output = new ArrayList<>();
            for (JsonElement child : value.getAsJsonArray())
                if (child.isJsonPrimitive()) output.add(child.getAsString());
            return output;
        }
        return value.isJsonPrimitive() ? List.of(value.getAsString()) : List.of();
    }

    private String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private void walk(JsonElement value, java.util.function.Consumer<JsonElement> visitor) {
        visitor.accept(value);
        if (value.isJsonObject())
            value.getAsJsonObject().entrySet().forEach(entry -> walk(entry.getValue(), visitor));
        else if (value.isJsonArray())
            value.getAsJsonArray().forEach(child -> walk(child, visitor));
    }
}
