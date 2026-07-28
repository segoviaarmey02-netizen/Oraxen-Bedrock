package dev.oraxenbedrock.conversion;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;

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
            value = normalize(key, value);
            if (value != null) output.add("minecraft:" + key, value);
        }
        if (!output.has("minecraft:max_damage")) {
            Integer durability = Maps.integer(
                    Maps.section(item.mechanics(), "durability"), "value");
            if (durability != null && durability > 0)
                output.addProperty("minecraft:max_damage", durability);
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

    private JsonElement normalize(String component, JsonElement value) {
        if (component.equals("consumable") && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean())
            return value.getAsBoolean() ? new JsonObject() : null;
        if (!value.isJsonObject()) return value;
        JsonObject object = value.getAsJsonObject();
        if (component.equals("equippable")) {
            if (!object.has("allowed_entities") && object.has("allowed_entity_types"))
                object.add("allowed_entities", object.remove("allowed_entity_types"));
            JsonElement allowed = object.get("allowed_entities");
            if (allowed != null && allowed.isJsonArray()) {
                for (int index = 0; index < allowed.getAsJsonArray().size(); index++) {
                    JsonElement entity = allowed.getAsJsonArray().get(index);
                    if (entity.isJsonPrimitive() && entity.getAsJsonPrimitive().isString())
                        allowed.getAsJsonArray().set(index,
                                new com.google.gson.JsonPrimitive(identifier(entity.getAsString())));
                }
            } else if (allowed != null && allowed.isJsonPrimitive()
                    && allowed.getAsJsonPrimitive().isString()) {
                object.addProperty("allowed_entities", identifier(allowed.getAsString()));
            }
            if (object.has("equip_sound")
                    && object.get("equip_sound").isJsonPrimitive())
                object.addProperty("equip_sound",
                        identifier(object.get("equip_sound").getAsString()));
        } else if (component.equals("consumable")) {
            normalizeConsumable(object);
        } else if (component.equals("tool")) {
            normalizeToolRules(object);
        } else if (component.equals("use_cooldown")) {
            normalizeUseCooldown(object);
        } else if (component.equals("repairable")) {
            normalizeHolderField(object, "items");
        }
        return object;
    }

    private void normalizeConsumable(JsonObject consumable) {
        if (consumable.has("animation")
                && consumable.get("animation").isJsonPrimitive())
            consumable.addProperty("animation",
                    consumable.get("animation").getAsString()
                            .toLowerCase(Locale.ROOT));
        if (consumable.has("sound")
                && consumable.get("sound").isJsonPrimitive())
            consumable.addProperty("sound",
                    identifier(consumable.get("sound").getAsString()));
        JsonElement effectsValue = consumable.get("on_consume_effects");
        if (effectsValue == null || !effectsValue.isJsonArray()) return;
        for (JsonElement value : effectsValue.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject effect = value.getAsJsonObject();
            String rawType = effect.has("type")
                    && effect.get("type").isJsonPrimitive()
                    ? effect.get("type").getAsString() : "";
            String type = rawType.toLowerCase(Locale.ROOT);
            if (!type.isBlank() && !type.contains(":"))
                effect.addProperty("type", "minecraft:" + type);
            String shortType = type.contains(":")
                    ? type.substring(type.indexOf(':') + 1) : type;
            if (shortType.equals("apply_effects")
                    && effect.has("effects")
                    && effect.get("effects").isJsonObject())
                effect.add("effects", normalizeEffectMap(
                        effect.getAsJsonObject("effects")));
            else if (shortType.equals("remove_effects"))
                normalizeHolderField(effect, "effects");
            else if (shortType.equals("play_sound")
                    && effect.has("sound")
                    && effect.get("sound").isJsonPrimitive())
                effect.addProperty("sound",
                        identifier(effect.get("sound").getAsString()));
        }
    }

    private JsonArray normalizeEffectMap(JsonObject effects) {
        JsonArray result = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : effects.entrySet()) {
            JsonObject normalized = entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject().deepCopy()
                    : new JsonObject();
            normalized.addProperty("id", identifier(entry.getKey()));
            if (normalized.has("duration")
                    && normalized.get("duration").isJsonPrimitive()
                    && normalized.get("duration").getAsJsonPrimitive().isNumber())
                normalized.addProperty("duration",
                        Math.max(0L, Math.round(
                                normalized.get("duration")
                                        .getAsDouble() * 20.0)));
            result.add(normalized);
        }
        return result;
    }

    private void normalizeUseCooldown(JsonObject cooldown) {
        if (!cooldown.has("cooldown_group") && cooldown.has("group"))
            cooldown.add("cooldown_group", cooldown.remove("group"));
        else
            cooldown.remove("group");
        if (!cooldown.has("cooldown_group")
                || !cooldown.get("cooldown_group").isJsonPrimitive()) return;
        String group = cooldown.get("cooldown_group").getAsString().trim();
        if (group.isEmpty()) cooldown.remove("cooldown_group");
        else cooldown.addProperty("cooldown_group", identifier(group));
    }

    private void normalizeHolderField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null) return;
        Set<String> holders = new java.util.LinkedHashSet<>();
        collectHolderValues(value, false, holders);
        if (holders.isEmpty()) return;
        if (holders.size() == 1)
            object.addProperty(field, holders.iterator().next());
        else {
            JsonArray values = new JsonArray();
            holders.forEach(values::add);
            object.add(field, values);
        }
    }

    private void normalizeToolRules(JsonObject tool) {
        if (!tool.has("rules") || !tool.get("rules").isJsonArray()) return;
        for (JsonElement value : tool.getAsJsonArray("rules")) {
            if (!value.isJsonObject()) continue;
            JsonObject rule = value.getAsJsonObject();
            Set<String> blocks = new java.util.LinkedHashSet<>();
            collectHolderValues(rule.get("blocks"), false, blocks);
            collectHolderValues(rule.get("material"), false, blocks);
            collectHolderValues(rule.get("materials"), false, blocks);
            collectHolderValues(rule.get("tag"), true, blocks);
            collectHolderValues(rule.get("tags"), true, blocks);
            rule.remove("material");
            rule.remove("materials");
            rule.remove("tag");
            rule.remove("tags");
            if (blocks.isEmpty()) continue;
            if (blocks.size() == 1)
                rule.addProperty("blocks", blocks.iterator().next());
            else {
                JsonArray values = new JsonArray();
                blocks.forEach(values::add);
                rule.add("blocks", values);
            }
        }
    }

    private void collectHolderValues(
            JsonElement value, boolean tag, Set<String> output) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(
                    child -> collectHolderValues(child, tag, output));
            return;
        }
        if (!value.isJsonPrimitive()) return;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) return;
        String raw = primitive.getAsString().trim();
        if (raw.isEmpty()) return;
        boolean tagged = tag || raw.startsWith("#");
        String clean = raw.startsWith("#") ? raw.substring(1) : raw;
        String normalized = identifier(clean);
        output.add(tagged ? "#" + normalized : normalized);
    }

    private String identifier(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }
}
