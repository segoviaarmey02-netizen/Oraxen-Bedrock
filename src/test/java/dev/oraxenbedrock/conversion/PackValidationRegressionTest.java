package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.config.BridgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackValidationRegressionTest {
    @TempDir Path temp;

    @Test
    void rejectsEmptyAndNonArrayItemDefinitions() throws Exception {
        Path bedrock = scaffold("invalid-items");
        JsonElement[] invalidDefinitions = {new JsonArray(), new JsonObject()};

        for (JsonElement invalid : invalidDefinitions) {
            JsonObject items = new JsonObject();
            items.add("minecraft:paper", invalid);
            IOException error = assertThrows(IOException.class, () ->
                    new PackValidator().validate(
                            bedrock, itemMappings(items), blockMappings(), "oraxen"));
            assertTrue(error.getMessage().contains("Item mapping 'minecraft:paper'"));
        }
    }

    @Test
    void rejectsInvalidAtlasResourcePaths() throws Exception {
        String[] invalidPaths = {
                "../outside", "/absolute/path", "C:/absolute/path",
                "texture/items/icon", "textures/../outside",
                "textures\\items\\icon", "textures//icon"
        };
        for (int index = 0; index < invalidPaths.length; index++) {
            Path bedrock = scaffold("invalid-atlas-" + index);
            writeAtlas(bedrock.resolve("textures/item_texture.json"),
                    invalidPaths[index]);

            IOException error = assertThrows(IOException.class, () ->
                    validate(bedrock, new JsonObject()));
            assertTrue(error.getMessage().contains("Invalid texture resource path"),
                    error.getMessage());
        }
    }

    @Test
    void acceptsAValidScalarAtlasPath() throws Exception {
        Path bedrock = scaffold("valid-atlas");
        writePng(bedrock.resolve("textures/items/valid.png"));
        writeAtlas(bedrock.resolve("textures/item_texture.json"),
                "textures/items/valid");

        PackValidator.Result result = validate(bedrock, new JsonObject());

        assertEquals(1, result.checkedReferences());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("should use a textures array")));
    }

    @Test
    void emptyOverrideMappingDoesNotMakeScaffoldingInstallable() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Path javaPack = oraxen.resolve("pack");
        write(javaPack.resolve("assets/oraxen/models/item/unused.json"), "{}");

        Path data = temp.resolve("plugins/OraxenBedrock");
        write(data.resolve("overrides/item-mappings.json"), """
                {"items":{"minecraft:paper":[]}}
                """);
        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path installed = geyser.resolve("packs/OraxenBedrock.mcpack");
        write(installed, "known-good-pack");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 0, 0}, false, false, false, false,
                false, false, true, false, false, 100, false);

        IOException error = assertThrows(IOException.class, () ->
                new PackConverter(data).convert(config));

        assertTrue(error.getMessage().contains("no usable Bedrock assets"));
        assertEquals("known-good-pack", Files.readString(installed));
    }

    @Test
    void identifierOnlyDefinitionIsNotUsable() throws Exception {
        Path bedrock = scaffold("identifier-only");
        JsonObject definition = new JsonObject();
        definition.addProperty("bedrock_identifier", "oraxen:identifier_only");
        JsonArray definitions = new JsonArray();
        definitions.add(definition);
        JsonObject items = new JsonObject();
        items.add("minecraft:paper", definitions);

        assertFalse(PackValidator.hasUsableItemMappings(items));
        IOException error = assertThrows(IOException.class, () ->
                validate(bedrock, items));
        assertTrue(error.getMessage().contains("definition requires model"),
                error.getMessage());
    }

    @Test
    void acceptsNestedGroupsWithInheritedModelAndLegacyDefinitions()
            throws Exception {
        Path bedrock = scaffold("valid-groups");

        JsonObject inherited = new JsonObject();
        inherited.addProperty("bedrock_identifier", "oraxen:inherited");

        JsonObject nestedLeaf = new JsonObject();
        nestedLeaf.addProperty("bedrock_identifier", "oraxen:nested");
        JsonArray nestedDefinitions = new JsonArray();
        nestedDefinitions.add(nestedLeaf);
        JsonObject nestedGroup = new JsonObject();
        nestedGroup.addProperty("type", "group");
        nestedGroup.add("definitions", nestedDefinitions);

        JsonObject legacy = new JsonObject();
        legacy.addProperty("type", "legacy");
        legacy.addProperty("bedrock_identifier", "oraxen:legacy");
        legacy.addProperty("custom_model_data", 42);

        JsonArray groupDefinitions = new JsonArray();
        groupDefinitions.add(inherited);
        groupDefinitions.add(nestedGroup);
        groupDefinitions.add(legacy);
        JsonObject group = new JsonObject();
        group.addProperty("type", "group");
        group.addProperty("model", "oraxen:furniture/table");
        group.add("definitions", groupDefinitions);

        JsonArray definitions = new JsonArray();
        definitions.add(group);
        JsonObject items = new JsonObject();
        items.add("minecraft:paper", definitions);

        assertTrue(PackValidator.hasUsableItemMappings(items));
        PackValidator.Result result = validate(bedrock, items);
        assertEquals(3, result.checkedReferences());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void rejectsNonFiniteLegacyCustomModelData() throws Exception {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            Path bedrock = scaffold("non-finite-" + Double.toHexString(value));
            JsonObject definition = new JsonObject();
            definition.addProperty("type", "legacy");
            definition.addProperty("bedrock_identifier", "oraxen:legacy");
            definition.addProperty("custom_model_data", value);
            JsonArray definitions = new JsonArray();
            definitions.add(definition);
            JsonObject items = new JsonObject();
            items.add("minecraft:paper", definitions);

            assertFalse(PackValidator.hasUsableItemMappings(items));
            IOException error = assertThrows(IOException.class, () ->
                    validate(bedrock, items));
            assertTrue(error.getMessage().contains("finite numeric custom_model_data"),
                    error.getMessage());
        }
    }

    private PackValidator.Result validate(Path bedrock, JsonObject items)
            throws IOException {
        return new PackValidator().validate(
                bedrock, itemMappings(items), blockMappings(), "oraxen");
    }

    private JsonObject itemMappings(JsonObject items) {
        JsonObject mappings = new JsonObject();
        mappings.add("items", items);
        return mappings;
    }

    private JsonObject blockMappings() {
        JsonObject mappings = new JsonObject();
        mappings.add("blocks", new JsonObject());
        return mappings;
    }

    private Path scaffold(String name) throws IOException {
        Path bedrock = temp.resolve(name);
        write(bedrock.resolve("manifest.json"), "{}");
        write(bedrock.resolve("textures/item_texture.json"),
                "{\"texture_data\":{}}");
        write(bedrock.resolve("textures/terrain_texture.json"),
                "{\"texture_data\":{}}");
        return bedrock;
    }

    private void writeAtlas(Path path, String texture) throws IOException {
        JsonObject entry = new JsonObject();
        entry.addProperty("textures", texture);
        JsonObject data = new JsonObject();
        data.add("test", entry);
        JsonObject atlas = new JsonObject();
        atlas.add("texture_data", data);
        JsonSupport.write(path, atlas);
    }

    private void writePng(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFFFFFF);
        if (!ImageIO.write(image, "png", path.toFile()))
            throw new IOException("PNG writer unavailable");
    }

    private void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }
}
