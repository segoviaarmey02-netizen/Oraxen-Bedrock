package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIconRendererTest {
    @TempDir Path temp;

    @Test
    void restoresJavaXAxisBeforeApplyingGuiTransform() throws Exception {
        Path red = solid("red.png", 16, 0xFFFF0000);
        Path blue = solid("blue.png", 16, 0xFF0000FF);
        JsonArray cubes = new JsonArray();
        // Java red was on +X and is therefore on -X in converted Bedrock space.
        cubes.add(cube(-6, 0, 0, 4, 8, 4, "red", 16));
        cubes.add(cube(2, 0, 0, 4, 8, 4, "blue", 16));
        BufferedImage icon = new ModelIconRenderer().render(model(
                cubes, 16, Map.of(
                        "red", material("red", red, 16),
                        "blue", material("blue", blue, 16))));

        long redX = 0, redPixels = 0, blueX = 0, bluePixels = 0;
        for (int y = 0; y < icon.getHeight(); y++) for (int x = 0;
                x < icon.getWidth(); x++) {
            int color = icon.getRGB(x, y);
            if ((color >>> 24) == 0) continue;
            int r = color >> 16 & 0xFF, b = color & 0xFF;
            if (r > b) { redX += x; redPixels++; }
            if (b > r) { blueX += x; bluePixels++; }
        }
        assertTrue(redPixels > 20 && bluePixels > 20);
        assertTrue((double) redX / redPixels > (double) blueX / bluePixels,
                "Java +X material must remain on the right in a zero-rotation GUI view");
    }

    @Test
    void normalizesGlobalUvsForLowerResolutionMaterial() throws Exception {
        Path split = temp.resolve("split.png");
        BufferedImage source = new BufferedImage(16, 16,
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            source.setRGB(x, y, x < 8 ? 0xFFFF0000 : 0xFF0000FF);
        ImageIO.write(source, "png", split.toFile());

        JsonArray cubes = new JsonArray();
        cubes.add(cube(-8, 0, 0, 16, 16, 1, "small", 64));
        BufferedImage icon = new ModelIconRenderer().render(model(
                cubes, 64, Map.of("small", material("small", split, 16))));

        int red = 0, blue = 0;
        for (int y = 0; y < icon.getHeight(); y++) for (int x = 0;
                x < icon.getWidth(); x++) {
            int color = icon.getRGB(x, y);
            if ((color >>> 24) == 0) continue;
            if ((color >> 16 & 0xFF) > (color & 0xFF)) red++;
            if ((color & 0xFF) > (color >> 16 & 0xFF)) blue++;
        }
        assertTrue(red > 300 && blue > 300,
                "Both halves of a 16px material must survive a 64px UV canvas");
    }

    private JavaModelConverter.ConvertedModel model(
            JsonArray cubes, int textureSize,
            Map<String, JavaModelConverter.Material> materials) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.test.icon");
        description.addProperty("texture_width", textureSize);
        description.addProperty("texture_height", textureSize);
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "root");
        bone.add("cubes", cubes);
        JsonArray bones = new JsonArray();
        bones.add(bone);
        JsonObject definition = new JsonObject();
        definition.add("description", description);
        definition.add("bones", bones);
        JsonArray definitions = new JsonArray();
        definitions.add(definition);
        JsonObject geometry = new JsonObject();
        geometry.add("minecraft:geometry", definitions);
        JsonObject display = new JsonObject();
        JsonObject gui = new JsonObject();
        gui.add("rotation", triple(0, 0, 0));
        gui.add("translation", triple(0, 0, 0));
        gui.add("scale", triple(1, 1, 1));
        display.add("gui", gui);
        return new JavaModelConverter.ConvertedModel(
                "geometry.test.icon", geometry,
                new LinkedHashMap<>(materials), display,
                false, false, List.of());
    }

    private JsonObject cube(double x, double y, double z,
                            double sx, double sy, double sz,
                            String material, int uvSize) {
        JsonObject cube = new JsonObject();
        cube.add("origin", triple(x, y, z));
        cube.add("size", triple(sx, sy, sz));
        JsonObject face = new JsonObject();
        face.add("uv", pair(0, 0));
        face.add("uv_size", pair(uvSize, uvSize));
        face.addProperty("material_instance", material);
        JsonObject faces = new JsonObject();
        faces.add("north", face);
        cube.add("uv", faces);
        return cube;
    }

    private JavaModelConverter.Material material(
            String name, Path path, int size) {
        return new JavaModelConverter.Material(
                name, "test:" + name, path, size, size);
    }

    private Path solid(String name, int size, int color) throws Exception {
        Path path = temp.resolve(name);
        BufferedImage image = new BufferedImage(
                size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            image.setRGB(x, y, color);
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private JsonArray triple(double x, double y, double z) {
        JsonArray result = new JsonArray();
        result.add(x); result.add(y); result.add(z);
        return result;
    }

    private JsonArray pair(double x, double y) {
        JsonArray result = new JsonArray();
        result.add(x); result.add(y);
        return result;
    }
}
