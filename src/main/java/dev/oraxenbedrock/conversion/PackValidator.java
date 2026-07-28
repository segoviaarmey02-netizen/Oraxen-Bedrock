package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Performs a final referential-integrity pass before the pack is installed. */
final class PackValidator {
    private static final Set<String> CONDITION_PROPERTIES = Set.of(
            "broken", "damaged", "custom_model_data",
            "has_component", "fishing_rod_cast");
    private static final Set<String> MATCH_PROPERTIES = Set.of(
            "charge_type", "trim_material", "context_dimension",
            "custom_model_data");
    private static final Set<String> RANGE_PROPERTIES = Set.of(
            "bundle_fullness", "damage", "count", "custom_model_data");
    private static final Pattern GEOMETRY_ALIAS =
            Pattern.compile("\\bGeometry\\.([A-Za-z0-9_.-]+)");
    private static final Pattern TEXTURE_ALIAS =
            Pattern.compile("\\bTexture\\.([A-Za-z0-9_.-]+)");

    record Result(int checkedReferences, List<String> warnings) {}

    Result validate(Path bedrock, JsonObject itemMappings,
                    JsonObject blockMappings, String generatedNamespace) throws IOException {
        for (String required : List.of("manifest.json", "textures/item_texture.json",
                "textures/terrain_texture.json")) {
            if (!Files.isRegularFile(bedrock.resolve(required)))
                throw new IOException("Generated Bedrock pack is missing " + required);
        }

        List<String> warnings = new ArrayList<>();
        int checked = 0;
        Set<String> ids = new HashSet<>();
        JsonObject mapped = itemMappings.getAsJsonObject("items");
        if (mapped != null) for (Map.Entry<String, JsonElement> mapping
                : mapped.entrySet()) {
            JsonElement definitions = mapping.getValue();
            if (!definitions.isJsonArray()) continue;
            for (JsonElement value : definitions.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject definition = value.getAsJsonObject();
                String id = string(definition.get("bedrock_identifier"));
                String context = id == null ? mapping.getKey() : id;
                if (id != null) {
                    if (!ids.add(id)) throw new IOException(
                            "Duplicate generated Bedrock item identifier: " + id);
                    checked++;
                }
                checked += validatePredicates(definition, context, warnings);
            }
        }

        checked += validateItemIcons(bedrock, itemMappings, warnings);
        checked += validateAtlas(bedrock, "textures/item_texture.json", warnings);
        checked += validateAtlas(bedrock, "textures/terrain_texture.json", warnings);
        checked += validateBlockMappings(blockMappings);
        checked += validateGeometries(
                bedrock, blockMappings, generatedNamespace, warnings);
        checked += validateAttachables(bedrock, generatedNamespace, warnings);
        checked += validateFlipbooks(bedrock, warnings);
        checked += validateSounds(bedrock, warnings);
        return new Result(checked, List.copyOf(warnings));
    }

    private int validateItemIcons(Path bedrock, JsonObject itemMappings,
                                  List<String> warnings) throws IOException {
        JsonObject atlas = JsonSupport.readObject(
                bedrock.resolve("textures/item_texture.json"))
                .getAsJsonObject("texture_data");
        JsonObject items = itemMappings.getAsJsonObject("items");
        if (atlas == null || items == null) return 0;
        int checked = 0;
        for (Map.Entry<String, JsonElement> mapping : items.entrySet()) {
            if (!mapping.getValue().isJsonArray()) continue;
            for (JsonElement value : mapping.getValue().getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject options = value.getAsJsonObject()
                        .getAsJsonObject("bedrock_options");
                String icon = options == null ? null : string(options.get("icon"));
                if (icon == null) continue;
                checked++;
                if (!atlas.has(icon))
                    throw new IOException("Item mapping '" + mapping.getKey()
                            + "' references missing icon shorthand: " + icon);
            }
        }
        return checked;
    }

    private int validateBlockMappings(JsonObject blockMappings) throws IOException {
        JsonObject blocks = blockMappings.getAsJsonObject("blocks");
        if (blocks == null)
            throw new IOException("Block mappings have no blocks object");
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
            if (!entry.getValue().isJsonObject())
                throw new IOException("Block mapping '" + entry.getKey()
                        + "' is not an object");
            JsonObject group = entry.getValue().getAsJsonObject();
            if (string(group.get("name")) == null)
                throw new IOException("Block mapping '" + entry.getKey()
                        + "' has no name");
            JsonObject states = group.getAsJsonObject("state_overrides");
            if (group.has("only_override_states")
                    && group.get("only_override_states").getAsBoolean()
                    && (states == null || states.isEmpty()))
                throw new IOException("Block mapping '" + entry.getKey()
                        + "' has no state overrides");
            if (states == null) continue;
            boolean baseRenderable = group.has("geometry") || group.has("unit_cube");
            for (Map.Entry<String, JsonElement> state : states.entrySet()) {
                checked++;
                if (!state.getValue().isJsonObject())
                    throw new IOException("Block state mapping '" + entry.getKey()
                            + "[" + state.getKey() + "]' is not an object");
                JsonObject override = state.getValue().getAsJsonObject();
                if (!baseRenderable && !override.has("geometry")
                        && !override.has("unit_cube"))
                    throw new IOException("Block state mapping '" + entry.getKey()
                            + "[" + state.getKey() + "]' has no renderable geometry");
            }
        }
        return checked;
    }

    private int validatePredicates(JsonObject definition, String context,
                                   List<String> warnings) {
        JsonElement predicate = definition.get("predicate");
        JsonElement strategy = definition.get("predicate_strategy");
        if (predicate == null) {
            if (strategy != null)
                warnings.add("Item mapping '" + context
                        + "' has predicate_strategy without a predicate");
            return 0;
        }

        List<JsonObject> predicates = new ArrayList<>();
        if (predicate.isJsonObject()) {
            predicates.add(predicate.getAsJsonObject());
            if (strategy != null)
                warnings.add("Item mapping '" + context
                        + "' uses predicate_strategy with a single predicate object");
        } else if (predicate.isJsonArray()) {
            if (predicate.getAsJsonArray().isEmpty())
                warnings.add("Item mapping '" + context + "' has an empty predicate array");
            for (JsonElement value : predicate.getAsJsonArray()) {
                if (value.isJsonObject()) predicates.add(value.getAsJsonObject());
                else warnings.add("Item mapping '" + context
                        + "' contains a non-object predicate");
            }
            String mode = string(strategy);
            if (mode == null || (!mode.equals("and") && !mode.equals("or")))
                warnings.add("Item mapping '" + context
                        + "' predicate array requires predicate_strategy 'and' or 'or'");
        } else {
            warnings.add("Item mapping '" + context
                    + "' predicate must be an object or array");
            return 0;
        }

        int checked = 0;
        for (JsonObject value : predicates) {
            checked++;
            String type = string(value.get("type"));
            String property = string(value.get("property"));
            if (type == null || property == null) {
                warnings.add("Item mapping '" + context
                        + "' predicate requires string type and property");
                continue;
            }
            switch (type) {
                case "condition" -> {
                    if (!CONDITION_PROPERTIES.contains(property))
                        warnings.add("Item mapping '" + context
                                + "' has unsupported condition property: " + property);
                    if (value.has("expected")
                            && !isBoolean(value.get("expected")))
                        warnings.add("Item mapping '" + context
                                + "' condition predicate expected must be boolean");
                    if (property.equals("has_component")
                            && string(value.get("component")) == null)
                        warnings.add("Item mapping '" + context
                                + "' has_component predicate requires component");
                }
                case "match" -> {
                    if (!MATCH_PROPERTIES.contains(property))
                        warnings.add("Item mapping '" + context
                                + "' has unsupported match property: " + property);
                    if (!isScalar(value.get("value")))
                        warnings.add("Item mapping '" + context
                                + "' match predicate requires a scalar value");
                }
                case "range_dispatch" -> {
                    if (!RANGE_PROPERTIES.contains(property))
                        warnings.add("Item mapping '" + context
                                + "' has unsupported range property: " + property);
                    if (!isNumber(value.get("threshold")))
                        warnings.add("Item mapping '" + context
                                + "' range predicate requires a numeric threshold");
                    if (value.has("normalize") && !isBoolean(value.get("normalize")))
                        warnings.add("Item mapping '" + context
                                + "' range predicate normalize must be boolean");
                    if (value.has("scale") && !isNumber(value.get("scale")))
                        warnings.add("Item mapping '" + context
                                + "' range predicate scale must be numeric");
                }
                default -> warnings.add("Item mapping '" + context
                        + "' has unsupported predicate type: " + type);
            }
            if (value.has("index") && (!isNumber(value.get("index"))
                    || value.get("index").getAsInt() < 0))
                warnings.add("Item mapping '" + context
                        + "' predicate index must be a non-negative number");
        }
        return checked;
    }

    private int validateAtlas(Path bedrock, String file, List<String> warnings) throws IOException {
        JsonObject root = JsonSupport.readObject(bedrock.resolve(file));
        JsonObject data = root.getAsJsonObject("texture_data");
        if (data == null)
            throw new IOException(file + " has no texture_data object");
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            if (!entry.getValue().isJsonObject())
                throw new IOException(file + " entry '" + entry.getKey()
                        + "' is not an object");
            JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
            if (textures != null && !textures.isJsonArray())
                warnings.add(file + " entry '" + entry.getKey()
                        + "' should use a textures array");
            List<String> texturePaths = strings(textures);
            if (texturePaths.isEmpty())
                throw new IOException(file + " entry '" + entry.getKey()
                        + "' has no texture paths");
            for (String texture : texturePaths) {
                checked++;
                if (!texture.startsWith("textures/")) continue;
                Path png = texturePath(bedrock, texture);
                if (png == null || !Files.isRegularFile(png))
                    throw new IOException(
                            "Missing texture referenced by " + file + ": " + texture);
                try (InputStream input = Files.newInputStream(png)) {
                    if (ImageIO.read(input) == null)
                        throw new IOException(
                                "Unreadable PNG referenced by " + file + ": " + texture);
                }
            }
        }
        return checked;
    }

    private int validateGeometries(Path bedrock, JsonObject blockMappings,
                                   String generatedNamespace,
                                   List<String> warnings) throws IOException {
        Set<String> available = new HashSet<>();
        Path models = bedrock.resolve("models");
        if (Files.isDirectory(models)) try (Stream<Path> paths = Files.walk(models)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    JsonArray definitions = JsonSupport.readObject(file)
                            .getAsJsonArray("minecraft:geometry");
                    if (definitions == null) continue;
                    for (JsonElement value : definitions) {
                        JsonObject description = value.getAsJsonObject()
                                .getAsJsonObject("description");
                        if (description != null && description.has("identifier"))
                            available.add(description.get("identifier").getAsString());
                    }
                } catch (RuntimeException ex) {
                    warnings.add("Could not validate geometry file " + file.getFileName());
                }
            }
        }
        int[] checked = {0};
        walk(blockMappings, value -> {
            if (!value.isJsonObject()) return;
            JsonObject object = value.getAsJsonObject();
            if (!object.has("geometry") || !object.get("geometry").isJsonPrimitive()) return;
            String geometry = object.get("geometry").getAsString();
            checked[0]++;
            if (geometry.startsWith("geometry." + generatedNamespace + ".")
                    && !available.contains(geometry))
                warnings.add("Missing generated geometry: " + geometry);
        });
        return checked[0];
    }

    private int validateAttachables(Path bedrock, String generatedNamespace,
                                    List<String> warnings) throws IOException {
        Path directory = bedrock.resolve("attachables");
        if (!Files.isDirectory(directory)) return 0;

        Map<String, JsonObject> geometries = collectGeometryDefinitions(bedrock, warnings);
        Map<String, JsonObject> renderControllers = collectDefinitions(
                bedrock.resolve("render_controllers"), "render_controllers",
                "render controller", warnings);
        Map<String, JsonObject> animations = collectDefinitions(
                bedrock.resolve("animations"), "animations", "animation", warnings);
        Map<String, JsonObject> animationControllers = collectDefinitions(
                bedrock.resolve("animation_controllers"), "animation_controllers",
                "animation controller", warnings);

        int checked = 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                String context = bedrock.relativize(file).toString().replace('\\', '/');
                JsonObject description;
                try {
                    JsonObject attachable = JsonSupport.readObject(file)
                            .getAsJsonObject("minecraft:attachable");
                    description = attachable == null
                            ? null : attachable.getAsJsonObject("description");
                } catch (RuntimeException ex) {
                    warnings.add("Could not validate attachable " + context);
                    continue;
                }
                if (description == null) {
                    warnings.add("Attachable " + context + " has no description object");
                    continue;
                }

                JsonObject geometryAliases = object(description.get("geometry"));
                JsonObject textureAliases = object(description.get("textures"));
                if (geometryAliases == null || geometryAliases.isEmpty())
                    warnings.add("Attachable " + context + " has no geometry aliases");
                if (textureAliases == null || textureAliases.isEmpty())
                    warnings.add("Attachable " + context + " has no texture aliases");

                if (geometryAliases != null) {
                    for (Map.Entry<String, JsonElement> entry : geometryAliases.entrySet()) {
                        String reference = string(entry.getValue());
                        if (reference == null) {
                            warnings.add("Attachable " + context + " geometry alias '"
                                    + entry.getKey() + "' is not a string");
                            continue;
                        }
                        checked++;
                        if (isGeneratedIdentifier(reference, "geometry", generatedNamespace)
                                && !geometries.containsKey(reference))
                            warnings.add("Attachable " + context
                                    + " references missing geometry: " + reference);
                    }
                }

                if (textureAliases != null) {
                    for (Map.Entry<String, JsonElement> entry : textureAliases.entrySet()) {
                        String reference = string(entry.getValue());
                        if (reference == null) {
                            warnings.add("Attachable " + context + " texture alias '"
                                    + entry.getKey() + "' is not a string");
                            continue;
                        }
                        checked++;
                        if (reference.startsWith("textures/")
                                && !isBuiltInTexture(reference)
                                && !textureExists(bedrock, reference))
                            warnings.add("Attachable " + context
                                    + " references missing texture: " + reference);
                    }
                }

                Map<String, String> animationAliases = new LinkedHashMap<>();
                JsonObject declaredAnimations = object(description.get("animations"));
                if (declaredAnimations != null) {
                    for (Map.Entry<String, JsonElement> entry
                            : declaredAnimations.entrySet()) {
                        String reference = string(entry.getValue());
                        if (reference == null) {
                            warnings.add("Attachable " + context + " animation alias '"
                                    + entry.getKey() + "' is not a string");
                            continue;
                        }
                        checked++;
                        animationAliases.put(entry.getKey(), reference);
                        if (isGeneratedIdentifier(reference, "animation", generatedNamespace)
                                && !animations.containsKey(reference))
                            warnings.add("Attachable " + context
                                    + " references missing animation: " + reference);
                        if (isGeneratedIdentifier(
                                reference, "controller.animation", generatedNamespace)
                                && !animationControllers.containsKey(reference))
                            warnings.add("Attachable " + context
                                    + " references missing animation controller: " + reference);
                    }
                }

                JsonObject scripts = object(description.get("scripts"));
                if (scripts != null && scripts.has("animate"))
                    checked += validateAnimateAliases(
                            scripts.get("animate"), animationAliases.keySet(), context, warnings);

                for (String reference : controllerReferences(
                        description.get("render_controllers"), context, warnings)) {
                    checked++;
                    JsonObject controller = renderControllers.get(reference);
                    if (controller == null) {
                        if (isGeneratedIdentifier(
                                reference, "controller.render", generatedNamespace))
                            warnings.add("Attachable " + context
                                    + " references missing render controller: " + reference);
                        continue;
                    }
                    checked += validateControllerAliases(controller, geometryAliases,
                            textureAliases, context, reference, warnings);
                }
            }
        }
        return checked;
    }

    private Map<String, JsonObject> collectGeometryDefinitions(
            Path bedrock, List<String> warnings) throws IOException {
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        Path models = bedrock.resolve("models");
        if (!Files.isDirectory(models)) return definitions;
        try (Stream<Path> paths = Files.walk(models)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                try {
                    JsonArray values = JsonSupport.readObject(file)
                            .getAsJsonArray("minecraft:geometry");
                    if (values == null) continue;
                    for (JsonElement value : values) {
                        JsonObject definition = object(value);
                        JsonObject description = definition == null
                                ? null : object(definition.get("description"));
                        String identifier = description == null
                                ? null : string(description.get("identifier"));
                        if (identifier != null) definitions.put(identifier, definition);
                    }
                } catch (RuntimeException ex) {
                    warnings.add("Could not inspect geometry references in "
                            + file.getFileName());
                }
            }
        }
        return definitions;
    }

    private Map<String, JsonObject> collectDefinitions(
            Path directory, String rootKey, String kind,
            List<String> warnings) throws IOException {
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) return definitions;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                try {
                    JsonObject values =
                            JsonSupport.readObject(file).getAsJsonObject(rootKey);
                    if (values == null) continue;
                    for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                        JsonObject definition = object(entry.getValue());
                        if (definition != null) definitions.put(entry.getKey(), definition);
                    }
                } catch (RuntimeException ex) {
                    warnings.add("Could not inspect " + kind + " references in "
                            + file.getFileName());
                }
            }
        }
        return definitions;
    }

    private int validateAnimateAliases(
            JsonElement animate, Set<String> declared, String context,
            List<String> warnings) {
        if (!animate.isJsonArray()) {
            warnings.add("Attachable " + context + " scripts.animate is not an array");
            return 0;
        }
        int checked = 0;
        for (JsonElement value : animate.getAsJsonArray()) {
            if (value.isJsonPrimitive()) {
                checked++;
                String alias = string(value);
                if (alias == null || !declared.contains(alias))
                    warnings.add("Attachable " + context
                            + " animates an undeclared alias: " + alias);
            } else if (value.isJsonObject()) {
                for (String alias : value.getAsJsonObject().keySet()) {
                    checked++;
                    if (!declared.contains(alias))
                        warnings.add("Attachable " + context
                                + " animates an undeclared alias: " + alias);
                }
            } else {
                warnings.add("Attachable " + context
                        + " has an invalid scripts.animate entry");
            }
        }
        return checked;
    }

    private List<String> controllerReferences(
            JsonElement controllers, String context, List<String> warnings) {
        if (controllers == null) {
            warnings.add("Attachable " + context + " has no render_controllers");
            return List.of();
        }
        List<String> references = new ArrayList<>();
        for (JsonElement value : controllers.isJsonArray()
                ? controllers.getAsJsonArray() : List.of(controllers)) {
            if (value.isJsonPrimitive()) {
                String reference = string(value);
                if (reference != null) references.add(reference);
            } else if (value.isJsonObject()) {
                references.addAll(value.getAsJsonObject().keySet());
            } else {
                warnings.add("Attachable " + context
                        + " has an invalid render controller reference");
            }
        }
        return references;
    }

    private int validateControllerAliases(
            JsonObject controller, JsonObject geometries, JsonObject textures,
            String context, String controllerId, List<String> warnings) {
        Set<String> geometryAliases = geometries == null
                ? Set.of() : geometries.keySet();
        Set<String> textureAliases = textures == null
                ? Set.of() : textures.keySet();
        Set<String> usedGeometries = referencedAliases(controller, GEOMETRY_ALIAS);
        Set<String> usedTextures = referencedAliases(controller, TEXTURE_ALIAS);
        for (String alias : usedGeometries)
            if (!geometryAliases.contains(alias))
                warnings.add("Render controller " + controllerId + " used by "
                        + context + " references undeclared Geometry." + alias);
        for (String alias : usedTextures)
            if (!textureAliases.contains(alias))
                warnings.add("Render controller " + controllerId + " used by "
                        + context + " references undeclared Texture." + alias);
        return usedGeometries.size() + usedTextures.size();
    }

    private Set<String> referencedAliases(JsonElement value, Pattern pattern) {
        Set<String> aliases = new LinkedHashSet<>();
        walk(value, child -> {
            String text = string(child);
            if (text == null) return;
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) aliases.add(matcher.group(1));
        });
        return aliases;
    }

    private boolean isGeneratedIdentifier(
            String identifier, String prefix, String generatedNamespace) {
        return identifier.startsWith(prefix + "." + generatedNamespace + ".");
    }

    private boolean isBuiltInTexture(String texture) {
        return texture.equals("textures/misc/enchanted_actor_glint");
    }

    private int validateFlipbooks(Path bedrock, List<String> warnings) throws IOException {
        Path file = bedrock.resolve("textures/flipbook_textures.json");
        if (!Files.isRegularFile(file)) return 0;
        JsonElement parsed;
        try (var reader = Files.newBufferedReader(file)) {
            parsed = com.google.gson.JsonParser.parseReader(reader);
        }
        if (!parsed.isJsonArray()) {
            warnings.add("textures/flipbook_textures.json is not an array");
            return 0;
        }
        int checked = 0;
        for (JsonElement value : parsed.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject flipbook = value.getAsJsonObject();
            if (!flipbook.has("flipbook_texture")) continue;
            String texture = flipbook.get("flipbook_texture").getAsString();
            checked++;
            if (!textureExists(bedrock, texture))
                warnings.add("Missing flipbook texture: " + texture);
        }
        return checked;
    }

    private int validateSounds(Path bedrock, List<String> warnings) throws IOException {
        Path file = bedrock.resolve("sounds/sound_definitions.json");
        if (!Files.isRegularFile(file)) return 0;
        JsonObject definitions = JsonSupport.readObject(file).getAsJsonObject("sound_definitions");
        if (definitions == null) return 0;
        int checked = 0;
        for (JsonElement definition : definitions.asMap().values()) {
            if (!definition.isJsonObject()) continue;
            JsonArray sounds = definition.getAsJsonObject().getAsJsonArray("sounds");
            if (sounds == null) continue;
            for (JsonElement sound : sounds) {
                String name = sound.isJsonObject()
                        ? string(sound.getAsJsonObject().get("name")) : string(sound);
                if (name == null || !name.startsWith("sounds/")) continue;
                checked++;
                Path ogg = resolveResource(bedrock, name + ".ogg");
                Path wav = resolveResource(bedrock, name + ".wav");
                if ((ogg == null || !Files.isRegularFile(ogg))
                        && (wav == null || !Files.isRegularFile(wav)))
                    warnings.add("Missing audio referenced by sound definitions: " + name);
            }
        }
        return checked;
    }

    private boolean textureExists(Path bedrock, String texture) {
        Path resolved = texturePath(bedrock, texture);
        return resolved != null && Files.isRegularFile(resolved);
    }

    private Path texturePath(Path bedrock, String texture) {
        String normalized = texture.replace('\\', '/').replaceFirst("\\.png$", "");
        return resolveResource(bedrock, normalized + ".png");
    }

    private Path resolveResource(Path bedrock, String relative) {
        if (relative == null || relative.startsWith("/")
                || Arrays.asList(relative.replace('\\', '/').split("/")).contains(".."))
            return null;
        Path normalizedRoot = bedrock.normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        return resolved.startsWith(normalizedRoot) ? resolved : null;
    }

    private List<String> strings(JsonElement value) {
        if (value == null) return List.of();
        if (value.isJsonArray()) {
            List<String> output = new ArrayList<>();
            for (JsonElement child : value.getAsJsonArray())
                if (child.isJsonPrimitive()) output.add(child.getAsString());
            return output;
        }
        return value.isJsonPrimitive() ? List.of(value.getAsString()) : List.of();
    }

    private String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private boolean isScalar(JsonElement value) {
        return value != null && value.isJsonPrimitive();
    }

    private boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean();
    }

    private boolean isNumber(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber();
    }

    private void walk(JsonElement value, java.util.function.Consumer<JsonElement> visitor) {
        visitor.accept(value);
        if (value.isJsonObject())
            value.getAsJsonObject().entrySet().forEach(entry -> walk(entry.getValue(), visitor));
        else if (value.isJsonArray())
            value.getAsJsonArray().forEach(child -> walk(child, visitor));
    }
}
