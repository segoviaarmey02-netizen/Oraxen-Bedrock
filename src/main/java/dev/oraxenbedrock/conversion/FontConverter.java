package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Packs Java bitmap font providers into Bedrock's 256-codepoint glyph pages.
 * Oraxen emojis use private-use BMP characters, which map directly to
 * font/glyph_XX.png cells in Bedrock.
 */
final class FontConverter {
    record Result(int glyphs, int pages) {}

    private static final int MIN_CELL = 16;
    /*
     * Bedrock renders the usual 16px private-use glyph slot at roughly the
     * height of normal text. That is too small for Oraxen emoji sprites.
     * A 64px slot (a 1024x1024 page) gives 8x9 and 16x16 emoji a readable
     * visual size on Bedrock's UI while retaining crisp nearest-neighbour
     * pixels. Servers can lower or raise it through emoji-cell-size.
     */
    private static final int DEFAULT_EMOJI_CELL = 64;
    private static final int MAX_CELL = 128;
    private final PackSource source;
    private final Path bedrock;
    private final int minEmojiCell;

    FontConverter(PackSource source, Path bedrock) {
        this(source, bedrock, DEFAULT_EMOJI_CELL);
    }

    FontConverter(PackSource source, Path bedrock, int minEmojiCell) {
        this.source = source;
        this.bedrock = bedrock;
        this.minEmojiCell = powerOfTwoCell(
                Math.max(MIN_CELL, Math.min(MAX_CELL, minEmojiCell)));
    }

    Result convert(List<String> warnings) throws IOException {
        Map<Integer, Map<Integer, Glyph>> pages = new TreeMap<>();
        if (source.assetRoots().isEmpty()) return new Result(0, 0);

        for (PackSource.AssetFile asset : source.effectiveAssetFiles()) {
            if (!isFontDefinition(asset.relative())) continue;
            Path file = asset.path();
            try {
                JsonArray providers = JsonSupport.readObject(file).getAsJsonArray("providers");
                if (providers == null) continue;
                for (JsonElement value : providers) {
                    if (!value.isJsonObject()) continue;
                    JsonObject provider = value.getAsJsonObject();
                    if (!provider.has("type")
                            || !provider.get("type").getAsString()
                            .replaceFirst("^minecraft:", "").equals("bitmap"))
                        continue;
                    readProvider(provider, pages, warnings);
                }
            } catch (IOException | RuntimeException ex) {
                warnings.add("Could not convert bitmap font " + file + ": " + ex.getMessage());
            }
        }

        int glyphCount = 0;
        for (Map.Entry<Integer, Map<Integer, Glyph>> pageEntry : pages.entrySet()) {
            int page = pageEntry.getKey();
            int cellSize = pageCellSize(page, pageEntry.getValue().values());
            BufferedImage sheet = new BufferedImage(cellSize * 16, cellSize * 16,
                    BufferedImage.TYPE_INT_ARGB);
            for (Map.Entry<Integer, Glyph> glyph : pageEntry.getValue().entrySet()) {
                int index = glyph.getKey();
                Glyph definition = glyph.getValue();
                BufferedImage image = definition.image();
                int visualSize = isPrivateUsePage(page)
                        ? visualCellSize(page, definition) : cellSize;
                Target target = fit(image.getWidth(), image.getHeight(),
                        index % 16 * cellSize, index / 16 * cellSize, cellSize,
                        visualSize, definition.height(), definition.ascent());
                drawScaled(sheet, image, target);
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
                              Map<Integer, Map<Integer, Glyph>> pages,
                              List<String> warnings) throws IOException {
        if (!provider.has("file") || !provider.has("chars")
                || !provider.get("chars").isJsonArray()) return;
        Path texture = fontTexture(provider.get("file").getAsString());
        if (texture == null) {
            String reference = provider.get("file").getAsString();
            // The vanilla ASCII sheet is client-owned and normally absent
            // from a server pack; Bedrock supplies its own base font.
            if (!reference.equalsIgnoreCase("minecraft:font/ascii.png"))
                warnings.add("Bitmap font texture not found: " + reference);
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
        if (atlas.getWidth() % columns != 0 || atlas.getHeight() % rows.size() != 0) {
            warnings.add("Bitmap font grid does not evenly divide texture "
                    + provider.get("file").getAsString());
            return;
        }
        int sourceCellWidth = atlas.getWidth() / columns;
        int sourceCellHeight = atlas.getHeight() / rows.size();
        if (sourceCellWidth <= 0 || sourceCellHeight <= 0) {
            warnings.add("Invalid bitmap font grid in " + provider.get("file").getAsString());
            return;
        }
        int renderHeight = clamp(provider.has("height")
                ? provider.get("height").getAsInt() : 8, 1, MAX_CELL);
        int ascent = clamp(provider.has("ascent")
                ? provider.get("ascent").getAsInt() : renderHeight,
                0, MAX_CELL);
        if (provider.has("height") && provider.get("height").getAsInt() > MAX_CELL)
            warnings.add("Bitmap font height above 128 was scaled down for Bedrock: "
                    + provider.get("file").getAsString());

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
                BufferedImage visibleGlyph = trimTransparent(glyph);
                if (visibleGlyph == null) {
                    warnings.add("Empty bitmap glyph U+"
                            + String.format(Locale.ROOT, "%04X", codepoint)
                            + " in " + provider.get("file").getAsString());
                    continue;
                }
                int page = codepoint >>> 8;
                int cell = codepoint & 0xFF;
                Glyph old = pages.computeIfAbsent(page, ignored -> new TreeMap<>())
                        .put(cell, new Glyph(visibleGlyph, renderHeight, ascent));
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
        return source.findAsset(namespace, "textures/" + clean);
    }

    private boolean isFontDefinition(String relative) {
        String value = relative.replace('\\', '/');
        return value.startsWith("font/") && value.endsWith(".json");
    }

    private static Target fit(int width, int height, int cellX, int cellY, int cellSize,
                              int visualSize, int renderHeight, int ascent) {
        double scale = Math.min((double) visualSize / width,
                (double) visualSize / height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        int x = cellX + (cellSize - targetWidth) / 2;
        int descent = Math.max(0, renderHeight - ascent);
        int scaledDescent =
                (int) Math.round((double) descent * visualSize / renderHeight);
        int y = cellY + Math.max(0, cellSize - targetHeight - scaledDescent);
        return new Target(x, y, targetWidth, targetHeight);
    }

    private int pageCellSize(int page, Collection<Glyph> glyphs) {
        int required = MIN_CELL;
        for (Glyph glyph : glyphs) {
            required = Math.max(required, Math.max(
                    glyph.image().getWidth(), glyph.image().getHeight()));
            required = Math.max(required, visualCellSize(page, glyph));
        }
        return powerOfTwoCell(required);
    }

    private int visualCellSize(int page, Glyph glyph) {
        if (!isPrivateUsePage(page)) return MIN_CELL;
        int requested = Math.max(minEmojiCell, glyph.height() * 2);
        return powerOfTwoCell(requested);
    }

    private static boolean isPrivateUsePage(int page) {
        return page >= 0xE0 && page <= 0xF8;
    }

    private static int powerOfTwoCell(int required) {
        int size = MIN_CELL;
        while (size < required && size < MAX_CELL) size *= 2;
        return Math.min(size, MAX_CELL);
    }

    private static void drawScaled(BufferedImage target, BufferedImage source, Target area) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            boolean downscale = area.width() < source.getWidth()
                    || area.height() < source.getHeight();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    downscale ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                            : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, area.x(), area.y(),
                    area.x() + area.width(), area.y() + area.height(),
                    0, 0, source.getWidth(), source.getHeight(), null);
        } finally {
            graphics.dispose();
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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

    private static BufferedImage trimTransparent(BufferedImage source) {
        int left = source.getWidth();
        int top = source.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if ((source.getRGB(x, y) >>> 24) == 0) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) return null;
        return copy(source.getSubimage(
                left, top, right - left + 1, bottom - top + 1));
    }

    private record Target(int x, int y, int width, int height) {}
    private record Glyph(BufferedImage image, int height, int ascent) {}
}
