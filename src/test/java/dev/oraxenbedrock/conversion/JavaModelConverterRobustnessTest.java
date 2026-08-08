package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaModelConverterRobustnessTest {
    @TempDir
    Path temp;

    @Test
    void skipsMalformedModelPartsWithoutDiscardingValidGeometry()
            throws Exception {
        Path pack = temp.resolve("pack");
        write(pack.resolve("assets/oraxen/models/item/mixed.json"), """
                {
                  "parent": 17,
                  "textures": {
                    "layer0": "oraxen:item/mixed",
                    "broken": {"path": "oraxen:item/missing"}
                  },
                  "display": [],
                  "elements": [
                    {
                      "from": ["bad", 0, 0],
                      "to": [16, 16, 16]
                    },
                    {
                      "from": [0, 0, 0],
                      "to": [16, 16, 16],
                      "rotation": {
                        "origin": "bad",
                        "axis": 4,
                        "angle": "bad",
                        "rescale": "bad"
                      },
                      "faces": {
                        "north": {"texture": {"bad": true}},
                        "south": {
                          "texture": "#layer0",
                          "uv": [0, 0, 16, 16],
                          "rotation": 90
                        },
                        "east": {
                          "texture": "#layer0",
                          "uv": [0, 0, "bad", 16]
                        },
                        "diagonal": {"texture": "#layer0"}
                      }
                    }
                  ]
                }
                """);
        writePng(pack.resolve("assets/oraxen/textures/item/mixed.png"));

        try (PackSource source = PackSource.open(pack)) {
            JavaModelConverter.ConvertedModel converted =
                    new JavaModelConverter(source, "oraxen", "bridge")
                            .convert("oraxen:item/mixed", "mixed", true);

            assertNotNull(converted);
            assertNotNull(converted.geometry());
            assertEquals(1, converted.materials().size());
            JsonObject definition = converted.geometry()
                    .getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject();
            JsonArray cubes = definition.getAsJsonArray("bones")
                    .get(0).getAsJsonObject().getAsJsonArray("cubes");
            assertEquals(1, cubes.size(),
                    "Only the element with numeric coordinates should survive");
            JsonObject faces = cubes.get(0).getAsJsonObject()
                    .getAsJsonObject("uv");
            assertEquals(2, faces.size());
            assertTrue(faces.has("south"));
            assertTrue(faces.has("east"));
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("valid from/to")));
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("malformed texture")));
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("default UV")));
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("unknown face")));
        }
    }

    @Test
    void malformedChildSectionsKeepUsableParentModelData() throws Exception {
        Path pack = temp.resolve("parent-pack");
        write(pack.resolve("assets/oraxen/models/item/base.json"), """
                {
                  "textures": {"all": "oraxen:item/base"},
                  "elements": [{
                    "from": [0, 0, 0],
                    "to": [16, 16, 16],
                    "faces": {"north": {"texture": "#all"}}
                  }]
                }
                """);
        write(pack.resolve("assets/oraxen/models/item/child.json"), """
                {
                  "parent": "oraxen:item/base",
                  "textures": [],
                  "elements": {},
                  "display": "invalid"
                }
                """);
        writePng(pack.resolve("assets/oraxen/textures/item/base.png"));

        try (PackSource source = PackSource.open(pack)) {
            JavaModelConverter.ConvertedModel converted =
                    new JavaModelConverter(source, "oraxen", "bridge")
                            .convert("oraxen:item/child", "child", false);

            assertNotNull(converted);
            assertNotNull(converted.geometry());
            assertEquals(1, converted.materials().size());
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("non-object textures")));
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("non-array elements")));
            assertTrue(converted.warnings().stream().anyMatch(warning ->
                    warning.contains("non-object display")));
        }
    }

    private void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private void writePng(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(
                16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, 0xFF44AA66);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
