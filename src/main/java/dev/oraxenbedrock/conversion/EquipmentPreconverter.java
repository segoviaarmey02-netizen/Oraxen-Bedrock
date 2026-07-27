package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

final class EquipmentPreconverter {
    private static final Set<String> WEAPON_SUFFIXES = Set.of(
            "_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE",
            "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "FISHING_ROD",
            "SPEAR");
    private final PackSource source;
    private final Path bedrock;
    private final String namespace;
    private final TextureAnimationConverter animationConverter;

    EquipmentPreconverter(PackSource source, Path bedrock, String namespace,
                          TextureAnimationConverter animationConverter) {
        this.source = source;
        this.bedrock = bedrock;
        this.namespace = namespace;
        this.animationConverter = animationConverter;
    }

    int preconvert(OraxenItem item, String safeId, String bedrockId,
                   JavaModelConverter.ConvertedModel model, JsonObject definition,
                   JsonObject options, List<String> warnings) throws IOException {
        String slot = armorSlot(item);
        boolean weapon = isWeapon(item.material());
        if (slot == null && !weapon) {
            if (model == null || model.geometry() == null || model.materials().isEmpty()) return 0;
            if (item.isFurniture()) options.addProperty("creative_category", "construction");
            return writeCustomModelAttachable(item, safeId, bedrockId, model, warnings);
        }

        options.addProperty("creative_category", "equipment");
        JsonObject components = definition.has("components")
                ? definition.getAsJsonObject("components") : new JsonObject();
        definition.add("components", components);
        components.addProperty("minecraft:max_stack_size", 1);

        if (weapon) {
            options.addProperty("display_handheld", true);
            options.addProperty("creative_group", weaponGroup(item.material()));
            if (model != null && model.geometry() != null && !model.materials().isEmpty()) {
                return writeCustomModelAttachable(item, safeId, bedrockId, model, warnings);
            }
            return 0;
        }

        JsonObject equippable = components.has("minecraft:equippable")
                && components.get("minecraft:equippable").isJsonObject()
                ? components.getAsJsonObject("minecraft:equippable").deepCopy()
                : new JsonObject();
        // "model" is Oraxen's equipment-asset shortcut rather than a Java
        // equippable field. Geyser also cannot translate these two fields.
        equippable.remove("model");
        equippable.remove("camera_overlay");
        equippable.remove("swappable");
        equippable.addProperty("slot", slot);
        components.add("minecraft:equippable", equippable);
        int protection = protectionValue(item.material(), slot);
        if (protection > 0) options.addProperty("protection_value", protection);
        options.addProperty("creative_group", armorGroup(slot));

        boolean elytra = item.material().equalsIgnoreCase("ELYTRA")
                || item.id().toLowerCase(Locale.ROOT).endsWith("_elytra");
        if (slot.equals("head") && isCustomHat(item)
                && model != null && model.geometry() != null && !model.materials().isEmpty()) {
            return writeCustomModelAttachable(item, safeId, bedrockId, model, warnings);
        }
        String armorName = armorName(item, slot, elytra);
        Path texture = findArmorTexture(armorName, slot, elytra);
        if (texture == null) {
            warnings.add("Equipment '" + item.id() + "': equipped texture was not found for " + armorName);
            return 0;
        }
        String targetName = armorName + (elytra ? "_elytra" : slot.equals("legs")
                ? "_armor_layer_2" : "_armor_layer_1");
        String texturePath = "textures/models/armor/" + sanitize(targetName);
        copy(texture, bedrock.resolve(texturePath + ".png"));
        writeArmorAttachable(safeId, bedrockId, slot, texturePath, elytra);
        return 1;
    }

    private int writeCustomModelAttachable(OraxenItem item, String safeId, String bedrockId,
                                           JavaModelConverter.ConvertedModel model,
                                           List<String> warnings) throws IOException {
        JavaModelConverter.Material material = model.materials().values().iterator().next();
        if (model.materials().size() > 1) {
            warnings.add("3D item '" + item.id() + "' uses multiple Java textures; "
                    + "its Bedrock attachable uses the first texture as an atlas fallback");
        }
        String texturePath = "textures/entity/oraxen/" + safeId;
        Path textureTarget = bedrock.resolve(texturePath + ".png");
        TextureAnimationConverter.Animation animation = animationConverter.install(
                material.source(), textureTarget, warnings);
        String geometry = writeAttachableGeometry(safeId, model);
        List<String> frameTextures = animation == null ? List.of()
                : animationConverter.writeFrameFiles(textureTarget, texturePath, animation);
        String renderController = animation == null
                ? "controller.render.item_default"
                : writeAnimatedRenderController(safeId, animation);
        writeHeldAttachable(safeId, bedrockId, geometry, texturePath,
                armorSlot(item) != null, renderController, frameTextures);
        return 1;
    }

    private String writeAttachableGeometry(String id,
                                           JavaModelConverter.ConvertedModel model) throws IOException {
        JsonObject geometry = model.geometry().deepCopy();
        JsonArray definitions = geometry.getAsJsonArray("minecraft:geometry");
        String identifier = model.identifier() + ".attachable";
        if (definitions != null && !definitions.isEmpty()) {
            JsonObject definition = definitions.get(0).getAsJsonObject();
            definition.getAsJsonObject("description").addProperty("identifier", identifier);
            JsonArray bones = definition.getAsJsonArray("bones");
            if (bones != null && !bones.isEmpty()) {
                bones.get(0).getAsJsonObject().addProperty("binding",
                        "q.item_slot_to_bone_name(c.item_slot)");
            }
        }
        JsonSupport.write(bedrock.resolve("models/oraxen")
                .resolve(id + ".attachable.geo.json"), geometry);
        return identifier;
    }

    private void writeArmorAttachable(String id, String identifier, String slot,
                                      String texture, boolean elytra) throws IOException {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", elytra ? "elytra" : "armor");
        materials.addProperty("enchanted", elytra ? "elytra_glint" : "armor_enchanted");
        description.add("materials", materials);
        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        description.add("textures", textures);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", elytra ? "geometry.elytra" : armorGeometry(slot));
        description.add("geometry", geometry);
        JsonObject scripts = new JsonObject();
        scripts.addProperty("parent_setup", parentSetup(slot));
        if (elytra) {
            JsonObject animations = new JsonObject();
            animations.addProperty("default_controller", "controller.animation.elytra.default");
            animations.addProperty("gliding", "animation.elytra.gliding");
            description.add("animations", animations);
            JsonArray animate = new JsonArray();
            animate.add("default_controller");
            scripts.add("animate", animate);
        }
        description.add("scripts", scripts);
        JsonArray controllers = new JsonArray();
        controllers.add("controller.render.armor");
        description.add("render_controllers", controllers);
        writeAttachable(id, description, elytra ? "1.10.0" : "1.8.0");
    }

    private void writeHeldAttachable(String id, String identifier, String geometryId,
                                     String texture, boolean hidesHelmetLayer,
                                     String renderController,
                                     List<String> frameTextures) throws IOException {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);
        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        for (int frame = 0; frame < frameTextures.size(); frame++)
            textures.addProperty("frame_" + frame, frameTextures.get(frame));
        description.add("textures", textures);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", geometryId);
        description.add("geometry", geometry);
        if (hidesHelmetLayer) {
            JsonObject scripts = new JsonObject();
            scripts.addProperty("parent_setup", "variable.helmet_layer_visible = 0.0;");
            description.add("scripts", scripts);
        }
        JsonArray controllers = new JsonArray();
        controllers.add(renderController);
        description.add("render_controllers", controllers);
        writeAttachable(id, description, "1.10.0");
    }

    private String writeAnimatedRenderController(String id,
                                                 TextureAnimationConverter.Animation animation)
            throws IOException {
        String identifier = "controller.render." + namespace + "." + id + ".animated";
        JsonObject controller = new JsonObject();
        controller.addProperty("geometry", "Geometry.default");
        JsonObject arrays = new JsonObject();
        JsonObject textureArrays = new JsonObject();
        JsonArray frames = new JsonArray();
        for (int frame : animation.frames()) frames.add("Texture.frame_" + frame);
        textureArrays.add("Array.frames", frames);
        arrays.add("textures", textureArrays);
        controller.add("arrays", arrays);
        JsonArray materials = new JsonArray();
        JsonObject all = new JsonObject();
        all.addProperty("*", "Material.default");
        materials.add(all);
        controller.add("materials", materials);
        JsonArray textures = new JsonArray();
        textures.add(animation.textureExpression());
        textures.add("Texture.enchanted");
        controller.add("textures", textures);

        JsonObject controllers = new JsonObject();
        controllers.add(identifier, controller);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("render_controllers", controllers);
        JsonSupport.write(bedrock.resolve("render_controllers/oraxen")
                .resolve(id + ".render_controllers.json"), root);
        return identifier;
    }

    private void writeAttachable(String id, JsonObject description, String version) throws IOException {
        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", version);
        root.add("minecraft:attachable", attachable);
        JsonSupport.write(bedrock.resolve("attachables/oraxen/" + id + ".attachable.json"), root);
    }

    private String armorSlot(OraxenItem item) {
        Map<String, Object> equippable = Maps.section(item.components(), "equippable");
        String explicit = Maps.string(equippable, "slot");
        if (explicit != null) return normalizeSlot(explicit);
        String id = item.id().toLowerCase(Locale.ROOT);
        String material = item.material().toLowerCase(Locale.ROOT);
        if (id.endsWith("_helmet") || material.endsWith("_helmet")) return "head";
        if (id.endsWith("_chestplate") || material.endsWith("_chestplate")) return "chest";
        if (id.endsWith("_leggings") || material.endsWith("_leggings")) return "legs";
        if (id.endsWith("_boots") || material.endsWith("_boots")) return "feet";
        if (id.endsWith("_elytra") || material.equals("elytra")) return "chest";
        return null;
    }

    private boolean isCustomHat(OraxenItem item) {
        if (Maps.section(item.components(), "equippable").isEmpty()) return false;
        String material = item.material().toLowerCase(Locale.ROOT);
        String id = item.id().toLowerCase(Locale.ROOT);
        return !material.endsWith("_helmet") && !id.endsWith("_helmet");
    }

    private String armorName(OraxenItem item, String slot, boolean elytra) {
        Map<String, Object> equippable = Maps.section(item.components(), "equippable");
        String model = Maps.string(equippable, "model");
        if (model != null) {
            int colon = model.indexOf(':');
            String path = colon >= 0 ? model.substring(colon + 1) : model;
            return Path.of(path).getFileName().toString();
        }
        String id = item.id().toLowerCase(Locale.ROOT);
        String suffix = elytra ? "_elytra" : switch (slot) {
            case "head" -> "_helmet";
            case "chest" -> "_chestplate";
            case "legs" -> "_leggings";
            case "feet" -> "_boots";
            default -> "";
        };
        return id.endsWith(suffix) ? id.substring(0, id.length() - suffix.length()) : id;
    }

    private Path findArmorTexture(String name, String slot, boolean elytra) throws IOException {
        List<String> candidates = elytra
                ? List.of(name + "_elytra", name)
                : slot.equals("legs")
                    ? List.of(name + "_armor_layer_2", name + "_layer_2")
                    : List.of(name + "_armor_layer_1", name + "_layer_1");
        for (String candidate : candidates) {
            Path texture = source.findTexture(candidate, namespace);
            if (texture != null) return texture;
        }
        return null;
    }

    private boolean isWeapon(String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        return WEAPON_SUFFIXES.stream().anyMatch(upper::endsWith);
    }

    private String weaponGroup(String material) {
        String value = material.toLowerCase(Locale.ROOT);
        if (value.contains("pickaxe")) return "itemGroup.name.pickaxe";
        if (value.contains("bow")) return "itemGroup.name.bow";
        if (value.contains("axe")) return "itemGroup.name.axe";
        if (value.contains("shovel")) return "itemGroup.name.shovel";
        if (value.contains("hoe")) return "itemGroup.name.hoe";
        return "itemGroup.name.sword";
    }

    private String armorGroup(String slot) {
        return switch (slot) {
            case "head" -> "itemGroup.name.helmet";
            case "chest" -> "itemGroup.name.chestplate";
            case "legs" -> "itemGroup.name.leggings";
            case "feet" -> "itemGroup.name.boots";
            default -> "itemGroup.name.armor";
        };
    }

    private int protectionValue(String material, String slot) {
        String value = material.toUpperCase(Locale.ROOT);
        int tier = value.startsWith("LEATHER") ? 0 : value.startsWith("CHAINMAIL") ? 1
                : value.startsWith("IRON") ? 2 : value.startsWith("DIAMOND") ? 3
                : value.startsWith("GOLDEN") ? 4 : value.startsWith("NETHERITE") ? 5 : -1;
        int[][] values = {{1, 2, 2, 3, 2, 3}, {3, 5, 6, 8, 5, 8},
                {2, 4, 5, 6, 3, 6}, {1, 1, 2, 3, 1, 3}};
        int row = switch (slot) {
            case "head" -> 0;
            case "chest" -> 1;
            case "legs" -> 2;
            case "feet" -> 3;
            default -> -1;
        };
        return tier < 0 || row < 0 ? 0 : values[row][tier];
    }

    private String armorGeometry(String slot) {
        return switch (slot) {
            case "head" -> "geometry.humanoid.armor.helmet";
            case "chest" -> "geometry.humanoid.armor.chestplate";
            case "legs" -> "geometry.humanoid.armor.leggings";
            case "feet" -> "geometry.humanoid.armor.boots";
            default -> "geometry.humanoid";
        };
    }

    private String parentSetup(String slot) {
        return switch (slot) {
            case "head" -> "variable.helmet_layer_visible = 0.0;";
            case "chest" -> "variable.chest_layer_visible = 0.0;";
            case "legs" -> "variable.leg_layer_visible = 0.0;";
            case "feet" -> "variable.boot_layer_visible = 0.0;";
            default -> "";
        };
    }

    private String normalizeSlot(String slot) {
        return switch (slot.toUpperCase(Locale.ROOT)) {
            case "HEAD", "HELMET" -> "head";
            case "CHEST", "CHESTPLATE", "BODY" -> "chest";
            case "LEGS", "LEGGINGS" -> "legs";
            case "FEET", "BOOTS" -> "feet";
            default -> slot.toLowerCase(Locale.ROOT);
        };
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static void copy(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
}
