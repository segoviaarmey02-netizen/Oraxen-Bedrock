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
import java.util.stream.Stream;

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

        Path assets = source.root().resolve("assets");
        if (Files.isDirectory(assets)) {
            try (Stream<Path> paths = Files.walk(assets)) {
                for (Path file : paths.filter(this::isSoundRegistry).sorted().toList()) {
                    try {
                        String namespace = file.getParent().getFileName().toString();
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
            }
        }

        // sound.yml is also accepted directly. This covers installations where
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
        String namespace = namespace(eventId);
        JsonArray sounds = new JsonArray();
        for (String file : metadata.files()) {
            JsonObject sound = installSound(file, namespace, copied, warnings);
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
        Path base = source.root().resolve("assets").resolve(namespace).resolve("sounds");
        for (String extension : List.of(".ogg", ".wav")) {
            Path candidate = base.resolve(name + extension);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private Map<String, SoundMetadata> readOraxenMetadata(List<String> warnings) {
        Path file = oraxenDirectory.resolve("sound.yml");
        if (!Files.isRegularFile(file)) return Map.of();
        try (InputStream input = Files.newInputStream(file)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object document = new Yaml(new SafeConstructor(options)).load(input);
            if (!(document instanceof Map<?, ?> root)) return Map.of();
            Map<String, Object> sounds = Maps.section(root, "sounds");
            Map<String, SoundMetadata> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : sounds.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
                String id = identifier(entry.getKey(), defaultNamespace);
                String category = Maps.string(raw, "category");
                boolean stream = booleanValue(Maps.get(raw, "stream"));
                List<String> files = stringList(Maps.get(raw, "sound"));
                if (files.isEmpty()) files = stringList(Maps.get(raw, "sounds"));
                result.put(id, new SoundMetadata(normalizeCategory(category), stream, files));
            }
            return result;
        } catch (IOException | RuntimeException ex) {
            warnings.add("Could not parse Oraxen sound.yml: " + ex.getMessage());
            return Map.of();
        }
    }

    private Set<String> recordSounds(List<OraxenItem> items) {
        Set<String> result = new HashSet<>();
        for (OraxenItem item : items) {
            Map<String, Object> component = Maps.section(item.components(), "jukebox_playable");
            String key = Maps.string(component, "song_key");
            if (key == null) key = Maps.string(component, "song");
            if (key != null) result.add(identifier(key, defaultNamespace));
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

    private boolean isSoundRegistry(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().equalsIgnoreCase("sounds.json")
                && path.getParent() != null
                && path.getParent().getParent() != null
                && path.getParent().getParent().getFileName().toString().equals("assets");
    }

    private static void copyNumber(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && source.get(key).isJsonPrimitive())
            target.add(key, source.get(key).deepCopy());
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return "master";
        String value = category.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "records", "music", "jukebox" -> "record";
            case "blocks" -> "block";
            case "players" -> "player";
            default -> value;
        };
    }

    private static String identifier(String value, String namespace) {
        String clean = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return clean.contains(":") ? clean : namespace + ":" + clean;
    }

    private static String namespace(String identifier) {
        int colon = identifier.indexOf(':');
        return colon < 0 ? "minecraft" : identifier.substring(0, colon);
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
