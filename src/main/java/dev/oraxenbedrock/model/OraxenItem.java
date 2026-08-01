package dev.oraxenbedrock.model;

import java.util.List;
import java.util.Map;

public record OraxenItem(
        String id,
        String displayName,
        String material,
        String model,
        String itemModel,
        Integer customModelData,
        boolean excludeFromItemModel,
        boolean excludeFromInventory,
        List<String> textures,
        String parentModel,
        Map<String, String> packModels,
        Map<String, Object> components,
        Map<String, Object> mechanics
) {
    public boolean isBlock() {
        return hasMechanic("noteblock")
                || hasMechanic("stringblock")
                || hasMechanic("chorusblock")
                || hasMechanic("shapedblock")
                || hasMechanic("shaped_block")
                || hasMechanic("block");
    }

    public boolean isFurniture() {
        return hasMechanic("furniture");
    }

    public boolean isHat() {
        return hasMechanic("hat");
    }

    public boolean isCosmeticBackpack() {
        return hasMechanic("backpack_cosmetic");
    }

    private boolean hasMechanic(String name) {
        String expected = normalizeMechanic(name);
        return mechanics.keySet().stream()
                .map(OraxenItem::normalizeMechanic)
                .anyMatch(expected::equals);
    }

    private static String normalizeMechanic(String name) {
        return name.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "").replace("-", "");
    }
}
