package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.model.OraxenItem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemComponentConverterTest {
    @Test
    void emitsComponentRemovalsInsteadOfLeavingVanillaBehaviorActive() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("consumable", false);
        components.put("!minecraft:food", Map.of());
        components.put("!durability", Map.of());

        JsonObject existing = new JsonObject();
        existing.add("minecraft:consumable", new JsonObject());
        existing.add("minecraft:food", new JsonObject());
        existing.addProperty("minecraft:max_damage", 20);
        JsonObject definition = new JsonObject();
        definition.add("components", existing);

        new ItemComponentConverter().apply(item(components,
                Map.of("durability", Map.of("value", 500))), definition);

        JsonObject converted = definition.getAsJsonObject("components");
        assertFalse(converted.has("minecraft:consumable"));
        assertFalse(converted.has("minecraft:food"));
        assertFalse(converted.has("minecraft:max_damage"));
        assertTrue(converted.get("!minecraft:consumable").isJsonObject());
        assertTrue(converted.get("!minecraft:food").isJsonObject());
        assertTrue(converted.get("!minecraft:max_damage").isJsonObject(),
                "An explicit removal must suppress the mechanics fallback");
    }

    @Test
    void normalizesOraxenAliasesAndNamespacedConsumableEffects() {
        Map<String, Object> equippable = new LinkedHashMap<>();
        equippable.put("slot", " HEAD ");
        equippable.put("equip_sound", " ITEM.ARMOR.EQUIP_CHAIN ");
        equippable.put("allowed_entities", "minecraft:player");
        equippable.put("allowed_entity_types",
                List.of("ZOMBIE", "minecraft:player"));

        Map<String, Object> applyEffect = new LinkedHashMap<>();
        applyEffect.put("type", "MINECRAFT:APPLY_EFFECTS");
        applyEffect.put("effects", Map.of("HASTE",
                Map.of("duration", 2, "amplifier", 1)));
        Map<String, Object> playSound = new LinkedHashMap<>();
        playSound.put("type", "PLAY_SOUND");
        playSound.put("sound", " ENTITY.ENDERMAN.TELEPORT ");

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("equippable", equippable);
        components.put("consumable", Map.of(
                "animation", "EAT",
                "on_consume_effects", List.of(applyEffect, playSound)));
        components.put("use-cooldown", Map.of(
                "seconds", 2.5, "group", " ORAXEN:HEALING "));

        JsonObject definition = new JsonObject();
        new ItemComponentConverter().apply(
                item(components, Map.of()), definition);
        JsonObject converted = definition.getAsJsonObject("components");

        JsonObject equipment = converted.getAsJsonObject(
                "minecraft:equippable");
        assertEquals("head", equipment.get("slot").getAsString());
        assertEquals("minecraft:item.armor.equip_chain",
                equipment.get("equip_sound").getAsString());
        assertFalse(equipment.has("allowed_entity_types"));
        assertEquals(List.of("minecraft:player", "minecraft:zombie"),
                strings(equipment.getAsJsonArray("allowed_entities")));

        JsonArray effects = converted.getAsJsonObject("minecraft:consumable")
                .getAsJsonArray("on_consume_effects");
        JsonObject apply = effects.get(0).getAsJsonObject();
        assertEquals("minecraft:apply_effects",
                apply.get("type").getAsString());
        JsonObject haste = apply.getAsJsonArray("effects")
                .get(0).getAsJsonObject();
        assertEquals("minecraft:haste", haste.get("id").getAsString());
        assertEquals(40, haste.get("duration").getAsInt());
        assertEquals("minecraft:play_sound",
                effects.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("minecraft:entity.enderman.teleport",
                effects.get(1).getAsJsonObject().get("sound").getAsString());
        assertEquals("oraxen:healing",
                converted.getAsJsonObject("minecraft:use_cooldown")
                        .get("cooldown_group").getAsString());
    }

    @Test
    void mergesFriendlyToolRulesAndDropsInvalidHolderShapes() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("tool", Map.of("rules", List.of(
                Map.of("blocks", List.of("STONE", "#mineable/pickaxe"),
                        "materials", List.of("minecraft:deepslate", "STONE"),
                        "tags", List.of("minecraft:mineable/pickaxe")),
                Map.of("blocks", Map.of("invalid", true)))));
        components.put("repairable", Map.of(
                "items", Map.of("invalid", true)));

        JsonObject definition = new JsonObject();
        new ItemComponentConverter().apply(
                item(components, Map.of()), definition);
        JsonObject converted = definition.getAsJsonObject("components");

        JsonArray rules = converted.getAsJsonObject("minecraft:tool")
                .getAsJsonArray("rules");
        assertEquals(List.of("minecraft:stone", "#minecraft:mineable/pickaxe",
                        "minecraft:deepslate"),
                strings(rules.get(0).getAsJsonObject()
                        .getAsJsonArray("blocks")));
        assertFalse(rules.get(0).getAsJsonObject().has("materials"));
        assertFalse(rules.get(0).getAsJsonObject().has("tags"));
        assertFalse(rules.get(1).getAsJsonObject().has("blocks"));
        assertFalse(converted.getAsJsonObject("minecraft:repairable")
                .has("items"));
    }

    private List<String> strings(JsonArray values) {
        return values.asList().stream().map(value -> value.getAsString()).toList();
    }

    private OraxenItem item(
            Map<String, Object> components, Map<String, Object> mechanics) {
        return new OraxenItem(
                "test", "Test", "PAPER", null, "oraxen:test", null,
                false, false, List.of(), null, Map.of(), components, mechanics);
    }
}
