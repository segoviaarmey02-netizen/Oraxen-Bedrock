package dev.oraxenbedrock.conversion;

import dev.oraxenbedrock.io.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FontConverterTest {
    @TempDir Path temp;

    @Test
    void fillsSharedPageCellSoLargeGlyphCannotShrinkInlineEmojis() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        Files.writeString(temp.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":32,\"description\":\"font test\"}}");

        writeSolidPng(textures.resolve("emoji_8x9.png"), 8, 9, 0xFFFF2020);
        writeSolidPng(textures.resolve("emoji_16x16.png"), 16, 16, 0xFF20FF20);
        writeSolidPng(textures.resolve("emoji_64x64.png"), 64, 64, 0xFF2020FF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[
                  {"type":"bitmap","file":"oraxen:font/emoji_8x9.png",
                   "height":9,"ascent":9,"chars":["\uE200"]},
                  {"type":"minecraft:bitmap","file":"oraxen:font/emoji_16x16.png",
                   "height":16,"ascent":16,"chars":["\uE201"]},
                  {"type":"bitmap","file":"oraxen:font/emoji_64x64.png",
                   "height":64,"ascent":64,"chars":["\uE202"]}
                ]}
                """);

        Path bedrock = temp.resolve("bedrock");
        List<String> warnings = new ArrayList<>();
        try (PackSource source = PackSource.open(temp)) {
            FontConverter.Result result =
                    new FontConverter(source, bedrock).convert(warnings);
            assertEquals(3, result.glyphs());
            assertEquals(1, result.pages());
        }

        assertTrue(warnings.isEmpty(), () -> String.join("\n", warnings));
        BufferedImage page = ImageIO.read(bedrock.resolve("font/glyph_E2.png").toFile());
        assertNotNull(page);
        // The 64px declared emoji selects a 128px grid for this page.
        assertEquals(2048, page.getWidth());
        assertEquals(2048, page.getHeight());

        // Bedrock applies one common cell size to the entire page. Both inline
        // emojis must fill that final 128px cell even though the third glyph's
        // Java height is what selected the larger page resolution.
        Bounds small = alphaBounds(page, 0, 0, 128);
        assertEquals(114, small.width());
        assertEquals(128, small.height());
        Bounds medium = alphaBounds(page, 128, 0, 128);
        assertEquals(128, medium.width());
        assertEquals(128, medium.height());
        Bounds large = alphaBounds(page, 256, 0, 128);
        assertEquals(128, large.width());
        assertEquals(128, large.height());
    }

    @Test
    void usesAReadableHdPageWhenOnlyEightByNineEmojiExist() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        Files.writeString(temp.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":32,\"description\":\"font test\"}}");
        writeSolidPng(textures.resolve("tiny.png"), 8, 9, 0xFFFFFFFF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"oraxen:font/tiny.png",
                  "height":9,"ascent":9,"chars":["\uE100"]}]}
                """);

        Path bedrock = temp.resolve("bedrock");
        try (PackSource source = PackSource.open(temp)) {
            new FontConverter(source, bedrock).convert(new ArrayList<>());
        }

        BufferedImage page = ImageIO.read(bedrock.resolve("font/glyph_E1.png").toFile());
        assertNotNull(page);
        assertEquals(1024, page.getWidth());
        assertEquals(1024, page.getHeight());
        Bounds emoji = alphaBounds(page, 0, 0, 64);
        assertEquals(57, emoji.width());
        assertEquals(64, emoji.height());
    }

    @Test
    void honorsConfiguredEmojiCellSize() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        Files.writeString(temp.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":32,\"description\":\"font test\"}}");
        writeSolidPng(textures.resolve("tiny.png"), 8, 9, 0xFFFFFFFF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"oraxen:font/tiny.png",
                  "height":9,"ascent":9,"chars":["\uE100"]}]}
                """);

        try (PackSource source = PackSource.open(temp)) {
            new FontConverter(source, temp.resolve("bedrock32"), 32)
                    .convert(new ArrayList<>());
        }
        try (PackSource source = PackSource.open(temp)) {
            new FontConverter(source, temp.resolve("bedrock128"), 128)
                    .convert(new ArrayList<>());
        }

        BufferedImage page32 = ImageIO.read(
                temp.resolve("bedrock32/font/glyph_E1.png").toFile());
        BufferedImage page128 = ImageIO.read(
                temp.resolve("bedrock128/font/glyph_E1.png").toFile());
        assertEquals(512, page32.getWidth());
        assertEquals(2048, page128.getWidth());
    }

    @Test
    void usesConfiguredEmojiCellSizeForOraxenAutoAssignedCodepoint() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        Files.writeString(temp.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":32,\"description\":\"font test\"}}");
        writeSolidPng(textures.resolve("auto.png"), 8, 8, 0xFFFFFFFF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"oraxen:font/auto.png",
                  "height":8,"ascent":8,"chars":["\uA410"]}]}
                """);

        Path bedrock = temp.resolve("bedrock-auto");
        Path glyphs = temp.resolve("glyphs");
        Files.createDirectories(glyphs);
        Files.writeString(glyphs.resolve("emoji.yml"), """
                auto_emoji:
                  texture: font/auto
                """);
        List<String> warnings = new ArrayList<>();
        try (PackSource source = PackSource.open(temp)) {
            FontConverter.Result result = new FontConverter(
                    source, bedrock, 64, glyphs)
                    .convert(warnings);
            assertEquals(1, result.glyphs());
            assertEquals(1, result.pages());
        }

        assertTrue(warnings.isEmpty(), () -> String.join("\n", warnings));
        BufferedImage page = ImageIO.read(
                bedrock.resolve("font/glyph_A4.png").toFile());
        assertNotNull(page);
        assertEquals(1024, page.getWidth());
        assertEquals(1024, page.getHeight());
        Bounds glyph = alphaBounds(page, 0, 64, 64);
        assertEquals(64, glyph.width());
        assertEquals(64, glyph.height());
    }

    @Test
    void currentOraxenEmojiStaysFullSizeBesideLargeInterfaceGlyph() throws Exception {
        Path textures = temp.resolve("assets/minecraft/textures/font");
        Path fonts = temp.resolve("assets/minecraft/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        writeSolidPng(textures.resolve("heart.png"), 8, 8, 0xFFFF2020);
        writeSolidPng(textures.resolve("menu.png"), 128, 128, 0xFF2020FF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[
                  {"type":"bitmap","file":"minecraft:font/heart.png",
                   "height":8,"ascent":8,"chars":["\uA410"]},
                  {"type":"bitmap","file":"minecraft:font/menu.png",
                   "height":128,"ascent":37,"chars":["\uA411"]}
                ]}
                """);

        Path glyphs = temp.resolve("glyphs");
        Files.createDirectories(glyphs);
        Files.writeString(glyphs.resolve("emoji.yml"), """
                heart:
                  texture: font/heart
                  ascent: 8
                  height: 8
                menu_items:
                  texture: font/menu
                  ascent: 37
                  height: 256
                """);

        Path bedrock = temp.resolve("bedrock-mixed-auto");
        List<String> warnings = new ArrayList<>();
        try (PackSource source = PackSource.open(temp)) {
            FontConverter.Result result = new FontConverter(
                    source, bedrock, 64, glyphs).convert(warnings);
            assertEquals(2, result.glyphs());
            assertEquals(1, result.pages());
        }

        assertTrue(warnings.isEmpty(), () -> String.join("\n", warnings));
        BufferedImage page = ImageIO.read(
                bedrock.resolve("font/glyph_A4.png").toFile());
        assertNotNull(page);
        assertEquals(2048, page.getWidth());
        Bounds heart = alphaBounds(page, 0, 128, 128);
        assertEquals(128, heart.width());
        assertEquals(128, heart.height());
    }

    @Test
    void keepsFirstBitmapProviderForDuplicateCodepoint() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        Files.writeString(temp.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":32,\"description\":\"font test\"}}");
        int firstColor = 0xFFFF2020;
        int secondColor = 0xFF2020FF;
        writeSolidPng(textures.resolve("first.png"), 8, 8, firstColor);
        writeSolidPng(textures.resolve("second.png"), 8, 8, secondColor);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[
                  {"type":"bitmap","file":"oraxen:font/first.png",
                   "height":8,"ascent":8,"chars":["\uE300"]},
                  {"type":"bitmap","file":"oraxen:font/second.png",
                   "height":8,"ascent":8,"chars":["\uE300"]}
                ]}
                """);

        Path bedrock = temp.resolve("bedrock-duplicate");
        List<String> warnings = new ArrayList<>();
        try (PackSource source = PackSource.open(temp)) {
            FontConverter.Result result = new FontConverter(source, bedrock)
                    .convert(warnings);
            assertEquals(1, result.glyphs());
            assertEquals(1, result.pages());
        }

        assertEquals(List.of(
                "Duplicate bitmap provider for U+E300; the first provider was kept"),
                warnings);
        BufferedImage page = ImageIO.read(
                bedrock.resolve("font/glyph_E3.png").toFile());
        assertNotNull(page);
        assertEquals(firstColor, page.getRGB(32, 32));
        assertNotEquals(secondColor, page.getRGB(32, 32));
    }

    @Test
    void convertsCurrentOraxenFlatMinecraftFontFallback() throws Exception {
        Path primary = temp.resolve("generated");
        Files.createDirectories(primary);
        Files.writeString(primary.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":75,\"description\":\"font fallback\"}}");

        Path fallback = temp.resolve("oraxen-pack");
        Path textures = fallback.resolve("textures/font");
        Path fonts = fallback.resolve("font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        writeSolidPng(textures.resolve("emoji.png"), 8, 8, 0xFFFFAA22);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"font/emoji.png",
                  "height":8,"ascent":8,"chars":["\uA410"]}]}
                """);

        Path bedrock = temp.resolve("bedrock-fallback");
        List<String> warnings = new ArrayList<>();
        try (PackSource source = PackSource.open(primary, fallback)) {
            FontConverter.Result result = new FontConverter(source, bedrock)
                    .convert(warnings);
            assertEquals(1, result.glyphs());
            assertEquals(1, result.pages());
        }

        assertTrue(warnings.isEmpty(), () -> String.join("\n", warnings));
        assertTrue(Files.isRegularFile(
                bedrock.resolve("font/glyph_A4.png")));
    }

    @Test
    void leavesNonEmojiBitmapFontSizingUnchanged() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        Files.writeString(temp.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":32,\"description\":\"font test\"}}");
        writeSolidPng(textures.resolve("letter.png"), 32, 32, 0xFFFFFFFF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"oraxen:font/letter.png",
                  "height":8,"ascent":8,"chars":["\u0100"]}]}
                """);

        Path bedrock = temp.resolve("bedrock");
        try (PackSource source = PackSource.open(temp)) {
            new FontConverter(source, bedrock).convert(new ArrayList<>());
        }

        BufferedImage page = ImageIO.read(bedrock.resolve("font/glyph_01.png").toFile());
        assertNotNull(page);
        assertEquals(512, page.getWidth());
        Bounds glyph = alphaBounds(page, 0, 0, 32);
        assertEquals(32, glyph.width());
        assertEquals(32, glyph.height());
    }

    @Test
    void doesNotTreatHangulAsAnOraxenEmojiRange() throws Exception {
        Path textures = temp.resolve("assets/oraxen/textures/font");
        Path fonts = temp.resolve("assets/oraxen/font");
        Files.createDirectories(textures);
        Files.createDirectories(fonts);
        writeSolidPng(textures.resolve("hangul.png"), 8, 8, 0xFFFFFFFF);
        Files.writeString(fonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"oraxen:font/hangul.png",
                  "height":8,"ascent":8,"chars":["\uAC00"]}]}
                """);

        Path bedrock = temp.resolve("bedrock-hangul");
        try (PackSource source = PackSource.open(temp)) {
            new FontConverter(source, bedrock, 64).convert(new ArrayList<>());
        }

        BufferedImage page = ImageIO.read(
                bedrock.resolve("font/glyph_AC.png").toFile());
        assertNotNull(page);
        assertEquals(256, page.getWidth());
    }

    @Test
    void primaryZipFontWinsOverFlatFallbackProvider() throws Exception {
        Path primary = temp.resolve("primary");
        Path primaryTextures = primary.resolve("assets/oraxen/textures/font");
        Path primaryFonts = primary.resolve("assets/oraxen/font");
        Files.createDirectories(primaryTextures);
        Files.createDirectories(primaryFonts);
        int primaryColor = 0xFFFF2020;
        writeSolidPng(primaryTextures.resolve("primary.png"), 8, 8, primaryColor);
        Files.writeString(primaryFonts.resolve("default.json"), """
                {"providers":[{"type":"bitmap","file":"oraxen:font/primary.png",
                  "height":8,"ascent":8,"chars":["\uE300"]}]}
                """);

        Path fallback = temp.resolve("fallback");
        Files.createDirectories(fallback.resolve("font"));
        Files.createDirectories(fallback.resolve("textures/font"));
        writeSolidPng(fallback.resolve("textures/font/fallback.png"),
                8, 8, 0xFF2020FF);
        Files.writeString(fallback.resolve("font/default.json"), """
                {"providers":[{"type":"bitmap","file":"font/fallback.png",
                  "height":8,"ascent":8,"chars":["\uE300"]}]}
                """);

        Path bedrock = temp.resolve("bedrock-precedence");
        try (PackSource source = PackSource.open(primary, fallback)) {
            new FontConverter(source, bedrock).convert(new ArrayList<>());
        }

        BufferedImage page = ImageIO.read(
                bedrock.resolve("font/glyph_E3.png").toFile());
        assertNotNull(page);
        assertEquals(primaryColor, page.getRGB(32, 32));
    }

    private static void writeSolidPng(Path target, int width, int height, int color)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, color);
        assertTrue(ImageIO.write(image, "png", target.toFile()));
    }

    private static Bounds alphaBounds(BufferedImage image, int x, int y, int cellSize) {
        int left = x + cellSize;
        int top = y + cellSize;
        int right = -1;
        int bottom = -1;
        for (int row = y; row < y + cellSize; row++) {
            for (int column = x; column < x + cellSize; column++) {
                if ((image.getRGB(column, row) >>> 24) == 0) continue;
                left = Math.min(left, column);
                top = Math.min(top, row);
                right = Math.max(right, column);
                bottom = Math.max(bottom, row);
            }
        }
        assertTrue(right >= left && bottom >= top);
        return new Bounds(right - left + 1, bottom - top + 1);
    }

    private record Bounds(int width, int height) {}
}
