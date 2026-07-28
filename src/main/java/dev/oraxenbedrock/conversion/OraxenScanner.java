package dev.oraxenbedrock.conversion;

import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class OraxenScanner {
    public List<OraxenItem> scan(Path oraxenDirectory) throws IOException {
        Path itemsDirectory = oraxenDirectory.resolve("items");
        if (!Files.isDirectory(itemsDirectory))
            throw new NoSuchFileException("Oraxen items directory not found: " + itemsDirectory);

        List<OraxenItem> items = new ArrayList<>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Stream<Path> paths = Files.walk(itemsDirectory)) {
            for (Path path : paths.filter(this::isYaml).sorted().toList()) {
                try (InputStream input = Files.newInputStream(path)) {
                    Object document = yaml.load(input);
                    if (!(document instanceof Map<?, ?> root)) continue;
                    for (Map.Entry<?, ?> entry : root.entrySet()) {
                        if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
                        items.add(readItem(String.valueOf(entry.getKey()), raw));
                    }
                } catch (RuntimeException ex) {
                    throw new IOException("Invalid Oraxen YAML " + path + ": " + ex.getMessage(), ex);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (OraxenItem item : items) {
            if (!seen.add(item.id().toLowerCase(Locale.ROOT)))
                throw new IOException("Duplicate Oraxen item id: " + item.id());
        }
        return items;
    }

    private OraxenItem readItem(String id, Map<?, ?> root) {
        Map<String, Object> pack = Maps.section(root, "Pack");
        Map<String, Object> components =
                new LinkedHashMap<>(Maps.section(root, "Components"));
        if (booleanValue(root, "unstackable") && Maps.get(components, "max_stack_size") == null)
            components.put("max_stack_size", 1);
        Map<String, Object> mechanics = Maps.section(root, "Mechanics");
        String material = Optional.ofNullable(Maps.string(root, "material")).orElse("PAPER");
        String displayName = Optional.ofNullable(first(
                Maps.string(root, "itemname"), Maps.string(root, "displayname"))).orElse(id);
        String model = first(Maps.string(pack, "item_model"), Maps.string(pack, "model"));
        String itemModel = Maps.string(components, "item_model");
        Integer customModelData = Maps.integer(pack, "custom_model_data");
        boolean excludeFromItemModel = Boolean.parseBoolean(
                String.valueOf(Maps.get(pack, "exclude_from_item_model")));
        boolean excludeFromInventory = booleanValue(root,
                "excludeFromInventory", "exclude_from_inventory");
        String parent = Maps.string(pack, "parent_model");
        return new OraxenItem(id, displayName, material, model, itemModel, customModelData,
                excludeFromItemModel, excludeFromInventory,
                readTextures(pack), parent, readPackModels(pack), components, mechanics);
    }

    private Map<String, String> readPackModels(Map<String, Object> pack) {
        Map<String, Object> configured = Maps.section(pack, "models");
        if (configured.isEmpty()) return Map.of();
        Map<String, String> models = new LinkedHashMap<>();
        Set<String> normalizedKeys = new HashSet<>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            String key = entry.getKey().trim();
            Object rawValue = entry.getValue();
            if (key.isEmpty() || rawValue == null
                    || rawValue instanceof Map<?, ?>
                    || rawValue instanceof Collection<?>) continue;
            String value = String.valueOf(rawValue).trim();
            if (value.isEmpty()) continue;
            if (!normalizedKeys.add(key.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException(
                        "Duplicate Pack.models key (case-insensitive): " + key);
            models.put(key, value);
        }
        return Collections.unmodifiableMap(models);
    }

    private List<String> readTextures(Map<String, Object> pack) {
        Object value = Maps.get(pack, "textures");
        if (value instanceof Map<?, ?> map)
            return map.values().stream().filter(Objects::nonNull)
                    .map(String::valueOf).filter(text -> !text.isBlank()).toList();
        if (value instanceof Collection<?> list)
            return list.stream().filter(Objects::nonNull)
                    .map(String::valueOf).filter(text -> !text.isBlank()).toList();
        String texture = first(Maps.string(pack, "texture"), value == null ? null : String.valueOf(value));
        return texture == null ? List.of() : List.of(texture);
    }

    private boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private boolean booleanValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = Maps.get(map, key);
            if (value != null) return Boolean.parseBoolean(String.valueOf(value));
        }
        return false;
    }
}
