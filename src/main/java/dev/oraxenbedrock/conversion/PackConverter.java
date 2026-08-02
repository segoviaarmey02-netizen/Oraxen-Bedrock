package dev.oraxenbedrock.conversion;

import com.google.gson.*;
import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.config.GeyserDisplayEntityMappingsWriter;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.io.JavaPackMetadata;
import dev.oraxenbedrock.model.ConversionResult;
import dev.oraxenbedrock.model.OraxenItem;
import dev.oraxenbedrock.util.Maps;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackConverter {
    private static final String ORAXEN_NAMESPACE = "oraxen";
    private static final AtomicLong PACK_GENERATION_SEQUENCE =
            new AtomicLong(System.currentTimeMillis() / 1000L);
    private final Path dataDirectory;

    public PackConverter(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public ConversionResult convert(BridgeConfig config) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<OraxenItem> items = new OraxenScanner().scan(config.oraxenDirectory());
        Map<String, OraxenItem> itemsById = new LinkedHashMap<>();
        items.forEach(item -> itemsById.put(
                item.id().toLowerCase(Locale.ROOT), item));
        Path work = dataDirectory.resolve(".work-" + UUID.randomUUID());
        Path bedrock = work.resolve("bedrock");
        Path generated = work.resolve("generated");
        Files.createDirectories(bedrock);
        Files.createDirectories(generated);

        int copiedTextures = 0;
        int blockCount = 0;
        int mappedItemCount = 0;
        try (PackSource source = PackSource.open(config.javaPack(),
                config.oraxenDirectory().resolve("pack"))) {
            if (source.effectiveAssetFiles().isEmpty())
                throw new IOException("Java resource pack contains no assets; refusing to "
                        + "replace the existing Bedrock pack. Check paths.java-pack and "
                        + "the root directory inside " + config.javaPack());
            JavaPackMetadata javaPackMetadata = source.metadata();
            if (javaPackMetadata.predatesSupportedRange())
                warnings.add("Java resource pack format " + javaPackMetadata.description()
                        + " predates Minecraft 1.20.5; conversion is best-effort");
            int[] effectivePackVersion = effectivePackVersion(config);
            writeManifest(bedrock, config, effectivePackVersion);

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
            Map<String, String> mappedBlockStates = new HashMap<>();
            JavaModelConverter modelConverter = new JavaModelConverter(
                    source, ORAXEN_NAMESPACE, config.namespace());
            JavaItemModelResolver itemModelResolver =
                    new JavaItemModelResolver(source, ORAXEN_NAMESPACE);
            LegacyJavaItemModelResolver legacyItemModelResolver =
                    new LegacyJavaItemModelResolver(source, ORAXEN_NAMESPACE);
            EquipmentPreconverter equipmentConverter = new EquipmentPreconverter(
                    source, bedrock, ORAXEN_NAMESPACE, config.namespace(), animationConverter);
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
                String configuredModel = modelReference(item);
                String resolvedModel = configuredModel;
                String itemModelReference = item.itemModel() == null
                        ? ORAXEN_NAMESPACE + ":" + item.id() : item.itemModel();
                JavaItemModelResolver.Result modern =
                        itemModelResolver.resolve(itemModelReference);
                boolean modernDefinitionFound = modern.definitionFound();
                boolean ambiguousMaterialAppearance = false;
                String mappingModelReference = normalizeModel(
                        item.itemModel(), ORAXEN_NAMESPACE + ":" + item.id());
                List<JsonObject> baseDefinitionPredicates = List.of();
                List<JavaItemModelResolver.Variant> baseVisualLayers =
                        modern.variants().stream()
                                .filter(variant -> variant.predicates().isEmpty())
                                .toList();
                Map<String, List<JavaItemModelResolver.Variant>> stateVisuals =
                        new LinkedHashMap<>();
                modern.variants().stream()
                        .filter(variant -> !variant.predicates().isEmpty())
                        .forEach(variant -> stateVisuals.computeIfAbsent(
                                JsonSupport.GSON.toJson(variant.predicates()),
                                ignored -> new ArrayList<>()).add(variant));

                /*
                 * Oraxen can use its model_data_ids appearance mode instead
                 * of assigning a custom item_model component. In that mode
                 * the vanilla material definition selects the Oraxen model
                 * through custom_model_data.strings[0].
                 */
                if (!modernDefinitionFound) {
                    String materialModel = javaIdentifier(item.material());
                    JavaItemModelResolver.Result materialDefinition =
                            itemModelResolver.resolve(materialModel);
                    Map<String, List<JavaItemModelResolver.Variant>>
                            selectedGroups = new LinkedHashMap<>();
                    materialDefinition.variants().stream()
                            .filter(variant ->
                                    matchesMaterialAppearance(item, variant))
                            .forEach(variant -> selectedGroups.computeIfAbsent(
                                    JsonSupport.GSON.toJson(
                                            variant.predicates()),
                                    ignored -> new ArrayList<>()).add(variant));
                    if (selectedGroups.isEmpty()) {
                        Map<String, List<JavaItemModelResolver.Variant>>
                                textureMatches = obfuscatedMaterialAppearances(
                                item, materialDefinition, modelConverter,
                                config.oraxenDirectory());
                        if (textureMatches.size() == 1) {
                            selectedGroups.putAll(textureMatches);
                            warnings.add("Item '" + item.id()
                                    + "' model_data_float appearance was recovered "
                                    + "from an obfuscated generated model by matching "
                                    + "its source texture");
                        } else if (textureMatches.size() > 1) {
                            ambiguousMaterialAppearance = true;
                            warnings.add("Item '" + item.id()
                                    + "' matches multiple obfuscated model_data_float "
                                    + "entries by texture; no numeric predicate was "
                                    + "guessed. Give the item a unique texture, enable "
                                    + "item_properties/model_data_ids, or add a mapping override");
                        }
                    }
                    if (!selectedGroups.isEmpty()) {
                        Map.Entry<String,
                                List<JavaItemModelResolver.Variant>> baseline =
                                selectedGroups.entrySet().stream()
                                        .min(Comparator.comparingInt(entry ->
                                                entry.getValue().get(0)
                                                        .predicates().size()))
                                        .orElseThrow();
                        List<JavaItemModelResolver.Variant> visuals =
                                baseline.getValue();
                        resolvedModel = visuals.get(0).model();
                        baseVisualLayers = visuals.size() == 1
                                ? List.of()
                                : List.copyOf(visuals.subList(
                                        1, visuals.size()));
                        baseDefinitionPredicates =
                                List.copyOf(visuals.get(0).predicates());
                        stateVisuals.clear();
                        selectedGroups.entrySet().stream()
                                .filter(entry -> !entry.getKey()
                                        .equals(baseline.getKey()))
                                .forEach(entry -> stateVisuals.put(
                                        entry.getKey(), entry.getValue()));
                        modern = materialDefinition;
                        modernDefinitionFound = true;
                        mappingModelReference = materialModel;
                    }
                }
                warnings.addAll(modern.warnings().stream()
                        .map(w -> item.id() + ": " + w).toList());
                LegacyJavaItemModelResolver.Result legacyModel =
                        modernDefinitionFound
                                ? new LegacyJavaItemModelResolver.Result(
                                resolvedModel, false, List.of(), List.of())
                                : legacyItemModelResolver.resolve(
                                resolvedModel, item.customModelData());
                warnings.addAll(legacyModel.warnings().stream()
                        .map(w -> item.id() + ": " + w).toList());
                if (legacyModel.modelFound()) resolvedModel = legacyModel.model();
                legacyModel.variants().forEach(variant ->
                        stateVisuals.computeIfAbsent(
                                JsonSupport.GSON.toJson(variant.predicates()),
                                ignored -> new ArrayList<>()).add(variant));
                // The item_model component is authoritative for Java 1.21.4+
                // rendering. Pack.model may still be present for legacy
                // clients or for the placed block and must not override it.
                if (modern.definitionFound() && modern.model() != null
                        && baseDefinitionPredicates.isEmpty())
                    resolvedModel = modern.model();
                Path texture = findItemTexture(source, item, resolvedModel, ORAXEN_NAMESPACE);
                if (texture != null && !readableImage(texture)) {
                    warnings.add("Source texture for item '" + item.id()
                            + "' is not a readable PNG: " + texture);
                    texture = null;
                }
                JavaModelConverter.ConvertedModel convertedModel = null;
                try {
                    boolean forceSprite = item.isFurniture()
                            || item.isHat() || item.isCosmeticBackpack()
                            || !Maps.section(item.components(), "equippable").isEmpty()
                            || !baseVisualLayers.isEmpty();
                    List<JavaModelConverter.ConvertedModel> visualModels =
                            new ArrayList<>();
                    JavaModelConverter.ConvertedModel primary =
                            modelConverter.convert(resolvedModel, safeId, forceSprite);
                    if (primary != null) visualModels.add(primary);
                    for (JavaItemModelResolver.Variant layer : baseVisualLayers) {
                        JavaModelConverter.ConvertedModel convertedLayer =
                                modelConverter.convert(layer.model(), safeId, true);
                        if (convertedLayer != null) visualModels.add(convertedLayer);
                    }
                    convertedModel = visualModels.size() <= 1
                            ? (visualModels.isEmpty() ? null : visualModels.get(0))
                            : mergeItemVisualModels(
                                    visualModels, safeId, config.namespace());
                    if (convertedModel != null) {
                        for (JavaModelConverter.ConvertedModel visual : visualModels)
                            warnings.addAll(visual.warnings().stream()
                                    .map(w -> item.id() + ": " + w).toList());
                        if (convertedModel.geometry() != null) {
                            JsonSupport.write(bedrock.resolve("models").resolve(config.namespace())
                                    .resolve(safeId + ".geo.json"), convertedModel.geometry());
                            geometryCount++;
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    warnings.add("3D model conversion failed for '" + item.id() + "': " + ex.getMessage());
                }
                List<Path> guiIconTextures = resolveGuiIconTextures(
                        item, modern, modelConverter, safeId, warnings);
                if (texture == null && convertedModel != null
                        && !convertedModel.materials().isEmpty())
                    texture = convertedModel.materials().values().iterator().next().source();

                if (config.convertItems() && ambiguousMaterialAppearance)
                    warnings.add("Skipped custom mapping for item '" + item.id()
                            + "' because its generated custom_model_data selector "
                            + "is ambiguous");
                if (config.convertItems() && !ambiguousMaterialAppearance) {
                    JsonObject definition = new JsonObject();
                    boolean legacy = item.customModelData() != null
                            && (item.excludeFromItemModel()
                            || !modernDefinitionFound);
                    definition.addProperty("type", legacy ? "legacy" : "definition");
                    if (!legacy)
                        definition.addProperty(
                                "model", mappingModelReference);
                    else
                        definition.addProperty("custom_model_data", item.customModelData());
                    if (!legacy && !modernDefinitionFound)
                        warnings.add("Item '" + item.id() + "' has no generated Java item definition"
                                + (item.customModelData() == null
                                ? " or custom_model_data; its Geyser mapping may not match"
                                : ""));
                    definition.addProperty("bedrock_identifier", bedrockId);
                    definition.addProperty("display_name", plainText(item.displayName()));
                    JsonObject options = new JsonObject();
                    options.addProperty("icon", bedrockId);
                    options.addProperty("creative_category",
                            item.excludeFromInventory() ? "none"
                                    : item.isBlock() || item.isFurniture()
                                    ? "construction" : "items");
                    definition.add("bedrock_options", options);
                    componentConverter.apply(item, definition);
                    if (!baseDefinitionPredicates.isEmpty())
                        addPredicates(definition, baseDefinitionPredicates);
                    try {
                        equipmentCount += equipmentConverter.preconvert(item, safeId, bedrockId,
                                convertedModel, definition, options, warnings);
                    } catch (IOException | RuntimeException ex) {
                        warnings.add("Equipment pre-conversion failed for '" + item.id()
                                + "': " + ex.getMessage());
                    }
                    if (item.excludeFromInventory())
                        options.addProperty("creative_category", "none");
                    List<Path> iconTextures = guiIconTextures.isEmpty()
                            ? itemIconTextures(texture, convertedModel)
                            : guiIconTextures;
                    if (!iconTextures.isEmpty()) {
                        if (installBestItemIcon(iconTextures, convertedModel,
                                guiIconTextures.isEmpty(), safeId, bedrockId, bedrock,
                                itemTexture, animationConverter, warnings)) {
                            addArrayValue(mappedItems, javaIdentifier(item.material()), definition);
                            mappedItemCount++;
                        } else {
                            warnings.add("Skipped custom mapping for item '" + item.id()
                                    + "' because its icon could not be decoded");
                        }
                    } else {
                        warnings.add("Skipped custom mapping for item '" + item.id()
                                + "' because no icon texture was found");
                    }

                    for (List<JavaItemModelResolver.Variant> visuals
                            : stateVisuals.values()) {
                        JavaItemModelResolver.Variant variant = visuals.get(0);
                        String stateSafeId = stateItemId(safeId, visuals);
                        String stateBedrockId = config.namespace() + ":" + stateSafeId;
                        if (!generatedIdentifiers.add(stateBedrockId))
                            throw new IOException("Generated item-state identifier collides: "
                                    + stateBedrockId);
                        JavaModelConverter.ConvertedModel stateModel = null;
                        try {
                            boolean forceSprite = visuals.size() > 1
                                    || item.isFurniture()
                                    || item.isHat() || item.isCosmeticBackpack()
                                    || !Maps.section(item.components(), "equippable").isEmpty();
                            List<JavaModelConverter.ConvertedModel> convertedVisuals =
                                    new ArrayList<>();
                            for (JavaItemModelResolver.Variant visual : visuals) {
                                JavaModelConverter.ConvertedModel convertedVisual =
                                        modelConverter.convert(
                                                visual.model(), stateSafeId, forceSprite);
                                if (convertedVisual != null)
                                    convertedVisuals.add(convertedVisual);
                            }
                            stateModel = convertedVisuals.size() <= 1
                                    ? (convertedVisuals.isEmpty()
                                    ? null : convertedVisuals.get(0))
                                    : mergeItemVisualModels(
                                            convertedVisuals, stateSafeId,
                                            config.namespace());
                            if (stateModel != null) {
                                for (JavaModelConverter.ConvertedModel visual
                                        : convertedVisuals)
                                    warnings.addAll(visual.warnings().stream()
                                            .map(w -> item.id()
                                                    + " item state: " + w).toList());
                                if (stateModel.geometry() != null) {
                                    JsonSupport.write(bedrock.resolve("models")
                                                    .resolve(config.namespace())
                                                    .resolve(stateSafeId + ".geo.json"),
                                            stateModel.geometry());
                                    geometryCount++;
                                }
                            }
                        } catch (IOException | RuntimeException ex) {
                            warnings.add("Item-state model conversion failed for '"
                                    + item.id() + "' (" + variant.model() + "): "
                                    + ex.getMessage());
                        }

                        JsonObject stateDefinition = definition.deepCopy();
                        stateDefinition.addProperty("bedrock_identifier", stateBedrockId);
                        JsonObject stateOptions =
                                stateDefinition.getAsJsonObject("bedrock_options");
                        stateOptions.addProperty("icon", stateBedrockId);
                        addPredicates(stateDefinition, variant.predicates());
                        try {
                            equipmentCount += equipmentConverter.preconvert(
                                    item, stateSafeId, stateBedrockId, stateModel,
                                    stateDefinition, stateOptions, warnings);
                        } catch (IOException | RuntimeException ex) {
                            warnings.add("Equipment state conversion failed for '"
                                    + item.id() + "' (" + variant.model() + "): "
                                    + ex.getMessage());
                        }
                        Path stateFallback =
                                source.findTexture(variant.model(), ORAXEN_NAMESPACE);
                        List<Path> stateTextures =
                                itemIconTextures(stateFallback, stateModel);
                        if (!stateTextures.isEmpty()) {
                            if (installBestItemIcon(stateTextures, stateModel, true,
                                    stateSafeId, stateBedrockId, bedrock,
                                    itemTexture, animationConverter, warnings)) {
                                addArrayValue(mappedItems, javaIdentifier(item.material()),
                                        stateDefinition);
                                mappedItemCount++;
                            } else {
                                warnings.add("Skipped custom mapping for item state '"
                                        + item.id() + "' (" + variant.model()
                                        + ") because its icon could not be decoded");
                            }
                        } else {
                            warnings.add("Skipped custom mapping for item state '"
                                    + item.id() + "' (" + variant.model()
                                    + ") because no icon texture was found");
                        }
                    }

                    OraxenItem packModelMappingItem = furnitureDisplayItem(
                            item, itemsById, warnings);
                    PackModelCounts packModelCounts = convertPackModelVariants(
                            item, packModelMappingItem, safeId, definition,
                            source, itemModelResolver,
                            modelConverter, equipmentConverter, mappedItems,
                            itemTexture, bedrock, animationConverter,
                            config.namespace(), generatedIdentifiers, warnings);
                    mappedItemCount += packModelCounts.mappings();
                    geometryCount += packModelCounts.geometries();
                    equipmentCount += packModelCounts.equipment();
                }

                if (config.convertBlocks() && item.isBlock()) {
                    List<BlockVariant> variants =
                            findJavaStates(item, resolvedModel, modelStates);
                    if (variants.isEmpty()) {
                        warnings.add("Block '" + item.id() + "' has no matching generated Java blockstate; add an override");
                        continue;
                    }
                    JsonObject baseTemplate = new JsonObject();
                    baseTemplate.addProperty("display_name", plainText(item.displayName()));
                    applyBlockProperties(item, baseTemplate);
                    Map<String, JsonObject> templatesByModel = new LinkedHashMap<>();
                    Map<String, List<BlockVariant>> variantsByState = new LinkedHashMap<>();
                    boolean mappedAnyState = false;
                    for (BlockVariant variant : variants)
                        variantsByState.computeIfAbsent(
                                variant.state(), ignored -> new ArrayList<>()).add(variant);
                    for (Map.Entry<String, List<BlockVariant>> stateEntry
                            : variantsByState.entrySet()) {
                        String state = stateEntry.getKey();
                        List<BlockVariant> stateVariants = stateEntry.getValue();
                        boolean composite = stateVariants.size() > 1
                                && stateVariants.stream().allMatch(BlockVariant::multipart);
                        BlockVariant variant = stateVariants.stream()
                                .max(Comparator.comparingInt(BlockVariant::weight))
                                .orElseThrow();
                        if (stateVariants.size() > 1 && !composite)
                            warnings.add("Block '" + item.id()
                                    + "' has weighted/random Java models for " + state
                                    + "; the highest-weight deterministic model was used");
                        if (!composite && variant.uvlock()
                                && (variant.xRotation() != 0
                                || variant.yRotation() != 0))
                            warnings.add("Block '" + item.id() + "' uses Java uvlock for "
                                    + state + "; Bedrock applies the declared rotation "
                                    + "but cannot lock the texture to world axes exactly");
                        String baseBlock = state.substring(0, state.indexOf('['));
                        String stateProperties = state.substring(state.indexOf('[') + 1, state.length() - 1);
                        JsonObject group = blockGroups.computeIfAbsent(baseBlock,
                                ignored -> newBlockGroup(config, baseBlock));
                        String templateKey = composite
                                ? "multipart:" + state : variant.model();
                        JsonObject template = templatesByModel.get(templateKey);
                        if (template == null) {
                            template = baseTemplate.deepCopy();
                            boolean primary = !composite && isPrimaryBlockModel(
                                    variant.model(), configuredModel, item.id());
                            boolean reusePrimary = primary
                                    && sameModel(variant.model(), resolvedModel);
                            String geometryFileId = primary ? safeId
                                    : stateGeometryId(safeId, templateKey);
                            JavaModelConverter.ConvertedModel stateModel =
                                    reusePrimary ? convertedModel : null;
                            if (composite) {
                                try {
                                    stateModel = mergeMultipartModels(
                                            stateVariants, geometryFileId,
                                            config.namespace(), modelConverter,
                                            item.id(), warnings);
                                } catch (IOException | RuntimeException ex) {
                                    warnings.add("Multipart block-state conversion failed for '"
                                            + item.id() + "' (" + state + "): "
                                            + ex.getMessage());
                                }
                            } else if (stateModel == null || stateModel.geometry() == null) {
                                try {
                                    stateModel = modelConverter.convert(
                                            variant.model(), geometryFileId, false);
                                    if (stateModel != null)
                                        warnings.addAll(stateModel.warnings().stream()
                                                .map(w -> item.id() + " block state: " + w)
                                                .toList());
                                } catch (IOException | RuntimeException ex) {
                                    warnings.add("Block-state model conversion failed for '"
                                            + item.id() + "' (" + variant.model() + "): "
                                            + ex.getMessage());
                                }
                            }
                            if (stateModel != null && stateModel.geometry() != null
                                    && !stateModel.materials().isEmpty()) {
                                if (!reusePrimary || convertedModel == null
                                        || convertedModel.geometry() == null) {
                                    JsonSupport.write(bedrock.resolve("models")
                                                    .resolve(config.namespace())
                                                    .resolve(geometryFileId + ".geo.json"),
                                            stateModel.geometry());
                                    geometryCount++;
                                }
                                template.addProperty("geometry", stateModel.identifier());
                                JsonObject materials = installModelMaterials(
                                        stateModel, geometryFileId, bedrock,
                                        terrainTexture, flipbookTextures, animationConverter,
                                        config.namespace(), warnings);
                                if (!materials.isEmpty())
                                    template.add("material_instances", materials);
                            } else if (texture != null) {
                                installFallbackBlockTexture(template, texture, safeId, bedrock,
                                        terrainTexture, flipbookTextures, animationConverter,
                                        config.namespace(), warnings);
                            }
                            if (!template.has("geometry") && !template.has("unit_cube")) {
                                warnings.add("Skipped block state '" + state
                                        + "' for item '" + item.id()
                                        + "' because it has no renderable geometry or texture");
                                continue;
                            }
                            templatesByModel.put(templateKey, template);
                        }
                        String previousItem = mappedBlockStates.putIfAbsent(state, item.id());
                        if (previousItem != null && !previousItem.equals(item.id()))
                            throw new IOException("Multiple Oraxen items map to Java block state "
                                    + state + "; the generated block mapping would be ambiguous");
                        JsonObject override = template.deepCopy();
                        if (!composite
                                && (variant.xRotation() != 0 || variant.yRotation() != 0)) {
                            JsonObject transformation = new JsonObject();
                            transformation.add("rotation", bedrockRotation(variant.xRotation(), variant.yRotation()));
                            override.add("transformation", transformation);
                        }
                        group.getAsJsonObject("state_overrides").add(stateProperties, override);
                        mappedAnyState = true;
                    }
                    if (mappedAnyState) blockCount++;
                }

                if (item.isFurniture()) {
                    if (convertedModel == null || convertedModel.geometry() == null)
                        warnings.add("Decoration '" + item.id()
                                + "' has no convertible 3D model; Bedrock uses its item icon fallback");
                }
            }

            JsonObject blockMappings = new JsonObject();
            blockMappings.addProperty("format_version", 1);
            JsonObject blocks = new JsonObject();
            blockGroups.entrySet().removeIf(entry ->
                    entry.getValue().getAsJsonObject("state_overrides").isEmpty());
            blockGroups.forEach(blocks::add);
            blockMappings.add("blocks", blocks);

            if (config.copyUi()) copyMatching(source, bedrock, this::isUiTexture, warnings);
            if (config.copySounds()) {
                SoundConverter.Result sounds = new SoundConverter(source, bedrock,
                        config.oraxenDirectory(), "minecraft").convert(items, warnings);
                soundEventCount = sounds.events();
                soundFileCount = sounds.files();
            }
            if (config.convertGlyphs()) {
                FontConverter.Result fonts = new FontConverter(
                        source, bedrock, config.emojiCellSize(),
                        config.oraxenDirectory().resolve("glyphs"))
                        .convert(warnings);
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
            ensureMeaningfulPack(bedrock, itemMappings, blockMappings,
                    config, items, warnings);
            JsonSupport.write(generated.resolve("oraxen-items.json"), itemMappings);
            JsonSupport.write(generated.resolve("oraxen-blocks.json"), blockMappings);
            PackValidator.Result validation =
                    new PackValidator().validate(
                            bedrock, itemMappings, blockMappings, config.namespace());
            warnings.addAll(validation.warnings());
            if (source.fallbackAssetCount() > 0)
                warnings.add("Recovered " + source.fallbackAssetCount()
                        + " asset files from the uncompressed Oraxen pack source");
            copiedTextures = countPngFiles(bedrock);

            Path localPack = generated.resolve("OraxenBedrock.mcpack");
            zip(bedrock, localPack);
            Path packTarget = config.geyserDirectory().resolve("packs/OraxenBedrock.mcpack");
            Path itemTarget = config.geyserDirectory().resolve("custom_mappings/oraxen-items.json");
            Path blockTarget = config.geyserDirectory().resolve("custom_mappings/oraxen-blocks.json");
            atomicInstall(localPack, packTarget);
            atomicInstall(generated.resolve("oraxen-items.json"), itemTarget);
            atomicInstall(generated.resolve("oraxen-blocks.json"), blockTarget);

            GeyserDisplayEntityMappingsWriter.Result displayEntityIntegration = null;
            List<String> displayFurniture = displayEntityFurniture(items);
            try {
                displayEntityIntegration = GeyserDisplayEntityMappingsWriter.write(
                        config.geyserDirectory(), config.namespace(), items,
                        mappedBedrockIdentifiers(mappedItems));
                addDisplayEntityDiagnostics(
                        displayEntityIntegration, displayFurniture, warnings);
            } catch (IOException | RuntimeException exception) {
                if (!displayFurniture.isEmpty())
                    warnings.add("Could not configure placed DISPLAY_ENTITY furniture "
                            + displayFurniture + " for GeyserDisplayEntity: "
                            + exception.getMessage() + ". Stock Geyser cannot render "
                            + "Java ItemDisplay entities; use ARMOR_STAND furniture until "
                            + "the extension mapping is fixed");
            }

            List<String> uniqueWarnings = List.copyOf(
                    new LinkedHashSet<>(warnings));

            writeReport(config, items.size(), mappedItemCount, blockCount,
                    copiedTextures, geometryCount,
                    equipmentCount, soundEventCount, soundFileCount, glyphCount,
                    glyphPageCount, languageCount, languageEntryCount,
                    animationConverter.installedAnimations(), validation.checkedReferences(),
                    effectivePackVersion, javaPackMetadata,
                    uniqueWarnings, packTarget, displayEntityIntegration);
            return new ConversionResult(Instant.now(), mappedItemCount, blockCount, copiedTextures,
                    packTarget, itemTarget, uniqueWarnings);
        } finally {
            deleteTree(work);
        }
    }

    private void writeManifest(Path bedrock, BridgeConfig config,
                               int[] effectiveVersion) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        JsonObject header = new JsonObject();
        header.addProperty("name", config.packName());
        header.addProperty("description", config.packDescription());
        header.addProperty("uuid", stableUuid(config.namespace() + ":header"));
        header.add("version", intArray(effectiveVersion));
        header.add("min_engine_version", intArray(new int[]{1, 21, 0}));
        manifest.add("header", header);
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", stableUuid(config.namespace() + ":resources"));
        module.add("version", intArray(effectiveVersion));
        JsonArray modules = new JsonArray();
        modules.add(module);
        manifest.add("modules", modules);
        JsonSupport.write(bedrock.resolve("manifest.json"), manifest);
    }

    int[] effectivePackVersion(BridgeConfig config) {
        long generation = PACK_GENERATION_SEQUENCE.updateAndGet(previous ->
                Math.max(previous + 1, System.currentTimeMillis() / 1000L));
        int[] configured = config.packVersion();
        int major = configured.length == 0
                ? 1 : Math.max(0, Math.min(65534, configured[0]));
        int middle = (int) ((generation / 65535L) % 65535L);
        int patch = (int) (generation % 65535L);
        return new int[]{major, middle, patch};
    }

    private Map<String, List<BlockVariant>> readBlockStates(PackSource source, List<String> warnings) {
        Map<String, List<BlockVariant>> modelToState = new HashMap<>();
        try {
            Map<String, Path> stateFiles = new LinkedHashMap<>();
            for (PackSource.AssetFile asset : source.effectiveAssetFiles()) {
                String relative = asset.relative().replace('\\', '/');
                if (!relative.startsWith("blockstates/")
                        || !relative.endsWith(".json")) continue;
                stateFiles.put(asset.namespace() + ":"
                        + relative.substring("blockstates/".length()), asset.path());
            }
            for (Map.Entry<String, Path> stateFile : stateFiles.entrySet()) {
                Path path = stateFile.getValue();
                try {
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
                                        entry.getKey(), entry.getValue(), false));
                    }
                    JsonArray multipart = root.getAsJsonArray("multipart");
                    if (multipart != null)
                        readMultipart(modelToState, base, namespace, multipart);
                } catch (IOException | RuntimeException exception) {
                    warnings.add("Could not inspect generated blockstate " + path + ": "
                            + exception.getMessage());
                }
            }
        } catch (IOException | RuntimeException exception) {
            warnings.add("Could not scan generated blockstates: " + exception.getMessage());
        }
        return modelToState;
    }

    private void addVariants(Map<String, List<BlockVariant>> output, String base,
                             String namespace, String properties, JsonElement definitions,
                             boolean multipart) {
        extractVariantObjects(definitions).forEach(value -> {
            if (!value.has("model")) return;
            String rawModel = value.get("model").getAsString();
            String model = normalizeModel(rawModel,
                    namespace + ":" + rawModel.replaceFirst("^models/", ""));
            if (!rawModel.contains(":")) model = namespace + ":"
                    + rawModel.replace('\\', '/').replaceFirst("\\.json$", "")
                    .replaceFirst("^models/", "");
            BlockVariant variant = new BlockVariant(model, base + "[" + properties + "]",
                    value.has("x") ? value.get("x").getAsInt() : 0,
                    value.has("y") ? value.get("y").getAsInt() : 0,
                    value.has("weight") ? Math.max(1, value.get("weight").getAsInt()) : 1,
                    value.has("uvlock") && value.get("uvlock").getAsBoolean(),
                    multipart);
            output.computeIfAbsent(model, ignored -> new ArrayList<>()).add(variant);
        });
    }

    private void readMultipart(Map<String, List<BlockVariant>> output, String base,
                               String namespace, JsonArray multipart) {
        List<Map<String, String>> states = multipartStates(base, multipart);
        for (JsonElement partValue : multipart) {
            if (!partValue.isJsonObject()) continue;
            JsonObject part = partValue.getAsJsonObject();
            JsonElement apply = part.get("apply");
            if (apply == null) continue;
            // An apply array is a weighted/random choice for one multipart
            // part, not several simultaneous geometry layers. Bedrock custom
            // block mappings cannot randomize it, so choose deterministically.
            if (apply.isJsonArray()) {
                JsonArray alternatives = apply.getAsJsonArray();
                if (alternatives.isEmpty()) continue;
                apply = extractVariantObjects(alternatives).stream()
                        .max(Comparator.comparingInt(value -> value.has("weight")
                                ? value.get("weight").getAsInt() : 1))
                        .map(value -> (JsonElement) value)
                        .orElse(alternatives.get(0));
            }
            JsonElement when = part.get("when");
            for (Map<String, String> state : states) {
                if (!matchesWhen(when, state)) continue;
                String stateString = state.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + "," + b).orElse("");
                addVariants(output, base, namespace, stateString, apply, true);
            }
        }
    }

    private List<Map<String, String>> multipartStates(
            String base, JsonArray multipart) {
        List<String> known = knownBooleanProperties(base);
        if (!known.isEmpty()) return booleanStates(known);

        Map<String, Set<String>> domains = new TreeMap<>();
        for (JsonElement partValue : multipart) {
            if (!partValue.isJsonObject()) continue;
            collectMultipartDomains(
                    partValue.getAsJsonObject().get("when"), domains);
        }
        for (Set<String> values : domains.values()) {
            if (values.contains("true") || values.contains("false")) {
                values.add("true");
                values.add("false");
            }
        }
        List<Map<String, String>> states = new ArrayList<>();
        states.add(new LinkedHashMap<>());
        for (Map.Entry<String, Set<String>> domain : domains.entrySet()) {
            List<Map<String, String>> expanded = new ArrayList<>();
            expansion:
            for (Map<String, String> state : states)
                for (String value : domain.getValue()) {
                    Map<String, String> next = new LinkedHashMap<>(state);
                    next.put(domain.getKey(), value);
                    expanded.add(next);
                    if (expanded.size() >= 4096) break expansion;
                }
            states = expanded;
            if (states.size() >= 4096) break;
        }
        return List.copyOf(states);
    }

    private void collectMultipartDomains(
            JsonElement when, Map<String, Set<String>> domains) {
        if (when == null || !when.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry
                : when.getAsJsonObject().entrySet()) {
            if ((entry.getKey().equals("OR") || entry.getKey().equals("AND"))
                    && entry.getValue().isJsonArray()) {
                entry.getValue().getAsJsonArray().forEach(
                        child -> collectMultipartDomains(child, domains));
                continue;
            }
            if (!entry.getValue().isJsonPrimitive()) continue;
            Set<String> values = domains.computeIfAbsent(
                    entry.getKey(), ignored -> new TreeSet<>());
            values.addAll(Arrays.asList(
                    entry.getValue().getAsString().split("\\|")));
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

    private List<BlockVariant> findJavaStates(
            OraxenItem item, String resolvedModel,
            Map<String, List<BlockVariant>> states) {
        List<BlockVariant> stringBlockStates = findStringBlockStates(item, states);
        if (!stringBlockStates.isEmpty()) return stringBlockStates;

        for (String model : List.of(
                normalizeModel(resolvedModel, ""),
                normalizeModel(mechanicModel(item, "noteblock"), ""),
                normalizeModel(mechanicModel(item, "stringblock"), ""),
                normalizeModel(mechanicModel(item, "chorusblock"), ""),
                normalizeModel(mechanicModel(item, "shapedblock"), ""),
                normalizeModel(mechanicModel(item, "shaped_block"), ""),
                normalizeModel(mechanicModel(item, "block"), ""),
                normalizeModel(item.model(), ""),
                ORAXEN_NAMESPACE + ":block/" + item.id(),
                ORAXEN_NAMESPACE + ":item/" + item.id(),
                ORAXEN_NAMESPACE + ":" + item.id())) {
            if (!model.isBlank() && states.containsKey(model))
                return relatedModelStates(states, model, item);
            if (!model.isBlank() && isShapedBlock(item)
                    && states.keySet().stream()
                    .anyMatch(candidate -> isRelatedModel(model, candidate)))
                return relatedModelStates(states, model, item);
            if (!model.isBlank()) {
                String suffix = ":" + model.substring(model.indexOf(':') + 1);
                Optional<Map.Entry<String, List<BlockVariant>>> match = states.entrySet().stream()
                        .filter(e -> e.getKey().endsWith(suffix)
                                || e.getKey().endsWith("/" + model.substring(model.indexOf(':') + 1)))
                        .sorted(Map.Entry.comparingByKey())
                        .findFirst();
                if (match.isPresent())
                    return relatedModelStates(states, match.get().getKey(), item);
            }
        }
        return List.of();
    }

    private List<BlockVariant> findStringBlockStates(
            OraxenItem item, Map<String, List<BlockVariant>> states) {
        Map<String, Object> mechanic = mechanicSection(item, "stringblock");
        if (mechanic.isEmpty()) {
            Map<String, Object> unified = mechanicSection(item, "block");
            String type = Maps.string(unified, "type");
            if (type != null && (type.equalsIgnoreCase("string")
                    || type.equalsIgnoreCase("stringblock")))
                mechanic = unified;
        }
        Integer baseVariation = Maps.integer(mechanic, "custom_variation");
        if (baseVariation == null || baseVariation < 1 || baseVariation > 127)
            return List.of();

        List<Integer> variations = new ArrayList<>();
        variations.add(baseVariation);
        Object configuredStack = Maps.get(mechanic, "stackable");
        if (configuredStack == null)
            configuredStack = Maps.get(mechanic, "stack_variations");
        if (configuredStack instanceof Collection<?> stack) {
            for (Object entry : stack) {
                if (!(entry instanceof Map<?, ?> variation)) continue;
                Integer id = Maps.integer(variation, "custom_variation");
                if (id != null && id >= 1 && id <= 127)
                    variations.add(id);
            }
        }
        // A normal StringBlock may intentionally use one multipart model for
        // many matching tripwire states. Keep model-based discovery for that
        // case; variation-based selection is needed only to gather the
        // separately-modelled stack levels.
        if (variations.size() == 1) return List.of();

        List<BlockVariant> baseStates =
                stringBlockStatesForVariation(states, baseVariation);
        if (baseStates.isEmpty()) return List.of();

        Set<BlockVariant> matched = new LinkedHashSet<>(baseStates);
        for (int i = 1; i < variations.size(); i++)
            matched.addAll(stringBlockStatesForVariation(
                    states, variations.get(i)));
        return matched.stream()
                .sorted(Comparator.comparing(BlockVariant::state)
                        .thenComparing(BlockVariant::model))
                .toList();
    }

    private List<BlockVariant> stringBlockStatesForVariation(
            Map<String, List<BlockVariant>> states, int variation) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("east", Boolean.toString((variation & 1) != 0));
        expected.put("west", Boolean.toString((variation & 2) != 0));
        expected.put("south", Boolean.toString((variation & 4) != 0));
        expected.put("north", Boolean.toString((variation & 8) != 0));
        expected.put("attached", Boolean.toString((variation & 16) != 0));
        expected.put("disarmed", Boolean.toString((variation & 32) != 0));
        expected.put("powered", Boolean.toString((variation & 64) != 0));
        return states.values().stream()
                .flatMap(Collection::stream)
                .filter(variant -> blockStateProperties(
                        variant.state(), "minecraft:tripwire").equals(expected))
                .toList();
    }

    private Map<String, String> blockStateProperties(
            String state, String expectedBlock) {
        int opening = state.indexOf('[');
        if (opening < 0 || !state.substring(0, opening).equals(expectedBlock)
                || !state.endsWith("]"))
            return Map.of();
        Map<String, String> properties = new LinkedHashMap<>();
        String values = state.substring(opening + 1, state.length() - 1);
        if (values.isBlank()) return properties;
        for (String property : values.split(",")) {
            int equals = property.indexOf('=');
            if (equals <= 0 || equals == property.length() - 1)
                return Map.of();
            properties.put(property.substring(0, equals),
                    property.substring(equals + 1));
        }
        return properties;
    }

    private List<BlockVariant> relatedModelStates(
            Map<String, List<BlockVariant>> states, String model, OraxenItem item) {
        boolean shaped = isShapedBlock(item);
        Set<String> multipartBases = states.entrySet().stream()
                .filter(entry -> entry.getKey().equals(model)
                        || shaped && isRelatedModel(model, entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(BlockVariant::multipart)
                .map(variant -> variant.state().substring(
                        0, variant.state().indexOf('[')))
                .collect(java.util.stream.Collectors.toSet());
        return states.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .filter(variant -> entry.getKey().equals(model)
                                || shaped && isRelatedModel(model, entry.getKey())
                                || variant.multipart()
                                && multipartBases.contains(variant.state().substring(
                                0, variant.state().indexOf('[')))))
                .sorted(Comparator.comparing(BlockVariant::state)
                        .thenComparing(BlockVariant::model))
                .toList();
    }

    private boolean isShapedBlock(OraxenItem item) {
        if (!mechanicSection(item, "shapedblock").isEmpty()) return true;
        String type = Maps.string(mechanicSection(item, "block"), "type");
        if (type == null) return false;
        return switch (type.toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "STAIR", "STAIRS", "SLAB", "DOOR", "TRAPDOOR",
                 "TRAP_DOOR", "GRATE", "BULB", "SHAPED" -> true;
            default -> false;
        };
    }

    private boolean isRelatedModel(String base, String candidate) {
        int baseColon = base.indexOf(':');
        int candidateColon = candidate.indexOf(':');
        if (baseColon < 0 || candidateColon < 0
                || !base.substring(0, baseColon)
                .equals(candidate.substring(0, candidateColon)))
            return false;
        String basePath = base.substring(baseColon + 1);
        String candidatePath = candidate.substring(candidateColon + 1);
        return candidatePath.startsWith(basePath + "_")
                || candidatePath.startsWith(basePath + "/");
    }

    private boolean isPrimaryBlockModel(
            String candidate, String configuredModel, String itemId) {
        String explicit = normalizeModel(configuredModel, "");
        return candidate.equals(explicit)
                || candidate.equals(ORAXEN_NAMESPACE + ":block/" + itemId)
                || candidate.equals(ORAXEN_NAMESPACE + ":item/" + itemId)
                || candidate.equals(ORAXEN_NAMESPACE + ":" + itemId);
    }

    private boolean sameModel(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank())
            return false;
        return normalizeModel(left, "").equals(normalizeModel(right, ""));
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
        Map<String, Object> section = mechanicSection(item, mechanic);
        String direct = Maps.string(section, "model");
        if (direct != null && !direct.isBlank()) return direct;
        Object appearanceValue = Maps.get(section, "appearance");
        if (appearanceValue instanceof Map<?, ?> appearance) {
            String model = Maps.string(appearance, "model");
            if (model != null && !model.isBlank()) return model;
        } else if (appearanceValue != null) {
            String model = String.valueOf(appearanceValue);
            if (!model.isBlank()) return model;
        }
        return null;
    }

    private String modelReference(OraxenItem item) {
        for (String mechanic : List.of("noteblock", "stringblock", "chorusblock",
                "shapedblock", "shaped_block", "block", "furniture")) {
            String model = mechanicModel(item, mechanic);
            if (model != null && !model.isBlank()) return model;
        }
        return item.model();
    }

    private OraxenItem furnitureDisplayItem(
            OraxenItem item, Map<String, OraxenItem> itemsById,
            List<String> warnings) {
        if (!item.isFurniture()) return item;
        String helperId = Maps.string(
                mechanicSection(item, "furniture"), "item");
        if (helperId == null || helperId.isBlank()) return item;
        OraxenItem helper = itemsById.get(
                helperId.trim().toLowerCase(Locale.ROOT));
        if (helper != null) return helper;
        warnings.add("Furniture '" + item.id()
                + "' references missing display item '" + helperId.trim()
                + "'; Pack.models states were mapped to the furniture's own material");
        return item;
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
                "shapedblock", "shaped_block", "block")) {
            Map<String, Object> section = mechanicSection(item, name);
            if (!section.isEmpty()) return section;
        }
        return Map.of();
    }

    private Map<String, Object> mechanicSection(OraxenItem item, String name) {
        String expected = normalizeMechanicName(name);
        for (Map.Entry<String, Object> entry : item.mechanics().entrySet()) {
            if (!normalizeMechanicName(entry.getKey()).equals(expected)
                    || !(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private String normalizeMechanicName(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "");
    }

    private JavaModelConverter.ConvertedModel mergeItemVisualModels(
            List<JavaModelConverter.ConvertedModel> models,
            String geometryFileId, String namespace) {
        List<JavaModelConverter.ConvertedModel> usable = models.stream()
                .filter(model -> model != null && model.geometry() != null)
                .toList();
        if (usable.isEmpty()) return models.isEmpty() ? null : models.get(0);
        if (usable.size() == 1) return usable.get(0);

        int textureWidth = 16;
        int textureHeight = 16;
        for (JavaModelConverter.ConvertedModel model : usable) {
            JsonObject description = model.geometry()
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                    .getAsJsonObject("description");
            textureWidth = Math.max(textureWidth,
                    description.has("texture_width")
                            ? description.get("texture_width").getAsInt() : 16);
            textureHeight = Math.max(textureHeight,
                    description.has("texture_height")
                            ? description.get("texture_height").getAsInt() : 16);
        }

        JsonArray bones = new JsonArray();
        Map<String, JavaModelConverter.Material> combinedMaterials =
                new LinkedHashMap<>();
        for (int visualIndex = 0; visualIndex < usable.size(); visualIndex++) {
            JavaModelConverter.ConvertedModel model = usable.get(visualIndex);
            JsonObject sourceDefinition = model.geometry()
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject sourceDescription =
                    sourceDefinition.getAsJsonObject("description");
            int sourceWidth = sourceDescription.has("texture_width")
                    ? sourceDescription.get("texture_width").getAsInt() : 16;
            int sourceHeight = sourceDescription.has("texture_height")
                    ? sourceDescription.get("texture_height").getAsInt() : 16;
            Map<String, String> materialNames = new LinkedHashMap<>();
            for (JavaModelConverter.Material material : model.materials().values()) {
                String newName = sanitize(
                        "visual_" + visualIndex + "_" + material.name());
                materialNames.put(material.name(), newName);
                combinedMaterials.putIfAbsent(newName,
                        new JavaModelConverter.Material(
                                newName, material.textureReference(),
                                material.source(), material.width(), material.height()));
            }
            JsonArray sourceBones = sourceDefinition.getAsJsonArray("bones");
            if (sourceBones == null) continue;
            for (int boneIndex = 0; boneIndex < sourceBones.size(); boneIndex++) {
                if (!sourceBones.get(boneIndex).isJsonObject()) continue;
                JsonObject bone = sourceBones.get(boneIndex).getAsJsonObject().deepCopy();
                bone.addProperty("name",
                        "visual_" + visualIndex + "_" + boneIndex);
                remapMultipartGeometry(
                        bone, materialNames,
                        (double) textureWidth / sourceWidth,
                        (double) textureHeight / sourceHeight);
                bones.add(bone);
            }
        }

        String identifier = "geometry." + namespace + "." + geometryFileId;
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", textureWidth);
        description.addProperty("texture_height", textureHeight);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        JsonArray boundsOffset = new JsonArray();
        boundsOffset.add(0);
        boundsOffset.add(1);
        boundsOffset.add(0);
        description.add("visible_bounds_offset", boundsOffset);
        JsonObject definition = new JsonObject();
        definition.add("description", description);
        definition.add("bones", bones);
        JsonArray definitions = new JsonArray();
        definitions.add(definition);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("format_version", "1.12.0");
        geometry.add("minecraft:geometry", definitions);
        JsonObject display = usable.stream()
                .map(JavaModelConverter.ConvertedModel::display)
                .filter(value -> value != null && !value.isEmpty())
                .findFirst().map(JsonObject::deepCopy).orElseGet(JsonObject::new);
        boolean generatedSprite = usable.stream()
                .allMatch(JavaModelConverter.ConvertedModel::generatedSprite);
        boolean handheld = usable.stream()
                .anyMatch(JavaModelConverter.ConvertedModel::handheld);
        return new JavaModelConverter.ConvertedModel(
                identifier, geometry, combinedMaterials, display,
                generatedSprite, handheld, List.of());
    }

    private JavaModelConverter.ConvertedModel mergeMultipartModels(
            List<BlockVariant> variants, String geometryFileId, String namespace,
            JavaModelConverter modelConverter, String itemId,
            List<String> warnings) throws IOException {
        List<MultipartPart> parts = new ArrayList<>();
        int textureWidth = 16;
        int textureHeight = 16;
        for (BlockVariant variant : variants) {
            JavaModelConverter.ConvertedModel model =
                    modelConverter.convert(variant.model(), geometryFileId, false);
            if (model == null || model.geometry() == null) {
                warnings.add("Multipart block '" + itemId
                        + "': model '" + variant.model() + "' could not be converted");
                continue;
            }
            warnings.addAll(model.warnings().stream()
                    .map(w -> itemId + " multipart state: " + w).toList());
            JsonObject definition = model.geometry()
                    .getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject();
            JsonObject description = definition.getAsJsonObject("description");
            int width = description != null && description.has("texture_width")
                    ? description.get("texture_width").getAsInt() : 16;
            int height = description != null && description.has("texture_height")
                    ? description.get("texture_height").getAsInt() : 16;
            textureWidth = Math.max(textureWidth, width);
            textureHeight = Math.max(textureHeight, height);
            parts.add(new MultipartPart(variant, model, width, height));
        }
        if (parts.isEmpty()) return null;

        JsonArray bones = new JsonArray();
        Map<String, JavaModelConverter.Material> combinedMaterials =
                new LinkedHashMap<>();
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            MultipartPart part = parts.get(partIndex);
            Map<String, String> materialNames = new LinkedHashMap<>();
            for (JavaModelConverter.Material material
                    : part.model().materials().values()) {
                String newName = sanitize("part_" + partIndex + "_" + material.name());
                materialNames.put(material.name(), newName);
                combinedMaterials.putIfAbsent(newName,
                        new JavaModelConverter.Material(
                                newName, material.textureReference(),
                                material.source(), material.width(), material.height()));
            }
            JsonObject sourceDefinition = part.model().geometry()
                    .getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject();
            JsonArray sourceBones = sourceDefinition.getAsJsonArray("bones");
            if (sourceBones == null) continue;
            for (int boneIndex = 0; boneIndex < sourceBones.size(); boneIndex++) {
                if (!sourceBones.get(boneIndex).isJsonObject()) continue;
                JsonObject bone = sourceBones.get(boneIndex).getAsJsonObject().deepCopy();
                bone.addProperty("name", "part_" + partIndex + "_" + boneIndex);
                remapMultipartGeometry(bone, materialNames,
                        (double) textureWidth / part.textureWidth(),
                        (double) textureHeight / part.textureHeight());
                if (part.variant().xRotation() != 0
                        || part.variant().yRotation() != 0) {
                    JsonArray pivot = new JsonArray();
                    pivot.add(0);
                    pivot.add(8);
                    pivot.add(0);
                    bone.add("pivot", pivot);
                    bone.add("rotation", bedrockRotation(
                            part.variant().xRotation(),
                            part.variant().yRotation()));
                }
                bones.add(bone);
            }
        }
        if (bones.isEmpty()) return null;

        String identifier = "geometry." + namespace + "." + geometryFileId;
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", textureWidth);
        description.addProperty("texture_height", textureHeight);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        JsonArray boundsOffset = new JsonArray();
        boundsOffset.add(0);
        boundsOffset.add(1);
        boundsOffset.add(0);
        description.add("visible_bounds_offset", boundsOffset);
        JsonObject definition = new JsonObject();
        definition.add("description", description);
        definition.add("bones", bones);
        JsonArray definitions = new JsonArray();
        definitions.add(definition);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("format_version", "1.12.0");
        geometry.add("minecraft:geometry", definitions);
        return new JavaModelConverter.ConvertedModel(
                identifier, geometry, combinedMaterials,
                new JsonObject(), false, false, List.of());
    }

    private void remapMultipartGeometry(
            JsonElement value, Map<String, String> materialNames,
            double scaleU, double scaleV) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child ->
                    remapMultipartGeometry(child, materialNames, scaleU, scaleV));
            return;
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("material_instance")
                && object.get("material_instance").isJsonPrimitive()) {
            String old = object.get("material_instance").getAsString();
            String replacement = materialNames.get(old);
            if (replacement != null)
                object.addProperty("material_instance", replacement);
        }
        JsonArray uv = object.has("uv") && object.get("uv").isJsonArray()
                ? object.getAsJsonArray("uv") : null;
        JsonArray uvSize = object.has("uv_size") && object.get("uv_size").isJsonArray()
                ? object.getAsJsonArray("uv_size") : null;
        if (uv != null && uv.size() >= 2) {
            uv.set(0, new JsonPrimitive(uv.get(0).getAsDouble() * scaleU));
            uv.set(1, new JsonPrimitive(uv.get(1).getAsDouble() * scaleV));
        }
        if (uvSize != null && uvSize.size() >= 2) {
            uvSize.set(0, new JsonPrimitive(
                    uvSize.get(0).getAsDouble() * scaleU));
            uvSize.set(1, new JsonPrimitive(
                    uvSize.get(1).getAsDouble() * scaleV));
        }
        object.entrySet().forEach(entry ->
                remapMultipartGeometry(entry.getValue(),
                        materialNames, scaleU, scaleV));
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
            instance.addProperty("render_method", renderMethod(material.source()));
            instance.addProperty("face_dimming", true);
            instance.addProperty("ambient_occlusion", true);
            materials.add(material.name(), instance);
        }
        return materials;
    }

    private void installFallbackBlockTexture(
            JsonObject template, Path texture, String safeId, Path bedrock,
            JsonObject terrainTexture, JsonArray flipbookTextures,
            TextureAnimationConverter animationConverter, String namespace,
            List<String> warnings) throws IOException {
        template.addProperty("unit_cube", true);
        String textureKey = namespace + "." + safeId;
        Path destination = bedrock.resolve("textures/blocks").resolve(safeId + ".png");
        if (!terrainTexture.getAsJsonObject("texture_data").has(textureKey)) {
            TextureAnimationConverter.Animation animation =
                    animationConverter.install(texture, destination, warnings);
            atlasEntry(terrainTexture, textureKey, "textures/blocks/" + safeId);
            if (animation != null)
                flipbookTextures.add(animation.flipbook(
                        textureKey, "textures/blocks/" + safeId));
        }
        JsonObject materials = new JsonObject();
        JsonObject all = new JsonObject();
        all.addProperty("texture", textureKey);
        all.addProperty("render_method", renderMethod(texture));
        materials.add("*", all);
        template.add("material_instances", materials);
    }

    private String stateGeometryId(String safeId, String model) {
        String hash = stableUuid(model).replace("-", "").substring(0, 12);
        return safeId + "_state_" + hash;
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
                                 String resolvedModel, String defaultNamespace) throws IOException {
        for (String reference : item.textures()) {
            Path found = source.findTexture(reference, defaultNamespace);
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
                    Path found = source.findTexture(
                            entry.getValue().getAsString(),
                            model.substring(0, colon));
                    if (found != null) return found;
                }
            }
        }
        return source.findTexture(item.id(), defaultNamespace);
    }

    private void copyMatching(PackSource source, Path bedrock, Predicate<Path> predicate,
                              List<String> warnings) {
        try {
            for (PackSource.AssetFile asset : source.effectiveAssetFiles()) {
                Path sourceFile = asset.path();
                if (!predicate.test(sourceFile)) continue;
                try {
                    String relative = asset.relative().replace('\\', '/');
                    Path target;
                    if (relative.startsWith("textures/gui/")) {
                        target = bedrock.resolve("textures/ui")
                                .resolve(sanitize(asset.namespace()))
                                .resolve(relative.substring("textures/gui/".length()));
                    } else if (relative.startsWith("sounds/")) {
                        target = bedrock.resolve("sounds")
                                .resolve(sanitize(asset.namespace()))
                                .resolve(relative.substring("sounds/".length()));
                    } else {
                        continue;
                    }
                    copy(sourceFile, target);
                } catch (IOException exception) {
                    warnings.add("Could not copy asset " + sourceFile + ": "
                            + exception.getMessage());
                }
            }
        } catch (IOException ex) {
            warnings.add("Could not scan UI/sound assets: " + ex.getMessage());
        }
    }

    private void copyPackIcon(PackSource source, Path bedrock, List<String> warnings) {
        for (String name : List.of("pack.png", "pack_icon.png")) {
            Path icon = source.findPackFile(name);
            if (icon == null) continue;
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
                String relative = overrides.relativize(file).toString()
                        .replace('\\', '/');
                if (isIgnoredOverride(relative)) continue;
                Path target = bedrock.resolve(relative);
                if (Files.isRegularFile(target)
                        && relative.matches("(?i)font/glyph_[0-9a-f]{2}\\.png"))
                    warnings.add("Native override replaced generated glyph page: "
                            + relative);
                copy(file, target);
            }
        }
        warnings.add("Native Bedrock overrides applied");
    }

    private void writeReport(BridgeConfig config, int scannedItemCount, int itemCount,
                             int blockCount, int textureCount,
                             int geometryCount, int equipmentCount, int soundEventCount,
                             int soundFileCount, int glyphCount, int glyphPageCount,
                             int languageCount, int languageEntryCount,
                             int animatedTextureCount, int validatedReferences,
                             int[] effectivePackVersion,
                             JavaPackMetadata javaPackMetadata,
                             List<String> warnings, Path target,
                             GeyserDisplayEntityMappingsWriter.Result displayEntityIntegration)
            throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("generated_at", Instant.now().toString());
        report.addProperty("java_pack", config.javaPack().toString());
        report.add("bedrock_manifest_version", intArray(effectivePackVersion));
        report.addProperty("java_pack_format", javaPackMetadata.description());
        report.addProperty("java_pack_overlays_declared", javaPackMetadata.overlays().size());
        report.addProperty("java_pack_overlays_applied", javaPackMetadata.activeOverlays().size());
        report.addProperty("supported_java_servers", "1.20.5+");
        report.addProperty("geyser_pack", target.toString());
        report.addProperty("oraxen_items_scanned", scannedItemCount);
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
        if (displayEntityIntegration == null) {
            report.addProperty("geyser_display_entity_status", "error");
        } else {
            report.addProperty("geyser_display_entity_status",
                    displayEntityIntegration.status().name().toLowerCase(Locale.ROOT));
            report.addProperty("geyser_display_entity_mappings",
                    displayEntityIntegration.mappings());
            report.addProperty("geyser_display_entity_companion_pack",
                    displayEntityIntegration.companionPackFound());
            if (displayEntityIntegration.written())
                report.addProperty("geyser_display_entity_mapping_file",
                        displayEntityIntegration.file().toString());
        }
        JsonArray warningArray = new JsonArray();
        warnings.forEach(warningArray::add);
        report.add("warnings", warningArray);
        JsonSupport.write(dataDirectory.resolve("last-report.json"), report);
    }

    private List<String> displayEntityFurniture(List<OraxenItem> items) {
        return items.stream().filter(OraxenItem::isFurniture)
                .filter(item -> {
                    String type = Maps.string(
                            mechanicSection(item, "furniture"), "type");
                    return type == null || type.isBlank()
                            || type.trim().equalsIgnoreCase("DISPLAY_ENTITY")
                            || type.trim().replace('-', '_')
                            .equalsIgnoreCase("DISPLAY_ENTITY");
                })
                .map(OraxenItem::id).sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private Set<String> mappedBedrockIdentifiers(JsonObject mappedItems) {
        Set<String> result = new LinkedHashSet<>();
        collectBedrockIdentifiers(mappedItems, result);
        return result;
    }

    private void collectBedrockIdentifiers(JsonElement value, Set<String> output) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child ->
                    collectBedrockIdentifiers(child, output));
            return;
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        JsonElement identifier = object.get("bedrock_identifier");
        if (identifier != null && identifier.isJsonPrimitive()
                && identifier.getAsJsonPrimitive().isString())
            output.add(identifier.getAsString().trim().toLowerCase(Locale.ROOT));
        object.entrySet().forEach(entry ->
                collectBedrockIdentifiers(entry.getValue(), output));
    }

    private void addDisplayEntityDiagnostics(
            GeyserDisplayEntityMappingsWriter.Result integration,
            List<String> furniture, List<String> warnings) {
        integration.diagnostics().forEach(diagnostic ->
                warnings.add("GeyserDisplayEntity: " + diagnostic));
        if (furniture.isEmpty()) return;
        if (!integration.written()) {
            warnings.add("Placed DISPLAY_ENTITY furniture " + furniture
                    + " cannot render for Bedrock with stock Geyser. Install the "
                    + "GeyserDisplayEntity extension and its companion resource pack, "
                    + "or change these Oraxen furniture entries to type ARMOR_STAND");
            return;
        }
        if (integration.mappings() > 0 && !integration.companionPackFound())
            warnings.add("GeyserDisplayEntity mappings were generated for placed furniture "
                    + furniture + ", but its companion GeyserDisplayEntityPack.mcpack "
                    + "was not found in the Geyser packs directory. Install that pack and "
                    + "restart Geyser; otherwise the furniture entities remain invisible");
    }

    private JsonObject textureAtlas(String name, String atlas) {
        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", name);
        root.addProperty("texture_name", atlas);
        root.add("texture_data", new JsonObject());
        return root;
    }

    private List<Path> resolveGuiIconTextures(
            OraxenItem item, JavaItemModelResolver.Result resolved,
            JavaModelConverter modelConverter, String safeId,
            List<String> warnings) {
        if (resolved.guiModels().isEmpty()) return List.of();
        Set<Path> textures = new LinkedHashSet<>();
        for (int index = 0; index < resolved.guiModels().size(); index++) {
            String guiModel = resolved.guiModels().get(index);
            try {
                JavaModelConverter.ConvertedModel converted =
                        modelConverter.convert(
                                guiModel, safeId + "_gui_" + index, true);
                if (converted == null) {
                    warnings.add("GUI model conversion produced no model for '"
                            + item.id() + "' (" + guiModel + ")");
                    continue;
                }
                warnings.addAll(converted.warnings().stream()
                        .map(w -> item.id() + " GUI model: " + w).toList());
                textures.addAll(itemIconTextures(null, converted));
            } catch (IOException | RuntimeException ex) {
                warnings.add("GUI model conversion failed for '" + item.id()
                        + "' (" + guiModel + "): " + ex.getMessage());
            }
        }
        return List.copyOf(textures);
    }

    private PackModelCounts convertPackModelVariants(
            OraxenItem item, OraxenItem mappingItem, String safeId,
            JsonObject templateDefinition,
            PackSource source, JavaItemModelResolver itemModelResolver,
            JavaModelConverter modelConverter,
            EquipmentPreconverter equipmentConverter,
            JsonObject mappedItems, JsonObject itemTexture, Path bedrock,
            TextureAnimationConverter animationConverter, String outputNamespace,
            Set<String> generatedIdentifiers, List<String> warnings) throws IOException {
        int mappings = 0;
        int geometries = 0;
        int equipment = 0;
        for (Map.Entry<String, String> entry : item.packModels().entrySet()) {
            String key = entry.getKey();
            String javaItemModel = ORAXEN_NAMESPACE + ":"
                    + item.id() + "/" + key;
            JavaItemModelResolver.Result resolved =
                    itemModelResolver.resolve(javaItemModel);
            warnings.addAll(resolved.warnings().stream()
                    .map(w -> item.id() + " Pack.models." + key + ": " + w)
                    .toList());
            if (!resolved.definitionFound())
                warnings.add("Item '" + item.id() + "' Pack.models." + key
                        + " has no generated Java item definition '" + javaItemModel
                        + "'; its Bedrock mapping may not match at runtime");

            String primaryModel = resolved.model() == null
                    ? entry.getValue() : resolved.model();
            List<String> baselineModels = new ArrayList<>();
            baselineModels.add(primaryModel);
            resolved.variants().stream()
                    .filter(variant -> variant.predicates().isEmpty())
                    .map(JavaItemModelResolver.Variant::model)
                    .forEach(baselineModels::add);

            String modelSafeId = packModelItemId(safeId, key);
            PackModelCounts baseline = convertPackModelDefinition(
                    item, mappingItem, javaItemModel, modelSafeId,
                    baselineModels, List.of(),
                    templateDefinition, source, modelConverter, equipmentConverter,
                    mappedItems, itemTexture, bedrock, animationConverter,
                    outputNamespace, generatedIdentifiers, warnings);
            mappings += baseline.mappings();
            geometries += baseline.geometries();
            equipment += baseline.equipment();

            Map<String, List<JavaItemModelResolver.Variant>> states =
                    new LinkedHashMap<>();
            resolved.variants().stream()
                    .filter(variant -> !variant.predicates().isEmpty())
                    .forEach(variant -> states.computeIfAbsent(
                            JsonSupport.GSON.toJson(variant.predicates()),
                            ignored -> new ArrayList<>()).add(variant));
            for (List<JavaItemModelResolver.Variant> visuals : states.values()) {
                String stateSafeId = stateItemId(modelSafeId, visuals);
                PackModelCounts state = convertPackModelDefinition(
                        item, mappingItem, javaItemModel, stateSafeId,
                        visuals.stream().map(JavaItemModelResolver.Variant::model)
                                .toList(),
                        visuals.get(0).predicates(), templateDefinition, source,
                        modelConverter, equipmentConverter, mappedItems,
                        itemTexture, bedrock, animationConverter, outputNamespace,
                        generatedIdentifiers, warnings);
                mappings += state.mappings();
                geometries += state.geometries();
                equipment += state.equipment();
            }
        }
        return new PackModelCounts(mappings, geometries, equipment);
    }

    private PackModelCounts convertPackModelDefinition(
            OraxenItem item, OraxenItem mappingItem, String javaItemModel,
            String stateSafeId,
            List<String> visualModels, List<JsonObject> predicates,
            JsonObject templateDefinition, PackSource source,
            JavaModelConverter modelConverter,
            EquipmentPreconverter equipmentConverter,
            JsonObject mappedItems, JsonObject itemTexture, Path bedrock,
            TextureAnimationConverter animationConverter, String outputNamespace,
            Set<String> generatedIdentifiers, List<String> warnings) throws IOException {
        if (visualModels.isEmpty()) return new PackModelCounts(0, 0, 0);
        String bedrockId = outputNamespace + ":" + stateSafeId;
        if (!generatedIdentifiers.add(bedrockId))
            throw new IOException("Generated Pack.models identifier collides: "
                    + bedrockId);

        JavaModelConverter.ConvertedModel convertedModel = null;
        int geometries = 0;
        try {
            boolean forceSprite = visualModels.size() > 1
                    || item.isFurniture()
                    || item.isHat() || item.isCosmeticBackpack()
                    || !Maps.section(item.components(), "equippable").isEmpty();
            List<JavaModelConverter.ConvertedModel> convertedVisuals =
                    new ArrayList<>();
            for (String visualModel : visualModels) {
                JavaModelConverter.ConvertedModel converted =
                        modelConverter.convert(visualModel, stateSafeId, forceSprite);
                if (converted != null) convertedVisuals.add(converted);
            }
            convertedModel = convertedVisuals.size() <= 1
                    ? (convertedVisuals.isEmpty() ? null : convertedVisuals.get(0))
                    : mergeItemVisualModels(
                            convertedVisuals, stateSafeId, outputNamespace);
            if (convertedModel != null) {
                for (JavaModelConverter.ConvertedModel visual : convertedVisuals)
                    warnings.addAll(visual.warnings().stream()
                            .map(w -> item.id() + " Pack.models state '"
                                    + javaItemModel + "': " + w).toList());
                if (convertedModel.geometry() != null) {
                    JsonSupport.write(bedrock.resolve("models")
                                    .resolve(outputNamespace)
                                    .resolve(stateSafeId + ".geo.json"),
                            convertedModel.geometry());
                    geometries++;
                }
            }
        } catch (IOException | RuntimeException ex) {
            warnings.add("Pack.models conversion failed for '" + item.id()
                    + "' (" + javaItemModel + "): " + ex.getMessage());
        }

        JsonObject definition = templateDefinition.deepCopy();
        if (mappingItem != item) {
            definition.remove("components");
            new ItemComponentConverter().apply(mappingItem, definition);
        }
        definition.addProperty("type", "definition");
        definition.remove("custom_model_data");
        definition.remove("predicate");
        definition.remove("predicate_strategy");
        definition.addProperty("model",
                normalizeModel(javaItemModel, javaItemModel));
        definition.addProperty("bedrock_identifier", bedrockId);
        JsonObject options = definition.getAsJsonObject("bedrock_options");
        options.addProperty("icon", bedrockId);
        if (!predicates.isEmpty()) addPredicates(definition, predicates);

        int equipment = 0;
        try {
            equipment = equipmentConverter.preconvert(
                    item, stateSafeId, bedrockId, convertedModel,
                    definition, options, warnings);
        } catch (IOException | RuntimeException ex) {
            warnings.add("Pack.models equipment conversion failed for '"
                    + item.id() + "' (" + javaItemModel + "): "
                    + ex.getMessage());
        }

        Path fallback = source.findTexture(
                visualModels.get(0), ORAXEN_NAMESPACE);
        List<Path> iconTextures = itemIconTextures(fallback, convertedModel);
        if (iconTextures.isEmpty()) {
            warnings.add("Skipped Pack.models mapping for item '" + item.id()
                    + "' (" + javaItemModel
                    + ") because no icon texture was found");
            return new PackModelCounts(0, geometries, equipment);
        }
        if (!installBestItemIcon(iconTextures, convertedModel, true,
                stateSafeId, bedrockId, bedrock,
                itemTexture, animationConverter, warnings)) {
            warnings.add("Skipped Pack.models mapping for item '" + item.id()
                    + "' (" + javaItemModel
                    + ") because its icon could not be decoded");
            return new PackModelCounts(0, geometries, equipment);
        }
        addArrayValue(mappedItems, javaIdentifier(mappingItem.material()), definition);
        return new PackModelCounts(1, geometries, equipment);
    }

    private String packModelItemId(String safeId, String key) {
        String readable = sanitize(key.replace('/', '_'));
        if (readable.length() > 32) readable = readable.substring(0, 32);
        String hash = stableUuid(key).replace("-", "").substring(0, 10);
        return safeId + "_model_" + readable + "_" + hash;
    }

    private List<Path> itemIconTextures(
            Path fallback, JavaModelConverter.ConvertedModel model) {
        if (model != null && model.generatedSprite()
                && !model.materials().isEmpty()) {
            List<Path> layers = model.materials().values().stream()
                    .map(JavaModelConverter.Material::source)
                    .distinct().toList();
            if (!layers.isEmpty()) return layers;
        }
        if (fallback != null) return List.of(fallback);
        if (model != null && !model.materials().isEmpty())
            return List.of(model.materials().values().iterator().next().source());
        return List.of();
    }

    private boolean installBestItemIcon(
            List<Path> textures, JavaModelConverter.ConvertedModel model,
            boolean allowModelThumbnail, String safeId, String bedrockId,
            Path bedrock, JsonObject itemTexture,
            TextureAnimationConverter animationConverter,
            List<String> warnings) throws IOException {
        if (allowModelThumbnail && model != null && model.geometry() != null
                && !model.generatedSprite()) {
            try {
                BufferedImage rendered = new ModelIconRenderer().render(model);
                if (rendered != null) {
                    Path destination = bedrock.resolve("textures/items")
                            .resolve(safeId + ".png");
                    Files.createDirectories(destination.getParent());
                    try (OutputStream output = Files.newOutputStream(destination)) {
                        if (!ImageIO.write(rendered, "png", output))
                            throw new IOException("No PNG writer is available for "
                                    + destination);
                    }
                    registerItemIcon(itemTexture, safeId, bedrockId);
                    return true;
                }
                warnings.add("3D inventory thumbnail for '" + bedrockId
                        + "' was empty; its source texture fallback was used");
            } catch (IOException | RuntimeException exception) {
                warnings.add("Could not render 3D inventory thumbnail for '"
                        + bedrockId + "': " + exception.getMessage()
                        + "; its source texture fallback was used");
            }
        }
        return installItemIcon(textures, safeId, bedrockId, bedrock,
                itemTexture, animationConverter, warnings);
    }

    private boolean installItemIcon(
            List<Path> textures, String safeId, String bedrockId, Path bedrock,
            JsonObject itemTexture, TextureAnimationConverter animationConverter,
            List<String> warnings) throws IOException {
        Path destination = bedrock.resolve("textures/items").resolve(safeId + ".png");
        if (textures.size() == 1) {
            TextureAnimationConverter.Animation animation =
                    animationConverter.install(textures.get(0), destination, warnings);
            // Bedrock's item atlas has no reliable flipbook support. Held and
            // equipped animation is handled by the generated attachable.
            if (animation != null)
                animationConverter.keepFirstFrame(destination, animation);
        } else {
            installLayeredItemIcon(
                    textures, destination, animationConverter, warnings);
        }
        if (!readableImage(destination)) {
            warnings.add("Generated item icon is not a readable PNG for '"
                    + bedrockId + "'");
            Files.deleteIfExists(destination);
            return false;
        }
        registerItemIcon(itemTexture, safeId, bedrockId);
        return true;
    }

    private void registerItemIcon(JsonObject itemTexture, String safeId, String bedrockId) {
        String path = "textures/items/" + safeId;
        String iconKey = bedrockIconKey(bedrockId);
        atlasEntry(itemTexture, iconKey, path);
        // Keep the namespaced alias for Geyser builds and hand-written
        // overrides that use the explicit identifier as their icon shorthand.
        if (!iconKey.equals(bedrockId))
            atlasEntry(itemTexture, bedrockId, path);
    }

    private boolean readableImage(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return ImageIO.read(input) != null;
        } catch (IOException ex) {
            return false;
        }
    }

    private void installLayeredItemIcon(
            List<Path> textures, Path destination,
            TextureAnimationConverter animationConverter,
            List<String> warnings) throws IOException {
        List<Path> temporaryFiles = new ArrayList<>();
        List<IconLayer> layers = new ArrayList<>();
        try {
            for (int index = 0; index < textures.size(); index++) {
                Path temporary = destination.resolveSibling(
                        "." + destination.getFileName() + ".icon-" + index + ".png");
                temporaryFiles.add(temporary);
                TextureAnimationConverter.Animation animation =
                        animationConverter.install(textures.get(index), temporary, warnings);
                BufferedImage strip;
                try (InputStream input = Files.newInputStream(temporary)) {
                    strip = ImageIO.read(input);
                }
                if (strip == null)
                    throw new IOException("Unreadable item icon layer: "
                            + textures.get(index));
                int width = animation == null
                        ? strip.getWidth() : animation.frameWidth();
                int height = animation == null
                        ? strip.getHeight() : animation.frameHeight();
                layers.add(new IconLayer(strip, width, height));
            }
            int width = layers.stream().mapToInt(IconLayer::width).max().orElse(16);
            int height = layers.stream().mapToInt(IconLayer::height).max().orElse(16);
            BufferedImage output =
                    new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (IconLayer layer : layers)
                compositeIconLayer(layer, output, width, height);
            Files.createDirectories(destination.getParent());
            try (OutputStream stream = Files.newOutputStream(destination)) {
                if (!ImageIO.write(output, "png", stream))
                    throw new IOException("No PNG writer is available for " + destination);
            }
        } finally {
            for (Path temporary : temporaryFiles)
                Files.deleteIfExists(temporary);
        }
    }

    private void compositeIconLayer(
            IconLayer layer, BufferedImage target,
            int targetWidth, int targetHeight) {
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(layer.height() - 1,
                    (int) ((long) y * layer.height() / targetHeight));
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(layer.width() - 1,
                        (int) ((long) x * layer.width() / targetWidth));
                int source = layer.image().getRGB(sourceX, sourceY);
                if ((source >>> 24) == 0) continue;
                target.setRGB(x, y,
                        alphaComposite(target.getRGB(x, y), source));
            }
        }
    }

    private int alphaComposite(int destination, int source) {
        int sourceAlpha = source >>> 24;
        if (sourceAlpha == 255) return source;
        int destinationAlpha = destination >>> 24;
        int inverse = 255 - sourceAlpha;
        int outputAlpha = sourceAlpha + destinationAlpha * inverse / 255;
        if (outputAlpha == 0) return 0;
        int denominator = outputAlpha * 255;
        int red = ((source >> 16 & 0xFF) * sourceAlpha * 255
                + (destination >> 16 & 0xFF)
                * destinationAlpha * inverse) / denominator;
        int green = ((source >> 8 & 0xFF) * sourceAlpha * 255
                + (destination >> 8 & 0xFF)
                * destinationAlpha * inverse) / denominator;
        int blue = ((source & 0xFF) * sourceAlpha * 255
                + (destination & 0xFF)
                * destinationAlpha * inverse) / denominator;
        return outputAlpha << 24 | red << 16 | green << 8 | blue;
    }

    private void addPredicates(JsonObject definition, List<JsonObject> predicates) {
        definition.remove("predicate_strategy");
        if (predicates.size() == 1) {
            definition.add("predicate", predicates.get(0).deepCopy());
        } else {
            JsonArray values = new JsonArray();
            predicates.forEach(value -> values.add(value.deepCopy()));
            definition.add("predicate", values);
            definition.addProperty("predicate_strategy", "and");
        }
    }

    private boolean matchesMaterialAppearance(
            OraxenItem item, JavaItemModelResolver.Variant variant) {
        String expected = ORAXEN_NAMESPACE + ":" + item.id();
        for (JsonObject predicate : variant.predicates()) {
            if (!predicate.has("type") || !predicate.has("property")
                    || !predicate.has("value")) continue;
            if (!predicate.get("type").getAsString()
                    .equalsIgnoreCase("match")
                    || !predicate.get("property").getAsString()
                    .equalsIgnoreCase("custom_model_data"))
                continue;
            if (predicate.get("value").getAsString()
                    .equalsIgnoreCase(expected))
                return true;
        }
        String candidate = normalizeModel(variant.model(), "");
        if (candidate.isBlank()) return false;
        for (String configured : List.of(
                normalizeModel(modelReference(item), ""),
                normalizeModel(item.model(), ""),
                ORAXEN_NAMESPACE + ":item/" + item.id(),
                ORAXEN_NAMESPACE + ":block/" + item.id(),
                ORAXEN_NAMESPACE + ":" + item.id())) {
            if (!configured.isBlank()
                    && candidate.equalsIgnoreCase(configured))
                return true;
        }
        return false;
    }

    private Map<String, List<JavaItemModelResolver.Variant>>
            obfuscatedMaterialAppearances(
            OraxenItem item, JavaItemModelResolver.Result materialDefinition,
            JavaModelConverter modelConverter, Path oraxenDirectory) {
        List<Path> originalTextures =
                originalItemTextures(item, oraxenDirectory);
        if (originalTextures.isEmpty()) return Map.of();

        Map<String, List<JavaItemModelResolver.Variant>> matches =
                new LinkedHashMap<>();
        for (JavaItemModelResolver.Variant variant
                : materialDefinition.variants()) {
            if (!hasCustomModelDataPredicate(variant)) continue;
            try {
                JavaModelConverter.ConvertedModel converted =
                        modelConverter.convert(
                                variant.model(), "appearance_probe", false);
                if (converted == null || converted.materials().isEmpty())
                    continue;
                List<Path> generatedTextures = converted.materials().values()
                        .stream().map(JavaModelConverter.Material::source)
                        .distinct().toList();
                boolean allMatch = originalTextures.stream().allMatch(
                        original -> generatedTextures.stream().anyMatch(
                                generated -> sameImage(original, generated)));
                if (!allMatch) continue;
                matches.computeIfAbsent(
                        JsonSupport.GSON.toJson(variant.predicates()),
                        ignored -> new ArrayList<>()).add(variant);
            } catch (IOException | RuntimeException ignored) {
                // This is a conservative recovery path. A failed probe must
                // never turn into a guessed custom_model_data predicate.
            }
        }
        return matches;
    }

    private boolean hasCustomModelDataPredicate(
            JavaItemModelResolver.Variant variant) {
        for (JsonObject predicate : variant.predicates()) {
            if (!predicate.has("property")
                    || !predicate.get("property").isJsonPrimitive())
                continue;
            if (predicate.get("property").getAsString()
                    .equalsIgnoreCase("custom_model_data"))
                return true;
        }
        return false;
    }

    private List<Path> originalItemTextures(
            OraxenItem item, Path oraxenDirectory) {
        Path pack = oraxenDirectory.resolve("pack").normalize();
        Set<Path> result = new LinkedHashSet<>();
        for (String reference : item.textures()) {
            String clean = reference.replace('\\', '/')
                    .replaceFirst("(?i)\\.png$", "");
            String namespace = ORAXEN_NAMESPACE;
            String path = clean;
            if (clean.startsWith("assets/")) {
                String asset = clean.substring("assets/".length());
                int slash = asset.indexOf('/');
                if (slash <= 0 || slash == asset.length() - 1) continue;
                namespace = asset.substring(0, slash);
                path = asset.substring(slash + 1)
                        .replaceFirst("^textures/", "");
            } else {
                int colon = clean.indexOf(':');
                if (colon >= 0) {
                    namespace = clean.substring(0, colon);
                    path = clean.substring(colon + 1);
                }
                path = path.replaceFirst("^textures/", "");
            }
            if (!namespace.matches("[a-z0-9_.-]+")
                    || path.isBlank() || path.startsWith("/")
                    || Arrays.asList(path.split("/")).contains(".."))
                continue;

            List<Path> candidates = new ArrayList<>();
            candidates.add(pack.resolve("assets").resolve(namespace)
                    .resolve("textures").resolve(path + ".png").normalize());
            if (namespace.equals(ORAXEN_NAMESPACE))
                candidates.add(pack.resolve("textures")
                        .resolve(path + ".png").normalize());
            for (Path candidate : candidates)
                if (candidate.startsWith(pack)
                        && Files.isRegularFile(candidate))
                    result.add(candidate);
        }
        return List.copyOf(result);
    }

    private boolean sameImage(Path left, Path right) {
        try (InputStream leftInput = Files.newInputStream(left);
             InputStream rightInput = Files.newInputStream(right)) {
            BufferedImage leftImage = ImageIO.read(leftInput);
            BufferedImage rightImage = ImageIO.read(rightInput);
            if (leftImage == null || rightImage == null
                    || leftImage.getWidth() != rightImage.getWidth()
                    || leftImage.getHeight() != rightImage.getHeight())
                return false;
            int width = leftImage.getWidth();
            int height = leftImage.getHeight();
            return Arrays.equals(
                    leftImage.getRGB(0, 0, width, height,
                            null, 0, width),
                    rightImage.getRGB(0, 0, width, height,
                            null, 0, width));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private String stateItemId(
            String safeId, List<JavaItemModelResolver.Variant> visuals) {
        String seed = visuals.stream()
                .map(JavaItemModelResolver.Variant::model)
                .reduce((left, right) -> left + "\n" + right).orElse("")
                + "\n" + JsonSupport.GSON.toJson(
                visuals.isEmpty() ? List.of() : visuals.get(0).predicates());
        return safeId + "_state_"
                + stableUuid(seed).replace("-", "").substring(0, 12);
    }

    private void atlasEntry(JsonObject atlas, String key, String path) {
        JsonObject value = new JsonObject();
        JsonArray textures = new JsonArray();
        textures.add(path);
        value.add("textures", textures);
        atlas.getAsJsonObject("texture_data").add(key, value);
    }

    private String bedrockIconKey(String identifier) {
        return identifier.replace(':', '.').replace('/', '_');
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

    private String renderMethod(Path texture) {
        try (InputStream input = Files.newInputStream(texture)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) return "alpha_test";
            if (!image.getColorModel().hasAlpha()) return "opaque";
            boolean transparent = false;
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                    switch (image.getRGB(x, y) >>> 24) {
                        case 0xFF -> {
                        }
                        case 0 -> transparent = true;
                        default -> {
                            // Binary cut-outs (foliage, doors, sprites) need
                            // alpha testing, while glass and soft pixels must
                            // retain their partial alpha through blending.
                            return "blend";
                        }
                    }
            return transparent ? "alpha_test" : "opaque";
        } catch (IOException exception) {
            return "alpha_test";
        }
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

    private void ensureMeaningfulPack(Path bedrock, JsonObject itemMappings,
                                      JsonObject blockMappings,
                                      BridgeConfig config,
                                      List<OraxenItem> items,
                                      List<String> warnings) throws IOException {
        long expectedItems = items.stream().filter(this::expectsCustomMapping).count();
        JsonObject mappedItems = itemMappings.getAsJsonObject("items");
        if (config.convertItems() && expectedItems > 0
                && (mappedItems == null || mappedItems.isEmpty())) {
            String diagnostic = warnings.stream()
                    .filter(warning -> warning.contains("Skipped custom mapping"))
                    .findFirst().orElse(warnings.isEmpty() ? "" : warnings.get(0));
            throw new IOException("No custom Oraxen item could be mapped from "
                    + expectedItems + " configured pack item(s); the previous pack was kept"
                    + (diagnostic.isBlank() ? "." : ". First diagnostic: " + diagnostic));
        }

        JsonObject mappedBlocks = blockMappings.getAsJsonObject("blocks");
        boolean mappings = mappedItems != null && !mappedItems.isEmpty()
                || mappedBlocks != null && !mappedBlocks.isEmpty();
        try (Stream<Path> paths = Files.walk(bedrock)) {
            boolean resources = paths.filter(Files::isRegularFile)
                    .anyMatch(path -> isMeaningfulResource(bedrock, path));
            if (!(mappings || resources))
                throw new IOException("Conversion produced no usable Bedrock assets; "
                        + "the previous pack was kept. Check the server log, Oraxen item "
                        + "YAML, and the Java pack layout");
        }
    }

    private boolean expectsCustomMapping(OraxenItem item) {
        return item.model() != null || item.itemModel() != null
                || item.customModelData() != null || item.parentModel() != null
                || !item.textures().isEmpty() || !item.packModels().isEmpty()
                || item.isBlock() || item.isFurniture() || item.isHat()
                || item.isCosmeticBackpack()
                || !Maps.section(item.components(), "equippable").isEmpty();
    }

    private boolean isMeaningfulResource(Path bedrock, Path file) {
        String relative = bedrock.relativize(file).toString().replace('\\', '/');
        if (isIgnoredOverride(relative)
                || relative.equals("manifest.json")
                || relative.equals("pack_icon.png")) return false;
        if (relative.equals("textures/item_texture.json")
                || relative.equals("textures/terrain_texture.json")) {
            try {
                JsonObject atlas = JsonSupport.readObject(file);
                JsonObject entries = atlas.getAsJsonObject("texture_data");
                return entries != null && !entries.isEmpty();
            } catch (IOException | RuntimeException ignored) {
                return false;
            }
        }
        int slash = relative.indexOf('/');
        String root = slash < 0 ? relative : relative.substring(0, slash);
        return Set.of("animation_controllers", "animations", "attachables",
                "entity", "fogs", "font", "materials", "models", "particles",
                "render_controllers", "sounds", "texts", "textures", "ui")
                .contains(root)
                || Set.of("biomes_client.json", "blocks.json", "sounds.json")
                .contains(relative);
    }

    private static boolean isIgnoredOverride(String relative) {
        String normalized = relative.replace('\\', '/');
        return Arrays.stream(normalized.split("/"))
                .anyMatch(segment -> segment.startsWith(".")
                        || segment.equalsIgnoreCase("Thumbs.db")
                        || segment.equalsIgnoreCase("desktop.ini")
                        || segment.equalsIgnoreCase("__MACOSX"));
    }

    private void atomicInstall(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
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

    private int countPngFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return Math.toIntExact(paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".png"))
                    .count());
        }
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
        String normalized = model.replace('\\', '/')
                .replaceFirst("(?i)\\.json$", "");
        if (normalized.startsWith("assets/")) {
            String asset = normalized.substring("assets/".length());
            int slash = asset.indexOf('/');
            if (slash > 0 && slash < asset.length() - 1)
                return asset.substring(0, slash) + ":"
                        + stripModelRoot(asset.substring(slash + 1));
        }
        int colon = normalized.indexOf(':');
        if (colon >= 0)
            return normalized.substring(0, colon) + ":"
                    + stripModelRoot(normalized.substring(colon + 1));
        return "oraxen:" + stripModelRoot(normalized);
    }

    private static String stripModelRoot(String value) {
        return value.replaceFirst("^models/", "")
                .replaceFirst("^items/", "");
    }

    private static String sanitize(String value) {
        String result = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return result.isBlank() ? "unnamed" : result;
    }

    private static String plainText(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", "").replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }

    private static String javaIdentifier(String material) {
        String value = material == null ? "paper"
                : material.toLowerCase(Locale.ROOT).replace('\\', '/');
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private record IconLayer(BufferedImage image, int width, int height) {}
    private record PackModelCounts(int mappings, int geometries, int equipment) {}
    private record MultipartPart(BlockVariant variant,
                                 JavaModelConverter.ConvertedModel model,
                                 int textureWidth, int textureHeight) {}
    private record BlockVariant(String model, String state, int xRotation,
                                int yRotation, int weight, boolean uvlock,
                                boolean multipart) {}
}
