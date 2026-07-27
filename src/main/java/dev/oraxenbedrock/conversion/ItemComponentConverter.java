package dev.oraxenbedrock.conversion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.model.OraxenItem;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the Java item components that Geyser custom mapping v2 can translate.
 * Runtime-only Oraxen sections and unsupported components are intentionally
 * omitted, as unknown component names make the complete mapping file invalid.
 */
final class ItemComponentConverter {
    private static final Set<String> SUPPORTED = Set.of(
            "consumable", "equippable", "food", "max_damage", "max_stack_size",
            "use_cooldown", "enchantable", "tool", "repairable",
            "kinetic_weapon", "piercing_weapon", "swing_animation", "use_effects",
            "enchantment_glint_override", "attack_range"
    );

    void apply(OraxenItem item, JsonObject definition) {
        JsonObject output = definition.has("components")
                ? definition.getAsJsonObject("components") : new JsonObject();
        for (Map.Entry<String, Object> entry : item.components().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
            // Oraxen exposes this component under its historical user-facing
            // name while Geyser expects the vanilla data-component name.
            if (key.equals("durability")) {
                key = "max_damage";
                Object durability = entry.getValue();
                if (durability instanceof Map<?, ?> map) durability = mapValue(map, "value");
                Integer parsed = integer(durability);
                if (parsed != null) output.addProperty("minecraft:max_damage", parsed);
                continue;
            }
            if (!SUPPORTED.contains(key)) continue;
            JsonElement value = JsonSupport.GSON.toJsonTree(entry.getValue());
            output.add("minecraft:" + key, value);
        }
        if (!output.isEmpty()) definition.add("components", output);
    }

    private Object mapValue(Map<?, ?> map, String key) {
        return map.entrySet().stream()
                .filter(entry -> String.valueOf(entry.getKey()).equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
