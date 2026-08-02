package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

final class EquipmentPreconverter {
    private static final Set<String> WEAPON_SUFFIXES = Set.of(
            "_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE",
            "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "FISHING_ROD",
            "SPEAR");
    private final PackSource source;
    private final Path bedrock;
    private final String sourceNamespace;
    private final String outputNamespace;
    private final TextureAnimationConverter animationConverter;

    EquipmentPreconverter(PackSource source, Path bedrock, String sourceNamespace,
                          String outputNamespace,
                          TextureAnimationConverter animationConverter) {
        this.source = source;
        this.bedrock = bedrock;
        this.sourceNamespace = sourceNamespace;
        this.outputNamespace = outputNamespace;
        this.animationConverter = animationConverter;
    }

    int preconvert(OraxenItem item, String safeId, String bedrockId,
                   JavaModelConverter.ConvertedModel model, JsonObject definition,
                   JsonObject options, List<String> warnings) throws IOException {
        String slot = armorSlot(item);
        boolean weapon = isWeapon(item, model);
        if (slot == null && !weapon) {
            if (model == null || model.geometry() == null || model.materials().isEmpty()) return 0;
            if (item.isFurniture()) options.addProperty("creative_category", "construction");
            return writeCustomModelAttachable(item, safeId, bedrockId, model, warnings);
        }

        options.addProperty("creative_category", "equipment");
        JsonObject components = definition.has("components")
                ? definition.getAsJsonObject("components") : new JsonObject();
        definition.add("components", components);
        if (!components.has("minecraft:max_stack_size"))
            components.addProperty("minecraft:max_stack_size", 1);

        if (weapon) {
            options.addProperty("display_handheld", true);
            options.addProperty("creative_group", weaponGroup(item));
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
                || item.id().toLowerCase(Locale.ROOT).endsWith("_elytra")
                || Maps.get(item.components(), "glider") != null;
        if (slot.equals("head") && isCustomHat(item)
                && model != null && model.geometry() != null && !model.materials().isEmpty()) {
            return writeCustomModelAttachable(item, safeId, bedrockId, model, warnings);
        }
        if ((item.isHat() || item.isCosmeticBackpack())
                && model != null && model.geometry() != null && !model.materials().isEmpty())
            return writeCustomModelAttachable(item, safeId, bedrockId, model, warnings);
        String armorName = armorName(item, slot, elytra);
        EquipmentAsset equipment =
                findEquipmentAsset(item, slot, elytra, warnings);
        Path legacyTexture = equipment == null
                ? findArmorTexture(armorName, slot, elytra) : null;
        if (equipment == null && legacyTexture == null) {
            warnings.add("Equipment '" + item.id() + "': equipped texture was not found for " + armorName);
            return 0;
        }
        String targetName = armorName + (elytra ? "_elytra" : slot.equals("legs")
                ? "_armor_layer_2" : "_armor_layer_1");
        String texturePath = "textures/models/armor/" + sanitize(targetName);
        Path textureTarget = bedrock.resolve(texturePath + ".png");
        TextureAnimationConverter.Animation animation = equipment == null
                ? animationConverter.install(legacyTexture, textureTarget, warnings)
                : installEquipmentLayers(item, equipment, textureTarget, warnings);
        List<String> frameTextures = animation == null ? List.of()
                : animationConverter.writeFrameFiles(textureTarget, texturePath, animation);
        String renderController = animation == null
                ? "controller.render.armor"
                : writeAnimatedRenderController(safeId, animation);
        writeArmorAttachable(safeId, bedrockId, slot, texturePath, elytra,
                renderController, frameTextures);
        return 1;
    }

    private int writeCustomModelAttachable(OraxenItem item, String safeId, String bedrockId,
                                           JavaModelConverter.ConvertedModel model,
                                           List<String> warnings) throws IOException {
        String texturePath = "textures/entity/" + outputNamespace + "/" + safeId;
        Path textureTarget = bedrock.resolve(texturePath + ".png");
        JsonObject geometryData = model.geometry().deepCopy();
        List<JavaModelConverter.Material> materials =
                referencedMaterials(geometryData, model.materials());
        TextureAnimationConverter.Animation animation;
        if (materials.size() <= 1) {
            JavaModelConverter.Material material = materials.isEmpty()
                    ? model.materials().values().iterator().next() : materials.get(0);
            animation = animationConverter.install(
                    material.source(), textureTarget, warnings);
        } else {
            animation = installMaterialAtlas(
                    item, geometryData, materials, textureTarget, warnings);
        }
        String geometry = writeAttachableGeometry(safeId, model, geometryData);
        DisplaySetup display = writeDisplayAnimations(safeId, model.display());
        List<String> frameTextures = animation == null ? List.of()
                : animationConverter.writeFrameFiles(textureTarget, texturePath, animation);
        String renderController = animation == null
                ? "controller.render.item_default"
                : writeAnimatedRenderController(safeId, animation);
        writeHeldAttachable(safeId, bedrockId, geometry, texturePath,
                armorSlot(item) != null, renderController, frameTextures, display);
        return 1;
    }

    private String writeAttachableGeometry(
            String id, JavaModelConverter.ConvertedModel model,
            JsonObject geometry) throws IOException {
        JsonArray definitions = geometry.getAsJsonArray("minecraft:geometry");
        String identifier = model.identifier() + ".attachable";
        if (definitions != null && !definitions.isEmpty()) {
            JsonObject definition = definitions.get(0).getAsJsonObject();
            definition.getAsJsonObject("description").addProperty("identifier", identifier);
            JsonArray bones = definition.getAsJsonArray("bones");
            if (bones != null && !bones.isEmpty()) {
                bones.get(0).getAsJsonObject().addProperty("binding",
                        "q.item_slot_to_bone_name(context.item_slot)");
            }
        }
        JsonSupport.write(bedrock.resolve("models").resolve(outputNamespace)
                .resolve(id + ".attachable.geo.json"), geometry);
        return identifier;
    }

    private List<JavaModelConverter.Material> referencedMaterials(
            JsonObject geometry, Map<String, JavaModelConverter.Material> materials) {
        Set<String> names = new LinkedHashSet<>();
        walkFaces(geometry, face -> {
            if (face.has("material_instance")
                    && face.get("material_instance").isJsonPrimitive())
                names.add(face.get("material_instance").getAsString());
        });
        Map<String, JavaModelConverter.Material> byName = new LinkedHashMap<>();
        materials.values().forEach(material -> byName.putIfAbsent(material.name(), material));
        List<JavaModelConverter.Material> result = names.stream()
                .map(byName::get).filter(Objects::nonNull).toList();
        return result.isEmpty() ? byName.values().stream().toList() : result;
    }

    private TextureAnimationConverter.Animation installMaterialAtlas(
            OraxenItem item, JsonObject geometry,
            List<JavaModelConverter.Material> materials,
            Path target, List<String> warnings) throws IOException {
        Map<String, AtlasRegion> regions = new LinkedHashMap<>();
        List<AtlasInput> inputs = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();
        int usedWidth = 0;
        int usedHeight = 0;
        try {
            for (int index = 0; index < materials.size(); index++) {
                JavaModelConverter.Material material = materials.get(index);
                Path temporary = target.resolveSibling("." + target.getFileName()
                        + ".atlas-" + index + ".png");
                temporaryFiles.add(temporary);
                TextureAnimationConverter.Animation animation =
                        animationConverter.install(material.source(), temporary, warnings);
                BufferedImage source;
                try (InputStream input = Files.newInputStream(temporary)) {
                    source = ImageIO.read(input);
                }
                if (source == null)
                    throw new IOException("Unreadable model texture: " + material.source());
                int width = animation == null
                        ? Math.min(material.width(), source.getWidth())
                        : animation.frameWidth();
                int height = animation == null
                        ? Math.min(material.height(), source.getHeight())
                        : animation.frameHeight();
                if (width <= 0 || height <= 0)
                    throw new IOException("Invalid model texture size: " + material.source());
                regions.put(material.name(),
                        new AtlasRegion(usedWidth, 0, width, height));
                inputs.add(new AtlasInput(material, source, animation, width, height));
                usedWidth += width;
                usedHeight = Math.max(usedHeight, height);
            }
            if (usedWidth > 8192 || usedHeight > 8192)
                throw new IOException("Combined model texture atlas exceeds 8192 pixels for "
                        + item.id());
            int atlasWidth = nextPowerOfTwo(usedWidth);
            int atlasHeight = nextPowerOfTwo(usedHeight);
            AtlasTimeline timeline = buildAtlasTimeline(
                    item, inputs, Math.max(1, 8192 / atlasHeight), warnings);
            BufferedImage atlas = new BufferedImage(
                    atlasWidth, atlasHeight * timeline.uniqueFrames().size(),
                    BufferedImage.TYPE_INT_ARGB);
            for (int outputFrame = 0;
                 outputFrame < timeline.uniqueFrames().size(); outputFrame++) {
                List<Integer> sourceFrames = timeline.uniqueFrames().get(outputFrame);
                for (int index = 0; index < inputs.size(); index++) {
                    AtlasInput input = inputs.get(index);
                    AtlasRegion region = regions.get(input.material().name());
                    copyPixels(input.strip(), 0,
                            sourceFrames.get(index) * input.height(), atlas,
                            region.x(), outputFrame * atlasHeight + region.y(),
                            region.width(), region.height());
                }
            }
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target)) {
                if (!ImageIO.write(atlas, "png", output))
                    throw new IOException("No PNG writer is available for " + target);
            }
            remapGeometryToAtlas(geometry, regions, atlasWidth, atlasHeight);
            if (!timeline.animated()) return null;
            return new TextureAnimationConverter.Animation(
                    timeline.uniqueFrames().size(), atlasWidth, atlasHeight,
                    timeline.ticksPerFrame(), timeline.sequence(), false);
        } finally {
            for (Path temporary : temporaryFiles)
                Files.deleteIfExists(temporary);
        }
    }

    private AtlasTimeline buildAtlasTimeline(
            OraxenItem item, List<AtlasInput> inputs,
            int maxUniqueFrames, List<String> warnings) {
        List<TextureAnimationConverter.Animation> animations = inputs.stream()
                .map(AtlasInput::animation).filter(Objects::nonNull).toList();
        if (animations.isEmpty())
            return new AtlasTimeline(false, 1, List.of(0),
                    List.of(Collections.nCopies(inputs.size(), 0)));

        int baseTick = animations.stream()
                .mapToInt(TextureAnimationConverter.Animation::ticksPerFrame)
                .reduce(EquipmentPreconverter::gcd).orElse(1);
        long cycle = 1;
        boolean cycleSimplified = false;
        for (TextureAnimationConverter.Animation animation : animations) {
            long duration = (long) animation.ticksPerFrame()
                    * animation.frames().size();
            long next = lcmCapped(cycle, duration, Integer.MAX_VALUE);
            if (next == Integer.MAX_VALUE && cycle != Integer.MAX_VALUE)
                cycleSimplified = true;
            cycle = next;
        }
        if (cycleSimplified) {
            cycle = animations.stream().mapToLong(animation ->
                    (long) animation.ticksPerFrame() * animation.frames().size())
                    .max().orElse(baseTick);
            warnings.add("Animation cycles for multi-texture item '" + item.id()
                    + "' were synchronized to their longest source cycle");
        }
        long step = Math.max(baseTick, divideRoundUp(cycle, 4096));
        AtlasTimeline timeline = timelineAtStep(inputs, cycle, step);
        boolean simplified = step > baseTick;
        while (timeline.uniqueFrames().size() > maxUniqueFrames
                && step < Integer.MAX_VALUE) {
            step = Math.min(Integer.MAX_VALUE, step * 2);
            timeline = timelineAtStep(inputs, cycle, step);
            simplified = true;
        }
        if (timeline.uniqueFrames().size() > maxUniqueFrames) {
            warnings.add("Multi-texture animation for '" + item.id()
                    + "' exceeded the Bedrock texture height limit; "
                    + "only its first combined frame was retained");
            return new AtlasTimeline(false, 1, List.of(0),
                    List.of(timeline.uniqueFrames().get(0)));
        }
        if (simplified)
            warnings.add("Multi-texture animation timing for '" + item.id()
                    + "' was sampled to fit Bedrock texture limits");
        return timeline;
    }

    private AtlasTimeline timelineAtStep(
            List<AtlasInput> inputs, long cycle, long step) {
        int steps = (int) Math.max(1, Math.min(4096, divideRoundUp(cycle, step)));
        Map<List<Integer>, Integer> frameIndices = new LinkedHashMap<>();
        List<List<Integer>> unique = new ArrayList<>();
        List<Integer> sequence = new ArrayList<>(steps);
        for (int sample = 0; sample < steps; sample++) {
            long time = sample * step;
            List<Integer> tuple = new ArrayList<>(inputs.size());
            for (AtlasInput input : inputs)
                tuple.add(sourceFrameAt(input.animation(), time));
            List<Integer> key = List.copyOf(tuple);
            Integer outputFrame = frameIndices.get(key);
            if (outputFrame == null) {
                outputFrame = unique.size();
                frameIndices.put(key, outputFrame);
                unique.add(key);
            }
            sequence.add(outputFrame);
        }
        return new AtlasTimeline(true,
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, step)),
                List.copyOf(sequence), List.copyOf(unique));
    }

    private int sourceFrameAt(
            TextureAnimationConverter.Animation animation, long time) {
        if (animation == null || animation.frames().isEmpty()) return 0;
        long sequenceIndex = time / animation.ticksPerFrame();
        return animation.frames().get(
                (int) (sequenceIndex % animation.frames().size()));
    }

    private static int gcd(int left, int right) {
        while (right != 0) {
            int old = right;
            right = left % right;
            left = old;
        }
        return Math.max(1, Math.abs(left));
    }

    private long lcmCapped(long left, long right, long cap) {
        long divisor = gcdLong(left, right);
        long reduced = left / divisor;
        return reduced > cap / Math.max(1, right) ? cap : reduced * right;
    }

    private long gcdLong(long left, long right) {
        while (right != 0) {
            long old = right;
            right = left % right;
            left = old;
        }
        return Math.max(1, Math.abs(left));
    }

    private long divideRoundUp(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private void remapGeometryToAtlas(
            JsonObject geometry, Map<String, AtlasRegion> regions,
            int atlasWidth, int atlasHeight) {
        JsonArray definitions = geometry.getAsJsonArray("minecraft:geometry");
        if (definitions == null) return;
        for (var definitionValue : definitions) {
            if (!definitionValue.isJsonObject()) continue;
            JsonObject definition = definitionValue.getAsJsonObject();
            JsonObject description = definition.getAsJsonObject("description");
            if (description == null) continue;
            double oldWidth = description.has("texture_width")
                    ? description.get("texture_width").getAsDouble() : 16;
            double oldHeight = description.has("texture_height")
                    ? description.get("texture_height").getAsDouble() : 16;
            walkFaces(definition, face -> {
                if (!face.has("material_instance")) return;
                AtlasRegion region =
                        regions.get(face.get("material_instance").getAsString());
                if (region == null) return;
                JsonArray uv = face.getAsJsonArray("uv");
                JsonArray size = face.getAsJsonArray("uv_size");
                if (uv != null && uv.size() >= 2) {
                    double u = uv.get(0).getAsDouble() * region.width() / oldWidth;
                    double v = uv.get(1).getAsDouble() * region.height() / oldHeight;
                    uv.set(0, new com.google.gson.JsonPrimitive(region.x() + u));
                    uv.set(1, new com.google.gson.JsonPrimitive(region.y() + v));
                }
                if (size != null && size.size() >= 2) {
                    size.set(0, new com.google.gson.JsonPrimitive(
                            size.get(0).getAsDouble() * region.width() / oldWidth));
                    size.set(1, new com.google.gson.JsonPrimitive(
                            size.get(1).getAsDouble() * region.height() / oldHeight));
                }
                face.remove("material_instance");
            });
            description.addProperty("texture_width", atlasWidth);
            description.addProperty("texture_height", atlasHeight);
        }
    }

    private void walkFaces(
            com.google.gson.JsonElement value,
            java.util.function.Consumer<JsonObject> visitor) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("uv") && object.has("uv_size")) visitor.accept(object);
            object.entrySet().forEach(entry -> walkFaces(entry.getValue(), visitor));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> walkFaces(child, visitor));
        }
    }

    private int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value && result < 8192) result <<= 1;
        return result;
    }

    private void copyPixels(
            BufferedImage source, int sourceX, int sourceY,
            BufferedImage target, int targetX, int targetY,
            int width, int height) {
        int[] pixels = source.getRGB(sourceX, sourceY, width, height,
                null, 0, width);
        target.setRGB(targetX, targetY, width, height,
                pixels, 0, width);
    }

    private record AtlasRegion(int x, int y, int width, int height) {}
    private record AtlasInput(JavaModelConverter.Material material,
                              BufferedImage strip,
                              TextureAnimationConverter.Animation animation,
                              int width, int height) {}
    private record ResourceLocation(String namespace, String path) {}
    private record EquipmentLayer(Path source, String textureReference,
                                  boolean dyeable, Integer colorWhenUndyed,
                                  boolean usePlayerTexture) {}
    private record EquipmentAsset(ResourceLocation identifier, String layerType,
                                  List<EquipmentLayer> layers) {}
    private record EquipmentInput(EquipmentLayer layer, BufferedImage strip,
                                  TextureAnimationConverter.Animation animation,
                                  int width, int height) {}
    private record AtlasTimeline(boolean animated, int ticksPerFrame,
                                 List<Integer> sequence,
                                 List<List<Integer>> uniqueFrames) {}
    private record DisplayTransform(double[] rotation, double[] translation,
                                    double[] scale) {
        private static final DisplayTransform IDENTITY = new DisplayTransform(
                new double[]{0, 0, 0}, new double[]{0, 0, 0},
                new double[]{1, 1, 1});
    }
    private record DisplaySetup(JsonObject animations, JsonArray animate) {
        private static final DisplaySetup EMPTY =
                new DisplaySetup(new JsonObject(), new JsonArray());
    }

    private void writeArmorAttachable(String id, String identifier, String slot,
                                      String texture, boolean elytra,
                                      String renderController,
                                      List<String> frameTextures) throws IOException {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", elytra ? "elytra" : "armor");
        materials.addProperty("enchanted", elytra ? "elytra_glint" : "armor_enchanted");
        description.add("materials", materials);
        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        for (int frame = 0; frame < frameTextures.size(); frame++)
            textures.addProperty("frame_" + frame, frameTextures.get(frame));
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
        controllers.add(renderController);
        description.add("render_controllers", controllers);
        writeAttachable(id, description, elytra ? "1.10.0" : "1.8.0");
    }

    private void writeHeldAttachable(String id, String identifier, String geometryId,
                                     String texture, boolean hidesHelmetLayer,
                                     String renderController,
                                     List<String> frameTextures,
                                     DisplaySetup display) throws IOException {
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
        JsonObject scripts = new JsonObject();
        if (hidesHelmetLayer)
            scripts.addProperty("parent_setup",
                    "variable.helmet_layer_visible = 0.0;");
        if (!display.animations().isEmpty()) {
            description.add("animations", display.animations().deepCopy());
            scripts.add("animate", display.animate().deepCopy());
        }
        if (!scripts.isEmpty())
            description.add("scripts", scripts);
        JsonArray controllers = new JsonArray();
        controllers.add(renderController);
        description.add("render_controllers", controllers);
        writeAttachable(id, description, "1.10.0");
    }

    private DisplaySetup writeDisplayAnimations(
            String id, JsonObject display) throws IOException {
        JsonObject source = display == null ? new JsonObject() : display;
        DisplayTransform first = Optional.ofNullable(firstTransform(source,
                        "firstperson_righthand", "firstperson_lefthand"))
                .orElse(DisplayTransform.IDENTITY);
        DisplayTransform third = Optional.ofNullable(firstTransform(source,
                        "thirdperson_righthand", "thirdperson_lefthand"))
                .orElse(DisplayTransform.IDENTITY);
        DisplayTransform head = Optional.ofNullable(
                        readDisplayTransform(source, "head"))
                .orElse(DisplayTransform.IDENTITY);

        Map<String, DisplayTransform> transforms = new LinkedHashMap<>();
        transforms.put("first_person", new DisplayTransform(
                new double[]{-90 + first.rotation()[1],
                        -first.rotation()[2], first.rotation()[0]},
                new double[]{-first.translation()[1],
                        12.5 + first.translation()[2],
                        first.translation()[0]},
                first.scale()));
        // Bedrock's third-person item bone uses a different coordinate basis
        // and already has a +90 degree X rotation.
        transforms.put("third_person", new DisplayTransform(
                new double[]{90, -third.rotation()[2], -third.rotation()[1]},
                new double[]{-third.translation()[0],
                        12.5 + third.translation()[2],
                        -third.translation()[1]},
                third.scale()));
        transforms.put("head", new DisplayTransform(
                new double[]{-head.rotation()[0],
                        -head.rotation()[1], head.rotation()[2]},
                new double[]{-head.translation()[0] * 0.655,
                        20 + head.translation()[1] * 0.655,
                        head.translation()[2] * 0.655},
                new double[]{head.scale()[0] * 0.655,
                        head.scale()[1] * 0.655,
                        head.scale()[2] * 0.655}));

        JsonObject definitions = new JsonObject();
        JsonObject references = new JsonObject();
        JsonArray animate = new JsonArray();
        for (Map.Entry<String, DisplayTransform> entry : transforms.entrySet()) {
            String shortName = "display_" + entry.getKey();
            String identifier = "animation." + outputNamespace + "." + id
                    + ".display." + entry.getKey();
            JsonObject bone = new JsonObject();
            bone.add("rotation", vector(entry.getValue().rotation()));
            bone.add("position", vector(entry.getValue().translation()));
            bone.add("scale", vector(entry.getValue().scale()));
            JsonObject bones = new JsonObject();
            bones.add("root", bone);
            JsonObject animation = new JsonObject();
            animation.addProperty("loop", true);
            animation.add("bones", bones);
            definitions.add(identifier, animation);
            references.addProperty(shortName, identifier);
            if (entry.getKey().equals("first_person")) {
                JsonObject condition = new JsonObject();
                condition.addProperty(shortName,
                        "context.is_first_person == 1.0 && "
                                + "(context.item_slot == 'main_hand' || "
                                + "context.item_slot == 'off_hand')");
                animate.add(condition);
            } else if (entry.getKey().equals("third_person")) {
                JsonObject condition = new JsonObject();
                condition.addProperty(shortName,
                        "context.is_first_person == 0.0 && "
                                + "(context.item_slot == 'main_hand' || "
                                + "context.item_slot == 'off_hand')");
                animate.add(condition);
            } else {
                JsonObject condition = new JsonObject();
                condition.addProperty(shortName,
                        "context.is_first_person == 0.0 && "
                                + "context.item_slot == 'head'");
                animate.add(condition);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", definitions);
        JsonSupport.write(bedrock.resolve("animations").resolve(outputNamespace)
                .resolve(id + ".display.animation.json"), root);
        return new DisplaySetup(references, animate);
    }

    private DisplayTransform firstTransform(
            JsonObject display, String first, String second) {
        DisplayTransform transform = readDisplayTransform(display, first);
        return transform != null ? transform : readDisplayTransform(display, second);
    }

    private DisplayTransform readDisplayTransform(JsonObject display, String key) {
        if (!display.has(key) || !display.get(key).isJsonObject()) return null;
        JsonObject value = display.getAsJsonObject(key);
        double[] rotation = triple(value.getAsJsonArray("rotation"), 0);
        double[] translation = triple(value.getAsJsonArray("translation"), 0);
        double[] scale = triple(value.getAsJsonArray("scale"), 1);
        return new DisplayTransform(rotation, translation, scale);
    }

    private double[] triple(JsonArray values, double fallback) {
        double[] result = {fallback, fallback, fallback};
        if (values == null) return result;
        for (int index = 0; index < Math.min(3, values.size()); index++)
            if (values.get(index).isJsonPrimitive())
                result[index] = values.get(index).getAsDouble();
        return result;
    }

    private JsonArray vector(double[] values) {
        JsonArray result = new JsonArray();
        for (double value : values) result.add(value);
        return result;
    }

    private String writeAnimatedRenderController(String id,
                                                 TextureAnimationConverter.Animation animation)
            throws IOException {
        String identifier = "controller.render." + outputNamespace + "." + id + ".animated";
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
        JsonSupport.write(bedrock.resolve("render_controllers").resolve(outputNamespace)
                .resolve(id + ".render_controllers.json"), root);
        return identifier;
    }

    private void writeAttachable(String id, JsonObject description, String version) throws IOException {
        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", version);
        root.add("minecraft:attachable", attachable);
        JsonSupport.write(bedrock.resolve("attachables").resolve(outputNamespace)
                .resolve(id + ".attachable.json"), root);
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
        if (item.isHat()) return "head";
        if (item.isCosmeticBackpack()) return "chest";
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
        String model = firstNonBlank(
                Maps.string(equippable, "model"),
                Maps.string(equippable, "asset_id"));
        if (model != null) {
            int colon = model.indexOf(':');
            String path = colon >= 0 ? model.substring(colon + 1) : model;
            try {
                return Path.of(path).getFileName().toString();
            } catch (InvalidPathException ignored) {
                // The identifier will be rejected by the exact asset lookup
                // below; retaining a sanitized name keeps the legacy fallback
                // safe and gives the user a useful warning.
                return sanitize(path);
            }
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

    private EquipmentAsset findEquipmentAsset(
            OraxenItem item, String slot, boolean elytra,
            List<String> warnings) {
        Map<String, Object> equippable = Maps.section(item.components(), "equippable");
        String reference = firstNonBlank(
                Maps.string(equippable, "model"),
                Maps.string(equippable, "asset_id"));
        if (reference == null) return null;
        ResourceLocation asset = resourceLocation(reference, sourceNamespace);
        if (asset == null) {
            warnings.add("Equipment '" + item.id()
                    + "': invalid equipment asset identifier '" + reference + "'");
            return null;
        }
        Path definition = source.findAsset(
                asset.namespace(), "equipment/" + asset.path() + ".json");
        if (definition == null) {
            // Equipment definitions spent one snapshot cycle below models;
            // accepting that path costs nothing and keeps transitional packs
            // convertible.
            definition = source.findAsset(
                    asset.namespace(), "models/equipment/" + asset.path() + ".json");
        }
        if (definition == null) return null;

        String layerType = elytra ? "wings"
                : slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        try {
            JsonObject root = JsonSupport.readObject(definition);
            JsonObject layers = root.getAsJsonObject("layers");
            JsonArray entries = layers == null ? null : layers.getAsJsonArray(layerType);
            if (entries == null || entries.isEmpty()) {
                warnings.add("Equipment '" + item.id() + "': asset '" + reference
                        + "' has no " + layerType + " layer");
                return null;
            }
            List<EquipmentLayer> resolved = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                if (!entries.get(index).isJsonObject()) {
                    warnings.add("Equipment '" + item.id() + "': ignored invalid "
                            + layerType + " layer " + index + " in " + reference);
                    continue;
                }
                JsonObject layer = entries.get(index).getAsJsonObject();
                if (!layer.has("texture") || !layer.get("texture").isJsonPrimitive()) {
                    warnings.add("Equipment '" + item.id() + "': ignored "
                            + layerType + " layer without a texture in " + reference);
                    continue;
                }
                String textureReference = layer.get("texture").getAsString();
                Path texture = findEquipmentLayerTexture(
                        textureReference, asset.namespace(), layerType);
                if (texture == null) {
                    warnings.add("Equipment '" + item.id() + "': texture '"
                            + textureReference + "' for " + layerType + " was not found");
                    continue;
                }
                JsonObject dyeable = layer.has("dyeable")
                        && layer.get("dyeable").isJsonObject()
                        ? layer.getAsJsonObject("dyeable") : null;
                Integer tint = dyeable != null && dyeable.has("color_when_undyed")
                        && dyeable.get("color_when_undyed").isJsonPrimitive()
                        ? parseRgb(dyeable.get("color_when_undyed").getAsString())
                        : null;
                boolean usePlayerTexture = layer.has("use_player_texture")
                        && layer.get("use_player_texture").getAsBoolean();
                resolved.add(new EquipmentLayer(
                        texture, textureReference, dyeable != null, tint,
                        usePlayerTexture));
            }
            if (resolved.isEmpty()) return null;
            if (resolved.stream().anyMatch(EquipmentLayer::usePlayerTexture))
                warnings.add("Equipment '" + item.id()
                        + "': player-specific wing textures cannot be embedded in a "
                        + "server resource pack; the declared fallback texture is used");
            if (resolved.stream().anyMatch(EquipmentLayer::dyeable))
                warnings.add("Equipment '" + item.id()
                        + "': Java dyeable equipment layers use their configured "
                        + "default color; Geyser custom mappings do not expose the "
                        + "Bedrock dyeable component for per-stack tinting");
            return new EquipmentAsset(asset, layerType, List.copyOf(resolved));
        } catch (IOException | RuntimeException exception) {
            warnings.add("Equipment '" + item.id() + "': could not read asset '"
                    + reference + "': " + exception.getMessage());
            return null;
        }
    }

    private Path findEquipmentLayerTexture(
            String reference, String defaultNamespace, String layerType)
            throws IOException {
        ResourceLocation texture = resourceLocation(reference, defaultNamespace);
        if (texture == null) return null;
        String path = texture.path().replaceFirst("(?i)\\.png$", "")
                .replaceFirst("^textures/", "");
        String equipmentPrefix = "entity/equipment/" + layerType + "/";
        if (path.startsWith(equipmentPrefix))
            path = path.substring(equipmentPrefix.length());
        Path exact = source.findAsset(texture.namespace(),
                "textures/entity/equipment/" + layerType + "/" + path + ".png");
        if (exact != null) return exact;
        // Some generators emit a fully qualified texture path even though the
        // Java format expects a short id. Preserve those packs too.
        exact = source.findAsset(texture.namespace(), "textures/" + path + ".png");
        return exact != null ? exact
                : source.findTexture(reference, defaultNamespace);
    }

    private TextureAnimationConverter.Animation installEquipmentLayers(
            OraxenItem item, EquipmentAsset asset, Path target,
            List<String> warnings) throws IOException {
        List<EquipmentInput> inputs = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();
        try {
            for (int index = 0; index < asset.layers().size(); index++) {
                EquipmentLayer layer = asset.layers().get(index);
                Path temporary = target.resolveSibling("." + target.getFileName()
                        + ".equipment-" + index + ".png");
                temporaryFiles.add(temporary);
                TextureAnimationConverter.Animation animation =
                        animationConverter.install(layer.source(), temporary, warnings);
                BufferedImage strip;
                try (InputStream input = Files.newInputStream(temporary)) {
                    strip = ImageIO.read(input);
                }
                if (strip == null)
                    throw new IOException("Unreadable equipment texture: " + layer.source());
                int width = animation == null
                        ? strip.getWidth() : animation.frameWidth();
                int height = animation == null
                        ? strip.getHeight() : animation.frameHeight();
                inputs.add(new EquipmentInput(layer, strip, animation, width, height));
            }
            int width = inputs.stream().mapToInt(EquipmentInput::width).max().orElse(0);
            int height = inputs.stream().mapToInt(EquipmentInput::height).max().orElse(0);
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
                throw new IOException("Invalid combined equipment texture size for "
                        + item.id() + ": " + width + "x" + height);
            long aspectRatios = inputs.stream()
                    .map(input -> input.width() + ":" + input.height())
                    .distinct().count();
            if (aspectRatios > 1)
                warnings.add("Equipment '" + item.id()
                        + "': layers with different resolutions were scaled to "
                        + width + "x" + height);

            List<AtlasInput> timelineInputs = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                EquipmentInput input = inputs.get(index);
                JavaModelConverter.Material material = new JavaModelConverter.Material(
                        "equipment_" + index, input.layer().textureReference(),
                        input.layer().source(), input.width(), input.height());
                timelineInputs.add(new AtlasInput(
                        material, input.strip(), input.animation(),
                        input.width(), input.height()));
            }
            AtlasTimeline timeline = buildAtlasTimeline(
                    item, timelineInputs, Math.max(1, 8192 / height), warnings);
            BufferedImage output = new BufferedImage(
                    width, height * timeline.uniqueFrames().size(),
                    BufferedImage.TYPE_INT_ARGB);
            for (int frame = 0; frame < timeline.uniqueFrames().size(); frame++) {
                List<Integer> sourceFrames = timeline.uniqueFrames().get(frame);
                for (int layerIndex = 0; layerIndex < inputs.size(); layerIndex++) {
                    EquipmentInput input = inputs.get(layerIndex);
                    Integer tint = input.layer().dyeable()
                            ? input.layer().colorWhenUndyed() : 0xFFFFFF;
                    if (tint == null) continue;
                    compositeScaledFrame(input, sourceFrames.get(layerIndex),
                            output, frame * height, width, height, tint);
                }
            }
            Files.createDirectories(target.getParent());
            try (OutputStream stream = Files.newOutputStream(target)) {
                if (!ImageIO.write(output, "png", stream))
                    throw new IOException("No PNG writer is available for " + target);
            }
            if (!timeline.animated()) return null;
            return new TextureAnimationConverter.Animation(
                    timeline.uniqueFrames().size(), width, height,
                    timeline.ticksPerFrame(), timeline.sequence(), false);
        } finally {
            for (Path temporary : temporaryFiles)
                Files.deleteIfExists(temporary);
        }
    }

    private void compositeScaledFrame(
            EquipmentInput input, int sourceFrame,
            BufferedImage target, int targetY,
            int targetWidth, int targetHeight, int tint) {
        int sourceY = sourceFrame * input.height();
        int tintRed = tint >> 16 & 0xFF;
        int tintGreen = tint >> 8 & 0xFF;
        int tintBlue = tint & 0xFF;
        for (int y = 0; y < targetHeight; y++) {
            int sourcePixelY = sourceY
                    + Math.min(input.height() - 1,
                    (int) ((long) y * input.height() / targetHeight));
            for (int x = 0; x < targetWidth; x++) {
                int sourcePixelX = Math.min(input.width() - 1,
                        (int) ((long) x * input.width() / targetWidth));
                int sourcePixel = input.strip().getRGB(sourcePixelX, sourcePixelY);
                int alpha = sourcePixel >>> 24;
                if (alpha == 0) continue;
                int red = (sourcePixel >> 16 & 0xFF) * tintRed / 255;
                int green = (sourcePixel >> 8 & 0xFF) * tintGreen / 255;
                int blue = (sourcePixel & 0xFF) * tintBlue / 255;
                int tinted = alpha << 24 | red << 16 | green << 8 | blue;
                int old = target.getRGB(x, targetY + y);
                target.setRGB(x, targetY + y, alphaComposite(old, tinted));
            }
        }
    }

    private int alphaComposite(int destination, int sourcePixel) {
        int sourceAlpha = sourcePixel >>> 24;
        if (sourceAlpha == 255) return sourcePixel;
        int destinationAlpha = destination >>> 24;
        int inverse = 255 - sourceAlpha;
        int outputAlpha = sourceAlpha + destinationAlpha * inverse / 255;
        if (outputAlpha == 0) return 0;
        int sourceRed = sourcePixel >> 16 & 0xFF;
        int sourceGreen = sourcePixel >> 8 & 0xFF;
        int sourceBlue = sourcePixel & 0xFF;
        int destinationRed = destination >> 16 & 0xFF;
        int destinationGreen = destination >> 8 & 0xFF;
        int destinationBlue = destination & 0xFF;
        int denominator = outputAlpha * 255;
        int red = (sourceRed * sourceAlpha * 255
                + destinationRed * destinationAlpha * inverse) / denominator;
        int green = (sourceGreen * sourceAlpha * 255
                + destinationGreen * destinationAlpha * inverse) / denominator;
        int blue = (sourceBlue * sourceAlpha * 255
                + destinationBlue * destinationAlpha * inverse) / denominator;
        return outputAlpha << 24 | red << 16 | green << 8 | blue;
    }

    private ResourceLocation resourceLocation(String value, String defaultNamespace) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        int colon = normalized.indexOf(':');
        String namespace = colon >= 0
                ? normalized.substring(0, colon) : defaultNamespace;
        String path = colon >= 0 ? normalized.substring(colon + 1) : normalized;
        if (namespace == null || !namespace.matches("[a-z0-9_.-]+")
                || path.isBlank() || path.startsWith("/")
                || !path.matches("[a-z0-9_./-]+")
                || Arrays.asList(path.split("/")).contains(".."))
            return null;
        return new ResourceLocation(namespace, path);
    }

    private Integer parseRgb(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        try {
            if (normalized.startsWith("#"))
                return Integer.parseInt(normalized.substring(1), 16) & 0xFFFFFF;
            if (normalized.startsWith("0x") || normalized.startsWith("0X"))
                return Integer.parseInt(normalized.substring(2), 16) & 0xFFFFFF;
            return Integer.parseInt(normalized) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null || second.isBlank() ? null : second;
    }

    private Path findArmorTexture(String name, String slot, boolean elytra) throws IOException {
        List<String> candidates = elytra
                ? List.of(name + "_elytra", name)
                : slot.equals("legs")
                    ? List.of(name + "_armor_layer_2", name + "_layer_2")
                    : List.of(name + "_armor_layer_1", name + "_layer_1");
        for (String candidate : candidates) {
            Path texture = source.findTexture(candidate, sourceNamespace);
            if (texture != null) return texture;
        }
        return null;
    }

    private boolean isWeapon(
            OraxenItem item, JavaModelConverter.ConvertedModel model) {
        String material = item.material().toUpperCase(Locale.ROOT);
        if (WEAPON_SUFFIXES.stream().anyMatch(material::endsWith)) return true;
        if (model != null && model.handheld()) return true;

        String configuredParent = item.parentModel();
        if (configuredParent != null) {
            String parent = configuredParent.toLowerCase(Locale.ROOT)
                    .replace('\\', '/');
            if (parent.endsWith("item/handheld")
                    || parent.endsWith("item/handheld_rod"))
                return true;
        }
        String id = item.id().toLowerCase(Locale.ROOT);
        if (id.endsWith("sword") || id.endsWith("_axe")
                || id.endsWith("pickaxe") || id.endsWith("shovel")
                || id.endsWith("_hoe") || id.equals("bow")
                || id.endsWith("_bow") || id.endsWith("crossbow")
                || id.endsWith("trident") || id.endsWith("_mace")
                || id.endsWith("shield") || id.endsWith("fishing_rod")
                || id.endsWith("_spear"))
            return true;
        return Maps.get(item.components(), "kinetic_weapon") != null
                || Maps.get(item.components(), "piercing_weapon") != null;
    }

    private String weaponGroup(OraxenItem item) {
        String value = (item.material() + "_" + item.id())
                .toLowerCase(Locale.ROOT);
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
}
