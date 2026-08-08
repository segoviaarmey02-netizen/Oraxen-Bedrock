package dev.oraxenbedrock.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PackSourceRegressionTest {
    @TempDir Path temp;

    @Test
    void discoversTheResourcePackRootInsideAWrappedZip() throws Exception {
        Path archive = temp.resolve("wrapped-pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(archive))) {
            entry(zip, "a/b/c/d/pack.mcmeta", """
                    {"pack":{"pack_format":75,"description":"Wrapped pack"}}
                    """);
            entry(zip, "a/b/c/d/assets/oraxen/textures/item/icon.png", "icon");
        }

        try (PackSource source = PackSource.open(archive)) {
            assertEquals(75, source.metadata().packFormat());
            Path asset = source.findAsset(
                    "oraxen", "textures/item/icon.png");
            assertNotNull(asset);
            assertEquals("icon", Files.readString(asset));
        }
    }

    @Test
    void enumeratesFlatFallbackFontFilesAsMinecraftAssets() throws Exception {
        Path primary = temp.resolve("primary");
        write(primary.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Fallback pack"}}
                """);

        Path fallback = temp.resolve("fallback");
        Path font = fallback.resolve("font/default.json");
        Path texture = fallback.resolve("textures/font/emoji.png");
        write(font, "{\"providers\":[]}");
        write(texture, "emoji");

        try (PackSource source = PackSource.open(primary, fallback)) {
            Map<String, PackSource.AssetFile> assets =
                    source.effectiveAssetFiles().stream().collect(Collectors.toMap(
                            asset -> asset.namespace() + ":" + asset.relative(),
                            Function.identity()));

            assertEquals(2, assets.size());
            assertEquals(font, assets.get("minecraft:font/default.json").path());
            assertEquals(texture,
                    assets.get("minecraft:textures/font/emoji.png").path());
            assertEquals(font, source.findAsset(
                    "minecraft", "font/default.json"));
            assertEquals(texture, source.findAsset(
                    "minecraft", "textures/font/emoji.png"));
        }
    }

    @Test
    void acceptsTheUncompressedFlatOraxenPackAsThePrimarySource()
            throws Exception {
        Path pack = temp.resolve("flat-pack");
        Path font = pack.resolve("font/default.json");
        Path texture = pack.resolve("textures/font/emoji.png");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Flat pack"}}
                """);
        write(font, "{\"providers\":[]}");
        write(texture, "emoji");

        try (PackSource source = PackSource.open(pack)) {
            Map<String, PackSource.AssetFile> assets =
                    source.effectiveAssetFiles().stream().collect(Collectors.toMap(
                            asset -> asset.namespace() + ":" + asset.relative(),
                            Function.identity()));
            assertEquals(font,
                    assets.get("minecraft:font/default.json").path());
            assertEquals(texture,
                    source.findAsset("minecraft", "textures/font/emoji.png"));
        }
    }

    @Test
    void discoversAFlatPackInsideAWrapperDirectory() throws Exception {
        Path archive = temp.resolve("wrapped-flat.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(archive))) {
            entry(zip, "Export/font/default.json", "{\"providers\":[]}");
            entry(zip, "Export/textures/font/emoji.png", "emoji");
        }

        try (PackSource source = PackSource.open(archive)) {
            Map<String, PackSource.AssetFile> assets =
                    source.effectiveAssetFiles().stream().collect(Collectors.toMap(
                            asset -> asset.namespace() + ":" + asset.relative(),
                            Function.identity()));
            assertEquals(2, assets.size());
            assertNotNull(assets.get("minecraft:font/default.json"));
            assertNotNull(assets.get("minecraft:textures/font/emoji.png"));
        }
    }

    @Test
    void basenameTextureFallbackNeverIndexesPngFilesOutsideTextures()
            throws Exception {
        Path pack = temp.resolve("texture-basename-scope");
        Path texture = pack.resolve(
                "assets/oraxen/textures/custom/icon.png");
        write(texture, "texture");
        write(pack.resolve("assets/oraxen/misc/icon.png"), "not-a-texture");
        write(pack.resolve("assets/oraxen/misc/ghost.png"), "not-a-texture");

        try (PackSource source = PackSource.open(pack)) {
            assertEquals(texture,
                    source.findTexture("missing/path/icon", "oraxen"));
            assertNull(source.findTexture("missing/path/ghost", "oraxen"));
        }
    }

    private static void entry(ZipOutputStream zip, String name, String value)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
