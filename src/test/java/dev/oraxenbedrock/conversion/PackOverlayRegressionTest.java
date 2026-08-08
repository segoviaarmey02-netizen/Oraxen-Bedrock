package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class PackOverlayRegressionTest {
    @TempDir Path temp;

    @Test
    void overlayReplacesWholeLanguageFileInsteadOfMergingStaleEntries()
            throws Exception {
        write(temp.resolve("pack.mcmeta"), """
                {
                  "pack":{"pack_format":75,"description":"Overlay regression"},
                  "overlays":{"entries":[{
                    "formats":{"min_inclusive":75,"max_inclusive":75},
                    "directory":"active"
                  }]}
                }
                """);
        write(temp.resolve("assets/oraxen/lang/en_us.json"), """
                {"base.only":"stale","shared":"base"}
                """);
        write(temp.resolve("active/assets/oraxen/lang/en_us.json"), """
                {"overlay.only":"current","shared":"overlay"}
                """);
        write(temp.resolve("assets/oraxen/textures/item/reference.png"), "png");

        Path bedrock = temp.resolve("bedrock");
        var warnings = new ArrayList<String>();
        try (PackSource source = PackSource.open(temp)) {
            assertEquals(1, source.effectiveAssetFiles().stream()
                    .filter(asset -> asset.relative().equals("lang/en_us.json"))
                    .count());
            assertEquals(temp.resolve("assets/oraxen/textures/item/reference.png"),
                    source.findTexture(
                            "assets/oraxen/textures/item/reference.png"));
            new LanguageConverter().convert(source, bedrock, warnings);
        }

        String language = Files.readString(bedrock.resolve("texts/en_US.lang"));
        assertTrue(language.contains("overlay.only=current"));
        assertTrue(language.contains("shared=overlay"));
        assertFalse(language.contains("base.only=stale"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void openEndedFormatRangeUsesItsMinimumAsTheOverlayBaseline()
            throws Exception {
        write(temp.resolve("pack.mcmeta"), """
                {
                  "min_format":[46,0],
                  "max_format":[999,0],
                  "pack":{"description":"Open-ended Oraxen pack"},
                  "overlays":{"entries":[
                    {"formats":{"min_inclusive":46,"max_inclusive":83},
                     "directory":"baseline"},
                    {"formats":{"min_inclusive":84,"max_inclusive":999},
                     "directory":"future"}
                  ]}
                }
                """);
        write(temp.resolve("assets/oraxen/models/default/table.json"), "base");
        write(temp.resolve(
                "baseline/assets/oraxen/models/default/table.json"), "baseline");
        write(temp.resolve(
                "future/assets/oraxen/models/default/table.json"), "future");

        try (PackSource source = PackSource.open(temp)) {
            assertEquals(46, source.metadata().minFormat());
            assertEquals(999, source.metadata().maxFormat());
            assertEquals(1, source.metadata().activeOverlays().size());
            assertEquals("baseline", Files.readString(source.findAsset(
                    "oraxen", "models/default/table.json")));
        }
    }

    @Test
    void validatorAcceptsDefaultExpectedValueForConditionPredicate()
            throws Exception {
        Path bedrock = temp.resolve("validator");
        write(bedrock.resolve("manifest.json"), "{}");
        write(bedrock.resolve("textures/item_texture.json"), """
                {"texture_data":{}}
                """);
        write(bedrock.resolve("textures/terrain_texture.json"), """
                {"texture_data":{}}
                """);

        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "condition");
        predicate.addProperty("property", "broken");
        JsonObject definition = new JsonObject();
        definition.addProperty("bedrock_identifier", "oraxen:test");
        definition.addProperty("model", "oraxen:test");
        definition.add("predicate", predicate);
        JsonArray definitions = new JsonArray();
        definitions.add(definition);
        JsonObject items = new JsonObject();
        items.add("minecraft:paper", definitions);
        JsonObject itemMappings = new JsonObject();
        itemMappings.add("items", items);
        JsonObject blockMappings = new JsonObject();
        blockMappings.add("blocks", new JsonObject());

        PackValidator.Result result = new PackValidator().validate(
                bedrock, itemMappings, blockMappings, "oraxen");

        assertTrue(result.warnings().isEmpty());
        assertEquals(2, result.checkedReferences());
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }
}
