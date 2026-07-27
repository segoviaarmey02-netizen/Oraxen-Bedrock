package dev.oraxenbedrock.conversion;

import com.google.gson.*;
import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.io.JavaPackMetadata;
import dev.oraxenbedrock.model.ConversionResult;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackConverter {
    private final Path dataDirectory;

    public PackConverter(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public ConversionResult convert(BridgeConfig config) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<OraxenItem> items = new OraxenScanner().scan(config.oraxenDirectory());
        Path work = dataDirectory.resolve(".work-" + UUID.randomUUID());
        Path bedrock = work.resolve("bedrock");
        Path generated = work.resolve("generated");
        Files.createDirectories(bedrock);
        Files.createDirectories(generated);

        int copiedTextures = 0;
        int blockCount = 0;
        try (PackSource source = PackSource.open(config.javaPack())) {
            JavaPackMetadata javaPackMetadata = source.metadata();
            if (javaPackMetadata.predatesSupportedRange())
                warnings.add("Java resource pack format " + javaPackMetadata.description()
                        + " predates Minecraft 1.20.5; conversion is best-effort");
            writeManifest(bedrock, config);

            JsonObject itemTexture = textureAtlas(config.packName(), "atlas.items");
            JsonObject terrainTexture = textureAtlas(config.packName(), "atlas.terrain");
            JsonArray flipbookTextures = new JsonArray();
            TextureAnimationConverter animationConverter = new TextureAnimationConverter();
            JsonObject itemMappings = new JsonObject();
            itemMappings.addProperty("format_version", 2);
            JsonObject mappedItems = new JsonObject();
            itemMappings.add("items", mappedItems);

            Map<String, List<BlockVariant>> modelStates = readBlockStates(source, warnings);
            Map<String, JsonObject> blockGroups = new LinkedHashMap<>();
            JavaModelConverter modelConverter = new JavaModelConverter(source, config.namespace());
            JavaItemModelResolver itemModelResolver =
                    new JavaItemModelResolver(source, config.namespace());
            EquipmentPreconverter equipmentConverter = new EquipmentPreconverter(
                    source, bedrock, config.namespace(), animationConverter);
            ItemComponentConverter componentConverter = new ItemComponentConverter();
            Set<String> generatedIdentifiers = new HashSet<>();
            int geometryCount = 0;
            int equipmentCount = 0;
            int soundEventCount = 0;
            int soundFileCount = 0;
            int glyphCount = 0;
            int glyphPageCount = 0;
            int languageCount = 0;
            int languageEntryCount = 0;

            for (OraxenItem item : items) {
                String safeId = sanitize(item.id());
                String bedrockId = config.namespace() + ":" + safeId;
                if (!generatedIdentifiers.add(bedrockId))
                    throw new IOException("Oraxen item identifiers collide after Bedrock sanitization: "
                            + bedrockId);
                String resolvedModel = modelReference(item);
                if (item.itemModel() != null) {
                    JavaItemModelResolver.Result modern = itemModelResolver.resolve(item.itemModel());
                    warnings.addAll(modern.warnings().stream()
                            .map(w -> item.id() + ": " + w).toList());
                    if ((resolvedModel == null || resolvedModel.isBlank()) && modern.model() != null)
                        resolvedModel = modern.model();
                }
                Path texture = findItemTexture(source, item, resolvedModel);
                JavaModelConverter.ConvertedModel convertedModel = null;
                try {
                    convertedModel = modelConverter.convert(resolvedModel, safeId);
                    if (convertedModel != null) {
                        warnings.addAll(convertedModel.warnings().stream()
                                .map(w -> item.id() + ": " + w).toList());
                        if (convertedModel.geometry() != null) {
                            JsonSupport.write(bedrock.resolve("models/oraxen")
                                    .resolve(safeId + ".geo.json"), convertedModel.geometry());
                            geometryCount++;
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    warnings.add("3D model conversion failed for '" + item.id() + "': " + ex.getMessage());
                }

                if (config.convertItems()) {
                    JsonObject definition = new JsonObject();
                    boolean legacy = item.excludeFromItemModel() && item.customModelData() != null;
                    definition.addProperty("type", legacy ? "legacy" : "definition");
                    String javaModel = normalizeModel(item.itemModel(), config.namespace() + ":" + safeId);
                    if (!legacy)
                        definition.addProperty("model", javaModel);
                    else
                        definition.addProperty("custom_model_data", item.customModelData());
                    definition.addProperty("bedrock_identifier", bedrockId);
                    definition.addProperty("display_name", plainText(item.displayName()));
                    JsonObject options = new JsonObject();
                    options.addProperty("icon", bedrockId);
                    options.addProperty("creative_category",
                            item.isBlock() || item.isFurniture() ? "construction" : "items");
                    definition.add("bedrock_options", options);
                    componentConverter.apply(item, definition);
                    try {
                        equipmentCount += equipmentConverter.preconvert(item, safeId, bedrockId,
                                convertedModel, definition, options, warnings);
                    } catch (IOException | RuntimeException ex) {
                        warnings.add("Equipment pre-conversion failed for '" + item.id()
                                + "': " + ex.getMessage());
                    }
                    addArrayValue(mappedItems, "minecraft:" + item.material().toLowerCase(Locale.ROOT), definition);

                    if (texture != null) {
                        Path destination = bedrock.resolve("textures/items").resolve(safeId + ".png");
                        TextureAnimationConverter.Animation animation =
                                animationConverter.install(texture, destination, warnings);
                        atlasEntry(itemTexture, bedrockId, "textures/items/" + safeId);
                        // Bedrock's item atlas has no reliable flipbook support.
                        // The icon stays on frame zero; held/equipped animation
                        // is supplied by the generated attachable.
                        if (animation != null)
                            animationConverter.keepFirstFrame(destination, animation);
                        copiedTextures++;
                    } else {
                        warnings.add("No icon texture found for item '" + item.id() + "'");
                    }
                }

                if (config.convertBlocks() && item.isBlock()) {
                    List<BlockVariant> variants = findJavaStates(item, modelStates);
                    if (variants.isEmpty()) {
                        warnings.add("Block '" + item.id() + "' has no matching generated Java blockstate; add an override");
                        continue;
                    }
                    JsonObject template = new JsonObject();
                    template.addProperty("display_name", plainText(item.displayName()));
                    applyBlockProperties(item, template);
                    if (convertedModel != null && convertedModel.geometry() != null) {
                        template.addProperty("geometry", convertedModel.identifier());
                        JsonObject materials = installModelMaterials(convertedModel, safeId, bedrock,
                                terrainTexture, flipbookTextures, animationConverter,
                                config.namespace(), warnings);
                        if (!materials.isEmpty()) template.add("material_instances", materials);
                    } else if (texture != null) {
                        template.addProperty("unit_cube", true);
                        String textureKey = config.namespace() + "." + safeId;
                        Path destination = bedrock.resolve("textures/blocks").resolve(safeId + ".png");
                        TextureAnimationConverter.Animation animation =
                                animationConverter.install(texture, destination, warnings);
                        atlasEntry(terrainTexture, textureKey, "textures/blocks/" + safeId);
                        if (animation != null)
                            flipbookTextures.add(animation.flipbook(
                                    textureKey, "textures/blocks/" + safeId));
                        JsonObject materials = new JsonObject();
                        JsonObject all = new JsonObject();
                        all.addProperty("texture", textureKey);
                        all.addProperty("render_method", isTransparent(texture) ? "alpha_test" : "opaque");
                        materials.add("*", all);
                        template.add("material_instances", materials);
                    }
                    for (BlockVariant variant : variants) {
                        String state = variant.state();
                        String baseBlock = state.substring(0, state.indexOf('['));
                        String stateProperties = state.substring(state.indexOf('[') + 1, state.length() - 1);
                        JsonObject group = blockGroups.computeIfAbsent(baseBlock,
                                ignored -> newBlockGroup(config, baseBlock));
                        JsonObject override = template.deepCopy();
                        if (variant.xRotation() != 0 || variant.yRotation() != 0) {
                            JsonObject transformation = new JsonObject();
                            transformation.add("rotation", bedrockRotation(variant.xRotation(), variant.yRotation()));
                            override.add("transformation", transformation);
                        }
                        group.getAsJsonObject("state_overrides").add(stateProperties, override);
                    }
                    blockCount++;
                }

                if (item.isFurniture() && (convertedModel == null
                        || convertedModel.geometry() == null)) {
                    warnings.add("Decoration '" + item.id()
                            + "' has no convertible 3D model; Bedrock uses its item icon fallback");
                }
            }

            JsonObject blockMappings = new JsonObject();
            blockMappings.addProperty("format_version", 1);
            JsonObject blocks = new JsonObject();
            blockGroups.forEach(blocks::add);
            blockMappings.add("blocks", blocks);

            if (config.copyUi()) copyMatching(source, bedrock, this::isUiTexture, warnings);
            if (config.copySounds()) {
                SoundConverter.Result sounds = new SoundConverter(source, bedrock,
                        config.oraxenDirectory(), config.namespace()).convert(items, warnings);
                soundEventCount = sounds.events();
                soundFileCount = sounds.files();
            }
            if (config.convertGlyphs()) {
                FontConverter.Result fonts = new FontConverter(source, bedrock).convert(warnings);
                glyphCount = fonts.glyphs();
                glyphPageCount = fonts.pages();
            }
            if (config.convertLanguages()) {
                LanguageConverter.Result languages =
                        new LanguageConverter().convert(source, bedrock, warnings);
                languageCount = languages.languages();
                languageEntryCount = languages.entries();
            }
            copyPackIcon(source, bedrock, warnings);

            JsonSupport.write(bedrock.resolve("textures/item_texture.json"), itemTexture);
            JsonSupport.write(bedrock.resolve("textures/terrain_texture.json"), terrainTexture);
            if (!flipbookTextures.isEmpty())
                JsonSupport.write(bedrock.resolve("textures/flipbook_textures.json"),
                        flipbookTextures);
            if (config.applyOverrides())
                applyOverrides(bedrock, itemMappings, blockMappings, warnings);
            JsonSupport.write(generated.resolve("oraxen-items.json"), itemMappings);
            JsonSupport.write(generated.resolve("oraxen-blocks.json"), blockMappings);
            PackValidator.Result validation =
                    new PackValidator().validate(bedrock, itemMappings, blockMappings);
            warnings.addAll(validation.warnings());

            Path localPack = generated.resolve("OraxenBedrock.mcpack");
            zip(bedrock, localPack);
            Path packTarget = config.geyserDirectory().resolve("packs/OraxenBedrock.mcpack");
            Path itemTarget = config.geyserDirectory().resolve("custom_mappings/oraxen-items.json");
            Path blockTarget = config.geyserDirectory().resolve("custom_mappings/oraxen-blocks.json");
            atomicInstall(localPack, packTarget);
            atomicInstall(generated.resolve("oraxen-items.json"), itemTarget);
            atomicInstall(generated.resolve("oraxen-blocks.json"), blockTarget);

            writeReport(config, items.size(), blockCount, copiedTextures, geometryCount,
                    equipmentCount, soundEventCount, soundFileCount, glyphCount,
                    glyphPageCount, languageCount, languageEntryCount,
                    animationConverter.installedAnimations(), validation.checkedReferences(),
                    javaPackMetadata, warnings, packTarget);
            return new ConversionResult(Instant.now(), items.size(), blockCount, copiedTextures,
                    packTarget, itemTarget, List.copyOf(warnings));
        } finally {
            deleteTree(work);
        }
    }

    private void writeManifest(Path bedrock, BridgeConfig config) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        JsonObject header = new JsonObject();
        header.addProperty("name", config.packName());
        header.addProperty("description", config.packDescription());
        header.addProperty("uuid", stableUuid(config.namespace() + ":header"));
        header.add("version", intArray(config.packVersion()));
        header.add("min_engine_version", intArray(new int[]{1, 21, 0}));
        manifest.add("header", header);
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", stableUuid(config.namespace() + ":resources"));
        module.add("version", intArray(config.packVersion()));
        JsonArray modules = new JsonArray();
        modules.add(module);
        manifest.add("modules", modules);
        JsonSupport.write(bedrock.resolve("manifest.json"), manifest);
    }

    private Map<String, List<BlockVariant>> readBlockStates(PackSource source, List<String> warnings) {
        Map<String, List<BlockVariant>> modelToState = new HashMap<>();
        if (source.assetRoots().isEmpty()) return modelToState;
        try {
            Map<String, Path> stateFiles = new LinkedHashMap<>();
            for (Path assets : source.assetRoots()) {
                try (Stream<Path> namespaces = Files.list(assets)) {
                    for (Path namespaceRoot : namespaces.filter(Files::isDirectory).sorted().toList()) {
                        Path blockstates = namespaceRoot.resolve("blockstates");
                        if (!Files.isDirectory(blockstates)) continue;
                        String namespace = namespaceRoot.getFileName().toString();
                        try (Stream<Path> paths = Files.walk(blockstates)) {
                            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                                String relative = blockstates.relativize(path).toString()
                                        .replace('\\', '/');
                                stateFiles.put(namespace + ":" + relative, path);
                            }
                        }
                    }
                }
            }
            for (Map.Entry<String, Path> stateFile : stateFiles.entrySet()) {
                Path path = stateFile.getValue();
                int separator = stateFile.getKey().indexOf(':');
                String namespace = stateFile.getKey().substring(0, separator);
                String relative = stateFile.getKey().substring(separator + 1)
                        .replaceFirst("\\.json$", "");
                String base = namespace + ":" + relative;
                JsonObject root = JsonSupport.readObject(path);
                JsonObject variants = root.getAsJsonObject("variants");
                if (variants != null) {
                    variants.entrySet().forEach(entry ->
                            addVariants(modelToState, base, namespace,
                                    entry.getKey(), entry.getValue()));
                }
                JsonArray multipart = root.getAsJsonArray("multipart");
                if (multipart != null)
                    readMultipart(modelToState, base, namespace, multipart);
            }
        } catch (IOException | RuntimeException ex) {
            warnings.add("Could not inspect generated blockstates: " + ex.getMessage());
        }
        return modelToState;
    }

    private void addVariants(Map<String, List<BlockVariant>> output, String base,
                             String namespace, String properties, JsonElement definitions) {
        extractVariantObjects(definitions).forEach(value -> {
            if (!value.has("model")) return;
            String rawModel = value.get("model").getAsString();
            String model = normalizeModel(rawModel,
                    namespace + ":" + rawModel.replaceFirst("^models/", ""));
            if (!rawModel.contains(":")) model = namespace + ":"
                    + rawModel.replace('\\', '/').replaceFirst("\\.json$", "")
                    .replaceFirst("^models/", "");
            BlockVariant variant = new BlockVariant(base + "[" + properties + "]",
                    value.has("x") ? value.get("x").getAsInt() : 0,
                    value.has("y") ? value.get("y").getAsInt() : 0);
            output.computeIfAbsent(model, ignored -> new ArrayList<>()).add(variant);
        });
    }

    private void readMultipart(Map<String, List<BlockVariant>> output, String base,
                               String namespace, JsonArray multipart) {
        List<String> properties = knownBooleanProperties(base);
        if (properties.isEmpty()) return;
        for (JsonElement partValue : multipart) {
            if (!partValue.isJsonObject()) continue;
            JsonObject part = partValue.getAsJsonObject();
            JsonElement apply = part.get("apply");
            if (apply == null) continue;
            JsonElement when = part.get("when");
            for (Map<String, String> state : booleanStates(properties)) {
                if (!matchesWhen(when, state)) continue;
                String stateString = state.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + "," + b).orElse("");
                addVariants(output, base, namespace, stateString, apply);
            }
        }
    }

    private List<String> knownBooleanProperties(String base) {
        return switch (base) {
            case "minecraft:tripwire" ->
                    List.of("attached", "disarmed", "east", "north", "powered", "south", "west");
            case "minecraft:chorus_plant", "minecraft:mushroom_stem",
                 "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block" ->
                    List.of("down", "east", "north", "south", "up", "west");
            default -> List.of();
        };
    }

    private List<Map<String, String>> booleanStates(List<String> properties) {
        List<Map<String, String>> states = new ArrayList<>();
        int combinations = 1 << properties.size();
        for (int mask = 0; mask < combinations; mask++) {
            Map<String, String> state = new LinkedHashMap<>();
            for (int i = 0; i < properties.size(); i++)
                state.put(properties.get(i), Boolean.toString((mask & (1 << i)) != 0));
            states.add(state);
        }
        return states;
    }

    private boolean matchesWhen(JsonElement when, Map<String, String> state) {
        if (when == null || when.isJsonNull()) return true;
        if (!when.isJsonObject()) return false;
        JsonObject condition = when.getAsJsonObject();
        if (condition.has("OR") && condition.get("OR").isJsonArray()) {
            for (JsonElement alternative : condition.getAsJsonArray("OR"))
                if (matchesWhen(alternative, state)) return true;
            return false;
        }
        if (condition.has("AND") && condition.get("AND").isJsonArray()) {
            for (JsonElement required : condition.getAsJsonArray("AND"))
                if (!matchesWhen(required, state)) return false;
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : condition.entrySet()) {
            String actual = state.get(entry.getKey());
            if (actual == null) return false;
            String expected = entry.getValue().getAsString();
            if (Arrays.stream(expected.split("\\|")).noneMatch(actual::equals)) return false;
        }
        return true;
    }

    private List<JsonObject> extractVariantObjects(JsonElement value) {
        if (value.isJsonArray()) {
            List<JsonObject> result = new ArrayList<>();
            value.getAsJsonArray().forEach(v -> result.addAll(extractVariantObjects(v)));
            return result;
        }
        if (value.isJsonObject()) return List.of(value.getAsJsonObject());
        return List.of();
    }

    private List<BlockVariant> findJavaStates(OraxenItem item, Map<String, List<BlockVariant>> states) {
        for (String model : List.of(
                normalizeModel(mechanicModel(item, "noteblock"), ""),
                normalizeModel(mechanicModel(item, "stringblock"), ""),
                normalizeModel(mechanicModel(item, "chorusblock"), ""),
                normalizeModel(mechanicModel(item, "shapedblock"), ""),
                normalizeModel(mechanicModel(item, "block"), ""),
                normalizeModel(item.model(), ""))) {
            if (!model.isBlank() && states.containsKey(model)) return states.get(model);
            if (!model.isBlank()) {
                String suffix = ":" + model.substring(model.indexOf(':') + 1);
                Optional<Map.Entry<String, List<BlockVariant>>> match = states.entrySet().stream()
                        .filter(e -> e.getKey().endsWith(suffix)
                                || e.getKey().endsWith("/" + model.substring(model.indexOf(':') + 1)))
                        .findFirst();
                if (match.isPresent()) return match.get().getValue();
            }
        }
        return List.of();
    }

    private JsonArray bedrockRotation(int x, int y) {
        JsonArray rotation = new JsonArray();
        rotation.add(normalizeRightAngle(-x));
        rotation.add(normalizeRightAngle(-y));
        rotation.add(0);
        return rotation;
    }

    private int normalizeRightAngle(int value) {
        int normalized = value % 360;
        if (normalized < 0) normalized += 360;
        return normalized;
    }

    private String mechanicModel(OraxenItem item, String mechanic) {
        return Maps.string(Maps.section(item.mechanics(), mechanic), "model");
    }

    private String modelReference(OraxenItem item) {
        for (String mechanic : List.of("noteblock", "stringblock", "chorusblock",
                "shapedblock", "block", "furniture")) {
            String model = mechanicModel(item, mechanic);
            if (model != null && !model.isBlank()) return model;
        }
        return item.model();
    }

    private void applyBlockProperties(OraxenItem item, JsonObject output) {
        Map<String, Object> mechanic = blockMechanic(item);
        Integer light = Maps.integer(mechanic, "light");
        if (light == null) light = Maps.integer(mechanic, "light_emission");
        if (light != null) output.addProperty("light_emission", Math.max(0, Math.min(15, light)));
        Integer dampening = Maps.integer(mechanic, "light_dampening");
        if (dampening != null)
            output.addProperty("light_dampening", Math.max(0, Math.min(15, dampening)));
        Double hardness = Maps.decimal(mechanic, "hardness");
        if (hardness != null && hardness >= 0)
            output.addProperty("destructible_by_mining", hardness);
        Double friction = Maps.decimal(mechanic, "friction");
        if (friction != null)
            output.addProperty("friction", Math.max(0, Math.min(1, friction)));
    }

    private Map<String, Object> blockMechanic(OraxenItem item) {
        for (String name : List.of("noteblock", "stringblock", "chorusblock",
                "shapedblock", "block")) {
            Map<String, Object> section = Maps.section(item.mechanics(), name);
            if (!section.isEmpty()) return section;
        }
        return Map.of();
    }

    private JsonObject installModelMaterials(JavaModelConverter.ConvertedModel model, String itemId,
                                             Path bedrock, JsonObject terrainTexture,
                                             JsonArray flipbookTextures,
                                             TextureAnimationConverter animationConverter,
                                             String namespace, List<String> warnings) throws IOException {
        JsonObject materials = new JsonObject();
        for (JavaModelConverter.Material material : model.materials().values()) {
            String fileName = itemId + "_" + sanitize(material.name());
            String textureKey = namespace + "." + fileName;
            Path target = bedrock.resolve("textures/blocks").resolve(fileName + ".png");
            TextureAnimationConverter.Animation animation =
                    animationConverter.install(material.source(), target, warnings);
            atlasEntry(terrainTexture, textureKey, "textures/blocks/" + fileName);
            if (animation != null)
                flipbookTextures.add(animation.flipbook(
                        textureKey, "textures/blocks/" + fileName));
            JsonObject instance = new JsonObject();
            instance.addProperty("texture", textureKey);
            instance.addProperty("render_method", "alpha_test");
            instance.addProperty("face_dimming", true);
            instance.addProperty("ambient_occlusion", true);
            materials.add(material.name(), instance);
        }
        return materials;
    }

    private JsonObject newBlockGroup(BridgeConfig config, String baseBlock) {
        JsonObject group = new JsonObject();
        group.addProperty("name", config.namespace() + "_"
                + sanitize(baseBlock.replace(':', '_')));
        group.addProperty("only_override_states", true);
        group.addProperty("included_in_creative_inventory", false);
        group.add("state_overrides", new JsonObject());
        return group;
    }

    private Path findItemTexture(PackSource source, OraxenItem item,
                                 String resolvedModel) throws IOException {
        for (String reference : item.textures()) {
            Path found = source.findTexture(reference);
            if (found != null) return found;
        }
        if (resolvedModel != null) {
            String model = normalizeModel(resolvedModel, resolvedModel);
            int colon = model.indexOf(':');
            Path json = source.findAsset(model.substring(0, colon),
                    "models/" + model.substring(colon + 1) + ".json");
            if (json != null && Files.isRegularFile(json)) {
                JsonObject textures = JsonSupport.readObject(json).getAsJsonObject("textures");
                if (textures != null) for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) continue;
                    Path found = source.findTexture(entry.getValue().getAsString());
                    if (found != null) return found;
                }
            }
        }
        return source.findTexture(item.id());
    }

    private void copyMatching(PackSource source, Path bedrock, Predicate<Path> predicate,
                              List<String> warnings) {
        try {
            for (Path assets : source.assetRoots()) {
                try (Stream<Path> paths = Files.walk(assets)) {
                    for (Path sourceFile : paths.filter(predicate).toList()) {
                String normalized = sourceFile.toString().replace('\\', '/');
                String marker = normalized.contains("/textures/gui/") ? "/textures/gui/" : "/sounds/";
                int index = normalized.indexOf(marker);
                if (index < 0) continue;
                String relative = normalized.substring(index + marker.length());
                String namespace = assetNamespace(normalized);
                Path target = marker.contains("gui")
                        ? bedrock.resolve("textures/ui").resolve(namespace).resolve(relative)
                        : bedrock.resolve("sounds/oraxen").resolve(relative);
                copy(sourceFile, target);
                    }
                }
            }
        } catch (IOException ex) {
            warnings.add("Could not copy UI/sound assets: " + ex.getMessage());
        }
    }

    private String assetNamespace(String normalizedPath) {
        int assets = normalizedPath.indexOf("/assets/");
        if (assets < 0) return "oraxen";
        String rest = normalizedPath.substring(assets + "/assets/".length());
        int slash = rest.indexOf('/');
        return slash > 0 ? sanitize(rest.substring(0, slash)) : "oraxen";
    }

    private void copyPackIcon(PackSource source, Path bedrock, List<String> warnings) {
        for (String name : List.of("pack.png", "pack_icon.png")) {
            Path icon = source.root().resolve(name);
            if (!Files.isRegularFile(icon)) continue;
            try {
                copy(icon, bedrock.resolve("pack_icon.png"));
            } catch (IOException ex) {
                warnings.add("Could not copy pack icon: " + ex.getMessage());
            }
            return;
        }
    }

    private void applyOverrides(Path bedrock, JsonObject items, JsonObject blocks,
                                List<String> warnings) throws IOException {
        Path overrides = dataDirectory.resolve("overrides");
        if (!Files.isDirectory(overrides)) return;
        Path itemFragment = overrides.resolve("item-mappings.json");
        Path blockFragment = overrides.resolve("block-mappings.json");
        if (Files.isRegularFile(itemFragment)) JsonSupport.deepMerge(items, JsonSupport.readObject(itemFragment));
        if (Files.isRegularFile(blockFragment)) JsonSupport.deepMerge(blocks, JsonSupport.readObject(blockFragment));
        try (Stream<Path> paths = Files.walk(overrides)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("README.txt") || name.equals("item-mappings.json")
                        || name.equals("block-mappings.json")) continue;
                copy(file, bedrock.resolve(overrides.relativize(file).toString()));
            }
        }
        warnings.add("Native Bedrock overrides applied");
    }

    private void writeReport(BridgeConfig config, int itemCount, int blockCount, int textureCount,
                             int geometryCount, int equipmentCount, int soundEventCount,
                             int soundFileCount, int glyphCount, int glyphPageCount,
                             int languageCount, int languageEntryCount,
                             int animatedTextureCount, int validatedReferences,
                             JavaPackMetadata javaPackMetadata,
                             List<String> warnings, Path target) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("generated_at", Instant.now().toString());
        report.addProperty("java_pack", config.javaPack().toString());
        report.addProperty("java_pack_format", javaPackMetadata.description());
        report.addProperty("java_pack_overlays_declared", javaPackMetadata.overlays().size());
        report.addProperty("java_pack_overlays_applied", javaPackMetadata.activeOverlays().size());
        report.addProperty("supported_java_servers", "1.20.5+");
        report.addProperty("geyser_pack", target.toString());
        report.addProperty("items", itemCount);
        report.addProperty("blocks", blockCount);
        report.addProperty("textures", textureCount);
        report.addProperty("converted_geometries", geometryCount);
        report.addProperty("generated_attachables_and_equipment", equipmentCount);
        report.addProperty("sound_events", soundEventCount);
        report.addProperty("sound_files", soundFileCount);
        report.addProperty("glyphs_and_emojis", glyphCount);
        report.addProperty("glyph_pages", glyphPageCount);
        report.addProperty("languages", languageCount);
        report.addProperty("language_entries", languageEntryCount);
        report.addProperty("animated_textures", animatedTextureCount);
        report.addProperty("validated_references", validatedReferences);
        JsonArray warningArray = new JsonArray();
        warnings.forEach(warningArray::add);
        report.add("warnings", warningArray);
        JsonSupport.write(dataDirectory.resolve("last-report.json"), report);
    }

    private JsonObject textureAtlas(String name, String atlas) {
        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", name);
        root.addProperty("texture_name", atlas);
        root.add("texture_data", new JsonObject());
        return root;
    }

    private void atlasEntry(JsonObject atlas, String key, String path) {
        JsonObject value = new JsonObject();
        value.addProperty("textures", path);
        atlas.getAsJsonObject("texture_data").add(key, value);
    }

    private void addArrayValue(JsonObject object, String key, JsonObject value) {
        JsonArray array = object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
        array.add(value);
        object.add(key, array);
    }

    private boolean isUiTexture(Path path) {
        String value = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return value.contains("/textures/gui/") && value.endsWith(".png");
    }

    private boolean isTransparent(Path ignored) {
        // Alpha-test is also safe for opaque block PNGs and preserves cutout plants.
        return true;
    }

    private void zip(Path root, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination));
             Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                ZipEntry entry = new ZipEntry(root.relativize(path).toString().replace('\\', '/'));
                entry.setTime(0);
                zip.putNextEntry(entry);
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private void atomicInstall(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copy(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private static JsonArray intArray(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }

    private static String stableUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeModel(String model, String fallback) {
        if (model == null || model.isBlank()) return fallback;
        String normalized = model.replace('\\', '/').replace(".json", "")
                .replaceFirst("^assets/", "").replaceFirst("^models/", "");
        if (!normalized.contains(":")) normalized = "oraxen:" + normalized;
        return normalized;
    }

    private static String sanitize(String value) {
        String result = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return result.isBlank() ? "unnamed" : result;
    }

    private static String plainText(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", "").replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }

    private record BlockVariant(String state, int xRotation, int yRotation) {}
}
