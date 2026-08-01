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
    void upscalesSmallEmojisAndKeepsDifferentDeclaredSizes() throws Exception {
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

        Bounds small = alphaBounds(page, 0, 0, 128);
        assertEquals(57, small.width());
        assertEquals(64, small.height());
        Bounds medium = alphaBounds(page, 128, 0, 128);
        assertEquals(64, medium.width());
        assertEquals(64, medium.height());
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
