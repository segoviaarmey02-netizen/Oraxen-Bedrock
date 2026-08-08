package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.model.ConversionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MalformedItemModelRegressionTest {
    @TempDir
    Path temp;

    @Test
    void malformedModelsSkipOnlyAffectedItemsInsteadOfAbortingThePack()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/items.yml"), """
                invalid_json:
                  material: PAPER
                  Pack:
                    model: item/invalid_json
                  Components:
                    item_model: oraxen:invalid_json
                non_object_textures:
                  material: PAPER
                  Pack:
                    model: item/non_object_textures
                  Components:
                    item_model: oraxen:non_object_textures
                valid_item:
                  material: PAPER
                  Pack:
                    model: item/valid_item
                  Components:
                    item_model: oraxen:valid_item
                """);

        Path javaPack = temp.resolve("java-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Malformed model regression"}}
                """);
        for (String id : new String[]{
                "invalid_json", "non_object_textures", "valid_item"})
            write(javaPack.resolve("assets/oraxen/items/" + id + ".json"),
                    """
                    {"model":{"type":"minecraft:model",
                      "model":"oraxen:item/%s"}}
                    """.formatted(id));
        write(javaPack.resolve(
                "assets/oraxen/models/item/invalid_json.json"), "{broken");
        write(javaPack.resolve(
                "assets/oraxen/models/item/non_object_textures.json"), """
                {"parent":"minecraft:item/generated","textures":[]}
                """);
        write(javaPack.resolve(
                "assets/oraxen/models/item/valid_item.json"), """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":"oraxen:item/valid_item"}}
                """);
        writePng(javaPack.resolve(
                "assets/oraxen/textures/item/valid_item.png"));

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 20, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(
                temp.resolve("plugins/OraxenBedrock")).convert(config);

        assertEquals(1, result.items());
        JsonObject mapped = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items");
        JsonArray paper = mapped.getAsJsonArray("minecraft:paper");
        assertNotNull(paper);
        assertEquals(1, paper.size());
        assertEquals("oraxen:valid_item", paper.get(0).getAsJsonObject()
                .get("bedrock_identifier").getAsString());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("Could not resolve source texture for item 'invalid_json'")));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("Could not resolve source texture for item 'non_object_textures'")));
        assertTrue(Files.isRegularFile(result.pack()));
    }

    @Test
    void malformedItemDefinitionsAreIsolatedFromFurnitureStatesAndOtherItems()
            throws Exception {
        Path oraxen = temp.resolve("definitions/Oraxen");
        write(oraxen.resolve("items/items.yml"), """
                broken_modern:
                  material: PAPER
                  Pack:
                    model: item/broken_modern
                  Components:
                    item_model: oraxen:broken_modern
                broken_legacy:
                  material: PAPER
                  Pack:
                    model: item/broken_legacy
                    custom_model_data: 7
                    exclude_from_item_model: true
                valid_item:
                  material: PAPER
                  Pack:
                    model: item/valid_item
                  Components:
                    item_model: oraxen:valid_item
                table:
                  material: PAPER
                  Pack:
                    model: item/table_base
                    models:
                      active: item/table_active
                  Components:
                    item_model: oraxen:table
                  Mechanics:
                    furniture:
                      type: DISPLAY_ENTITY
                      stages:
                        - model: active
                """);

        Path javaPack = temp.resolve("definitions/java-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Definition isolation"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/broken_modern.json"), "{broken");
        write(javaPack.resolve("assets/oraxen/items/valid_item.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:item/valid_item"}}
                """);
        write(javaPack.resolve("assets/oraxen/items/table.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:item/table_base"}}
                """);
        write(javaPack.resolve(
                "assets/oraxen/items/table/active.json"), "{broken");

        write(javaPack.resolve(
                "assets/oraxen/models/item/broken_legacy.json"), "{broken");
        for (String id : new String[]{
                "broken_modern", "valid_item", "table_base", "table_active"}) {
            write(javaPack.resolve("assets/oraxen/models/item/" + id + ".json"),
                    """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"oraxen:item/%s"}}
                    """.formatted(id));
            writePng(javaPack.resolve(
                    "assets/oraxen/textures/item/" + id + ".png"));
        }

        Path geyser = temp.resolve("definitions/Geyser-Spigot");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Test pack", "oraxen", new int[]{1, 20, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(
                temp.resolve("definitions/OraxenBedrock")).convert(config);

        JsonArray paper = JsonSupport.readObject(result.mappings())
                .getAsJsonObject("items").getAsJsonArray("minecraft:paper");
        assertNotNull(paper);
        Map<String, String> identifiers = new LinkedHashMap<>();
        paper.forEach(value -> {
            JsonObject definition = value.getAsJsonObject();
            identifiers.put(definition.get("model").getAsString(),
                    definition.get("bedrock_identifier").getAsString());
        });
        assertEquals(Set.of(
                        "oraxen:broken_modern", "oraxen:valid_item",
                        "oraxen:table", "oraxen:table/active"),
                identifiers.keySet());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains(
                        "Could not resolve item definition for 'broken_modern'")));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains(
                        "Could not resolve legacy model for 'broken_legacy'")));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains(
                        "Could not resolve Pack.models.active definition for item 'table'")));

        String activeId = identifiers.get("oraxen:table/active")
                .substring("oraxen:".length());
        try (var pack = java.nio.file.FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/items/" + activeId + ".png")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/models/oraxen/" + activeId + ".attachable.geo.json")));
        }
    }

    private void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private void writePng(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(
                16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, 0xFFCC8844);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
