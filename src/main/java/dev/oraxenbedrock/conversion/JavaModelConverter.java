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
    record ConvertedModel(String identifier, JsonObject geometry, Map<String, Material> materials,
                          boolean generatedSprite, List<String> warnings) {}

    private static final Set<String> BUILTIN_PARENTS = Set.of(
            "minecraft:item/generated", "minecraft:item/handheld",
            "minecraft:item/handheld_rod", "minecraft:builtin/entity");
    private final PackSource pack;
    private final String namespace;

    JavaModelConverter(PackSource pack, String namespace) {
        this.pack = pack;
        this.namespace = namespace;
    }

    ConvertedModel convert(String requestedModel, String itemId) throws IOException {
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
            Optional<String> animatedMaterial = materials.entrySet().stream()
                    .filter(entry -> hasAnimation(entry.getValue().source()))
                    .map(Map.Entry::getKey).findFirst();
            if (animatedMaterial.isEmpty())
                return new ConvertedModel(geometryId(itemId), null, materials, true, warnings);
            modelElements.add(spriteElement(animatedMaterial.get()));
        }

        int textureWidth = materials.values().stream().mapToInt(Material::width).max().orElse(16);
        int textureHeight = materials.values().stream().mapToInt(Material::height).max().orElse(16);
        JsonArray cubes = new JsonArray();
        for (JsonElement elementValue : modelElements) {
            if (!elementValue.isJsonObject()) continue;
            JsonObject element = elementValue.getAsJsonObject();
            JsonArray from = element.getAsJsonArray("from");
            JsonArray to = element.getAsJsonArray("to");
            if (!validVector(from) || !validVector(to)) {
                warnings.add("Skipped element without valid from/to in " + model);
                continue;
            }
            double fx = number(from, 0), fy = number(from, 1), fz = number(from, 2);
            double tx = number(to, 0), ty = number(to, 1), tz = number(to, 2);
            JsonObject cube = new JsonObject();
            cube.add("origin", vector(fx - 8, fy, 8 - tz));
            cube.add("size", vector(tx - fx, ty - fy, tz - fz));
            if (element.has("rotation") && element.get("rotation").isJsonObject())
                addRotation(cube, element.getAsJsonObject("rotation"), warnings);
            JsonObject faces = element.getAsJsonObject("faces");
            if (faces != null) cube.add("uv", convertFaces(faces, resolved.textures, from, to));
            cubes.add(cube);
        }
        if (cubes.isEmpty()) return new ConvertedModel(geometryId(itemId), null, materials, true, warnings);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometryId(itemId));
        description.addProperty("texture_width", textureWidth);
        description.addProperty("texture_height", textureHeight);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        description.add("visible_bounds_offset", vector(0, 1, 0));
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "root");
        bone.add("pivot", vector(0, 0, 0));
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
                generatedSprite, warnings);
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
            return new Resolved(new LinkedHashMap<>(), new JsonArray());
        }
        JsonObject json = JsonSupport.readObject(path);
        Resolved parent = null;
        if (json.has("parent")) {
            String parentName = normalizeParent(json.get("parent").getAsString());
            if (!BUILTIN_PARENTS.contains(parentName)) parent = resolve(parentName, chain, warnings);
            else parent = builtinModel(parentName);
        }
        Map<String, String> textures = parent == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(parent.textures);
        JsonObject textureJson = json.getAsJsonObject("textures");
        if (textureJson != null) textureJson.entrySet().forEach(e -> {
            if (e.getValue().isJsonPrimitive()) textures.put(e.getKey(), e.getValue().getAsString());
        });
        JsonArray elements = json.has("elements") ? json.getAsJsonArray("elements")
                : parent == null ? new JsonArray() : parent.elements.deepCopy();
        chain.remove(model);
        return new Resolved(textures, elements);
    }

    private Map<String, Material> resolveMaterials(Map<String, String> textures,
                                                    List<String> warnings) throws IOException {
        Map<String, Material> result = new LinkedHashMap<>();
        for (String key : textures.keySet()) {
            String reference = resolveAlias(key, textures, new HashSet<>());
            if (reference == null || reference.startsWith("#")) continue;
            Path texture = pack.findTexture(reference);
            if (texture == null) {
                warnings.add("Texture not found: " + reference);
                continue;
            }
            int width = 16, height = 16;
            try (InputStream input = Files.newInputStream(texture)) {
                BufferedImage image = ImageIO.read(input);
                if (image != null) {
                    width = image.getWidth();
                    height = image.getHeight();
                    int[] frameSize = animationFrameSize(texture, width, height);
                    width = frameSize[0];
                    height = frameSize[1];
                }
            }
            result.put(key, new Material(materialName(key), reference, texture, width, height));
        }
        return result;
    }

    private JsonObject convertFaces(JsonObject faces, Map<String, String> textures,
                                    JsonArray from, JsonArray to) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject javaFace = entry.getValue().getAsJsonObject();
            String texture = javaFace.has("texture") ? javaFace.get("texture").getAsString() : "#all";
            String key = texture.startsWith("#") ? texture.substring(1) : findTextureKey(texture, textures);
            JsonArray uv = javaFace.getAsJsonArray("uv");
            if (!validUv(uv)) uv = defaultUv(entry.getKey(), from, to);
            JsonObject bedrockFace = new JsonObject();
            bedrockFace.add("uv", vector(number(uv, 0), number(uv, 1)));
            bedrockFace.add("uv_size", vector(number(uv, 2) - number(uv, 0),
                    number(uv, 3) - number(uv, 1)));
            bedrockFace.addProperty("material_instance", materialName(key));
            if (javaFace.has("rotation"))
                bedrockFace.addProperty("uv_rotation", javaFace.get("rotation").getAsInt());
            result.add(mapFace(entry.getKey()), bedrockFace);
        }
        return result;
    }

    private void addRotation(JsonObject cube, JsonObject rotation, List<String> warnings) {
        JsonArray origin = rotation.getAsJsonArray("origin");
        String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";
        double angle = rotation.has("angle") ? rotation.get("angle").getAsDouble() : 0;
        if (!validVector(origin)) origin = vector(8, 8, 8);
        cube.add("pivot", vector(number(origin, 0) - 8, number(origin, 1), 8 - number(origin, 2)));
        double x = 0, y = 0, z = 0;
        switch (axis) {
            case "x" -> x = -angle;
            case "y" -> y = -angle;
            case "z" -> z = angle;
            default -> warnings.add("Unknown Java model rotation axis: " + axis);
        }
        cube.add("rotation", vector(x, y, z));
        if (rotation.has("rescale") && rotation.get("rescale").getAsBoolean())
            warnings.add("Java rescale rotation approximated by Bedrock geometry");
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
        for (String candidate : List.of(namespace + ":item/" + id, namespace + ":block/" + id,
                namespace + ":" + id)) {
            if (Files.isRegularFile(modelPath(candidate))) return candidate;
        }
        return null;
    }

    private Resolved builtinModel(String model) {
        String path = model.substring(model.indexOf(':') + 1);
        JsonArray elements = new JsonArray();
        switch (path) {
            case "block/cube_all" -> elements.add(cube(Map.of(
                    "down", "#all", "up", "#all", "north", "#all",
                    "south", "#all", "west", "#all", "east", "#all")));
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
            default -> {
                return null;
            }
        }
        return new Resolved(new LinkedHashMap<>(), elements);
    }

    private JsonObject cube(Map<String, String> textures) {
        JsonObject element = new JsonObject();
        element.add("from", vector(0, 0, 0));
        element.add("to", vector(16, 16, 16));
        JsonObject faces = new JsonObject();
        textures.forEach((face, texture) -> {
            JsonObject data = new JsonObject();
            data.addProperty("texture", texture);
            faces.add(face, data);
        });
        element.add("faces", faces);
        return element;
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

    private JsonObject spriteElement(String texture) {
        JsonObject element = new JsonObject();
        element.add("from", vector(0, 0, 7.5));
        element.add("to", vector(16, 16, 8.5));
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

    private boolean hasAnimation(Path texture) {
        return Files.isRegularFile(texture.resolveSibling(texture.getFileName() + ".mcmeta"));
    }

    private int[] animationFrameSize(Path texture, int imageWidth, int imageHeight) {
        Path metadata = texture.resolveSibling(texture.getFileName() + ".mcmeta");
        if (!Files.isRegularFile(metadata)) return new int[]{imageWidth, imageHeight};
        try {
            JsonObject animation = JsonSupport.readObject(metadata).getAsJsonObject("animation");
            if (animation == null) return new int[]{imageWidth, imageHeight};
            int width = animation.has("width")
                    ? animation.get("width").getAsInt() : imageWidth;
            int height = animation.has("height")
                    ? animation.get("height").getAsInt() : width;
            if (width > 0 && height > 0) return new int[]{width, height};
        } catch (IOException | RuntimeException ignored) {
            // TextureAnimationConverter reports malformed metadata later.
        }
        return new int[]{imageWidth, imageHeight};
    }

    private Path modelPath(String model) {
        int colon = model.indexOf(':');
        String namespace = model.substring(0, colon);
        String relative = "models/" + model.substring(colon + 1) + ".json";
        Path found = pack.findAsset(namespace, relative);
        return found != null ? found : pack.root().resolve("assets")
                .resolve(namespace).resolve(relative);
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
        String value = model.replace('\\', '/').replace(".json", "")
                .replaceFirst("^assets/", "").replaceFirst("^models/", "");
        return value.contains(":") ? value : namespace + ":" + value;
    }

    private String normalizeParent(String model) {
        String value = model.replace('\\', '/').replace(".json", "")
                .replaceFirst("^assets/", "").replaceFirst("^models/", "");
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private String geometryId(String itemId) {
        return "geometry." + namespace + "." + itemId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String materialName(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String mapFace(String face) {
        return switch (face) {
            case "north" -> "south";
            case "south" -> "north";
            default -> face;
        };
    }

    private static boolean validVector(JsonArray value) {
        return value != null && value.size() >= 3;
    }

    private static boolean validUv(JsonArray value) {
        return value != null && value.size() >= 4;
    }

    private static double number(JsonArray value, int index) {
        return value.get(index).getAsDouble();
    }

    private static JsonArray vector(double... values) {
        JsonArray result = new JsonArray();
        for (double value : values) result.add(value);
        return result;
    }

    private record Resolved(Map<String, String> textures, JsonArray elements) {}
}
