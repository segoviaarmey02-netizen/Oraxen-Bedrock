package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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
import java.util.stream.Stream;

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
    private final Path glyphConfigDirectory;

    FontConverter(PackSource source, Path bedrock) {
        this(source, bedrock, DEFAULT_EMOJI_CELL);
    }

    FontConverter(PackSource source, Path bedrock, int minEmojiCell) {
        this(source, bedrock, minEmojiCell, null);
    }

    FontConverter(PackSource source, Path bedrock, int minEmojiCell,
                  Path glyphConfigDirectory) {
        this.source = source;
        this.bedrock = bedrock;
        this.minEmojiCell = powerOfTwoCell(
                Math.max(MIN_CELL, Math.min(MAX_CELL, minEmojiCell)));
        this.glyphConfigDirectory = glyphConfigDirectory;
    }

    Result convert(List<String> warnings) throws IOException {
        Map<Integer, Map<Integer, Glyph>> pages = new TreeMap<>();
        Set<Integer> oraxenGlyphCodepoints =
                readConfiguredGlyphCodepoints(warnings);
        int fontDefinitions = 0;
        int bitmapProviders = 0;
        List<PackSource.AssetFile> fontAssets = source.effectiveAssetFiles().stream()
                .filter(asset -> isFontDefinition(asset.relative()))
                // Java searches providers in declaration order. The generated
                // ZIP is authoritative over the uncompressed fallback even
                // when the two definitions use different namespaces.
                .sorted(Comparator.comparing(PackSource.AssetFile::fallback))
                .toList();
        for (PackSource.AssetFile asset : fontAssets) {
            fontDefinitions++;
            Path file = asset.path();
            try {
                JsonArray providers = JsonSupport.readObject(file).getAsJsonArray("providers");
                if (providers == null) continue;
                for (JsonElement value : providers) {
                    if (!value.isJsonObject()) continue;
                    JsonObject provider = value.getAsJsonObject();
                    try {
                        if (!provider.has("type")) continue;
                        String type = provider.get("type").getAsString()
                                .replaceFirst("^minecraft:", "");
                        if (!type.equals("bitmap")) {
                            if (type.equals("ttf") || type.equals("unihex"))
                                warnings.add("Unsupported Java font provider '" + type
                                        + "' in " + file);
                            continue;
                        }
                        bitmapProviders++;
                        readProvider(provider, pages, oraxenGlyphCodepoints,
                                warnings);
                    } catch (IOException | RuntimeException ex) {
                        warnings.add("Could not convert bitmap provider in "
                                + file + ": " + ex.getMessage());
                    }
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
                int visualSize = definition.emoji()
                        ? visualCellSize(definition)
                        : intrinsicCellSize(definition);
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
        if (fontDefinitions > 0 && bitmapProviders == 0)
            warnings.add("Java font definitions were found, but none contain "
                    + "convertible bitmap glyphs");
        else if (bitmapProviders > 0 && glyphCount == 0)
            warnings.add("Java bitmap font providers were found, but no visible "
                    + "Bedrock glyphs could be generated");
        return new Result(glyphCount, pages.size());
    }

    private void readProvider(JsonObject provider,
                              Map<Integer, Map<Integer, Glyph>> pages,
                              Set<Integer> oraxenGlyphCodepoints,
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
                Glyph definition = new Glyph(visibleGlyph, renderHeight, ascent,
                        isEmojiCodepoint(codepoint, oraxenGlyphCodepoints));
                Glyph old = pages.computeIfAbsent(page, ignored -> new TreeMap<>())
                        .putIfAbsent(cell, definition);
                if (old != null) warnings.add("Duplicate bitmap provider for U+"
                        + String.format(Locale.ROOT, "%04X", codepoint)
                        + "; the first provider was kept");
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
            if (glyph.emoji())
                required = Math.max(required, visualCellSize(glyph));
        }
        return powerOfTwoCell(required);
    }

    private int visualCellSize(Glyph glyph) {
        int requested = Math.max(minEmojiCell, glyph.height() * 2);
        return powerOfTwoCell(requested);
    }

    private int intrinsicCellSize(Glyph glyph) {
        return powerOfTwoCell(Math.max(MIN_CELL, Math.max(
                glyph.image().getWidth(), glyph.image().getHeight())));
    }

    private boolean isEmojiCodepoint(int codepoint,
                                     Set<Integer> oraxenGlyphCodepoints) {
        // Oraxen auto-assigns glyphs from decimal 42000 (U+A410), outside the
        // private-use area. Only codes actually declared by Oraxen are scaled;
        // treating the whole interval as emoji would also enlarge real Unicode
        // scripts such as Hangul.
        return codepoint >= 0xE000 && codepoint <= 0xF8FF
                || oraxenGlyphCodepoints.contains(codepoint);
    }

    private Set<Integer> readConfiguredGlyphCodepoints(List<String> warnings) {
        if (glyphConfigDirectory == null
                || !Files.isDirectory(glyphConfigDirectory)) return Set.of();
        Set<Integer> result = new LinkedHashSet<>();
        List<Map<?, ?>> configuredGlyphs = new ArrayList<>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Stream<Path> paths = Files.walk(glyphConfigDirectory)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(FontConverter::isYaml).sorted().toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    Object document = yaml.load(input);
                    if (!(document instanceof Map<?, ?> root)) continue;
                    for (Object value : root.values()) {
                        if (!(value instanceof Map<?, ?> glyph)) continue;
                        configuredGlyphs.add(glyph);
                        Object characters = valueIgnoreCase(glyph, "char");
                        if (characters == null)
                            characters = valueIgnoreCase(glyph, "chars");
                        collectCharacters(characters, result);
                        Object legacyCode = valueIgnoreCase(glyph, "code");
                        if (legacyCode instanceof Number number)
                            result.add(number.intValue());
                        else if (legacyCode instanceof String text) {
                            try {
                                result.add(Integer.parseInt(text.trim()));
                            } catch (NumberFormatException ignored) {
                                // Oraxen only treats numeric legacy codes as codes.
                            }
                        }
                    }
                } catch (IOException | RuntimeException exception) {
                    warnings.add("Could not read Oraxen glyph configuration "
                            + file + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            warnings.add("Could not scan Oraxen glyph configurations: "
                    + exception.getMessage());
        }
        // With disable_automatic_glyph_code enabled, Oraxen still assigns
        // sequential characters to generated providers but intentionally does
        // not write them back to YAML. Mirror that allocation so U+A410+
        // glyphs keep their configured Bedrock size in this mode too.
        for (Map<?, ?> glyph : configuredGlyphs) {
            if (hasConfiguredCharacters(glyph)
                    || valueIgnoreCase(glyph, "reference") instanceof Map<?, ?>
                    || (valueIgnoreCase(glyph, "texture") != null
                    && valueIgnoreCase(glyph, "animation") instanceof Map<?, ?>))
                continue;
            Map<?, ?> grid = valueIgnoreCase(glyph, "grid") instanceof Map<?, ?> map
                    ? map : Map.of();
            int rows = positiveInt(valueIgnoreCase(glyph, "rows"),
                    positiveInt(valueIgnoreCase(grid, "rows"), 1));
            Object columnsValue = valueIgnoreCase(glyph, "columns");
            if (columnsValue == null) columnsValue = valueIgnoreCase(glyph, "cols");
            int columns = positiveInt(columnsValue,
                    positiveInt(valueIgnoreCase(grid, "columns"), 1));
            for (int cell = 0; cell < rows * columns; cell++) {
                int codepoint = 42000;
                while (result.contains(codepoint)) codepoint++;
                result.add(codepoint);
            }
        }
        return result;
    }

    private static boolean hasConfiguredCharacters(Map<?, ?> glyph) {
        Object characters = valueIgnoreCase(glyph, "char");
        if (characters == null) characters = valueIgnoreCase(glyph, "chars");
        if (characters instanceof String text && !text.isBlank()) return true;
        if (characters instanceof Collection<?> values && !values.isEmpty()) return true;
        Object code = valueIgnoreCase(glyph, "code");
        if (code instanceof Number) return true;
        if (code instanceof String text)
            try {
                Integer.parseInt(text.trim());
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        return false;
    }

    private static int positiveInt(Object value, int fallback) {
        int parsed;
        if (value instanceof Number number) parsed = number.intValue();
        else if (value instanceof String text)
            try {
                parsed = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        else return fallback;
        return Math.max(1, parsed);
    }

    private static void collectCharacters(Object value, Set<Integer> result) {
        if (value instanceof Collection<?> values) {
            values.forEach(entry -> collectCharacters(entry, result));
        } else if (value instanceof String text) {
            text.codePoints().forEach(result::add);
        } else if (value instanceof Character character) {
            result.add((int) character);
        }
    }

    private static Object valueIgnoreCase(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet())
            if (String.valueOf(entry.getKey()).equalsIgnoreCase(key))
                return entry.getValue();
        return null;
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
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
    private record Glyph(BufferedImage image, int height, int ascent,
                         boolean emoji) {}
}
