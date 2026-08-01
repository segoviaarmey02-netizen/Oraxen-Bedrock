package dev.oraxenbedrock.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GeyserConfigPatcherTest {
    @TempDir Path temp;

    @Test
    void enablesNestedSettingWithoutReformattingTheConfig() throws Exception {
        Path config = temp.resolve("config.yml");
        Files.writeString(config, """
                bedrock:
                  port: 19132
                gameplay:
                  enable-custom-content: false # required for mappings
                  ping-passthrough: true
                """);

        assertEquals(GeyserConfigPatcher.Result.UPDATED,
                GeyserConfigPatcher.enable(config));
        String updated = Files.readString(config);
        assertTrue(updated.contains(
                "  enable-custom-content: true # required for mappings"));
        assertTrue(updated.contains("  ping-passthrough: true"));
        assertEquals(GeyserConfigPatcher.Result.ALREADY_ENABLED,
                GeyserConfigPatcher.enable(config));
    }

    @Test
    void insertsMissingSettingIntoGameplaySection() throws Exception {
        Path config = temp.resolve("config.yml");
        Files.writeString(config, "gameplay:\n  ping-passthrough: true\n");

        assertEquals(GeyserConfigPatcher.Result.UPDATED,
                GeyserConfigPatcher.enable(config));
        assertTrue(Files.readString(config).startsWith(
                "gameplay:\n  enable-custom-content: true\n"));
    }

    @Test
    void reportsMissingConfigWithoutCreatingAPartialOne() throws Exception {
        Path config = temp.resolve("config.yml");
        assertEquals(GeyserConfigPatcher.Result.CONFIG_MISSING,
                GeyserConfigPatcher.enable(config));
        assertFalse(Files.exists(config));
    }
}
