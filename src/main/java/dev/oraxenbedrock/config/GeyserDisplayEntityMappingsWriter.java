package dev.oraxenbedrock.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Writes Oraxen furniture mappings consumed by GeyserDisplayEntity. */
public final class GeyserDisplayEntityMappingsWriter {
    public static final String EXTENSION_ID = "geyserdisplayentity";
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final int MAX_PACK_JSON_BYTES = 2 * 1024 * 1024;
    private static final String COMPANION_PACK_UUID =
            "e8f5c939-a701-11eb-b2a3-057d7bb383ba";
    private static final String COMPANION_ENTITY_ID = "geyser:item_display";
    private static final Pattern EXTENSION_DESCRIPTOR_ID = Pattern.compile(
            "(?im)^\\s*id\\s*:\\s*['\"]?" + EXTENSION_ID
                    + "['\"]?\\s*(?:#.*)?$");

    public enum Status { WRITTEN, EXTENSION_MISSING }

    public record Result(Status status, Path file, int mappings,
                         boolean companionPackFound,
                         List<String> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean written() {
            return status == Status.WRITTEN;
        }
    }

    private GeyserDisplayEntityMappingsWriter() {}

    /**
     * Generates {@code extensions/geyserdisplayentity/Mappings/oraxen.yml}.
     * No directories or files are created when the extension is not installed.
     */
    public static Result write(Path geyserDirectory, String bedrockNamespace,
                               Collection<OraxenItem> items) throws IOException {
        return write(geyserDirectory, bedrockNamespace, items, null);
    }

    /**
     * Generates mappings only for item identifiers that were actually written
     * to Geyser's custom item mapping. Passing {@code null} keeps the public
     * standalone writer backwards-compatible and does not filter items.
     */
    public static Result write(Path geyserDirectory, String bedrockNamespace,
                               Collection<OraxenItem> items,
                               Collection<String> availableBedrockIdentifiers)
            throws IOException {
        Objects.requireNonNull(geyserDirectory, "geyserDirectory");
        Objects.requireNonNull(items, "items");

        Path extensions = geyserDirectory.resolve("extensions");
        Path dataFolder = extensions.resolve(EXTENSION_ID);
        Path target = dataFolder.resolve("Mappings/oraxen.yml");
        boolean companionPackFound = hasCompanionPack(
                geyserDirectory.resolve("packs"));
        if (!hasExtensionJar(extensions))
            return new Result(Status.EXTENSION_MISSING, target, 0,
                    companionPackFound, List.of());

        String namespace = sanitizeNamespace(bedrockNamespace);
        Set<String> available = null;
        if (availableBedrockIdentifiers != null) {
            available = new HashSet<>();
            for (String identifier : availableBedrockIdentifiers) {
                if (identifier != null)
                    available.add(identifier.trim().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, OraxenItem> itemsById = new LinkedHashMap<>();
        for (OraxenItem item : items) {
            if (item == null || item.id() == null) continue;
            itemsById.putIfAbsent(item.id().trim().toLowerCase(Locale.ROOT), item);
        }
        List<OraxenItem> furniture = new ArrayList<>();
        for (OraxenItem item : items)
            if (item != null && item.isFurniture()) furniture.add(item);
        furniture.sort(Comparator.comparing(
                item -> safeId(item.id()), String.CASE_INSENSITIVE_ORDER));

        Map<String, Object> mappings = new LinkedHashMap<>();
        Set<String> furnitureIds = new HashSet<>();
        List<String> diagnostics = new ArrayList<>();
        for (OraxenItem item : furniture) {
            String id = safeId(item.id());
            Map<String, Object> furnitureMechanic =
                    Maps.section(item.mechanics(), "furniture");
            String type = Maps.string(furnitureMechanic, "type");
            String normalizedType = type == null || type.isBlank()
                    ? "DISPLAY_ENTITY"
                    : type.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            if (!normalizedType.equals("DISPLAY_ENTITY")) continue;

            if (!furnitureIds.add(id))
                throw new IOException("Oraxen furniture identifiers collide after "
                        + "Bedrock sanitization: " + id);

            OraxenItem displayedItem = item;
            String helperId = Maps.string(furnitureMechanic, "item");
            if (helperId != null && !helperId.isBlank()) {
                displayedItem = itemsById.get(
                        helperId.trim().toLowerCase(Locale.ROOT));
                if (displayedItem == null) {
                    diagnostics.add("Skipped furniture '" + item.id()
                            + "': Mechanics.furniture.item references missing Oraxen item '"
                            + helperId.trim() + "'");
                    continue;
                }
            }

            String displayedId = safeId(displayedItem.id());
            List<String> bedrockIdentifiers = furnitureBedrockIdentifiers(
                    namespace, id, displayedId, available);
            if (bedrockIdentifiers.isEmpty()) {
                diagnostics.add("Skipped furniture '" + item.id()
                        + "': its displayed Bedrock item '" + namespace + ":"
                        + displayedId
                        + "' was not generated");
                continue;
            }

            int variant = 0;
            for (String bedrockIdentifier : bedrockIdentifiers) {
                String identifier = bedrockIdentifier.substring(
                        bedrockIdentifier.indexOf(':') + 1);
                String mappingKey = uniqueMappingKey(
                        mappings, id, identifier, variant++);
                Map<String, Object> mapping = new LinkedHashMap<>();
                mapping.put("type", javaIdentifier(displayedItem.material()));
                mapping.put("item-identifier", extensionItemIdentifier(
                        namespace, identifier));
                mappings.put(mappingKey, mapping);
            }
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("mappings", mappings);
        String content = "# Generated by OraxenBedrock. Restart Geyser to reload.\n"
                + yaml().dump(document);
        writeAtomically(target, content);
        return new Result(Status.WRITTEN, target, mappings.size(),
                companionPackFound, diagnostics);
    }

    /** Uses the plugin's default Bedrock namespace. */
    public static Result write(Path geyserDirectory, Collection<OraxenItem> items)
            throws IOException {
        return write(geyserDirectory, "oraxen", items);
    }

    private static boolean hasExtensionJar(Path extensions) throws IOException {
        if (!Files.isDirectory(extensions)) return false;
        try (var files = Files.list(extensions)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                if (hasExtensionDescriptor(file)) return true;
            }
        }
        return false;
    }

    private static List<String> furnitureBedrockIdentifiers(
            String namespace, String furnitureId, String displayedId,
            Set<String> available) {
        String base = namespace + ":" + displayedId;
        if (available == null) return List.of(base);
        String prefix = namespace + ":";
        // GeyserDisplayEntity compares modern mappings to the exact translated
        // Bedrock identifier. PackConverter gives generated visual states these
        // deterministic names, so every present state needs its own entry.
        Pattern generatedState = Pattern.compile(Pattern.quote(furnitureId)
                + "(?:_state_[0-9a-f]{12}|_model_.+_[0-9a-f]{10}"
                + "(?:_state_[0-9a-f]{12})?)");
        return available.stream()
                .filter(identifier -> identifier.startsWith(prefix))
                .filter(identifier -> {
                    String id = identifier.substring(prefix.length());
                    return identifier.equals(base)
                            || generatedState.matcher(id).matches();
                })
                .sorted(Comparator
                        .comparing((String identifier) -> !identifier.equals(base))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static String uniqueMappingKey(
            Map<String, Object> mappings, String furnitureId,
            String bedrockId, int variant) {
        String preferred = variant == 0
                ? furnitureId : furnitureId + "__" + bedrockId;
        String result = preferred;
        for (int suffix = 2; mappings.containsKey(result); suffix++)
            result = preferred + "__" + suffix;
        return result;
    }

    private static boolean hasCompanionPack(Path packs) throws IOException {
        if (!Files.isDirectory(packs)) return false;
        try (var files = Files.list(packs)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if ((!name.endsWith(".mcpack") && !name.endsWith(".zip"))) continue;
                if (isCompanionPack(file)) return true;
            }
        }
        return false;
    }

    private static boolean isCompanionPack(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                boolean manifest = path.equals("manifest.json")
                        || path.endsWith("/manifest.json");
                boolean itemDisplay = path.equals("entity/item_display.entity.json")
                        || path.endsWith("/entity/item_display.entity.json");
                if (!manifest && !itemDisplay) continue;
                JsonObject json;
                try {
                    json = readPackJson(zip, entry);
                } catch (IOException | RuntimeException ignored) {
                    // Continue scanning other markers in the same archive.
                    continue;
                }
                if (json == null) continue;
                if (manifest && hasCompanionUuid(json)) return true;
                if (itemDisplay && hasCompanionEntity(json)) return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken or non-ZIP archive is not the companion pack.
        }
        return false;
    }

    private static JsonObject readPackJson(ZipFile zip, ZipEntry entry)
            throws IOException {
        if (entry.getSize() > MAX_PACK_JSON_BYTES) return null;
        byte[] bytes;
        try (InputStream input = zip.getInputStream(entry)) {
            bytes = input.readNBytes(MAX_PACK_JSON_BYTES + 1);
        }
        if (bytes.length > MAX_PACK_JSON_BYTES) return null;
        JsonElement parsed = JsonParser.parseString(
                new String(bytes, StandardCharsets.UTF_8));
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static boolean hasCompanionUuid(JsonObject manifest) {
        JsonObject header = object(manifest, "header");
        return header != null && string(header, "uuid")
                .map(COMPANION_PACK_UUID::equalsIgnoreCase).orElse(false);
    }

    private static boolean hasCompanionEntity(JsonObject root) {
        JsonObject clientEntity = object(root, "minecraft:client_entity");
        JsonObject description = object(clientEntity, "description");
        return description != null && string(description, "identifier")
                .map(COMPANION_ENTITY_ID::equalsIgnoreCase).orElse(false);
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject()
                ? value.getAsJsonObject() : null;
    }

    private static java.util.Optional<String> string(JsonObject parent, String key) {
        if (parent == null) return java.util.Optional.empty();
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString())
            return java.util.Optional.empty();
        return java.util.Optional.of(value.getAsString());
    }

    private static boolean hasExtensionDescriptor(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry descriptor = zip.getEntry("extension.yml");
            if (descriptor == null || descriptor.getSize() > MAX_DESCRIPTOR_BYTES)
                return false;
            byte[] bytes;
            try (InputStream input = zip.getInputStream(descriptor)) {
                bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            }
            if (bytes.length > MAX_DESCRIPTOR_BYTES) return false;
            return EXTENSION_DESCRIPTOR_ID.matcher(
                    new String(bytes, StandardCharsets.UTF_8)).find();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String extensionItemIdentifier(String namespace, String id) {
        // GeyserDisplayEntity strips only Geyser's implicit namespace. Custom
        // namespaces remain part of ItemData#getDefinition().getIdentifier().
        return namespace.equals("geyser_custom") ? id : namespace + ":" + id;
    }

    private static String javaIdentifier(String material) {
        String value = material == null ? "paper"
                : material.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (value.isBlank()) value = "paper";
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String safeId(String value) {
        String source = value == null ? "" : value;
        String result = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        return result.isBlank() ? "unnamed" : result;
    }

    private static String sanitizeNamespace(String value) {
        String source = value == null ? "" : value;
        String clean = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        return clean.isBlank() || clean.equals("minecraft") ? "oraxen" : clean;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setSplitLines(false);
        return new Yaml(options);
    }

    private static void writeAtomically(Path target, String content)
            throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null)
            throw new IOException("Display entity mapping has no parent: " + target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent,
                ".oraxenbedrock-display-", ".yml");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
