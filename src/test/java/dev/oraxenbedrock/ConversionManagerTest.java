package dev.oraxenbedrock;

import dev.oraxenbedrock.config.BridgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ConversionManagerTest {
    @TempDir Path temp;

    @Test
    void fingerprintIgnoresGeneratedZipAndMetadataOnlyRewrites() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Path metadata = oraxen.resolve("pack/pack.mcmeta");
        Path texture = oraxen.resolve("pack/textures/item/test.png");
        Files.createDirectories(texture.getParent());
        Files.writeString(metadata, "{\"pack\":{\"pack_format\":75}}");
        Files.write(texture, new byte[]{1, 2, 3, 4});
        Files.write(javaPack, new byte[]{9, 8, 7});
        BridgeConfig config = config(oraxen, javaPack);

        long initial = ConversionManager.calculateFingerprint(
                config, temp.resolve("plugins/OraxenBedrock"));

        Files.write(javaPack, new byte[]{4, 5, 6, 7, 8});
        String sameMetadata = Files.readString(metadata);
        Files.writeString(metadata, sameMetadata);
        long regenerated = ConversionManager.calculateFingerprint(
                config, temp.resolve("plugins/OraxenBedrock"));

        assertEquals(initial, regenerated,
                "Generated pack.zip and mtime-only rewrites must not invalidate the conversion");
    }

    @Test
    void fingerprintDetectsActualSourceChanges() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Path texture = oraxen.resolve("pack/textures/item/test.png");
        Path item = oraxen.resolve("items/test.yml");
        Files.createDirectories(texture.getParent());
        Files.createDirectories(item.getParent());
        Files.write(texture, new byte[]{1, 2, 3, 4});
        Files.writeString(item, "test: one");
        Files.write(javaPack, new byte[]{9});
        BridgeConfig config = config(oraxen, javaPack);
        Path data = temp.resolve("plugins/OraxenBedrock");

        long initial = ConversionManager.calculateFingerprint(config, data);
        Files.write(texture, new byte[]{4, 3, 2, 1});
        long textureChanged = ConversionManager.calculateFingerprint(config, data);
        assertNotEquals(initial, textureChanged);

        Files.writeString(item, "test: two");
        long itemChanged = ConversionManager.calculateFingerprint(config, data);
        assertNotEquals(textureChanged, itemChanged);
    }

    @Test
    void fingerprintDetectsConversionSettingChanges() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        Files.write(javaPack, new byte[]{1});
        Path data = temp.resolve("plugins/OraxenBedrock");

        BridgeConfig first = config(oraxen, javaPack);
        BridgeConfig changed = new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"), javaPack,
                "Test", "Test", "changed_namespace", new int[]{1, 0, 0},
                true, true, true, true, true, true, true,
                true, true, 100, false);

        assertNotEquals(
                ConversionManager.calculateFingerprint(first, data),
                ConversionManager.calculateFingerprint(changed, data));
    }

    private BridgeConfig config(Path oraxen, Path javaPack) {
        return new BridgeConfig(
                temp, oraxen, temp.resolve("plugins/Geyser-Spigot"), javaPack,
                "Test", "Test", "oraxen", new int[]{1, 0, 0},
                true, true, true, true, true, true, true,
                true, true, 100, false);
    }
}
