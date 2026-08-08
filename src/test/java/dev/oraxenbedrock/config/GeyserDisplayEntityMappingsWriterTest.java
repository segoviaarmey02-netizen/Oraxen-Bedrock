package dev.oraxenbedrock.config;

import dev.oraxenbedrock.model.OraxenItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GeyserDisplayEntityMappingsWriterTest {
    @TempDir Path temp;

    @Test
    void writesOnlyModernIdentifiersUsingCurrentExtensionSchema()
            throws Exception {
        Path geyser = installedExtension("current-schema");

        OraxenItem modern = item("Fancy Chair", "PAPER", null, true);
        OraxenItem legacyConfigured = item(
                "old_lamp", "minecraft:STICK", 1234, furniture());
        OraxenItem ordinaryItem = item("not_furniture", "STONE", null, false);

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, "oraxen",
                        List.of(ordinaryItem, legacyConfigured, modern));

        assertEquals(GeyserDisplayEntityMappingsWriter.Status.WRITTEN,
                result.status());
        assertEquals(2, result.mappings());
        assertEquals(geyser.resolve(
                "extensions/geyserdisplayentity/Mappings/oraxen.yml"),
                result.file());
        assertTrue(Files.isRegularFile(result.file()));

        Map<?, ?> mappings = section(load(result.file()), "mappings");
        assertEquals(List.of("fancy_chair", "old_lamp"),
                List.copyOf(mappings.keySet()));

        Map<?, ?> modernMapping = section(mappings, "fancy_chair");
        assertEquals("minecraft:paper", modernMapping.get("type"));
        assertEquals("oraxen:fancy_chair",
                modernMapping.get("item-identifier"));
        assertFalse(modernMapping.containsKey("model-data"));
        assertFalse(modernMapping.containsKey("displayentityoptions"));

        Map<?, ?> legacyMapping = section(mappings, "old_lamp");
        assertEquals("minecraft:stick", legacyMapping.get("type"));
        assertEquals("oraxen:old_lamp", legacyMapping.get("item-identifier"));
        assertFalse(legacyMapping.containsKey("model-data"));
        assertFalse(legacyMapping.containsKey("displayentityoptions"));
        assertFalse(result.companionPackFound());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void doesNothingWhenExtensionIsNotInstalled() throws Exception {
        Path geyser = temp.resolve("missing-extension");

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, List.of(item("chair", "PAPER", null, true)));

        assertEquals(GeyserDisplayEntityMappingsWriter.Status.EXTENSION_MISSING,
                result.status());
        assertEquals(0, result.mappings());
        assertFalse(result.companionPackFound());
        assertTrue(result.diagnostics().isEmpty());
        assertFalse(Files.exists(geyser));
        assertFalse(Files.exists(result.file()));
    }

    @Test
    void staleDataFolderAndCorruptNamedJarDoNotCountAsInstalled()
            throws Exception {
        Path geyser = temp.resolve("stale-extension");
        Path target = geyser.resolve(
                "extensions/geyserdisplayentity/Mappings/oraxen.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "existing: true\n");
        Files.writeString(geyser.resolve(
                "extensions/GeyserDisplayEntity.jar"), "not a jar");

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, List.of(item("chair", "PAPER", null, true)));

        assertEquals(GeyserDisplayEntityMappingsWriter.Status.EXTENSION_MISSING,
                result.status());
        assertEquals(0, result.mappings());
        assertEquals("existing: true\n", Files.readString(target));
    }

    @Test
    void detectsRenamedExtensionJarByDescriptor() throws Exception {
        Path geyser = temp.resolve("renamed-jar");
        Path extensions = geyser.resolve("extensions");
        Files.createDirectories(extensions);
        writeExtensionJar(extensions.resolve("display-support.jar"));

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, "geyser_custom",
                        List.of(item("modern", "PAPER", null, true)));

        assertTrue(result.written());
        Map<?, ?> mapping = section(section(load(result.file()), "mappings"),
                "modern");
        // The extension itself strips Geyser's implicit namespace.
        assertEquals("modern", mapping.get("item-identifier"));
    }

    @Test
    void furnitureItemUsesHelperMaterialAndGeneratedBedrockIdentifier()
            throws Exception {
        Path geyser = installedExtension("helper-item");
        OraxenItem furniture = item("chair", "PAPER", null,
                furniture("type", "DISPLAY_ENTITY", "item", "chair_model"));
        OraxenItem helper = item("chair_model", "DIAMOND", 77, Map.of());

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, "oraxen", List.of(furniture, helper));

        assertEquals(1, result.mappings());
        assertTrue(result.diagnostics().isEmpty());
        Map<?, ?> mapping = section(section(load(result.file()), "mappings"),
                "chair");
        assertEquals("minecraft:diamond", mapping.get("type"));
        assertEquals("oraxen:chair_model", mapping.get("item-identifier"));
        assertFalse(mapping.containsKey("model-data"));
    }

    @Test
    void writesMappingsForEveryGeneratedFurnitureStateIdentifier()
            throws Exception {
        Path geyser = installedExtension("generated-states");
        OraxenItem furniture = item("chair", "PAPER", null,
                furniture("item", "chair_helper"));
        OraxenItem helper = item("chair_helper", "DIAMOND", null, Map.of());

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, "oraxen", List.of(furniture, helper), List.of(
                                "oraxen:chair_helper",
                                "oraxen:chair_state_0123456789ab",
                                "oraxen:chair_model_active_0123456789",
                                "oraxen:chair_model_active_0123456789_state_abcdef012345",
                                "oraxen:chair_model_unrelated",
                                "oraxen:chair_state_not_a_hash",
                                "other:chair_state_wrong_namespace"));

        assertEquals(4, result.mappings());
        assertTrue(result.diagnostics().isEmpty());
        Map<?, ?> mappings = section(load(result.file()), "mappings");
        assertEquals(4, mappings.size());
        assertEquals(java.util.Set.of(
                        "oraxen:chair_helper",
                        "oraxen:chair_state_0123456789ab",
                        "oraxen:chair_model_active_0123456789",
                        "oraxen:chair_model_active_0123456789_state_abcdef012345"),
                mappings.values().stream()
                        .map(value -> ((Map<?, ?>) value).get("item-identifier"))
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(mappings.values().stream()
                .map(value -> ((Map<?, ?>) value).get("type"))
                .allMatch("minecraft:diamond"::equals));
    }

    @Test
    void skipsFurnitureWhenConfiguredHelperItemIsMissing() throws Exception {
        Path geyser = installedExtension("missing-helper");
        OraxenItem furniture = item("chair", "PAPER", null,
                furniture("item", "does_not_exist"));

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, List.of(furniture));

        assertEquals(0, result.mappings());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("does_not_exist"));
        assertTrue(section(load(result.file()), "mappings").isEmpty());
    }

    @Test
    void skipsFurnitureWhoseBedrockItemWasNotGenerated() throws Exception {
        Path geyser = installedExtension("missing-bedrock-item");
        OraxenItem furniture = item("chair", "PAPER", null, furniture());

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(
                        geyser, "oraxen", List.of(furniture), List.of());

        assertEquals(0, result.mappings());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("oraxen:chair"));
        assertTrue(section(load(result.file()), "mappings").isEmpty());
    }

    @Test
    void mapsOnlyDisplayEntityFurnitureAndDefaultsMissingTypeToDisplay()
            throws Exception {
        Path geyser = installedExtension("furniture-types");
        List<OraxenItem> items = List.of(
                item("default_display", "PAPER", null, furniture()),
                item("explicit_display", "PAPER", null,
                        furniture("type", "display-entity")),
                item("frame", "PAPER", null, furniture("type", "ITEM_FRAME")),
                item("glow_frame", "PAPER", null,
                        furniture("type", "GLOW_ITEM_FRAME")),
                item("stand", "PAPER", null, furniture("type", "ARMOR_STAND")));

        GeyserDisplayEntityMappingsWriter.Result result =
                GeyserDisplayEntityMappingsWriter.write(geyser, items);

        Map<?, ?> mappings = section(load(result.file()), "mappings");
        assertEquals(List.of("default_display", "explicit_display"),
                List.copyOf(mappings.keySet()));
        assertEquals(2, result.mappings());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void reportsCompanionPackPresentByManifestOrEntityMarker() throws Exception {
        Path missing = installedExtension("companion-missing");
        GeyserDisplayEntityMappingsWriter.Result missingResult =
                GeyserDisplayEntityMappingsWriter.write(missing, List.of());
        assertFalse(missingResult.companionPackFound());

        Path manifest = installedExtension("companion-manifest");
        writeArchive(manifest.resolve("packs/display.mcpack"), Map.of(
                "manifest.json", """
                        {"header":{"uuid":"e8f5c939-a701-11eb-b2a3-057d7bb383ba"}}
                        """));
        assertTrue(GeyserDisplayEntityMappingsWriter.write(manifest, List.of())
                .companionPackFound());

        Path entity = installedExtension("companion-entity");
        writeArchive(entity.resolve("packs/renamed.zip"), Map.of(
                "wrapped/entity/item_display.entity.json", """
                        {"minecraft:client_entity":{"description":
                          {"identifier":"geyser:item_display"}}}
                        """));
        assertTrue(GeyserDisplayEntityMappingsWriter.write(entity, List.of())
                .companionPackFound());
    }

    @Test
    void collisionFailsBeforeReplacingExistingMapping() throws Exception {
        Path geyser = installedExtension("collision");
        Path target = geyser.resolve(
                "extensions/geyserdisplayentity/Mappings/oraxen.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "existing: true\n");

        IOException error = assertThrows(IOException.class,
                () -> GeyserDisplayEntityMappingsWriter.write(geyser, List.of(
                        item("Fancy Chair", "PAPER", null, true),
                        item("fancy_chair", "STICK", null, true))));

        assertTrue(error.getMessage().contains("fancy_chair"));
        assertEquals("existing: true\n", Files.readString(target));
    }

    private Path installedExtension(String name) throws Exception {
        Path geyser = temp.resolve(name);
        Path extensions = geyser.resolve("extensions");
        Files.createDirectories(extensions);
        writeExtensionJar(extensions.resolve("GeyserDisplayEntity.jar"));
        return geyser;
    }

    private static void writeExtensionJar(Path target) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(target))) {
            output.putNextEntry(new ZipEntry("extension.yml"));
            output.write("name: Display Support\nid: geyserdisplayentity\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void writeArchive(Path target, Map<String, String> entries)
            throws Exception {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(target))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static Map<String, Object> furniture(Object... entries) {
        Map<String, Object> furniture = new java.util.LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2)
            furniture.put(String.valueOf(entries[index]), entries[index + 1]);
        return Map.of("furniture", furniture);
    }

    private static OraxenItem item(String id, String material,
                                   Integer modelData, boolean furniture) {
        return item(id, material, modelData,
                furniture ? furniture() : Map.of());
    }

    private static OraxenItem item(String id, String material,
                                   Integer modelData,
                                   Map<String, Object> mechanics) {
        return new OraxenItem(id, id, material, null, null, modelData,
                false, false, List.of(), null, Map.of(), Map.of(),
                mechanics);
    }

    private static Map<?, ?> load(Path file) throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(file)) {
            Object value = new Yaml(new SafeConstructor(options)).load(input);
            assertInstanceOf(Map.class, value);
            return (Map<?, ?>) value;
        }
    }

    private static Map<?, ?> section(Map<?, ?> root, String key) {
        Object value = root.get(key);
        assertInstanceOf(Map.class, value);
        return (Map<?, ?>) value;
    }

}
