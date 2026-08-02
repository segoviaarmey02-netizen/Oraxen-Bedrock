package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic orthographic renderer used for Bedrock inventory icons.
 * Copying the first texture of a Blockbench model exposes its UV atlas rather
 * than the item Java players see in the GUI. Rendering the converted cubes
 * gives furniture and other 3D items a useful, model-shaped thumbnail without
 * requiring a desktop OpenGL context on the server.
 */
final class ModelIconRenderer {
    private static final int ICON_SIZE = 64;
    private static final int MARGIN = 5;

    BufferedImage render(JavaModelConverter.ConvertedModel model) throws IOException {
        if (model == null || model.geometry() == null || model.materials().isEmpty())
            return null;

        int[] textureCanvas = textureCanvas(model.geometry());
        Map<String, Texture> textures = loadTextures(
                model.materials(), textureCanvas[0], textureCanvas[1]);
        if (textures.isEmpty()) return null;
        Transform gui = guiTransform(model.display());
        List<Face> faces = collectFaces(model.geometry(), textures, gui);
        if (faces.isEmpty()) return null;

        Bounds bounds = bounds(faces);
        if (!bounds.valid()) return null;
        double width = Math.max(1.0e-6, bounds.maxX() - bounds.minX());
        double height = Math.max(1.0e-6, bounds.maxY() - bounds.minY());
        double scale = Math.min(
                (ICON_SIZE - MARGIN * 2.0) / width,
                (ICON_SIZE - MARGIN * 2.0) / height);
        double offsetX = ICON_SIZE / 2.0
                - (bounds.minX() + bounds.maxX()) * scale / 2.0;
        double offsetY = ICON_SIZE / 2.0
                + (bounds.minY() + bounds.maxY()) * scale / 2.0;

        BufferedImage icon = new BufferedImage(
                ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        double[][] depth = new double[ICON_SIZE][ICON_SIZE];
        for (int y = 0; y < ICON_SIZE; y++)
            java.util.Arrays.fill(depth[y], Double.NEGATIVE_INFINITY);

        for (Face face : faces) {
            ScreenVertex[] vertices = new ScreenVertex[4];
            for (int index = 0; index < 4; index++) {
                Vertex vertex = face.vertices()[index];
                vertices[index] = new ScreenVertex(
                        vertex.position().x() * scale + offsetX,
                        -vertex.position().y() * scale + offsetY,
                        vertex.position().z(), vertex.u(), vertex.v());
            }
            drawTriangle(icon, depth, vertices[0], vertices[1], vertices[2],
                    face.texture(), face.shade());
            drawTriangle(icon, depth, vertices[0], vertices[2], vertices[3],
                    face.texture(), face.shade());
        }
        return hasVisiblePixels(icon) ? icon : null;
    }

    private Map<String, Texture> loadTextures(
            Map<String, JavaModelConverter.Material> materials,
            int coordinateWidth, int coordinateHeight) throws IOException {
        Map<String, Texture> textures = new HashMap<>();
        for (JavaModelConverter.Material material : materials.values()) {
            BufferedImage image;
            try (InputStream input = Files.newInputStream(material.source())) {
                image = ImageIO.read(input);
            }
            if (image == null) continue;
            int width = Math.max(1, Math.min(material.width(), image.getWidth()));
            int height = Math.max(1, Math.min(material.height(), image.getHeight()));
            textures.putIfAbsent(material.name(), new Texture(
                    image, width, height, coordinateWidth, coordinateHeight));
        }
        return textures;
    }

    private int[] textureCanvas(JsonObject geometry) {
        int width = 16, height = 16;
        JsonArray definitions = geometry.getAsJsonArray("minecraft:geometry");
        if (definitions == null || definitions.isEmpty()
                || !definitions.get(0).isJsonObject())
            return new int[]{width, height};
        JsonObject description = definitions.get(0).getAsJsonObject()
                .getAsJsonObject("description");
        if (description == null) return new int[]{width, height};
        if (description.has("texture_width"))
            width = Math.max(1, description.get("texture_width").getAsInt());
        if (description.has("texture_height"))
            height = Math.max(1, description.get("texture_height").getAsInt());
        return new int[]{width, height};
    }

    private List<Face> collectFaces(
            JsonObject geometry, Map<String, Texture> textures, Transform gui) {
        List<Face> result = new ArrayList<>();
        JsonArray definitions = geometry.getAsJsonArray("minecraft:geometry");
        if (definitions == null) return result;
        for (JsonElement definitionValue : definitions) {
            if (!definitionValue.isJsonObject()) continue;
            JsonArray bones = definitionValue.getAsJsonObject().getAsJsonArray("bones");
            if (bones == null) continue;
            for (JsonElement boneValue : bones) {
                if (!boneValue.isJsonObject()) continue;
                JsonArray cubes = boneValue.getAsJsonObject().getAsJsonArray("cubes");
                if (cubes == null) continue;
                for (JsonElement cubeValue : cubes) {
                    if (cubeValue.isJsonObject())
                        collectCube(cubeValue.getAsJsonObject(), textures, gui, result);
                }
            }
        }
        return result;
    }

    private void collectCube(JsonObject cube, Map<String, Texture> textures,
                             Transform gui, List<Face> output) {
        double[] origin = triple(cube.getAsJsonArray("origin"), 0);
        double[] size = triple(cube.getAsJsonArray("size"), 0);
        double x0 = origin[0], x1 = origin[0] + size[0];
        double y0 = origin[1], y1 = origin[1] + size[1];
        double z0 = origin[2], z1 = origin[2] + size[2];
        Map<String, Vec[]> vertices = Map.of(
                "west", new Vec[]{vec(x0, y0, z1), vec(x0, y0, z0),
                        vec(x0, y1, z0), vec(x0, y1, z1)},
                "east", new Vec[]{vec(x1, y0, z0), vec(x1, y0, z1),
                        vec(x1, y1, z1), vec(x1, y1, z0)},
                "down", new Vec[]{vec(x0, y0, z0), vec(x1, y0, z0),
                        vec(x1, y0, z1), vec(x0, y0, z1)},
                "up", new Vec[]{vec(x0, y1, z1), vec(x1, y1, z1),
                        vec(x1, y1, z0), vec(x0, y1, z0)},
                "north", new Vec[]{vec(x1, y0, z0), vec(x0, y0, z0),
                        vec(x0, y1, z0), vec(x1, y1, z0)},
                "south", new Vec[]{vec(x0, y0, z1), vec(x1, y0, z1),
                        vec(x1, y1, z1), vec(x0, y1, z1)});
        JsonObject uv = cube.getAsJsonObject("uv");
        if (uv == null) return;
        double[] pivot = triple(cube.getAsJsonArray("pivot"), 0);
        double[] cubeRotation = triple(cube.getAsJsonArray("rotation"), 0);

        for (Map.Entry<String, JsonElement> entry : uv.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Vec[] faceVertices = vertices.get(entry.getKey());
            if (faceVertices == null) continue;
            JsonObject faceData = entry.getValue().getAsJsonObject();
            String material = string(faceData, "material_instance");
            Texture texture = material == null ? null : textures.get(material);
            if (texture == null) continue;
            double[] start = pair(faceData.getAsJsonArray("uv"), 0);
            double[] extent = pair(faceData.getAsJsonArray("uv_size"), 0);
            double[][] coordinates = {
                    {start[0], start[1] + extent[1]},
                    {start[0] + extent[0], start[1] + extent[1]},
                    {start[0] + extent[0], start[1]},
                    {start[0], start[1]}
            };
            int rotations = faceData.has("uv_rotation")
                    ? Math.floorMod(faceData.get("uv_rotation").getAsInt(), 360) / 90 : 0;
            Vertex[] transformed = new Vertex[4];
            for (int index = 0; index < 4; index++) {
                Vec positioned = rotateAround(
                        faceVertices[index], vec(pivot), cubeRotation);
                positioned = gui.apply(positioned);
                double[] coordinate = coordinates[Math.floorMod(index - rotations, 4)];
                transformed[index] = new Vertex(
                        positioned, coordinate[0], coordinate[1]);
            }
            output.add(new Face(transformed, texture, shade(transformed)));
        }
    }

    private Transform guiTransform(JsonObject display) {
        JsonObject gui = display == null ? null : display.getAsJsonObject("gui");
        double[] rotation = gui == null
                ? new double[]{30, 225, 0}
                : triple(gui.getAsJsonArray("rotation"), 0);
        double[] translation = gui == null
                ? new double[]{0, 0, 0}
                : triple(gui.getAsJsonArray("translation"), 0);
        double[] scale = gui == null
                ? new double[]{0.625, 0.625, 0.625}
                : triple(gui.getAsJsonArray("scale"), 1);
        return new Transform(rotation, translation, scale);
    }

    private void drawTriangle(BufferedImage target, double[][] depth,
                              ScreenVertex a, ScreenVertex b, ScreenVertex c,
                              Texture texture, double shade) {
        double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
        if (Math.abs(area) < 1.0e-8) return;
        int minX = clamp((int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))));
        int maxX = clamp((int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))));
        int minY = clamp((int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))));
        int maxY = clamp((int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5, py = y + 0.5;
                double wa = edge(b.x(), b.y(), c.x(), c.y(), px, py) / area;
                double wb = edge(c.x(), c.y(), a.x(), a.y(), px, py) / area;
                double wc = 1 - wa - wb;
                if (wa < -1.0e-7 || wb < -1.0e-7 || wc < -1.0e-7) continue;
                double z = wa * a.z() + wb * b.z() + wc * c.z();
                if (z < depth[y][x]) continue;
                double u = wa * a.u() + wb * b.u() + wc * c.u();
                double v = wa * a.v() + wb * b.v() + wc * c.v();
                int source = texture.sample(u, v);
                if ((source >>> 24) == 0) continue;
                source = shade(source, shade);
                target.setRGB(x, y, composite(target.getRGB(x, y), source));
                depth[y][x] = z;
            }
        }
    }

    private double shade(Vertex[] vertices) {
        Vec edge1 = vertices[1].position().subtract(vertices[0].position());
        Vec edge2 = vertices[2].position().subtract(vertices[0].position());
        Vec normal = edge1.cross(edge2).normalize();
        Vec light = new Vec(-0.35, 0.8, 0.48).normalize();
        return 0.62 + 0.38 * Math.abs(normal.dot(light));
    }

    private int shade(int color, double factor) {
        int alpha = color >>> 24;
        int red = (int) Math.round((color >> 16 & 0xFF) * factor);
        int green = (int) Math.round((color >> 8 & 0xFF) * factor);
        int blue = (int) Math.round((color & 0xFF) * factor);
        return alpha << 24 | Math.min(255, red) << 16
                | Math.min(255, green) << 8 | Math.min(255, blue);
    }

    private int composite(int destination, int source) {
        int sourceAlpha = source >>> 24;
        if (sourceAlpha == 255) return source;
        int destinationAlpha = destination >>> 24;
        int inverse = 255 - sourceAlpha;
        int outputAlpha = sourceAlpha + destinationAlpha * inverse / 255;
        if (outputAlpha == 0) return 0;
        int denominator = outputAlpha * 255;
        int red = ((source >> 16 & 0xFF) * sourceAlpha * 255
                + (destination >> 16 & 0xFF) * destinationAlpha * inverse)
                / denominator;
        int green = ((source >> 8 & 0xFF) * sourceAlpha * 255
                + (destination >> 8 & 0xFF) * destinationAlpha * inverse)
                / denominator;
        int blue = ((source & 0xFF) * sourceAlpha * 255
                + (destination & 0xFF) * destinationAlpha * inverse)
                / denominator;
        return outputAlpha << 24 | red << 16 | green << 8 | blue;
    }

    private Bounds bounds(List<Face> faces) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Face face : faces) for (Vertex vertex : face.vertices()) {
            minX = Math.min(minX, vertex.position().x());
            minY = Math.min(minY, vertex.position().y());
            maxX = Math.max(maxX, vertex.position().x());
            maxY = Math.max(maxY, vertex.position().y());
        }
        return new Bounds(minX, minY, maxX, maxY);
    }

    private boolean hasVisiblePixels(BufferedImage image) {
        int pixels = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) != 0 && ++pixels >= 8)
                    return true;
        return false;
    }

    private double edge(double ax, double ay, double bx, double by,
                        double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private int clamp(int coordinate) {
        return Math.max(0, Math.min(ICON_SIZE - 1, coordinate));
    }

    private double[] triple(JsonArray value, double fallback) {
        double[] result = {fallback, fallback, fallback};
        if (value == null) return result;
        for (int index = 0; index < Math.min(3, value.size()); index++)
            if (value.get(index).isJsonPrimitive())
                result[index] = value.get(index).getAsDouble();
        return result;
    }

    private double[] pair(JsonArray value, double fallback) {
        double[] result = {fallback, fallback};
        if (value == null) return result;
        for (int index = 0; index < Math.min(2, value.size()); index++)
            if (value.get(index).isJsonPrimitive())
                result[index] = value.get(index).getAsDouble();
        return result;
    }

    private String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
                ? value.getAsString() : null;
    }

    private Vec rotateAround(Vec point, Vec pivot, double[] rotation) {
        return point.subtract(pivot).rotate(rotation).add(pivot);
    }

    private Vec vec(double x, double y, double z) {
        return new Vec(x, y, z);
    }

    private Vec vec(double[] values) {
        return vec(values[0], values[1], values[2]);
    }

    private record Transform(double[] rotation, double[] translation,
                             double[] scale) {
        Vec apply(Vec source) {
            // JavaModelConverter has already mirrored X for Bedrock geometry.
            // display.gui is still expressed in Java model coordinates, so
            // restore that basis before applying its transform. Without this,
            // asymmetric furniture is mirrored and its GUI yaw is reversed.
            Vec centered = new Vec(-source.x(), source.y(), source.z())
                    .subtract(new Vec(0, 8, 0));
            Vec scaled = new Vec(centered.x() * scale[0],
                    centered.y() * scale[1], centered.z() * scale[2]);
            Vec rotated = scaled.rotate(rotation);
            return rotated.add(new Vec(
                    translation[0], translation[1], translation[2]));
        }
    }

    private record Vec(double x, double y, double z) {
        Vec add(Vec other) {
            return new Vec(x + other.x, y + other.y, z + other.z);
        }

        Vec subtract(Vec other) {
            return new Vec(x - other.x, y - other.y, z - other.z);
        }

        Vec rotate(double[] degrees) {
            Vec result = this;
            double xAngle = Math.toRadians(degrees[0]);
            double yAngle = Math.toRadians(degrees[1]);
            double zAngle = Math.toRadians(degrees[2]);
            result = new Vec(result.x,
                    result.y * Math.cos(xAngle) - result.z * Math.sin(xAngle),
                    result.y * Math.sin(xAngle) + result.z * Math.cos(xAngle));
            result = new Vec(result.x * Math.cos(yAngle)
                    + result.z * Math.sin(yAngle), result.y,
                    -result.x * Math.sin(yAngle)
                            + result.z * Math.cos(yAngle));
            return new Vec(result.x * Math.cos(zAngle)
                    - result.y * Math.sin(zAngle),
                    result.x * Math.sin(zAngle)
                            + result.y * Math.cos(zAngle), result.z);
        }

        Vec cross(Vec other) {
            return new Vec(y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }

        double dot(Vec other) {
            return x * other.x + y * other.y + z * other.z;
        }

        Vec normalize() {
            double length = Math.sqrt(dot(this));
            return length < 1.0e-9 ? new Vec(0, 1, 0)
                    : new Vec(x / length, y / length, z / length);
        }
    }

    private record Texture(BufferedImage image, int width, int height,
                           int coordinateWidth, int coordinateHeight) {
        int sample(double u, double v) {
            // Geometry UVs share description.texture_width/height even when
            // individual material images have different resolutions.
            int x = Math.max(0, Math.min(width - 1,
                    (int) Math.floor(u * width / coordinateWidth)));
            int y = Math.max(0, Math.min(height - 1,
                    (int) Math.floor(v * height / coordinateHeight)));
            return image.getRGB(x, y);
        }
    }

    private record Vertex(Vec position, double u, double v) {}
    private record Face(Vertex[] vertices, Texture texture, double shade) {}
    private record ScreenVertex(double x, double y, double z,
                                double u, double v) {}
    private record Bounds(double minX, double minY, double maxX, double maxY) {
        boolean valid() {
            return Double.isFinite(minX) && Double.isFinite(minY)
                    && Double.isFinite(maxX) && Double.isFinite(maxY)
                    && maxX > minX && maxY > minY;
        }
    }
}
