package dev.oraxenbedrock.conversion;

import com.google.gson.*;
import dev.oraxenbedrock.io.PackSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts Minecraft Java element models to Bedrock cube geometry.
 * Java rotations are limited to one axis per element, which maps cleanly to a
 * Bedrock cube rotation. Parent models and texture aliases are resolved first.
 */
final class JavaModelConverter {
    record Material(String name, String textureReference, Path source, int width, int height) {}
    record ConvertedModel(String identifier, JsonObject geometry,
                          Map<String, Material> materials, JsonObject display,
                          boolean generatedSprite, boolean handheld,
                          List<String> warnings) {}

    private static final Set<String> BUILTIN_PARENTS = Set.of(
            "minecraft:item/generated", "minecraft:item/handheld",
            "minecraft:item/handheld_rod", "minecraft:builtin/entity");
    private final PackSource pack;
    private final String sourceNamespace;
    private final String outputNamespace;

    JavaModelConverter(PackSource pack, String sourceNamespace, String outputNamespace) {
        this.pack = pack;
        this.sourceNamespace = sourceNamespace;
        this.outputNamespace = outputNamespace;
    }

    ConvertedModel convert(String requestedModel, String itemId,
                           boolean forceGeneratedSprite) throws IOException {
        String model = requestedModel == null || requestedModel.isBlank()
                ? findGeneratedModel(itemId) : normalize(requestedModel);
        if (model != null && !Files.isRegularFile(modelPath(model))) {
            String generated = findGeneratedModel(itemId);
            if (generated != null) model = generated;
        }
        if (model == null) return null;

        List<String> warnings = new ArrayList<>();
        Resolved resolved = resolve(model, new LinkedHashSet<>(), warnings);
        if (resolved == null) return null;
        Map<String, Material> materials = resolveMaterials(resolved.textures, warnings);
        JsonArray modelElements = resolved.elements == null
                ? new JsonArray() : resolved.elements.deepCopy();
        boolean generatedSprite = modelElements.isEmpty();
        if (generatedSprite) {
            List<String> spriteMaterials;
            if (forceGeneratedSprite) {
                spriteMaterials = materials.keySet().stream()
                        .sorted(Comparator.comparingInt(this::layerIndex)
                                .thenComparing(Comparator.naturalOrder()))
                        .toList();
            } else {
                spriteMaterials = materials.entrySet().stream()
                        .filter(entry -> hasAnimation(entry.getValue().source()))
                        .map(Map.Entry::getKey).limit(1).toList();
            }
            if (spriteMaterials.isEmpty())
                return new ConvertedModel(geometryId(itemId), null, materials,
                        resolved.display.deepCopy(), true,
                        resolved.handheld, warnings);
            for (int index = 0; index < spriteMaterials.size(); index++)
                modelElements.add(spriteElement(
                        spriteMaterials.get(index), index, spriteMaterials.size()));
        }

        int textureWidth = materials.values().stream().mapToInt(Material::width).max().orElse(16);
        int textureHeight = materials.values().stream().mapToInt(Material::height).max().orElse(16);
        JsonArray cubes = new JsonArray();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (JsonElement elementValue : modelElements) {
            if (!elementValue.isJsonObject()) continue;
            JsonObject element = elementValue.getAsJsonObject();
            JsonArray from = array(element.get("from"));
            JsonArray to = array(element.get("to"));
            if (!validVector(from) || !validVector(to)) {
                warnings.add("Skipped element without valid from/to in " + model);
                continue;
            }
            double fx = number(from, 0), fy = number(from, 1), fz = number(from, 2);
            double tx = number(to, 0), ty = number(to, 1), tz = number(to, 2);
            double sizeX = Math.abs(tx - fx);
            double sizeY = Math.abs(ty - fy);
            double sizeZ = Math.abs(tz - fz);
            double sourceX = Math.min(fx, tx) - 8;
            double originX = -(sourceX + sizeX);
            double originY = Math.min(fy, ty);
            double originZ = Math.min(fz, tz) - 8;
            JsonObject cube = new JsonObject();
            cube.add("origin", vector(originX, originY, originZ));
            cube.add("size", vector(sizeX, sizeY, sizeZ));
            JsonElement rotation = element.get("rotation");
            if (rotation != null && rotation.isJsonObject())
                addRotation(cube, rotation.getAsJsonObject(), warnings);
            else if (rotation != null && !rotation.isJsonNull())
                warnings.add("Ignored malformed element rotation in " + model);
            JsonObject faces = object(element.get("faces"));
            if (faces != null)
                cube.add("uv", convertFaces(
                        faces, resolved.textures, materials, from, to,
                        textureWidth, textureHeight, warnings, model));
            cubes.add(cube);
            minX = Math.min(minX, originX);
            minY = Math.min(minY, originY);
            minZ = Math.min(minZ, originZ);
            maxX = Math.max(maxX, originX + sizeX);
            maxY = Math.max(maxY, originY + sizeY);
            maxZ = Math.max(maxZ, originZ + sizeZ);
        }
        if (cubes.isEmpty())
            return new ConvertedModel(geometryId(itemId), null, materials,
                    resolved.display.deepCopy(), true,
                    resolved.handheld, warnings);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometryId(itemId));
        description.addProperty("texture_width", textureWidth);
        description.addProperty("texture_height", textureHeight);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        description.add("visible_bounds_offset", vector(0, 0.75, 0));
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "root");
        bone.add("pivot", vector(
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                (minZ + maxZ) / 2));
        bone.add("cubes", cubes);
        JsonArray bones = new JsonArray();
        bones.add(bone);
        JsonObject definition = new JsonObject();
        definition.add("description", description);
        definition.add("bones", bones);
        JsonArray definitions = new JsonArray();
        definitions.add(definition);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("format_version", "1.12.0");
        geometry.add("minecraft:geometry", definitions);
        return new ConvertedModel(geometryId(itemId), geometry, materials,
                resolved.display.deepCopy(),
                generatedSprite, resolved.handheld, warnings);
    }

    private Resolved resolve(String model, Set<String> chain, List<String> warnings) throws IOException {
        model = normalize(model);
        if (!chain.add(model)) {
            warnings.add("Circular Java model parent chain: " + String.join(" -> ", chain));
            return null;
        }
        Path path = modelPath(model);
        if (!Files.isRegularFile(path)) {
            Resolved builtin = builtinModel(model);
            if (builtin != null) return builtin;
            if (!BUILTIN_PARENTS.contains(model))
                warnings.add("Java model not found: " + model);
            return new Resolved(new LinkedHashMap<>(), new JsonArray(),
                    new JsonObject(), false);
        }
        JsonObject json = JsonSupport.readObject(path);
        Resolved parent = null;
        JsonElement parentValue = json.get("parent");
        if (parentValue != null && !parentValue.isJsonNull()) {
            String parentReference = string(parentValue);
            if (parentReference == null || parentReference.isBlank()) {
                warnings.add("Ignored malformed Java model parent in " + model);
            } else {
                String parentName = normalizeParent(parentReference);
                if (!BUILTIN_PARENTS.contains(parentName))
                    parent = resolve(parentName, chain, warnings);
                else parent = builtinModel(parentName);
            }
        }
        Map<String, String> textures = parent == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(parent.textures);
        JsonElement textureValue = json.get("textures");
        JsonObject textureJson = object(textureValue);
        if (textureJson != null) {
            for (Map.Entry<String, JsonElement> entry : textureJson.entrySet()) {
                String reference = string(entry.getValue());
                if (reference == null || reference.isBlank()) {
                    warnings.add("Ignored malformed texture '" + entry.getKey()
                            + "' in " + model);
                    continue;
                }
                textures.put(entry.getKey(), reference);
            }
        } else if (textureValue != null && !textureValue.isJsonNull()) {
            warnings.add("Ignored non-object textures section in " + model);
        }
        JsonElement elementValue = json.get("elements");
        JsonArray ownElements = array(elementValue);
        JsonArray elements = ownElements != null ? ownElements
                : parent == null ? new JsonArray() : parent.elements.deepCopy();
        if (elementValue != null && !elementValue.isJsonNull()
                && ownElements == null)
            warnings.add("Ignored non-array elements section in " + model);
        JsonObject display = parent == null
                ? new JsonObject() : parent.display.deepCopy();
        JsonElement displayValue = json.get("display");
        JsonObject ownDisplay = object(displayValue);
        if (ownDisplay != null)
            ownDisplay.entrySet().forEach(entry ->
                    display.add(entry.getKey(), entry.getValue().deepCopy()));
        else if (displayValue != null && !displayValue.isJsonNull())
            warnings.add("Ignored non-object display section in " + model);
        chain.remove(model);
        return new Resolved(textures, elements, display,
                parent != null && parent.handheld);
    }

    private Map<String, Material> resolveMaterials(Map<String, String> textures,
                                                    List<String> warnings) throws IOException {
        Map<String, Material> result = new LinkedHashMap<>();
        Set<String> materialNames = new HashSet<>();
        for (String key : textures.keySet()) {
            String reference = resolveAlias(key, textures, new HashSet<>());
            if (reference == null || reference.startsWith("#")) continue;
            // Unqualified texture references inside Oraxen models are relative
            // to the Oraxen asset namespace, not to minecraft.
            Path texture = pack.findTexture(reference, sourceNamespace);
            if (texture == null) {
                warnings.add("Texture not found: " + reference);
                continue;
            }
            int width = 16, height = 16;
            try (InputStream input = Files.newInputStream(texture)) {
                BufferedImage image = ImageIO.read(input);
                if (image == null) {
                    warnings.add("Texture is not a readable PNG: " + reference);
                    continue;
                }
                width = image.getWidth();
                height = image.getHeight();
                int[] frameSize = animationFrameSize(texture, width, height);
                width = frameSize[0];
                height = frameSize[1];
            }
            String baseName = materialName(key);
            String name = baseName;
            int suffix = 2;
            while (!materialNames.add(name)) name = baseName + "_" + suffix++;
            if (!name.equals(baseName))
                warnings.add("Material name collision for texture key '" + key
                        + "'; renamed to " + name);
            result.put(key, new Material(name, reference, texture, width, height));
        }
        return result;
    }

    private JsonObject convertFaces(JsonObject faces, Map<String, String> textures,
                                    Map<String, Material> materials,
                                    JsonArray from, JsonArray to,
                                    int textureWidth, int textureHeight,
                                    List<String> warnings, String model) {
        JsonObject result = new JsonObject();
        double scaleX = textureWidth / 16.0;
        double scaleY = textureHeight / 16.0;
        for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
            if (!isFace(entry.getKey())) {
                warnings.add("Ignored unknown face '" + entry.getKey()
                        + "' in " + model);
                continue;
            }
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject javaFace = entry.getValue().getAsJsonObject();
            String texture = javaFace.has("texture")
                    ? string(javaFace.get("texture")) : "#all";
            if (texture == null || texture.isBlank()) {
                warnings.add("Skipped " + entry.getKey()
                        + " face with malformed texture in " + model);
                continue;
            }
            String key = texture.startsWith("#") ? texture.substring(1) : findTextureKey(texture, textures);
            Material material = materials.get(key);
            if (material == null) {
                warnings.add("Skipped " + entry.getKey() + " face with unresolved texture '"
                        + texture + "' in " + model);
                continue;
            }
            JsonArray uv = array(javaFace.get("uv"));
            if (!validUv(uv)) {
                if (javaFace.has("uv"))
                    warnings.add("Used default UV for malformed " + entry.getKey()
                            + " face in " + model);
                uv = defaultUv(entry.getKey(), from, to);
            }
            JsonObject bedrockFace = new JsonObject();
            boolean horizontal = entry.getKey().equals("up")
                    || entry.getKey().equals("down");
            if (horizontal) {
                // Bedrock reads top/bottom UVs from the opposite corner. A
                // negative extent preserves Java's face orientation instead
                // of mirroring furniture seats and table tops.
                bedrockFace.add("uv", vector(
                        number(uv, 2) * scaleX,
                        number(uv, 3) * scaleY));
                bedrockFace.add("uv_size", vector(
                        (number(uv, 0) - number(uv, 2)) * scaleX,
                        (number(uv, 1) - number(uv, 3)) * scaleY));
            } else {
                bedrockFace.add("uv", vector(
                        number(uv, 0) * scaleX,
                        number(uv, 1) * scaleY));
                bedrockFace.add("uv_size", vector(
                        (number(uv, 2) - number(uv, 0)) * scaleX,
                        (number(uv, 3) - number(uv, 1)) * scaleY));
            }
            bedrockFace.addProperty("material_instance", material.name());
            JsonElement rotation = javaFace.get("rotation");
            if (rotation != null && isNumber(rotation))
                bedrockFace.addProperty("uv_rotation", rotation.getAsInt());
            else if (rotation != null && !rotation.isJsonNull())
                warnings.add("Ignored malformed UV rotation on " + entry.getKey()
                        + " face in " + model);
            result.add(mapFace(entry.getKey()), bedrockFace);
        }
        return result;
    }

    private void addRotation(JsonObject cube, JsonObject rotation, List<String> warnings) {
        JsonArray origin = array(rotation.get("origin"));
        String configuredAxis = string(rotation.get("axis"));
        String axis = configuredAxis == null ? "y"
                : configuredAxis.toLowerCase(Locale.ROOT);
        if (rotation.has("axis") && configuredAxis == null)
            warnings.add("Ignored malformed Java model rotation axis");
        JsonElement angleValue = rotation.get("angle");
        boolean validAngle = isNumber(angleValue)
                && Double.isFinite(angleValue.getAsDouble());
        double angle = validAngle ? angleValue.getAsDouble() : 0;
        if (angleValue != null && !angleValue.isJsonNull()
                && !validAngle)
            warnings.add("Ignored malformed Java model rotation angle");
        if (!validVector(origin)) origin = vector(8, 8, 8);
        cube.add("pivot", vector(
                -(number(origin, 0) - 8),
                number(origin, 1),
                number(origin, 2) - 8));
        double x = 0, y = 0, z = 0;
        switch (axis) {
            case "x" -> x = -angle;
            case "y" -> y = angle;
            case "z" -> z = angle;
            default -> warnings.add("Unknown Java model rotation axis: " + axis);
        }
        cube.add("rotation", vector(x, y, z));
        JsonElement rescale = rotation.get("rescale");
        if (rescale != null && rescale.isJsonPrimitive()
                && rescale.getAsJsonPrimitive().isBoolean()
                && rescale.getAsBoolean())
            rescaleCube(cube, axis, angle, warnings);
        else if (rescale != null && !rescale.isJsonNull()
                && (!rescale.isJsonPrimitive()
                || !rescale.getAsJsonPrimitive().isBoolean()))
            warnings.add("Ignored malformed Java model rescale flag");
    }

    private void rescaleCube(
            JsonObject cube, String axis, double angle,
            List<String> warnings) {
        double cosine = Math.cos(Math.toRadians(Math.abs(angle)));
        if (cosine < 1.0e-6) {
            warnings.add("Java model rescale could not be represented for "
                    + angle + " degree rotation");
            return;
        }
        double factor = 1.0 / cosine;
        JsonArray origin = cube.getAsJsonArray("origin");
        JsonArray size = cube.getAsJsonArray("size");
        JsonArray pivot = cube.getAsJsonArray("pivot");
        if (!validVector(origin) || !validVector(size) || !validVector(pivot)) return;
        for (int index = 0; index < 3; index++) {
            boolean rotationAxis = switch (axis) {
                case "x" -> index == 0;
                case "y" -> index == 1;
                case "z" -> index == 2;
                default -> true;
            };
            if (rotationAxis) continue;
            double oldOrigin = number(origin, index);
            double center = number(pivot, index);
            origin.set(index, new JsonPrimitive(
                    center + (oldOrigin - center) * factor));
            size.set(index, new JsonPrimitive(number(size, index) * factor));
        }
    }

    private JsonArray defaultUv(String face, JsonArray from, JsonArray to) {
        double fx = number(from, 0), fy = number(from, 1), fz = number(from, 2);
        double tx = number(to, 0), ty = number(to, 1), tz = number(to, 2);
        return switch (face) {
            case "down" -> vector(fx, 16 - tz, tx, 16 - fz);
            case "up" -> vector(fx, fz, tx, tz);
            case "north" -> vector(16 - tx, 16 - ty, 16 - fx, 16 - fy);
            case "south" -> vector(fx, 16 - ty, tx, 16 - fy);
            case "west" -> vector(fz, 16 - ty, tz, 16 - fy);
            case "east" -> vector(16 - tz, 16 - ty, 16 - fz, 16 - fy);
            default -> vector(0, 0, 16, 16);
        };
    }

    private String findGeneratedModel(String id) {
        for (String candidate : List.of(sourceNamespace + ":item/" + id,
                sourceNamespace + ":block/" + id, sourceNamespace + ":" + id)) {
            if (Files.isRegularFile(modelPath(candidate))) return candidate;
        }
        return null;
    }

    private Resolved builtinModel(String model) {
        String path = model.substring(model.indexOf(':') + 1);
        JsonArray elements = new JsonArray();
        switch (path) {
            case "item/generated", "item/handheld", "item/handheld_rod",
                 "builtin/entity" -> {
                // These parents define item rendering semantics rather than
                // cube geometry. Their textures and display transforms are
                // supplied by the child model.
            }
            case "block/cube_all", "block/cube_mirrored_all", "block/leaves" ->
                    elements.add(cube(Map.of(
                    "down", "#all", "up", "#all", "north", "#all",
                    "south", "#all", "west", "#all", "east", "#all")));
            case "block/cube" -> elements.add(cube(Map.of(
                    "down", "#down", "up", "#up", "north", "#north",
                    "south", "#south", "west", "#west", "east", "#east")));
            case "block/cube_column", "block/cube_column_horizontal" -> elements.add(cube(Map.of(
                    "down", "#end", "up", "#end", "north", "#side",
                    "south", "#side", "west", "#side", "east", "#side")));
            case "block/cube_bottom_top" -> elements.add(cube(Map.of(
                    "down", "#bottom", "up", "#top", "north", "#side",
                    "south", "#side", "west", "#side", "east", "#side")));
            case "block/orientable", "block/orientable_with_bottom" -> elements.add(cube(Map.of(
                    "down", "#bottom", "up", "#top", "north", "#front",
                    "south", "#side", "west", "#side", "east", "#side")));
            case "block/orientable_vertical" -> elements.add(cube(Map.of(
                    "down", "#side", "up", "#front", "north", "#side",
                    "south", "#side", "west", "#side", "east", "#side")));
            case "block/cross", "block/tinted_cross" -> {
                elements.add(crossElement(45));
                elements.add(crossElement(-45));
            }
            case "block/slab" -> elements.add(box(0, 0, 0, 16, 8, 16,
                    topBottomSide()));
            case "block/slab_top" -> elements.add(box(0, 8, 0, 16, 16, 16,
                    topBottomSide()));
            case "block/stairs" -> {
                elements.add(box(0, 0, 0, 16, 8, 16, topBottomSide()));
                elements.add(box(0, 8, 8, 16, 16, 16, topBottomSide()));
            }
            case "block/inner_stairs" -> {
                elements.add(box(0, 0, 0, 16, 8, 16, topBottomSide()));
                elements.add(box(0, 8, 8, 16, 16, 16, topBottomSide()));
                elements.add(box(0, 8, 0, 8, 16, 8, topBottomSide()));
            }
            case "block/outer_stairs" -> {
                elements.add(box(0, 0, 0, 16, 8, 16, topBottomSide()));
                elements.add(box(0, 8, 8, 8, 16, 16, topBottomSide()));
            }
            case "block/trapdoor_bottom", "block/template_trapdoor_bottom",
                 "block/template_orientable_trapdoor_bottom" -> elements.add(box(
                    0, 0, 0, 16, 3, 16, allFaces("#texture")));
            case "block/trapdoor_top", "block/template_trapdoor_top",
                 "block/template_orientable_trapdoor_top" -> elements.add(box(
                    0, 13, 0, 16, 16, 16, allFaces("#texture")));
            case "block/trapdoor_open", "block/template_trapdoor_open",
                 "block/template_orientable_trapdoor_open" -> elements.add(box(
                    0, 0, 13, 16, 16, 16, allFaces("#texture")));
            case "block/door_bottom_left", "block/door_bottom_right" ->
                    elements.add(box(0, 0, 13, 16, 16, 16, allFaces("#bottom")));
            case "block/door_top_left", "block/door_top_right" ->
                    elements.add(box(0, 0, 13, 16, 16, 16, allFaces("#top")));
            default -> {
                return null;
            }
        }
        boolean handheld = path.equals("item/handheld")
                || path.equals("item/handheld_rod");
        return new Resolved(new LinkedHashMap<>(), elements,
                new JsonObject(), handheld);
    }

    private JsonObject cube(Map<String, String> textures) {
        return box(0, 0, 0, 16, 16, 16, textures);
    }

    private JsonObject box(double fromX, double fromY, double fromZ,
                           double toX, double toY, double toZ,
                           Map<String, String> textures) {
        JsonObject element = new JsonObject();
        element.add("from", vector(fromX, fromY, fromZ));
        element.add("to", vector(toX, toY, toZ));
        JsonObject faces = new JsonObject();
        textures.forEach((face, texture) -> {
            JsonObject data = new JsonObject();
            data.addProperty("texture", texture);
            faces.add(face, data);
        });
        element.add("faces", faces);
        return element;
    }

    private Map<String, String> topBottomSide() {
        return Map.of("down", "#bottom", "up", "#top", "north", "#side",
                "south", "#side", "west", "#side", "east", "#side");
    }

    private Map<String, String> allFaces(String texture) {
        return Map.of("down", texture, "up", texture, "north", texture,
                "south", texture, "west", texture, "east", texture);
    }

    private JsonObject crossElement(double angle) {
        JsonObject element = new JsonObject();
        element.add("from", vector(0, 0, 7.99));
        element.add("to", vector(16, 16, 8.01));
        JsonObject rotation = new JsonObject();
        rotation.add("origin", vector(8, 8, 8));
        rotation.addProperty("axis", "y");
        rotation.addProperty("angle", angle);
        element.add("rotation", rotation);
        JsonObject faces = new JsonObject();
        for (String face : List.of("north", "south")) {
            JsonObject data = new JsonObject();
            data.addProperty("texture", "#cross");
            data.add("uv", vector(0, 0, 16, 16));
            faces.add(face, data);
        }
        element.add("faces", faces);
        return element;
    }

    private JsonObject spriteElement(String texture, int layer, int layerCount) {
        double depth = Math.min(0.45, Math.max(0, layerCount - 1) * 0.02);
        double z = 8.0 - depth / 2 + (layerCount <= 1
                ? 0 : (double) layer * depth / (layerCount - 1));
        JsonObject element = new JsonObject();
        element.add("from", vector(0, 0, z - 0.01));
        element.add("to", vector(16, 16, z + 0.01));
        JsonObject faces = new JsonObject();
        for (String face : List.of("north", "south")) {
            JsonObject data = new JsonObject();
            data.addProperty("texture", "#" + texture);
            data.add("uv", vector(0, 0, 16, 16));
            faces.add(face, data);
        }
        element.add("faces", faces);
        return element;
    }

    private int layerIndex(String name) {
        if (name != null && name.matches("layer\\d+")) {
            try {
                return Integer.parseInt(name.substring("layer".length()));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE - 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    private boolean hasAnimation(Path texture) {
        return Files.isRegularFile(texture.resolveSibling(texture.getFileName() + ".mcmeta"));
    }

    private int[] animationFrameSize(Path texture, int imageWidth, int imageHeight) {
        Path metadata = texture.resolveSibling(texture.getFileName() + ".mcmeta");
        if (!Files.isRegularFile(metadata)) return new int[]{imageWidth, imageHeight};
        try {
            JsonObject animation = object(
                    JsonSupport.readObject(metadata).get("animation"));
            if (animation == null) return new int[]{imageWidth, imageHeight};
            int width = isNumber(animation.get("width"))
                    ? animation.get("width").getAsInt() : imageWidth;
            int height = isNumber(animation.get("height"))
                    ? animation.get("height").getAsInt() : width;
            if (width > 0 && height > 0) return new int[]{width, height};
        } catch (IOException | RuntimeException ignored) {
            // TextureAnimationConverter reports malformed metadata later.
        }
        return new int[]{imageWidth, imageHeight};
    }

    private Path modelPath(String model) {
        int colon = model.indexOf(':');
        if (colon <= 0 || colon == model.length() - 1)
            return pack.root().resolve(".missing-model");
        String namespace = model.substring(0, colon);
        String relative = "models/" + model.substring(colon + 1) + ".json";
        Path found = pack.findAsset(namespace, relative);
        return found != null ? found : pack.root().resolve(".missing-model");
    }

    private String resolveAlias(String key, Map<String, String> textures, Set<String> seen) {
        if (!seen.add(key)) return null;
        String value = textures.get(key);
        if (value == null) return null;
        return value.startsWith("#") ? resolveAlias(value.substring(1), textures, seen) : value;
    }

    private String findTextureKey(String reference, Map<String, String> textures) {
        return textures.entrySet().stream().filter(e -> reference.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse("all");
    }

    private String normalize(String model) {
        return normalizeResource(model, sourceNamespace);
    }

    private String normalizeParent(String model) {
        return normalizeResource(model, "minecraft");
    }

    private String normalizeResource(String model, String fallbackNamespace) {
        String value = model.replace('\\', '/')
                .replaceFirst("(?i)\\.json$", "");
        if (value.startsWith("assets/")) {
            String asset = value.substring("assets/".length());
            int slash = asset.indexOf('/');
            if (slash > 0 && slash < asset.length() - 1)
                return asset.substring(0, slash) + ":"
                        + asset.substring(slash + 1)
                        .replaceFirst("^models/", "");
        }
        int colon = value.indexOf(':');
        if (colon >= 0)
            return value.substring(0, colon) + ":"
                    + value.substring(colon + 1)
                    .replaceFirst("^models/", "");
        return fallbackNamespace + ":"
                + value.replaceFirst("^models/", "");
    }

    private String geometryId(String itemId) {
        return "geometry." + outputNamespace + "." + itemId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String materialName(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String mapFace(String face) {
        return face;
    }

    private static boolean validVector(JsonArray value) {
        return validNumbers(value, 3);
    }

    private static boolean validUv(JsonArray value) {
        return validNumbers(value, 4);
    }

    private static double number(JsonArray value, int index) {
        return value.get(index).getAsDouble();
    }

    private static boolean validNumbers(JsonArray value, int count) {
        if (value == null || value.size() < count) return false;
        for (int index = 0; index < count; index++) {
            JsonElement element = value.get(index);
            if (!isNumber(element)
                    || !Double.isFinite(element.getAsDouble())) return false;
        }
        return true;
    }

    private static boolean isNumber(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber();
    }

    private static JsonArray array(JsonElement value) {
        return value != null && value.isJsonArray()
                ? value.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject()
                ? value.getAsJsonObject() : null;
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static boolean isFace(String value) {
        return switch (value) {
            case "down", "up", "north", "south", "west", "east" -> true;
            default -> false;
        };
    }

    private static JsonArray vector(double... values) {
        JsonArray result = new JsonArray();
        for (double value : values) result.add(value);
        return result;
    }

    private record Resolved(Map<String, String> textures, JsonArray elements,
                            JsonObject display, boolean handheld) {}
}
