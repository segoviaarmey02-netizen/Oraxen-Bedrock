package dev.oraxenbedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OraxenBedrockPluginTest {
    @TempDir Path temp;

    @Test
    void packReadinessRejectsPartialAndContentlessArchives() throws Exception {
        Path partial = temp.resolve("partial.zip");
        Files.write(partial, new byte[]{0x50, 0x4b, 0x03, 0x04});
        assertFalse(OraxenBedrockPlugin.isJavaPackReady(partial));

        Path metadataOnly = temp.resolve("metadata-only.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(metadataOnly))) {
            entry(zip, "pack.mcmeta", "{}".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(OraxenBedrockPlugin.isJavaPackReady(metadataOnly));
    }

    @Test
    void packReadinessAcceptsAssetsInsideAWrapperDirectory() throws Exception {
        Path wrapped = temp.resolve("wrapped.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(wrapped))) {
            entry(zip, "Export/pack.mcmeta",
                    "{}".getBytes(StandardCharsets.UTF_8));
            entry(zip, "Export/assets/oraxen/textures/item/test.png",
                    new byte[]{1});
        }

        assertTrue(OraxenBedrockPlugin.isJavaPackReady(wrapped));
    }

    @Test
    void packReadinessAcceptsAnUncompressedFlatOraxenPack() throws Exception {
        Path flat = temp.resolve("pack");
        Files.createDirectories(flat.resolve("font"));
        Files.writeString(flat.resolve("font/default.json"), "{}");

        assertTrue(OraxenBedrockPlugin.isJavaPackReady(flat));
    }

    @Test
    void packReadinessDoesNotStopBeforeNestedAssetFiles() throws Exception {
        Path directoryPack = temp.resolve("directory-pack");
        Files.createDirectories(
                directoryPack.resolve("assets/oraxen/textures/item"));
        Files.write(directoryPack.resolve(
                "assets/oraxen/textures/item/test.png"), new byte[]{1});
        assertTrue(OraxenBedrockPlugin.isJavaPackReady(directoryPack));

        Path wrappedFlat = temp.resolve("wrapped-flat.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(wrappedFlat))) {
            entry(zip, "Export/font/default.json",
                    "{}".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(OraxenBedrockPlugin.isJavaPackReady(wrappedFlat));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] value)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }
}
