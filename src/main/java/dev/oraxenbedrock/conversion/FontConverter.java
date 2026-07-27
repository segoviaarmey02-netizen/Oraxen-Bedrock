package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Packs Java bitmap font providers into Bedrock's 256-codepoint glyph pages.
 * Oraxen emojis use private-use BMP characters, which map directly to
 * font/glyph_XX.png cells in Bedrock.
 */
final class FontConverter {
    record Result(int glyphs, int pages) {}

    private static final int CELL = 16;
    private final PackSource source;
    private final Path bedrock;

    FontConverter(PackSource source, Path bedrock) {
        this.source = source;
        this.bedrock = bedrock;
    }

    Result convert(List<String> warnings) throws IOException {
        Map<Integer, Map<Integer, BufferedImage>> pages = new TreeMap<>();
        Path assets = source.root().resolve("assets");
        if (!Files.isDirectory(assets)) return new Result(0, 0);

        try (Stream<Path> paths = Files.walk(assets)) {
            for (Path file : paths.filter(this::isFontDefinition).sorted().toList()) {
                try {
                    JsonArray providers = JsonSupport.readObject(file).getAsJsonArray("providers");
                    if (providers == null) continue;
                    for (JsonElement value : providers) {
                        if (!value.isJsonObject()) continue;
                        JsonObject provider = value.getAsJsonObject();
                        if (!provider.has("type")
                                || !provider.get("type").getAsString().equals("bitmap"))
                            continue;
                        readProvider(provider, pages, warnings);
                    }
                } catch (IOException | RuntimeException ex) {
                    warnings.add("Could not convert bitmap font " + file + ": " + ex.getMessage());
                }
            }
        }

        int glyphCount = 0;
        for (Map.Entry<Integer, Map<Integer, BufferedImage>> pageEntry : pages.entrySet()) {
            BufferedImage sheet = new BufferedImage(CELL * 16, CELL * 16,
                    BufferedImage.TYPE_INT_ARGB);
            for (Map.Entry<Integer, BufferedImage> glyph : pageEntry.getValue().entrySet()) {
                int index = glyph.getKey();
                BufferedImage image = glyph.getValue();
                Target target = fit(image.getWidth(), image.getHeight(),
                        index % 16 * CELL, index / 16 * CELL);
                drawNearest(sheet, image, target);
                glyphCount++;
            }
            Path target = bedrock.resolve("font")
                    .resolve(String.format(Locale.ROOT, "glyph_%02X.png", pageEntry.getKey()));
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target)) {
                if (!ImageIO.write(sheet, "png", output))
                    throw new IOException("No PNG writer is available for " + target);
            }
        }
        return new Result(glyphCount, pages.size());
    }

    private void readProvider(JsonObject provider,
                              Map<Integer, Map<Integer, BufferedImage>> pages,
                              List<String> warnings) throws IOException {
        if (!provider.has("file") || !provider.has("chars")
                || !provider.get("chars").isJsonArray()) return;
        Path texture = fontTexture(provider.get("file").getAsString());
        if (texture == null) {
            warnings.add("Bitmap font texture not found: " + provider.get("file").getAsString());
            return;
        }
        BufferedImage atlas;
        try (InputStream input = Files.newInputStream(texture)) {
            atlas = ImageIO.read(input);
        }
        if (atlas == null) {
            warnings.add("Bitmap font texture is not a readable PNG: " + texture);
            return;
        }

        JsonArray chars = provider.getAsJsonArray("chars");
        List<int[]> rows = new ArrayList<>();
        int columns = 0;
        for (JsonElement row : chars) {
            if (!row.isJsonPrimitive()) continue;
            int[] codepoints = row.getAsString().codePoints().toArray();
            rows.add(codepoints);
            columns = Math.max(columns, codepoints.length);
        }
        if (rows.isEmpty() || columns == 0) return;
        int sourceCellWidth = atlas.getWidth() / columns;
        int sourceCellHeight = atlas.getHeight() / rows.size();
        if (sourceCellWidth <= 0 || sourceCellHeight <= 0) {
            warnings.add("Invalid bitmap font grid in " + provider.get("file").getAsString());
            return;
        }

        for (int row = 0; row < rows.size(); row++) {
            int[] codepoints = rows.get(row);
            for (int column = 0; column < codepoints.length; column++) {
                int codepoint = codepoints[column];
                if (codepoint == 0 || Character.isWhitespace(codepoint)) continue;
                if (codepoint > 0xFFFF) {
                    warnings.add("Bedrock glyph pages cannot represent supplementary character U+"
                            + Integer.toHexString(codepoint).toUpperCase(Locale.ROOT));
                    continue;
                }
                BufferedImage glyph = atlas.getSubimage(column * sourceCellWidth,
                        row * sourceCellHeight, sourceCellWidth, sourceCellHeight);
                int page = codepoint >>> 8;
                int cell = codepoint & 0xFF;
                BufferedImage old = pages.computeIfAbsent(page, ignored -> new TreeMap<>())
                        .put(cell, copy(glyph));
                if (old != null) warnings.add("Duplicate bitmap provider for U+"
                        + String.format(Locale.ROOT, "%04X", codepoint)
                        + "; the last provider wins");
            }
        }
    }

    private Path fontTexture(String reference) {
        String clean = reference.replace('\\', '/');
        String namespace = "minecraft";
        int colon = clean.indexOf(':');
        if (colon >= 0) {
            namespace = clean.substring(0, colon);
            clean = clean.substring(colon + 1);
        }
        clean = clean.replaceFirst("^textures/", "");
        Path texture = source.root().resolve("assets").resolve(namespace)
                .resolve("textures").resolve(clean);
        return Files.isRegularFile(texture) ? texture : null;
    }

    private boolean isFontDefinition(Path path) {
        String value = path.toString().replace('\\', '/');
        return Files.isRegularFile(path) && value.contains("/font/")
                && value.endsWith(".json");
    }

    private static Target fit(int width, int height, int cellX, int cellY) {
        double scale = Math.min((double) CELL / width, (double) CELL / height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        int x = cellX + (CELL - targetWidth) / 2;
        int y = cellY + CELL - targetHeight;
        return new Target(x, y, targetWidth, targetHeight);
    }

    private static void drawNearest(BufferedImage target, BufferedImage source, Target area) {
        for (int y = 0; y < area.height(); y++) {
            int sourceY = Math.min(source.getHeight() - 1,
                    y * source.getHeight() / area.height());
            for (int x = 0; x < area.width(); x++) {
                int sourceX = Math.min(source.getWidth() - 1,
                        x * source.getWidth() / area.width());
                target.setRGB(area.x() + x, area.y() + y, source.getRGB(sourceX, sourceY));
            }
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.getRGB(0, 0, source.getWidth(), source.getHeight(),
                null, 0, source.getWidth());
        image.setRGB(0, 0, source.getWidth(), source.getHeight(),
                pixels, 0, source.getWidth());
        return image;
    }

    private record Target(int x, int y, int width, int height) {}
}
