package dev.oraxenbedrock.conversion;

import com.google.gson.JsonObject;
import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.model.ConversionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModernItemModelRegressionTest {
    @TempDir Path temp;

    @Test
    void retainsAllBaselineCompositeLayersInDeclarationOrder() throws Exception {
        Path pack = resourcePack(Map.of(
                "baseline_composite", """
                        {"model":{"type":"minecraft:composite","models":[
                          {"type":"minecraft:model","model":"oraxen:item/base"},
                          {"type":"minecraft:model","model":"oraxen:item/glint"},
                          {"type":"minecraft:model","model":"oraxen:item/emblem"}
                        ]}}
                        """));

        try (PackSource source = PackSource.open(pack)) {
            JavaItemModelResolver.Result result =
                    new JavaItemModelResolver(source, "oraxen")
                            .resolve("oraxen:baseline_composite");

            assertTrue(result.definitionFound());
            assertEquals("oraxen:item/base", result.model());
            assertEquals(List.of("oraxen:item/glint", "oraxen:item/emblem"),
                    result.variants().stream()
                            .map(JavaItemModelResolver.Variant::model).toList());
            assertTrue(result.variants().stream()
                    .allMatch(variant -> variant.predicates().isEmpty()));
        }
    }

    @Test
    void retainsSharedBaselineInEveryConditionalCompositeState() throws Exception {
        Path pack = resourcePack(Map.of(
                "conditional_composite", """
                        {"model":{"type":"minecraft:condition",
                          "property":"minecraft:damaged",
                          "on_false":{"type":"minecraft:composite","models":[
                            {"type":"minecraft:model","model":"oraxen:item/shared"},
                            {"type":"minecraft:model","model":"oraxen:item/intact"}
                          ]},
                          "on_true":{"type":"minecraft:composite","models":[
                            {"type":"minecraft:model","model":"oraxen:item/shared"},
                            {"type":"minecraft:model","model":"oraxen:item/damaged"}
                          ]}
                        }}
                        """));

        try (PackSource source = PackSource.open(pack)) {
            JavaItemModelResolver.Result result =
                    new JavaItemModelResolver(source, "oraxen")
                            .resolve("oraxen:conditional_composite");

            assertEquals("oraxen:item/shared", result.model());
            assertEquals(4, result.variants().size());
            assertEquals(List.of("oraxen:item/shared", "oraxen:item/intact"),
                    modelsForExpected(result, false));
            assertEquals(List.of("oraxen:item/shared", "oraxen:item/damaged"),
                    modelsForExpected(result, true));
            result.variants().forEach(variant -> {
                assertEquals(1, variant.predicates().size());
                JsonObject predicate = variant.predicates().get(0);
                assertEquals("condition", predicate.get("type").getAsString());
                assertEquals("damaged", predicate.get("property").getAsString());
            });
        }
    }

    @Test
    void resolvesSpecialBaseFromBothStringAndModelObject() throws Exception {
        Path pack = resourcePack(Map.of(
                "string_special", """
                        {"model":{"type":"minecraft:special",
                          "base":"oraxen:item/string_base",
                          "model":{"type":"minecraft:shield"}}}
                        """,
                "object_special", """
                        {"model":{"type":"minecraft:special",
                          "base":{"type":"minecraft:model",
                            "model":"oraxen:item/object_base"},
                          "model":{"type":"minecraft:shield"}}}
                        """));

        try (PackSource source = PackSource.open(pack)) {
            JavaItemModelResolver resolver =
                    new JavaItemModelResolver(source, "oraxen");
            JavaItemModelResolver.Result stringResult =
                    resolver.resolve("oraxen:string_special");
            JavaItemModelResolver.Result objectResult =
                    resolver.resolve("oraxen:object_special");

            assertEquals("oraxen:item/string_base", stringResult.model());
            assertTrue(stringResult.variants().isEmpty());
            assertEquals("oraxen:item/object_base", objectResult.model());
            assertTrue(objectResult.variants().isEmpty());
        }
    }

    @Test
    void convertsCanonicalBundleFullnessProperty() throws Exception {
        Path pack = resourcePack(Map.of(
                "bundle", """
                        {"model":{"type":"minecraft:range_dispatch",
                          "property":"minecraft:bundle/fullness",
                          "fallback":{"type":"minecraft:model",
                            "model":"oraxen:item/bundle_empty"},
                          "entries":[{"threshold":0.5,
                            "model":{"type":"minecraft:model",
                              "model":"assets/oraxen/models/item/bundle_half.json"}}]
                        }}
                        """));

        try (PackSource source = PackSource.open(pack)) {
            JavaItemModelResolver.Result result =
                    new JavaItemModelResolver(source, "oraxen")
                            .resolve("assets/oraxen/items/bundle.json");

            assertEquals("oraxen:item/bundle_empty", result.model());
            assertEquals(1, result.variants().size());
            JsonObject predicate = result.variants().get(0).predicates().get(0);
            assertEquals("range_dispatch", predicate.get("type").getAsString());
            assertEquals("bundle_fullness",
                    predicate.get("property").getAsString());
            assertEquals(0.5, predicate.get("threshold").getAsDouble());
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void modernItemDefinitionOverridesStalePackModel() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/modern.yml"), """
                current_item:
                  displayname: "Current Item"
                  material: PAPER
                  Pack:
                    model: item/stale
                    textures: [stale]
                  Components:
                    item_model: oraxen:current_item
                """);

        Path javaPack = temp.resolve("authoritative-modern-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Modern precedence"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/current_item.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"assets/oraxen/models/item/current.json"}}
                """);
        write(javaPack.resolve("assets/oraxen/models/item/stale.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/stale"}}
                """);
        write(javaPack.resolve("assets/oraxen/models/item/current.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/current"}}
                """);
        writePng(javaPack.resolve("assets/oraxen/textures/item/stale.png"),
                0xFFFF0000);
        writePng(javaPack.resolve("assets/oraxen/textures/item/current.png"),
                0xFF00FF00);

        BridgeConfig config = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"), javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 8, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result =
                new PackConverter(temp.resolve("plugins/OraxenBedrock"))
                        .convert(config);
        try (FileSystem pack = FileSystems.newFileSystem(result.pack());
             InputStream input = Files.newInputStream(
                     pack.getPath("/textures/items/current_item.png"))) {
            BufferedImage icon = ImageIO.read(input);
            assertNotNull(icon);
            assertEquals(0xFF00FF00, icon.getRGB(8, 8),
                    "The modern item definition must select the visible model");
        }
    }

    @Test
    void compositesGeneratedIconLayersBackToFrontWithSourceAlpha() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Files.writeString(oraxen.resolve("items/layered.yml"), """
                layered_icon:
                  displayname: "Layered Icon"
                  material: PAPER
                  Components:
                    item_model: oraxen:layered_icon
                """);

        Path javaPack = temp.resolve("java-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Layered icon regression"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/layered_icon.json"), """
                {"model":{"type":"minecraft:composite","models":[
                  {"type":"minecraft:model","model":"oraxen:item/icon_base"},
                  {"type":"minecraft:model","model":"oraxen:item/icon_overlay"}
                ]}}
                """);
        write(javaPack.resolve("assets/oraxen/models/item/icon_base.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/icon_base"}}
                """);
        write(javaPack.resolve("assets/oraxen/models/item/icon_overlay.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/icon_overlay"}}
                """);
        writePng(javaPack.resolve("assets/oraxen/textures/item/icon_base.png"),
                0xFFFF0000);
        writePng(javaPack.resolve("assets/oraxen/textures/item/icon_overlay.png"),
                0x8000FF00);

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 7, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);

        JsonObject definitions = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items");
        assertEquals(1, definitions.getAsJsonArray("minecraft:paper").size(),
                "An unconditional composite must remain one mapped item");
        try (FileSystem pack = FileSystems.newFileSystem(result.pack());
             InputStream input = Files.newInputStream(
                     pack.getPath("/textures/items/layered_icon.png"))) {
            BufferedImage icon = ImageIO.read(input);
            assertNotNull(icon);
            assertEquals(0xFF7F8000, icon.getRGB(8, 8),
                    "layer0 must be the back layer and layer1 must blend over it");
        }
    }

    @Test
    void convertsEveryPackModelsFurnitureStateIntoItsOwnGeyserDefinition()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/furniture.yml"), """
                growing_table:
                  displayname: "Growing Table"
                  material: PAPER
                  Pack:
                    model: furniture/table_base
                    models:
                      active: furniture/table_active
                      stage0: furniture/table_stage0
                  Components:
                    item_model: oraxen:growing_table
                  Mechanics:
                    furniture:
                      type: DISPLAY_ENTITY
                      stages:
                        - model: stage0
                """);

        Path javaPack = temp.resolve("pack-models-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Pack.models regression"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/growing_table.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:item/table_base"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/growing_table/active.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:item/table_active"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/growing_table/stage0.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:item/table_stage0"}}
                """);
        for (String model : List.of("table_base", "table_active", "table_stage0"))
            write(javaPack.resolve("assets/oraxen/models/item/" + model + ".json"),
                    """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"oraxen:item/%s"}}
                    """.formatted(model));
        writePng(javaPack.resolve("assets/oraxen/textures/item/table_base.png"),
                0xFF8B5A2B);
        writePng(javaPack.resolve("assets/oraxen/textures/item/table_active.png"),
                0xFFFFAA00);
        writePng(javaPack.resolve("assets/oraxen/textures/item/table_stage0.png"),
                0xFF33AA55);

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 9, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        var definitions = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper");
        assertEquals(3, definitions.size(),
                "Base plus both Pack.models entries must be mapped");
        Map<String, String> identifiers = new java.util.LinkedHashMap<>();
        definitions.forEach(value -> {
            JsonObject definition = value.getAsJsonObject();
            assertEquals("definition", definition.get("type").getAsString());
            identifiers.put(definition.get("model").getAsString(),
                    definition.get("bedrock_identifier").getAsString());
        });
        assertEquals(Set.of(
                        "oraxen:growing_table",
                        "oraxen:growing_table/active",
                        "oraxen:growing_table/stage0"),
                identifiers.keySet());
        assertEquals(3, new java.util.HashSet<>(identifiers.values()).size());

        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            for (String bedrockIdentifier : identifiers.values()) {
                String pathId = bedrockIdentifier.substring(
                        bedrockIdentifier.indexOf(':') + 1);
                assertTrue(Files.isRegularFile(pack.getPath(
                        "/textures/items/" + pathId + ".png")));
                assertTrue(Files.isRegularFile(pack.getPath(
                        "/attachables/oraxen/" + pathId + ".attachable.json")));
                assertTrue(Files.isRegularFile(pack.getPath(
                        "/models/oraxen/" + pathId + ".attachable.geo.json")),
                        "Generated 2D furniture states need plane geometry");
            }
        }
        assertFalse(result.warnings().stream()
                .anyMatch(warning -> warning.contains("Pack.models")
                        && warning.contains("no generated Java item definition")));
    }

    @Test
    void usesGuiModelForSwordIconAndFallbackModelForHeldAttachable()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/sword.yml"), """
                context_sword:
                  displayname: "Context Sword"
                  material: DIAMOND_SWORD
                  Pack:
                    model: item/context_held
                    gui_model: item/context_gui
                  Components:
                    item_model: oraxen:context_sword
                """);

        Path javaPack = temp.resolve("display-context-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"GUI model regression"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/context_sword.json"), """
                {"model":{"type":"minecraft:select",
                  "property":"minecraft:display_context",
                  "cases":[{"when":["gui"],
                    "model":{"type":"minecraft:model",
                      "model":"oraxen:item/context_gui"}}],
                  "fallback":{"type":"minecraft:model",
                    "model":"oraxen:item/context_held"}}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/models/item/context_gui.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/context_gui"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/models/item/context_held.json"), """
                {"textures":{"layer0":"oraxen:item/context_held"},
                 "elements":[{"from":[6,0,7],"to":[10,16,9],
                   "faces":{
                     "north":{"texture":"#layer0","uv":[0,0,16,16]},
                     "south":{"texture":"#layer0","uv":[0,0,16,16]}
                   }}]}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/item/context_gui.png"), 0xFF00CC44);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/item/context_held.png"), 0xFFCC2200);

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 10, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject definition = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond_sword")
                .get(0).getAsJsonObject();
        assertEquals("oraxen:context_sword",
                definition.get("model").getAsString());
        assertTrue(definition.getAsJsonObject("bedrock_options")
                .get("display_handheld").getAsBoolean());

        try (FileSystem pack = FileSystems.newFileSystem(result.pack());
             InputStream iconInput = Files.newInputStream(
                     pack.getPath("/textures/items/context_sword.png"));
             InputStream heldInput = Files.newInputStream(
                     pack.getPath(
                             "/textures/entity/oraxen/context_sword.png"))) {
            BufferedImage icon = ImageIO.read(iconInput);
            BufferedImage held = ImageIO.read(heldInput);
            assertNotNull(icon);
            assertNotNull(held);
            assertEquals(0xFF00CC44, icon.getRGB(8, 8),
                    "Bedrock inventory must use Pack.gui_model");
            assertEquals(0xFFCC2200, held.getRGB(8, 8),
                    "Held attachable must keep the fallback 3D sword model");
        }
    }

    @Test
    void discoversOraxenModelDataIdsInsideTheVanillaMaterialDefinition()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/model-data-id.yml"), """
                id_sword:
                  displayname: "ID Sword"
                  material: DIAMOND_SWORD
                  Pack:
                    model: item/id_sword
                """);

        Path javaPack = temp.resolve("model-data-ids-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"model_data_ids regression"}}
                """);
        write(javaPack.resolve(
                "assets/minecraft/items/diamond_sword.json"), """
                {"model":{"type":"minecraft:select",
                  "property":"minecraft:custom_model_data",
                  "index":0,
                  "cases":[
                    {"when":"oraxen:other_item",
                     "model":{"type":"minecraft:model",
                       "model":"oraxen:item/other_item"}},
                    {"when":"oraxen:id_sword",
                     "model":{"type":"minecraft:model",
                       "model":"oraxen:item/id_sword"}}
                  ],
                  "fallback":{"type":"minecraft:model",
                    "model":"minecraft:item/diamond_sword"}}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/models/item/id_sword.json"), """
                {"parent":"minecraft:item/handheld",
                 "textures":{"layer0":"oraxen:item/id_sword"}}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/item/id_sword.png"), 0xFF6677EE);

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 11, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject definition = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond_sword")
                .get(0).getAsJsonObject();
        assertEquals("definition", definition.get("type").getAsString());
        assertEquals("minecraft:diamond_sword",
                definition.get("model").getAsString());
        JsonObject predicate = definition.getAsJsonObject("predicate");
        assertEquals("match", predicate.get("type").getAsString());
        assertEquals("custom_model_data",
                predicate.get("property").getAsString());
        assertEquals("oraxen:id_sword",
                predicate.get("value").getAsString());
        assertEquals(0, predicate.get("index").getAsInt());
        assertFalse(result.warnings().stream().anyMatch(warning ->
                warning.contains("no generated Java item definition")));
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/items/id_sword.png")));
        }
    }

    @Test
    void discoversModelDataFloatWithoutAConfiguredCustomModelData()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/model-data-float.yml"), """
                float_relic:
                  displayname: "Float Relic"
                  material: PAPER
                  Pack:
                    model: item/float_relic
                """);

        Path javaPack = temp.resolve("model-data-float-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,
                  "description":"model_data_float regression"}}
                """);
        write(javaPack.resolve("assets/minecraft/items/paper.json"), """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "index":0,
                  "fallback":{"type":"minecraft:model",
                    "model":"minecraft:item/paper"},
                  "entries":[
                    {"threshold":734,
                     "model":{"type":"minecraft:model",
                       "model":"oraxen:item/float_relic"}}
                  ]}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/models/item/float_relic.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/float_relic"}}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/item/float_relic.png"),
                0xFFCC8844);

        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"),
                javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 14, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject definition = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper")
                .get(0).getAsJsonObject();
        assertEquals("definition", definition.get("type").getAsString());
        assertEquals("minecraft:paper",
                definition.get("model").getAsString());
        JsonObject predicate = definition.getAsJsonObject("predicate");
        assertEquals("range_dispatch",
                predicate.get("type").getAsString());
        assertEquals("custom_model_data",
                predicate.get("property").getAsString());
        assertEquals(734, predicate.get("threshold").getAsInt());
        assertEquals(0, predicate.get("index").getAsInt());
        assertFalse(result.warnings().stream().anyMatch(warning ->
                warning.contains("no generated Java item definition")));
    }

    @Test
    void discoversFullyObfuscatedModelDataFloatFromTheOriginalTexture()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/obfuscated-float.yml"), """
                obfuscated_float:
                  displayname: "Obfuscated Float"
                  material: PAPER
                  Pack:
                    model: item/readable_float
                    textures:
                      - default/readable_float
                """);
        writePng(oraxen.resolve(
                "pack/textures/default/readable_float.png"),
                0xFF35AADD);

        Path javaPack = temp.resolve("obfuscated-float-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,
                  "description":"Obfuscated model_data_float regression"}}
                """);
        write(javaPack.resolve("assets/minecraft/items/paper.json"), """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "index":0,
                  "fallback":{"type":"minecraft:model",
                    "model":"minecraft:item/paper"},
                  "entries":[
                    {"threshold":901,
                     "model":{"type":"minecraft:model",
                       "model":"oraxen:x/fm1"}}
                  ]}}
                """);
        write(javaPack.resolve("assets/oraxen/models/x/fm1.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:x/ft1"}}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/x/ft1.png"), 0xFF35AADD);

        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"),
                javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 15, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject definition = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper")
                .get(0).getAsJsonObject();
        JsonObject predicate = definition.getAsJsonObject("predicate");
        assertEquals("minecraft:paper",
                definition.get("model").getAsString());
        assertEquals(901, predicate.get("threshold").getAsInt());
        assertEquals("custom_model_data",
                predicate.get("property").getAsString());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("obfuscated_float")
                        && warning.contains("recovered")));
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/items/obfuscated_float.png")));
        }
    }

    @Test
    void refusesToGuessAnAmbiguousObfuscatedModelDataFloat()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/ambiguous-float.yml"), """
                ambiguous_float:
                  displayname: "Ambiguous Float"
                  material: PAPER
                  Pack:
                    model: item/readable_ambiguous
                    textures:
                      - default/shared_float
                """);
        writePng(oraxen.resolve(
                "pack/textures/default/shared_float.png"),
                0xFFAA7733);

        Path javaPack = temp.resolve("ambiguous-float-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,
                  "description":"Ambiguous obfuscation regression"}}
                """);
        write(javaPack.resolve("assets/minecraft/items/paper.json"), """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "index":0,
                  "fallback":{"type":"minecraft:model",
                    "model":"minecraft:item/paper"},
                  "entries":[
                    {"threshold":100,
                     "model":{"type":"minecraft:model",
                       "model":"oraxen:x/a1"}},
                    {"threshold":200,
                     "model":{"type":"minecraft:model",
                       "model":"oraxen:x/a2"}}
                  ]}}
                """);
        for (String model : List.of("a1", "a2"))
            write(javaPack.resolve(
                    "assets/oraxen/models/x/" + model + ".json"), """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"oraxen:x/shared"}}
                    """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/x/shared.png"), 0xFFAA7733);

        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"),
                javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 16, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject mapped = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items");
        assertFalse(mapped.has("minecraft:paper"),
                "An ambiguous numeric CMD must not be assigned arbitrarily");
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("ambiguous_float")
                        && warning.contains("multiple obfuscated")));
    }

    @Test
    void treatsPaperItemWithHandheldModelParentAsAWeapon()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/handheld-paper.yml"), """
                handheld_relic:
                  displayname: "Handheld Relic"
                  material: PAPER
                  Pack:
                    model: item/handheld_relic
                  Components:
                    item_model: oraxen:handheld_relic
                    max_stack_size: 16
                """);

        Path javaPack = temp.resolve("handheld-paper-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,
                  "description":"Handheld parent regression"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/handheld_relic.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:item/handheld_relic"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/models/item/handheld_relic.json"), """
                {"parent":"minecraft:item/handheld",
                 "textures":{"layer0":"oraxen:item/handheld_relic"}}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/item/handheld_relic.png"),
                0xFF8855CC);

        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"),
                javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 14, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject definition = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper")
                .get(0).getAsJsonObject();
        JsonObject options = definition.getAsJsonObject("bedrock_options");
        assertTrue(options.get("display_handheld").getAsBoolean());
        assertEquals("equipment",
                options.get("creative_category").getAsString());
        assertEquals(16, definition.getAsJsonObject("components")
                .get("minecraft:max_stack_size").getAsInt(),
                "An explicitly configured stack size must not be overwritten");
    }

    @Test
    void resolvesObfuscatedBlockstateThroughTheEffectiveItemModel()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/obfuscated-block.yml"), """
                obfuscated_block:
                  displayname: "Obfuscated Block"
                  material: PAPER
                  Pack:
                    model: blocks/readable_name
                  Components:
                    item_model: oraxen:obfuscated_block
                  Mechanics:
                    noteblock:
                      model: blocks/readable_name
                """);

        Path javaPack = temp.resolve("obfuscated-block-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Obfuscation regression"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/obfuscated_block.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:x/a1b2"}}
                """);
        write(javaPack.resolve("assets/oraxen/models/x/a1b2.json"), """
                {"parent":"minecraft:block/cube_all",
                 "textures":{"all":"oraxen:x/c3d4"}}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/x/c3d4.png"), 0xFF775533);
        write(javaPack.resolve(
                "assets/minecraft/blockstates/note_block.json"), """
                {"variants":{
                  "instrument=harp,note=0,powered=false":{
                    "model":"oraxen:x/a1b2"
                  }
                }}
                """);

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 12, 0},
                true, true, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject blockMappings = JsonSupport.readObject(
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
        JsonObject state = blockMappings.getAsJsonObject("blocks")
                .getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides")
                .getAsJsonObject(
                        "instrument=harp,note=0,powered=false");
        assertNotNull(state);
        assertTrue(state.has("geometry"));
        assertEquals(1, result.blocks());
        assertFalse(result.warnings().stream().anyMatch(warning ->
                warning.contains("no matching generated Java blockstate")));
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            String geometry = state.get("geometry").getAsString();
            String fileId = geometry.substring(
                    "geometry.oraxen.".length());
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/models/oraxen/" + fileId + ".geo.json")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/items/obfuscated_block.png")));
        }
    }

    @Test
    void convertsAllStackableStringBlockLevelsEvenWhenModelsAreObfuscated()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/stackable-stringblock.yml"), """
                pink_petals:
                  displayname: "Pink Petals"
                  material: PAPER
                  Pack:
                    model: default/pink_petals_1
                  Components:
                    item_model: oraxen:pink_petals
                  Mechanics:
                    stringblock:
                      custom_variation: 40
                      model: default/pink_petals_1
                      stackable:
                        - custom_variation: 41
                          model: default/pink_petals_2
                        - custom_variation: 42
                          model: default/pink_petals_3
                """);

        Path javaPack = temp.resolve("stackable-stringblock-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,
                  "description":"Stackable StringBlock regression"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/pink_petals.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:x/p1"}}
                """);
        for (int stage = 1; stage <= 3; stage++) {
            write(javaPack.resolve(
                    "assets/oraxen/models/x/p" + stage + ".json"), """
                    {"parent":"minecraft:block/cube_all",
                     "textures":{"all":"oraxen:x/t%s"}}
                    """.formatted(stage));
            writePng(javaPack.resolve(
                    "assets/oraxen/textures/x/t" + stage + ".png"),
                    0xFFAA4477 + stage);
        }
        write(javaPack.resolve(
                "assets/minecraft/blockstates/tripwire.json"), """
                {"variants":{
                  "east=false,west=false,south=false,north=true,attached=false,disarmed=true,powered=false":
                    {"model":"oraxen:x/p1"},
                  "east=true,west=false,south=false,north=true,attached=false,disarmed=true,powered=false":
                    {"model":"oraxen:x/p2"},
                  "east=false,west=true,south=false,north=true,attached=false,disarmed=true,powered=false":
                    {"model":"oraxen:x/p3"}
                }}
                """);

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 13, 0},
                true, true, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject stateOverrides = JsonSupport.readObject(
                        geyser.resolve("custom_mappings/oraxen-blocks.json"))
                .getAsJsonObject("blocks")
                .getAsJsonObject("minecraft:tripwire")
                .getAsJsonObject("state_overrides");
        assertEquals(3, stateOverrides.size());
        for (String state : List.of(
                "east=false,west=false,south=false,north=true,attached=false,disarmed=true,powered=false",
                "east=true,west=false,south=false,north=true,attached=false,disarmed=true,powered=false",
                "east=false,west=true,south=false,north=true,attached=false,disarmed=true,powered=false")) {
            JsonObject override = stateOverrides.getAsJsonObject(state);
            assertNotNull(override);
            assertTrue(override.has("geometry"));
        }
        assertEquals(1, result.blocks());
        assertFalse(result.warnings().stream().anyMatch(warning ->
                warning.contains("no matching generated Java blockstate")));
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            long geometryCount;
            try (var geometries =
                         Files.list(pack.getPath("/models/oraxen"))) {
                geometryCount = geometries
                        .filter(path -> path.toString().endsWith(".geo.json"))
                        .count();
            }
            assertTrue(geometryCount >= 3,
                    "Every stack level needs its own Bedrock geometry");
        }
    }

    @Test
    void recoversLogicalModelsAndTexturesFromTheOraxenSourcePack()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/source-fallback.yml"), """
                table:
                  displayname: "Table"
                  material: PAPER
                  Pack:
                    model: default/table
                  Components:
                    item_model: oraxen:table
                  Mechanics:
                    furniture:
                      type: DISPLAY_ENTITY
                arrow_next_icon:
                  displayname: "Next"
                  material: PAPER
                  Pack:
                    generate_model: true
                    textures: [icons/arrow_next]
                  Components:
                    item_model: oraxen:arrow_next_icon
                """);
        write(oraxen.resolve("pack/models/default/table.json"), """
                {"textures":{"all":"oraxen:default/table"},
                 "elements":[{"from":[0,0,0],"to":[16,8,16],
                   "faces":{"north":{"texture":"#all"}}}]}
                """);
        writePng(oraxen.resolve("pack/textures/default/table.png"),
                0xFF8B5A2B);
        writePng(oraxen.resolve("pack/textures/icons/arrow_next.png"),
                0xFFFFFFFF);

        Path javaPack = temp.resolve("generated-obfuscated-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"min_format":[46,0],"max_format":[999,0],
                 "pack":{"description":"Logical source fallback"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/table.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:default/table"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/arrow_next_icon.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:arrow_next_icon"}}
                """);

        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"),
                javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 17, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        assertEquals(2, result.items());
        assertFalse(result.warnings().stream().anyMatch(warning ->
                warning.contains("Skipped custom mapping")));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("Recovered")
                        && warning.contains("Oraxen pack source")));
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/models/oraxen/table.geo.json")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/items/table.png")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/items/arrow_next_icon.png")));
        }
    }

    private Path resourcePack(Map<String, String> definitions) throws IOException {
        Path pack = temp.resolve("resolver-pack");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Resolver regression"}}
                """);
        for (Map.Entry<String, String> definition : definitions.entrySet())
            write(pack.resolve("assets/oraxen/items/"
                    + definition.getKey() + ".json"), definition.getValue());
        return pack;
    }

    private List<String> modelsForExpected(
            JavaItemModelResolver.Result result, boolean expected) {
        return result.variants().stream()
                .filter(variant -> variant.predicates().size() == 1
                        && variant.predicates().get(0).has("expected")
                        && variant.predicates().get(0).get("expected")
                                .getAsBoolean() == expected)
                .map(JavaItemModelResolver.Variant::model)
                .toList();
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static void writePng(Path path, int argb) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image =
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, argb);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
