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
        List<String> textures,
        String parentModel,
        Map<String, Object> components,
    Map<String, Object> mechanics
) {
    public boolean isBlock() {
        return hasMechanic("noteblock")
                || hasMechanic("stringblock")
                || hasMechanic("chorusblock")
                || hasMechanic("shapedblock")
                || hasMechanic("block");
    }

    public boolean isFurniture() {
        return hasMechanic("furniture");
    }

    private boolean hasMechanic(String name) {
        return mechanics.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }
}
