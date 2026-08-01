package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Converts Java sounds.json declarations into Bedrock sound definitions.
 * Copying an OGG alone is not sufficient: Bedrock only resolves named sounds
 * (including custom jukebox songs) when they are present in this registry.
 */
final class SoundConverter {
    record Result(int events, int files) {}

    private final PackSource source;
    private final Path bedrock;
    private final Path oraxenDirectory;
    private final String defaultNamespace;

    SoundConverter(PackSource source, Path bedrock, Path oraxenDirectory, String defaultNamespace) {
        this.source = source;
        this.bedrock = bedrock;
        this.oraxenDirectory = oraxenDirectory;
        this.defaultNamespace = defaultNamespace;
    }

    Result convert(List<OraxenItem> items, List<String> warnings) throws IOException {
        Map<String, SoundMetadata> metadata = readOraxenMetadata(warnings);
        Set<String> records = recordSounds(items);
        JsonObject definitions = new JsonObject();
        Set<String> copied = new HashSet<>();

        for (PackSource.AssetFile asset : source.effectiveAssetFiles()) {
            if (!asset.relative().equalsIgnoreCase("sounds.json")) continue;
            Path file = asset.path();
            try {
                String namespace = asset.namespace();
                JsonObject javaDefinitions = JsonSupport.readObject(file);
                for (Map.Entry<String, JsonElement> entry : javaDefinitions.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    String eventId = identifier(entry.getKey(), namespace);
                    JsonObject converted = convertDefinition(eventId, namespace,
                            entry.getValue().getAsJsonObject(), metadata.get(eventId),
                            records.contains(eventId), copied, warnings);
                    if (converted != null) definitions.add(eventId, converted);
                }
            } catch (IOException | RuntimeException ex) {
                warnings.add("Could not convert sound registry " + file + ": "
                        + ex.getMessage());
            }
        }

        // sounds.yml (and legacy sound.yml) is also accepted directly. This
        // covers installations where
        // Oraxen's generated sounds.json has not yet been put into pack.zip.
        for (Map.Entry<String, SoundMetadata> entry : metadata.entrySet()) {
            if (definitions.has(entry.getKey()) || entry.getValue().files().isEmpty()) continue;
            JsonObject converted = convertMetadata(entry.getKey(), entry.getValue(),
                    records.contains(entry.getKey()), copied, warnings);
            if (converted != null) definitions.add(entry.getKey(), converted);
        }

        if (!definitions.isEmpty()) {
            JsonObject root = new JsonObject();
            root.addProperty("format_version", "1.14.0");
            root.add("sound_definitions", definitions);
            JsonSupport.write(bedrock.resolve("sounds/sound_definitions.json"), root);
        }
        return new Result(definitions.size(), copied.size());
    }

    private JsonObject convertDefinition(String eventId, String namespace, JsonObject javaDefinition,
                                         SoundMetadata metadata, boolean record,
                                         Set<String> copied, List<String> warnings) throws IOException {
        JsonArray javaSounds = javaDefinition.getAsJsonArray("sounds");
        if (javaSounds == null || javaSounds.isEmpty()) return null;
        JsonArray sounds = new JsonArray();
        for (JsonElement value : javaSounds) {
            String name;
            JsonObject properties = null;
            if (value.isJsonPrimitive()) {
                name = value.getAsString();
            } else if (value.isJsonObject()) {
                properties = value.getAsJsonObject();
                if (!properties.has("name")) continue;
                if (properties.has("type")
                        && properties.get("type").getAsString().equalsIgnoreCase("event")) {
                    warnings.add("Sound event reference '" + properties.get("name").getAsString()
                            + "' in " + eventId + " cannot be embedded in a Bedrock sound definition");
                    continue;
                }
                name = properties.get("name").getAsString();
            } else continue;

            JsonObject sound = installSound(name, namespace, copied, warnings);
            if (sound == null) continue;
            copyNumber(properties, sound, "volume");
            copyNumber(properties, sound, "pitch");
            copyNumber(properties, sound, "weight");
            boolean stream = record || metadata != null && metadata.stream()
                    || properties != null && properties.has("stream")
                    && properties.get("stream").getAsBoolean();
            if (stream) sound.addProperty("stream", true);
            sounds.add(sound);
        }
        return definition(category(metadata, record), sounds);
    }

    private JsonObject convertMetadata(String eventId, SoundMetadata metadata, boolean record,
                                       Set<String> copied, List<String> warnings) throws IOException {
        JsonArray sounds = new JsonArray();
        for (String file : metadata.files()) {
            // Current Oraxen resolves an unqualified sound file reference in
            // the minecraft namespace even when the event id is namespaced.
            JsonObject sound = installSound(file, "minecraft", copied, warnings);
            if (sound == null) continue;
            if (record || metadata.stream()) sound.addProperty("stream", true);
            sounds.add(sound);
        }
        return definition(category(metadata, record), sounds);
    }

    private JsonObject installSound(String rawName, String fallbackNamespace,
                                    Set<String> copied, List<String> warnings) throws IOException {
        String clean = rawName.replace('\\', '/');
        String namespace = fallbackNamespace;
        int colon = clean.indexOf(':');
        if (colon >= 0) {
            namespace = clean.substring(0, colon);
            clean = clean.substring(colon + 1);
        }
        clean = clean.replaceFirst("^sounds/", "")
                .replaceFirst("\\.(ogg|wav)$", "");
        Path audio = findAudio(namespace, clean);
        if (audio == null) {
            // Vanilla registries reference client-owned audio that is
            // intentionally absent from server packs. Only missing custom
            // audio is actionable here.
            if (!namespace.equals("minecraft"))
                warnings.add("Sound file not found for '" + rawName + "'");
            return null;
        }
        String extension = extension(audio);
        String relative = namespace + "/" + clean + extension;
        Path target = bedrock.resolve("sounds").resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(audio, target, StandardCopyOption.REPLACE_EXISTING);
        copied.add(relative);

        JsonObject sound = new JsonObject();
        sound.addProperty("name", "sounds/" + namespace + "/" + clean);
        return sound;
    }

    private Path findAudio(String namespace, String name) {
        for (String extension : List.of(".ogg", ".wav")) {
            Path candidate = source.findAsset(namespace, "sounds/" + name + extension);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Map<String, SoundMetadata> readOraxenMetadata(List<String> warnings) {
        Path file = Files.isRegularFile(oraxenDirectory.resolve("sounds.yml"))
                ? oraxenDirectory.resolve("sounds.yml")
                : oraxenDirectory.resolve("sound.yml");
        if (!Files.isRegularFile(file)) return Map.of();
        try (InputStream input = Files.newInputStream(file)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object document = new Yaml(new SafeConstructor(options)).load(input);
            if (!(document instanceof Map<?, ?> root)) return Map.of();
            Map<String, SoundMetadata> result = new LinkedHashMap<>();
            Object soundsValue = Maps.get(root, "sounds");
            if (soundsValue instanceof Map<?, ?> sounds) {
                for (Map.Entry<?, ?> entry : sounds.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
                    addSoundMetadata(result, String.valueOf(entry.getKey()), raw);
                }
            } else if (soundsValue instanceof Collection<?> sounds) {
                for (Object value : sounds) {
                    if (!(value instanceof Map<?, ?> raw)) continue;
                    String id = Maps.string(raw, "id");
                    if (id == null) id = Maps.string(raw, "key");
                    if (id != null) addSoundMetadata(result, id, raw);
                }
            }
            return result;
        } catch (IOException | RuntimeException ex) {
            warnings.add("Could not parse Oraxen sound configuration "
                    + file.getFileName() + ": " + ex.getMessage());
            return Map.of();
        }
    }

    private void addSoundMetadata(Map<String, SoundMetadata> result,
                                  String rawId, Map<?, ?> raw) {
        String id = identifier(rawId, defaultNamespace);
        String category = Maps.string(raw, "category");
        boolean stream = booleanValue(Maps.get(raw, "stream"));
        List<String> files = stringList(Maps.get(raw, "sound"));
        if (files.isEmpty()) files = stringList(Maps.get(raw, "sounds"));
        result.put(id, new SoundMetadata(
                normalizeCategory(category), stream, files));
    }

    private Set<String> recordSounds(List<OraxenItem> items) {
        Set<String> result = new HashSet<>();
        for (OraxenItem item : items) {
            Map<String, Object> component = Maps.section(item.components(), "jukebox_playable");
            String key = Maps.string(component, "song_key");
            if (key == null) key = Maps.string(component, "song");
            if (key != null) {
                String id = identifier(key, defaultNamespace);
                result.add(id);
                // Oraxen migrates legacy unqualified sound events to
                // minecraft:<key> while keeping oraxen:<key> jukebox song ids.
                if (id.startsWith("oraxen:"))
                    result.add("minecraft:" + id.substring("oraxen:".length()));
            }
        }
        return result;
    }

    private JsonObject definition(String category, JsonArray sounds) {
        if (sounds.isEmpty()) return null;
        JsonObject definition = new JsonObject();
        definition.addProperty("category", category);
        definition.add("sounds", sounds);
        return definition;
    }

    private String category(SoundMetadata metadata, boolean record) {
        if (record) return "record";
        return metadata == null ? "master" : metadata.category();
    }

    private static void copyNumber(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && source.get(key).isJsonPrimitive())
            target.add(key, source.get(key).deepCopy());
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return "master";
        String value = category.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "records", "jukebox" -> "record";
            case "music" -> "music";
            case "blocks" -> "block";
            case "players" -> "player";
            default -> value;
        };
    }

    private static String identifier(String value, String namespace) {
        String clean = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return clean.contains(":") ? clean : namespace + ":" + clean;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection)
            return collection.stream().map(String::valueOf).toList();
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static boolean booleanValue(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private record SoundMetadata(String category, boolean stream, List<String> files) {}
}
