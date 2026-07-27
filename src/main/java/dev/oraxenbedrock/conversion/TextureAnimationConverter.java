package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads Java *.png.mcmeta animation metadata and prepares the vertical frame
 * strips expected by Bedrock. Per-frame Java timings are represented by
 * repeated indices at their greatest-common-divisor tick rate.
 */
final class TextureAnimationConverter {
    private int installedAnimations;

    record Animation(int frameCount, int frameWidth, int frameHeight,
                     int ticksPerFrame, List<Integer> frames, boolean interpolate) {
        JsonObject flipbook(String atlasTile, String texturePath) {
            JsonObject value = new JsonObject();
            value.addProperty("flipbook_texture", texturePath);
            value.addProperty("atlas_tile", atlasTile);
            value.addProperty("ticks_per_frame", ticksPerFrame);
            JsonArray sequence = new JsonArray();
            frames.forEach(sequence::add);
            value.add("frames", sequence);
            value.addProperty("blend_frames", interpolate);
            return value;
        }

        String textureExpression() {
            return "Array.frames[math.mod(math.floor(q.life_time * "
                    + decimal(20.0 / ticksPerFrame) + "), " + frames.size() + ")]";
        }

        private static String decimal(double value) {
            return String.format(Locale.ROOT, "%.6f", value)
                    .replaceAll("0+$", "").replaceAll("\\.$", ".0");
        }
    }

    Animation install(Path source, Path target, List<String> warnings) throws IOException {
        Path metadata = source.resolveSibling(source.getFileName() + ".mcmeta");
        if (!Files.isRegularFile(metadata)) {
            copy(source, target);
            return null;
        }
        try {
            JsonObject root = JsonSupport.readObject(metadata);
            JsonObject section = root.getAsJsonObject("animation");
            if (section == null) {
                copy(source, target);
                return null;
            }
            Animation animation = convert(source, target, section, warnings);
            if (animation != null) installedAnimations++;
            return animation;
        } catch (IOException | RuntimeException ex) {
            copy(source, target);
            warnings.add("Could not convert texture animation " + metadata + ": "
                    + ex.getMessage());
            return null;
        }
    }

    int installedAnimations() {
        return installedAnimations;
    }

    void keepFirstFrame(Path strip, Animation animation) throws IOException {
        BufferedImage image;
        try (InputStream input = Files.newInputStream(strip)) {
            image = ImageIO.read(input);
        }
        if (image == null) throw new IOException("Unreadable converted animation: " + strip);
        writeImage(image.getSubimage(0, 0, animation.frameWidth(),
                animation.frameHeight()), strip);
    }

    List<String> writeFrameFiles(Path strip, String texturePath,
                                 Animation animation) throws IOException {
        BufferedImage image;
        try (InputStream input = Files.newInputStream(strip)) {
            image = ImageIO.read(input);
        }
        if (image == null) throw new IOException("Unreadable converted animation: " + strip);
        String fileName = strip.getFileName().toString();
        String stem = fileName.endsWith(".png")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        List<String> paths = new ArrayList<>();
        for (int frame = 0; frame < animation.frameCount(); frame++) {
            Path target = strip.resolveSibling(stem + "_" + frame + ".png");
            writeImage(image.getSubimage(0, frame * animation.frameHeight(),
                    animation.frameWidth(), animation.frameHeight()), target);
            paths.add(texturePath + "_" + frame);
        }
        return List.copyOf(paths);
    }

    private Animation convert(Path source, Path target, JsonObject section,
                              List<String> warnings) throws IOException {
        BufferedImage image;
        try (InputStream input = Files.newInputStream(source)) {
            image = ImageIO.read(input);
        }
        if (image == null) throw new IOException("Unreadable animated PNG: " + source);

        int frameWidth = positiveInt(section, "width", image.getWidth());
        int frameHeight = positiveInt(section, "height", frameWidth);
        int columns = image.getWidth() / frameWidth;
        int rows = image.getHeight() / frameHeight;
        int frameCount = columns * rows;
        if (columns <= 0 || rows <= 0 || image.getWidth() % frameWidth != 0
                || image.getHeight() % frameHeight != 0)
            throw new IOException("Texture size " + image.getWidth() + "x" + image.getHeight()
                    + " is not divisible by animation frame " + frameWidth + "x" + frameHeight);
        if (frameCount < 2) {
            copy(source, target);
            warnings.add("Animation metadata has only one frame: " + source);
            return null;
        }

        int defaultTime = positiveInt(section, "frametime", 1);
        List<TimedFrame> timedFrames = readFrames(section.getAsJsonArray("frames"),
                frameCount, defaultTime, warnings, source);
        if (timedFrames.isEmpty()) {
            for (int index = 0; index < frameCount; index++)
                timedFrames.add(new TimedFrame(index, defaultTime));
        }
        int tick = timedFrames.stream().mapToInt(TimedFrame::time)
                .reduce(TextureAnimationConverter::gcd).orElse(defaultTime);
        List<Integer> sequence = new ArrayList<>();
        for (TimedFrame frame : timedFrames)
            for (int repeat = 0; repeat < frame.time() / tick; repeat++)
                sequence.add(frame.index());

        BufferedImage strip = new BufferedImage(frameWidth, frameHeight * frameCount,
                BufferedImage.TYPE_INT_ARGB);
        for (int index = 0; index < frameCount; index++) {
            int sourceX = index % columns * frameWidth;
            int sourceY = index / columns * frameHeight;
            int[] pixels = image.getRGB(sourceX, sourceY, frameWidth, frameHeight,
                    null, 0, frameWidth);
            strip.setRGB(0, index * frameHeight, frameWidth, frameHeight,
                    pixels, 0, frameWidth);
        }
        Files.createDirectories(target.getParent());
        writeImage(strip, target);
        boolean interpolate = section.has("interpolate")
                && section.get("interpolate").getAsBoolean();
        return new Animation(frameCount, frameWidth, frameHeight,
                tick, List.copyOf(sequence), interpolate);
    }

    private List<TimedFrame> readFrames(JsonArray values, int frameCount, int defaultTime,
                                        List<String> warnings, Path source) {
        if (values == null) return new ArrayList<>();
        List<TimedFrame> result = new ArrayList<>();
        for (JsonElement value : values) {
            int index;
            int time = defaultTime;
            if (value.isJsonPrimitive()) {
                index = value.getAsInt();
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (!object.has("index")) continue;
                index = object.get("index").getAsInt();
                time = positiveInt(object, "time", defaultTime);
            } else continue;
            if (index < 0 || index >= frameCount) {
                warnings.add("Skipped invalid animation frame " + index + " in " + source);
                continue;
            }
            result.add(new TimedFrame(index, time));
        }
        return result;
    }

    private static int positiveInt(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        int value = object.get(key).getAsInt();
        return value > 0 ? value : fallback;
    }

    private static int gcd(int left, int right) {
        while (right != 0) {
            int old = right;
            right = left % right;
            left = old;
        }
        return Math.max(1, Math.abs(left));
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeImage(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream output = Files.newOutputStream(target)) {
            if (!ImageIO.write(image, "png", output))
                throw new IOException("No PNG writer is available for " + target);
        }
    }

    private record TimedFrame(int index, int time) {}
}
