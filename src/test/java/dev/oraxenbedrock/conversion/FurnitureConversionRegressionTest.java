package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.model.ConversionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FurnitureConversionRegressionTest {
    @TempDir Path temp;

    @Test
    void rendersFurnitureThumbnailAndGeneratesArmorStandHeadAttachable()
            throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        write(oraxen.resolve("items/furniture.yml"), """
                armchair:
                  itemname: "<gold>Armchair"
                  material: PAPER
                  Pack:
                    generate_model: false
                    model: default/armchair
                  Components:
                    item_model: oraxen:armchair
                  Mechanics:
                    furniture:
                      type: ARMOR_STAND
                      small: false
                      armor_stand_properties:
                        translation: { x: 0.0, y: -1.22, z: 0.0 }
                        scale: { x: 1.5, y: 1.5, z: 1.5 }
                display_cart:
                  itemname: "Display Cart"
                  material: PAPER
                  Pack:
                    generate_model: false
                    model: default/display_cart
                    models:
                      active: default/display_cart_active
                  Components:
                    item_model: oraxen:display_cart
                  Mechanics:
                    furniture:
                      type: DISPLAY_ENTITY
                      display_entity_properties:
                        display_transform: FIXED
                """);

        Path javaPack = temp.resolve("generated-pack");
        write(javaPack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":75,"description":"Furniture repro"}}
                """);
        for (String id : Set.of("armchair", "display_cart")) {
            write(javaPack.resolve("assets/oraxen/items/" + id + ".json"), """
                    {"model":{"type":"minecraft:model",
                      "model":"oraxen:default/%s"}}
                    """.formatted(id));
        }
        write(javaPack.resolve(
                "assets/oraxen/items/display_cart/active.json"), """
                {"model":{"type":"minecraft:model",
                  "model":"oraxen:default/display_cart_active"}}
                """);
        String model = """
                {"textures":{"wood":"oraxen:default/furniture_atlas"},
                 "display":{
                   "gui":{"rotation":[25,135,0],"translation":[0,1,0],
                          "scale":[0.8,0.8,0.8]},
                   "firstperson_righthand":{"rotation":[10,20,30],
                          "translation":[1,2,3],"scale":[0.5,0.6,0.7]},
                   "thirdperson_righthand":{"rotation":[40,50,60],
                          "translation":[4,5,6],"scale":[0.8,0.9,1.0]},
                   "head":{"rotation":[5,15,25],"translation":[1,2,3],
                          "scale":[1.5,1.25,1.0]}},
                 "elements":[
                   {"from":[0,0,0],"to":[4,10,16],"faces":%s},
                   {"from":[12,0,0],"to":[16,10,16],"faces":%s},
                   {"from":[0,8,4],"to":[16,12,16],"faces":%s}
                 ]}
                """.formatted(faces(), faces(), faces());
        write(javaPack.resolve("assets/oraxen/models/default/armchair.json"), model);
        write(javaPack.resolve("assets/oraxen/models/default/display_cart.json"), model);
        write(javaPack.resolve(
                "assets/oraxen/models/default/display_cart_active.json"), model);
        writeFurnitureTexture(javaPack.resolve(
                "assets/oraxen/textures/default/furniture_atlas.png"));

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        writeExtensionJar(geyser.resolve(
                "extensions/GeyserDisplayEntity.jar"));
        ConversionResult result = new PackConverter(
                temp.resolve("plugins/OraxenBedrock")).convert(new BridgeConfig(
                temp, oraxen, geyser, javaPack,
                "Test", "Furniture pack", "oraxen", new int[]{1, 20, 0},
                true, false, false, false, false, false, false,
                false, false, 100, false));

        assertEquals(3, result.items());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("display_cart")
                        && warning.contains("GeyserDisplayEntity")));
        assertFalse(result.warnings().stream().anyMatch(warning ->
                warning.contains("armchair")
                        && warning.contains("GeyserDisplayEntity")));
        Path displayMappings = geyser.resolve(
                "extensions/geyserdisplayentity/Mappings/oraxen.yml");
        assertTrue(Files.isRegularFile(displayMappings));
        String displayMappingText = Files.readString(displayMappings);
        assertTrue(displayMappingText.contains("display_cart:"));
        assertTrue(displayMappingText.contains("oraxen:display_cart"));
        assertTrue(displayMappingText.contains(
                "oraxen:display_cart_model_active_"));
        assertEquals(2, displayMappingText.lines()
                .filter(line -> line.trim().startsWith("item-identifier:"))
                .count());
        assertFalse(displayMappingText.contains("armchair:"));

        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            BufferedImage icon;
            try (InputStream input = Files.newInputStream(
                    pack.getPath("/textures/items/armchair.png"))) {
                icon = ImageIO.read(input);
            }
            assertNotNull(icon);
            assertEquals(64, icon.getWidth(), result.warnings().toString());
            assertEquals(64, icon.getHeight());
            int visible = 0;
            Set<Integer> colors = new HashSet<>();
            for (int y = 0; y < icon.getHeight(); y++) {
                for (int x = 0; x < icon.getWidth(); x++) {
                    int color = icon.getRGB(x, y);
                    if ((color >>> 24) == 0) continue;
                    visible++;
                    colors.add(color);
                }
            }
            assertTrue(visible > 250, "The furniture silhouette must be visible");
            assertTrue(visible < 3500, "A UV atlas must not fill the whole icon");
            assertTrue(colors.size() >= 3,
                    "Model faces should retain texture and directional shading");

            JsonObject attachable = JsonSupport.readObject(pack.getPath(
                    "/attachables/oraxen/armchair.attachable.json"));
            JsonObject description = attachable.getAsJsonObject("minecraft:attachable")
                    .getAsJsonObject("description");
            assertTrue(description.getAsJsonObject("animations")
                    .has("display_first_person"));
            assertTrue(description.getAsJsonObject("animations")
                    .has("display_third_person"));
            assertTrue(description.getAsJsonObject("animations")
                    .has("display_head"));
            JsonArray animate = description.getAsJsonObject("scripts")
                    .getAsJsonArray("animate");
            assertEquals(3, animate.size());
            assertTrue(animate.toString().contains("context.item_slot == 'head'"));

            JsonObject geometry = JsonSupport.readObject(pack.getPath(
                    "/models/oraxen/armchair.attachable.geo.json"));
            JsonObject rootBone = geometry.getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject().getAsJsonArray("bones")
                    .get(0).getAsJsonObject();
            assertEquals("q.item_slot_to_bone_name(context.item_slot)",
                    rootBone.get("binding").getAsString());
            JsonObject firstCube = rootBone.getAsJsonArray("cubes")
                    .get(0).getAsJsonObject();
            assertEquals(4.0, firstCube.getAsJsonArray("origin")
                    .get(0).getAsDouble(), 0.0001,
                    "Bedrock geometry must mirror Java's X axis");
            JsonObject topFace = firstCube.getAsJsonObject("uv")
                    .getAsJsonObject("up");
            assertArray2(topFace.getAsJsonArray("uv"), 16, 16);
            assertArray2(topFace.getAsJsonArray("uv_size"), -16, -16);

            JsonObject animations = JsonSupport.readObject(pack.getPath(
                    "/animations/oraxen/armchair.display.animation.json"))
                    .getAsJsonObject("animations");
            JsonObject head = animations.getAsJsonObject(
                            "animation.oraxen.armchair.display.head")
                    .getAsJsonObject("bones").getAsJsonObject("root");
            assertArray(head.getAsJsonArray("position"),
                    -0.655, 21.31, 1.965);
            assertArray(head.getAsJsonArray("rotation"), -5, -15, 25);
            assertArray(head.getAsJsonArray("scale"),
                    0.9825, 0.81875, 0.655);
        }
    }

    private String faces() {
        return """
                {"north":{"texture":"#wood","uv":[0,0,16,16]},
                 "south":{"texture":"#wood","uv":[0,0,16,16]},
                 "east":{"texture":"#wood","uv":[0,0,16,16]},
                 "west":{"texture":"#wood","uv":[0,0,16,16]},
                 "up":{"texture":"#wood","uv":[0,0,16,16]},
                 "down":{"texture":"#wood","uv":[0,0,16,16]}}
                """;
    }

    private void assertArray(JsonArray actual, double x, double y, double z) {
        assertEquals(x, actual.get(0).getAsDouble(), 0.0001);
        assertEquals(y, actual.get(1).getAsDouble(), 0.0001);
        assertEquals(z, actual.get(2).getAsDouble(), 0.0001);
    }

    private void assertArray2(JsonArray actual, double x, double y) {
        assertEquals(x, actual.get(0).getAsDouble(), 0.0001);
        assertEquals(y, actual.get(1).getAsDouble(), 0.0001);
    }

    private void writeFurnitureTexture(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            image.setRGB(x, y, (x / 4 + y / 4) % 2 == 0
                    ? 0xFFB9783A : 0xFF59351E);
        try (OutputStream output = Files.newOutputStream(path)) {
            assertTrue(ImageIO.write(image, "png", output));
        }
    }

    private void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private void writeExtensionJar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("extension.yml"));
            output.write("name: Display Support\nid: geyserdisplayentity\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
