package dev.oraxenbedrock.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads both classic and modern pack.mcmeta version declarations.
 * Minecraft 1.20.5 starts at resource-pack format 32; unknown newer formats
 * remain accepted so a new game release does not require an artificial gate.
 */
public record JavaPackMetadata(Integer packFormat, Integer minFormat,
                               Integer maxFormat, List<Overlay> overlays) {
    public record Overlay(String directory, Integer minFormat, Integer maxFormat) {
        public boolean appliesTo(int format) {
            return (minFormat == null || format >= minFormat)
                    && (maxFormat == null || format <= maxFormat);
        }
    }

    public static JavaPackMetadata read(Path root) throws IOException {
        Path file = root.resolve("pack.mcmeta");
        if (!Files.isRegularFile(file))
            return new JavaPackMetadata(null, null, null, List.of());
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject pack = json.getAsJsonObject("pack");
            Integer format = pack == null ? null : integer(pack.get("pack_format"));
            JsonElement supported = pack != null && pack.has("supported_formats")
                    ? pack.get("supported_formats") : json.get("supported_formats");
            JsonElement directMin = pack != null && pack.has("min_format")
                    ? pack.get("min_format") : json.get("min_format");
            JsonElement directMax = pack != null && pack.has("max_format")
                    ? pack.get("max_format") : json.get("max_format");
            Integer min = firstInteger(directMin, supported, true);
            Integer max = firstInteger(directMax, supported, false);
            if (min == null) min = format;
            if (max == null) max = format;

            List<Overlay> overlays = new ArrayList<>();
            JsonObject overlayRoot = json.getAsJsonObject("overlays");
            if (overlayRoot != null && overlayRoot.has("entries")
                    && overlayRoot.get("entries").isJsonArray()) {
                for (JsonElement value : overlayRoot.getAsJsonArray("entries")) {
                    if (!value.isJsonObject()) continue;
                    JsonObject entry = value.getAsJsonObject();
                    JsonElement directory = entry.get("directory");
                    if (directory != null && directory.isJsonPrimitive()) {
                        JsonElement formats = entry.get("formats");
                        Integer overlayMin = firstInteger(null, formats, true);
                        Integer overlayMax = firstInteger(null, formats, false);
                        overlays.add(new Overlay(directory.getAsString(), overlayMin, overlayMax));
                    }
                }
            }
            return new JavaPackMetadata(format, min, max, List.copyOf(overlays));
        } catch (RuntimeException ex) {
            throw new IOException("Invalid pack.mcmeta: " + ex.getMessage(), ex);
        }
    }

    public boolean predatesSupportedRange() {
        return maxFormat != null && maxFormat < 32;
    }

    public List<Overlay> activeOverlays() {
        int target = packFormat != null ? packFormat
                : minFormat != null ? minFormat
                : maxFormat != null ? maxFormat : Integer.MAX_VALUE;
        return overlays.stream().filter(overlay -> overlay.appliesTo(target)).toList();
    }

    public String description() {
        if (packFormat == null && minFormat == null && maxFormat == null) return "unknown";
        if (minFormat != null && maxFormat != null && !minFormat.equals(maxFormat))
            return minFormat + "-" + maxFormat;
        return String.valueOf(packFormat != null ? packFormat : maxFormat);
    }

    private static Integer firstInteger(JsonElement direct, JsonElement supported, boolean minimum) {
        Integer value = integer(direct);
        if (value != null) return value;
        if (supported == null) return null;
        if (supported.isJsonArray()) {
            var array = supported.getAsJsonArray();
            if (array.isEmpty()) return null;
            return integer(array.get(minimum ? 0 : array.size() - 1));
        }
        if (supported.isJsonObject()) {
            JsonObject object = supported.getAsJsonObject();
            return integer(object.get(minimum ? "min_inclusive" : "max_inclusive"));
        }
        return integer(supported);
    }

    private static Integer integer(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty())
            return integer(value.getAsJsonArray().get(0));
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        return value.getAsInt();
    }
}
