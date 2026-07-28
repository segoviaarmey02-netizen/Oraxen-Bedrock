package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.io.PackSource;
import dev.oraxenbedrock.model.ConversionResult;
import dev.oraxenbedrock.util.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackConverterTest {
    @TempDir Path temp;

    @Test
    void assignsANewManifestVersionForEveryGeneration() {
        BridgeConfig config = new BridgeConfig(
                temp, temp.resolve("Oraxen"), temp.resolve("Geyser"),
                temp.resolve("pack.zip"), "Test", "Test", "oraxen",
                new int[]{1, 0, 0}, true, true, true, true, true, true, true,
                false, false, 100, false);
        PackConverter converter = new PackConverter(temp.resolve("data"));
        int[] first = converter.effectivePackVersion(config);
        int[] second = converter.effectivePackVersion(config);
        assertFalse(java.util.Arrays.equals(first, second));
        for (int value : first)
            assertTrue(value >= 0 && value <= 65534);
        for (int value : second)
            assertTrue(value >= 0 && value <= 65534);
    }

    @Test
    void convertsModernItemAndDiscoveredNoteBlockState() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Path items = oraxen.resolve("items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("content.yml"), """
                ruby:
                  displayname: "<red>Ruby"
                  material: DIAMOND
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [ruby]
                ruby_block:
                  displayname: "Ruby Block"
                  material: PAPER
                  Pack:
                    generate_model: true
                    parent_model: block/cube_all
                    textures: [ruby_block]
                  Mechanics:
                    noteblock:
                      custom_variation: 2
                      model: ruby_block
                flower:
                  displayname: "Flower"
                  material: PAPER
                  Pack:
                    generate_model: true
                    parent_model: block/cross
                    textures: [flower]
                  Mechanics:
                    stringblock:
                      custom_variation: 1
                      model: flower
                ruby_sword:
                  displayname: "Ruby Sword"
                  material: DIAMOND_SWORD
                  Components:
                    durability: 2048
                  Pack:
                    generate_model: false
                    model: item/ruby_sword
                    textures: [ruby_sword]
                ruby_helmet:
                  displayname: "Ruby Helmet"
                  material: PAPER
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [ruby_helmet]
                  Components:
                    equippable:
                      slot: HEAD
                      model: oraxen:ruby
                      allowed_entity_types: [PLAYER]
                broken_icon:
                  displayname: "Broken Icon"
                  material: STICK
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [broken_icon]
                broken_block:
                  displayname: "Broken Block"
                  material: PAPER
                  Pack:
                    model: block/broken_block
                    textures: [broken_icon]
                  Mechanics:
                    noteblock:
                      custom_variation: 3
                      model: broken_block
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"pack_format":32,"description":"Minecraft 1.20.5"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/ruby.png", png(16, 16, 0xFFE02040));
            entry(zip, "assets/oraxen/textures/ruby_block.png",
                    animatedPng(16, 0xFFFF0000, 0xFF00FF00));
            entry(zip, "assets/oraxen/textures/ruby_block.png.mcmeta", """
                    {"animation":{"frametime":2,"interpolate":true}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/flower.png", png(16, 16, 0xFF40D060));
            entry(zip, "assets/oraxen/textures/ruby_sword.png",
                    png(16, 16, 0xFFE03050));
            entry(zip, "assets/oraxen/textures/ruby_helmet.png",
                    png(16, 16, 0xFFB02040));
            entry(zip, "assets/oraxen/textures/broken_icon.png",
                    new byte[]{0x01, 0x02, 0x03});
            entry(zip, "assets/oraxen/textures/ruby_armor_layer_1.png",
                    animatedPng(16, 0xFFCC2233, 0xFFEE6677));
            entry(zip, "assets/oraxen/textures/ruby_armor_layer_1.png.mcmeta", """
                    {"animation":{"frametime":4,"frames":[1,0]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/ruby_block.json", """
                    {
                      "textures":{"all":"oraxen:ruby_block"},
                      "elements":[{
                        "from":[1,0,1],"to":[15,14,15],
                        "rotation":{"origin":[8,8,8],"axis":"y","angle":22.5},
                        "faces":{
                          "north":{"texture":"#all","uv":[0,0,16,16]},
                          "south":{"texture":"#all","uv":[0,0,16,16]}
                        }
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/flower.json", """
                    {"parent":"minecraft:block/cross","textures":{"cross":"oraxen:flower"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/broken_block.json", """
                    {"parent":"minecraft:block/cube_all",
                     "textures":{"all":"oraxen:broken_icon"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/ruby_sword.json", """
                    {"textures":{"blade":"oraxen:ruby_sword"},"elements":[{
                      "from":[7,0,7],"to":[9,16,9],
                      "faces":{"north":{"texture":"#blade","uv":[0,0,2,16]}}
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/minecraft/blockstates/note_block.json", """
                    {"variants":{
                      "instrument=banjo,note=1,powered=false":
                        {"model":"oraxen:block/ruby_block"},
                      "instrument=banjo,note=2,powered=false":
                        {"model":"oraxen:block/ruby_block","y":90},
                      "instrument=banjo,note=3,powered=false":
                        {"model":"oraxen:block/broken_block"}
                    }}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/minecraft/blockstates/tripwire.json", """
                    {"multipart":[
                      {"when":{"attached":"false","powered":"false"},
                       "apply":{"model":"oraxen:block/flower"}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 0, 0}, true, true, false, false, true, true, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);

        assertEquals(5, result.items());
        assertEquals(2, result.blocks());
        assertTrue(Files.size(result.pack()) > 0);
        JsonObject itemJson = JsonSupport.readObject(result.mappings());
        assertFalse(itemJson.getAsJsonObject("items").has("minecraft:stick"));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("Skipped custom mapping for item 'broken_icon'")));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("Skipped block state")
                        && warning.contains("broken_block")));
        assertEquals("oraxen:ruby", itemJson.getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond").get(0).getAsJsonObject()
                .get("model").getAsString());
        JsonObject blockJson = JsonSupport.readObject(
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
        assertTrue(blockJson.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides")
                .has("instrument=banjo,note=1,powered=false"));
        assertFalse(blockJson.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides")
                .has("instrument=banjo,note=3,powered=false"));
        JsonObject state = blockJson.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides")
                .getAsJsonObject("instrument=banjo,note=1,powered=false");
        assertEquals("geometry.oraxen.ruby_block", state.get("geometry").getAsString());
        JsonObject rotated = blockJson.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides")
                .getAsJsonObject("instrument=banjo,note=2,powered=false");
        assertEquals(270, rotated.getAsJsonObject("transformation")
                .getAsJsonArray("rotation").get(1).getAsInt());
        assertEquals(32, blockJson.getAsJsonObject("blocks").getAsJsonObject("minecraft:tripwire")
                .getAsJsonObject("state_overrides").size());
        JsonObject sword = itemJson.getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond_sword").get(0).getAsJsonObject();
        assertTrue(sword.getAsJsonObject("bedrock_options").get("display_handheld").getAsBoolean());
        assertEquals(2048, sword.getAsJsonObject("components")
                .get("minecraft:max_damage").getAsInt());
        JsonObject helmet = itemJson.getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(value -> value.get("bedrock_identifier").getAsString().endsWith("ruby_helmet"))
                .findFirst().orElseThrow();
        assertEquals("head", helmet.getAsJsonObject("components")
                .getAsJsonObject("minecraft:equippable").get("slot").getAsString());
        assertEquals("minecraft:player", helmet.getAsJsonObject("components")
                .getAsJsonObject("minecraft:equippable").getAsJsonArray("allowed_entities")
                .get(0).getAsString());
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/ruby_sword.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/ruby_helmet.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath("/textures/models/armor/ruby_armor_layer_1.png")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/models/armor/ruby_armor_layer_1_0.png")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/models/armor/ruby_armor_layer_1_1.png")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/render_controllers/oraxen/ruby_helmet.render_controllers.json")));
            assertFalse(Files.exists(
                    pack.getPath("/textures/items/broken_icon.png")));
            JsonObject helmetAttachable = JsonSupport.readObject(
                    pack.getPath("/attachables/oraxen/ruby_helmet.attachable.json"));
            assertEquals("controller.render.oraxen.ruby_helmet.animated",
                    helmetAttachable.getAsJsonObject("minecraft:attachable")
                            .getAsJsonObject("description")
                            .getAsJsonArray("render_controllers").get(0).getAsString());
            var flipbooks = JsonSupport.GSON.fromJson(Files.readString(
                    pack.getPath("/textures/flipbook_textures.json")),
                    com.google.gson.JsonArray.class);
            JsonObject animatedBlock = flipbooks.asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .filter(value -> value.get("atlas_tile").getAsString()
                            .equals("oraxen.ruby_block_all"))
                    .findFirst().orElseThrow();
            assertEquals(List.of(0, 1), animatedBlock.getAsJsonArray("frames").asList()
                    .stream().map(JsonElement::getAsInt).toList());
            assertEquals(2, animatedBlock.get("ticks_per_frame").getAsInt());
            assertTrue(animatedBlock.get("blend_frames").getAsBoolean());
            JsonObject blockGeometry = JsonSupport.readObject(
                    pack.getPath("/models/oraxen/ruby_block.geo.json"));
            JsonObject blockDescription = blockGeometry.getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject().getAsJsonObject("description");
            assertEquals(16, blockDescription.get("texture_width").getAsInt());
            assertEquals(16, blockDescription.get("texture_height").getAsInt());
        }
    }

    @Test
    void convertsEmojiCustomMusicDiscHatAndDecoration() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Path items = oraxen.resolve("items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("extras.yml"), """
                night_disc:
                  displayname: "Night Song"
                  material: MUSIC_DISC_13
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [night_disc]
                  Components:
                    jukebox_playable:
                      song_key: oraxen:night_song
                crown:
                  displayname: "Golden Crown"
                  material: PAPER
                  Pack:
                    model: block/crown
                    textures: [crown]
                  Components:
                    equippable:
                      slot: HEAD
                chair:
                  displayname: "Chair"
                  material: PAPER
                  Pack:
                    model: block/chair
                    textures: [chair]
                  Mechanics:
                    furniture:
                      rotatable: true
                animated_sword:
                  itemname: "Animated Sword"
                  material: DIAMOND_SWORD
                  Pack:
                    model: block/animated_sword
                    textures: [animated_sword]
                flat_hat:
                  itemname: "Flat Hat"
                  material: PAPER
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [crown]
                  Mechanics:
                    hat:
                      enabled: true
                dual_staff:
                  itemname: "Dual Material Staff"
                  material: DIAMOND_SWORD
                  Pack:
                    model: block/dual_staff
                    textures: [staff_red, staff_blue]
                """);
        Files.writeString(oraxen.resolve("sound.yml"), """
                settings:
                  automatically_generate: true
                sounds:
                  night_song:
                    category: records
                    sound: music/night.ogg
                    stream: true
                """);

        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        byte[] texture = png(16, 16, 0xFFFFBE00);
        byte[] animatedTexture = animatedPng(16,
                0xFFFF2030, 0xFF20FF60, 0xFF2060FF);
        byte[] emoji = png(16, 16, 0xFF28DC78);
        byte[] smallEmoji = png(32, 32, 0xFFFFA020);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"description":"Modern pack",
                      "min_format":[69,0],"max_format":[84,0]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/night_disc.png", animatedTexture);
            entry(zip, "assets/oraxen/textures/night_disc.png.mcmeta", """
                    {"animation":{"frametime":3}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/crown.png", texture);
            entry(zip, "assets/oraxen/textures/chair.png", animatedTexture);
            entry(zip, "assets/oraxen/textures/chair.png.mcmeta", """
                    {"animation":{"frametime":2,"interpolate":true,
                      "frames":[0,{"index":1,"time":4},2]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/animated_sword.png", animatedTexture);
            entry(zip, "assets/oraxen/textures/animated_sword.png.mcmeta", """
                    {"animation":{"frametime":1,"frames":[
                      {"index":0,"time":10000},{"index":1,"time":9999},2
                    ]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/staff_red.png",
                    animatedPng(16, 0xFFFF2233, 0xFF22FF55));
            entry(zip, "assets/oraxen/textures/staff_red.png.mcmeta", """
                    {"animation":{"frametime":2}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/staff_blue.png",
                    png(32, 32, 0xFF2255FF));
            entry(zip, "assets/oraxen/textures/font/emojis.png", emoji);
            entry(zip, "assets/oraxen/textures/font/small_emojis.png", smallEmoji);
            entry(zip, "assets/oraxen/models/block/crown.json", cubeModel("oraxen:crown"));
            entry(zip, "assets/oraxen/models/block/chair.json", cubeModel("oraxen:chair"));
            entry(zip, "assets/oraxen/models/block/animated_sword.json",
                    cubeModel("oraxen:animated_sword"));
            entry(zip, "assets/oraxen/models/block/dual_staff.json", """
                    {"textures":{
                      "red":"oraxen:staff_red",
                      "blue":"oraxen:staff_blue"
                    },"display":{
                      "firstperson_righthand":{
                        "rotation":[10,20,30],
                        "translation":[1,2,3],
                        "scale":[0.5,0.6,0.7]
                      },
                      "thirdperson_righthand":{
                        "rotation":[40,50,60],
                        "translation":[4,5,6],
                        "scale":[0.8,0.9,1.0]
                      }
                    },"elements":[
                      {"from":[0,0,7],"to":[8,16,9],
                       "faces":{"north":{"texture":"#red","uv":[0,0,16,16]}}},
                      {"from":[8,0,7],"to":[16,16,9],
                       "faces":{"north":{"texture":"#blue","uv":[0,0,16,16]}}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/night_disc.json", """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"night_disc"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/flat_hat.json", """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"oraxen:crown"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/font/default.json", ("""
                    {"providers":[
                      {"type":"bitmap","file":"oraxen:font/emojis.png","ascent":15,"height":16,
                       "chars":["%s"]},
                      {"type":"minecraft:bitmap","file":"oraxen:font/small_emojis.png",
                       "ascent":7,"height":8,"chars":["%s"]}
                    ]}
                    """.formatted(Character.toString(0xE101), Character.toString(0xE102)))
                    .getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/sounds.json", """
                    {"night_song":{"sounds":[
                      {"name":"oraxen:music/night","stream":true}
                    ]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/sounds/music/night.ogg", new byte[]{79, 103, 103, 83});
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 1, 0}, true, true, true, false, true, true, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject mappings = JsonSupport.readObject(result.mappings());
        JsonObject crown = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(value -> value.get("bedrock_identifier").getAsString().equals("oraxen:crown"))
                .findFirst().orElseThrow();
        assertEquals("head", crown.getAsJsonObject("components")
                .getAsJsonObject("minecraft:equippable").get("slot").getAsString());
        JsonObject flatHat = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(value -> value.get("bedrock_identifier").getAsString()
                        .equals("oraxen:flat_hat"))
                .findFirst().orElseThrow();
        assertEquals("head", flatHat.getAsJsonObject("components")
                .getAsJsonObject("minecraft:equippable").get("slot").getAsString());
        JsonObject disc = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:music_disc_13").get(0).getAsJsonObject();
        assertEquals("oraxen:night_disc", disc.getAsJsonObject("bedrock_options")
                .get("icon").getAsString());

        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertAllMappedIconsResolve(mappings, pack);
            JsonObject itemAtlas = JsonSupport.readObject(
                    pack.getPath("/textures/item_texture.json"))
                    .getAsJsonObject("texture_data");
            assertTrue(itemAtlas.has("oraxen.night_disc"));
            assertTrue(itemAtlas.has("oraxen:night_disc"));
            assertTrue(itemAtlas.getAsJsonObject("oraxen.night_disc")
                    .get("textures").isJsonArray());
            try (InputStream input = Files.newInputStream(
                    pack.getPath("/textures/items/night_disc.png"))) {
                BufferedImage discIcon = ImageIO.read(input);
                assertNotNull(discIcon);
                assertEquals(16, discIcon.getWidth());
                assertEquals(16, discIcon.getHeight());
                assertEquals(0xFFFF2030, discIcon.getRGB(8, 8));
            }
            assertTrue(Files.isRegularFile(pack.getPath("/font/glyph_E1.png")));
            assertTrue(Files.isRegularFile(pack.getPath("/sounds/oraxen/music/night.ogg")));
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/crown.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/chair.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/animated_sword.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/night_disc.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/flat_hat.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/dual_staff.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/models/oraxen/flat_hat.attachable.geo.json")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/models/oraxen/crown.attachable.geo.json")));
            JsonObject staffGeometry = JsonSupport.readObject(pack.getPath(
                    "/models/oraxen/dual_staff.attachable.geo.json"));
            JsonObject staffDefinition = staffGeometry.getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject();
            assertEquals(64, staffDefinition.getAsJsonObject("description")
                    .get("texture_width").getAsInt());
            assertEquals(32, staffDefinition.getAsJsonObject("description")
                    .get("texture_height").getAsInt());
            JsonArray staffCubes = staffDefinition.getAsJsonArray("bones").get(0)
                    .getAsJsonObject().getAsJsonArray("cubes");
            JsonObject redFace = staffCubes.get(0).getAsJsonObject()
                    .getAsJsonObject("uv").getAsJsonObject("south");
            JsonObject blueFace = staffCubes.get(1).getAsJsonObject()
                    .getAsJsonObject("uv").getAsJsonObject("south");
            assertEquals(0, redFace.getAsJsonArray("uv").get(0).getAsInt());
            assertEquals(16, redFace.getAsJsonArray("uv_size").get(0).getAsInt());
            assertEquals(16, blueFace.getAsJsonArray("uv").get(0).getAsInt());
            assertEquals(32, blueFace.getAsJsonArray("uv_size").get(0).getAsInt());
            try (InputStream input = Files.newInputStream(pack.getPath(
                    "/textures/entity/oraxen/dual_staff.png"))) {
                BufferedImage atlas = ImageIO.read(input);
                assertEquals(64, atlas.getWidth());
                assertEquals(64, atlas.getHeight());
                assertEquals(0xFFFF2233, atlas.getRGB(8, 8));
                assertEquals(0xFF2255FF, atlas.getRGB(24, 8));
            }
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/entity/oraxen/dual_staff_0.png")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/entity/oraxen/dual_staff_1.png")));
            JsonObject staffController = JsonSupport.readObject(pack.getPath(
                    "/render_controllers/oraxen/dual_staff.render_controllers.json"));
            assertEquals(List.of("Texture.frame_0", "Texture.frame_1"),
                    staffController.getAsJsonObject("render_controllers")
                            .entrySet().iterator().next().getValue().getAsJsonObject()
                            .getAsJsonObject("arrays").getAsJsonObject("textures")
                            .getAsJsonArray("Array.frames").asList().stream()
                            .map(JsonElement::getAsString).toList());
            JsonObject staffDisplay = JsonSupport.readObject(pack.getPath(
                    "/animations/oraxen/dual_staff.display.animation.json"));
            JsonObject firstPerson = staffDisplay.getAsJsonObject("animations")
                    .getAsJsonObject(
                            "animation.oraxen.dual_staff.display.first_person")
                    .getAsJsonObject("bones").getAsJsonObject("root");
            assertEquals(List.of(-10.0, -20.0, 30.0),
                    firstPerson.getAsJsonArray("rotation").asList().stream()
                            .map(JsonElement::getAsDouble).toList());
            assertEquals(List.of(1.0, 2.0, -3.0),
                    firstPerson.getAsJsonArray("position").asList().stream()
                            .map(JsonElement::getAsDouble).toList());
            JsonObject staffAttachable = JsonSupport.readObject(pack.getPath(
                    "/attachables/oraxen/dual_staff.attachable.json"));
            JsonObject staffDescription = staffAttachable
                    .getAsJsonObject("minecraft:attachable")
                    .getAsJsonObject("description");
            assertTrue(staffDescription.getAsJsonObject("animations")
                    .has("display_first_person"));
            assertEquals(2, staffDescription.getAsJsonObject("scripts")
                    .getAsJsonArray("animate").size());
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/render_controllers/oraxen/chair.render_controllers.json")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/render_controllers/oraxen/animated_sword.render_controllers.json")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/render_controllers/oraxen/night_disc.render_controllers.json")));
            try (InputStream input = Files.newInputStream(
                    pack.getPath("/textures/items/night_disc.png"))) {
                BufferedImage icon = ImageIO.read(input);
                assertEquals(16, icon.getWidth());
                assertEquals(16, icon.getHeight());
            }
            JsonObject discGeometry = JsonSupport.readObject(pack.getPath(
                    "/models/oraxen/night_disc.attachable.geo.json"));
            JsonObject discDescription = discGeometry.getAsJsonArray("minecraft:geometry")
                    .get(0).getAsJsonObject().getAsJsonObject("description");
            assertEquals(16, discDescription.get("texture_width").getAsInt());
            assertEquals(16, discDescription.get("texture_height").getAsInt());
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/entity/oraxen/chair_0.png")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/entity/oraxen/chair_1.png")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/entity/oraxen/chair_2.png")));

            JsonObject chairController = JsonSupport.readObject(pack.getPath(
                    "/render_controllers/oraxen/chair.render_controllers.json"));
            JsonObject controller = chairController.getAsJsonObject("render_controllers")
                    .entrySet().iterator().next().getValue().getAsJsonObject();
            assertEquals(List.of("Texture.frame_0", "Texture.frame_1",
                            "Texture.frame_1", "Texture.frame_2"),
                    controller.getAsJsonObject("arrays").getAsJsonObject("textures")
                            .getAsJsonArray("Array.frames").asList().stream()
                            .map(JsonElement::getAsString).toList());
            assertTrue(controller.getAsJsonArray("textures").get(0).getAsString()
                    .contains("10.0"));
            JsonObject swordController = JsonSupport.readObject(pack.getPath(
                    "/render_controllers/oraxen/animated_sword.render_controllers.json"));
            JsonObject swordRenderController = swordController.getAsJsonObject("render_controllers")
                    .entrySet().iterator().next().getValue().getAsJsonObject();
            assertTrue(swordRenderController.getAsJsonObject("arrays")
                    .getAsJsonObject("textures").getAsJsonArray("Array.frames").size() <= 4096);

            JsonObject sounds = JsonSupport.readObject(
                    pack.getPath("/sounds/sound_definitions.json"));
            JsonObject song = sounds.getAsJsonObject("sound_definitions")
                    .getAsJsonObject("oraxen:night_song");
            assertEquals("record", song.get("category").getAsString());
            assertTrue(song.getAsJsonArray("sounds").get(0).getAsJsonObject()
                    .get("stream").getAsBoolean());
            assertEquals("sounds/oraxen/music/night",
                    song.getAsJsonArray("sounds").get(0).getAsJsonObject()
                            .get("name").getAsString());

            try (InputStream input = Files.newInputStream(pack.getPath("/font/glyph_E1.png"))) {
                BufferedImage page = ImageIO.read(input);
                assertEquals(512, page.getWidth());
                assertEquals(512, page.getHeight());
                // A 32px source keeps its resolution; every glyph on the
                // page uses the same 32px grid.
                assertNotEquals(0, page.getRGB(48, 16) >>> 24);
                // Java's 8px text metric must not shrink a Bedrock glyph to
                // half of its Bedrock page cell.
                assertNotEquals(0, page.getRGB(80, 4) >>> 24);
                assertNotEquals(0, page.getRGB(80, 24) >>> 24);
            }
        }

        JsonObject report = JsonSupport.readObject(data.resolve("last-report.json"));
        assertEquals(1, report.get("sound_events").getAsInt());
        assertEquals(1, report.get("sound_files").getAsInt());
        assertEquals(2, report.get("glyphs_and_emojis").getAsInt());
        assertEquals(1, report.get("glyph_pages").getAsInt());
        assertEquals(8, report.get("animated_textures").getAsInt());
    }

    @Test
    void convertsModernDefinitionsShapedBlocksLanguagesAndAdvancedProperties() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Files.writeString(oraxen.resolve("items/modern.yml"), """
                modern_blade:
                  itemname: "<gold>Modern Blade"
                  material: DIAMOND_SWORD
                  Components:
                    item_model: oraxen:modern_blade
                    durability:
                      value: 3072
                      damage_block_break: true
                    attack_range:
                      min_reach: 0
                      max_reach: 4
                    consumable:
                      consume_seconds: 1.5
                      animation: EAT
                      sound: entity.generic.eat
                      on_consume_effects:
                        - type: apply_effects
                          effects:
                            haste:
                              duration: 2
                              amplifier: 1
                          probability: 0.75
                    use_cooldown:
                      seconds: 2.5
                      group: oraxen:weapons
                    repairable:
                      items:
                        - DIAMOND
                        - minecraft:netherite_ingot
                    tool:
                      damage_per_block: 1
                      default_mining_speed: 2.0
                      rules:
                        - speed: 10.0
                          correct_for_drops: true
                          material: DIAMOND_BLOCK
                          tags:
                            - minecraft:mineable/pickaxe
                sculpted_tile:
                  displayname: "Sculpted Tile"
                  material: PAPER
                  Pack:
                    model: custom:block/sculpted_tile
                    textures: [custom:block/sculpted_tile]
                  Mechanics:
                    ShapedBlock:
                      model: custom:block/sculpted_tile
                      light: 11
                      hardness: 4.5
                      friction: 0.3
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"description":"Modern pack",
                      "min_format":[69,0],"max_format":[84,0]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "pack.png", png(32, 32, 0xFF8844FF));
            entry(zip, "assets/aaa/blockstates/broken.json", "{".getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/aaa/lang/aa_aa.json", "{".getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/items/modern_blade.json", """
                    {"model":{"type":"minecraft:condition",
                      "property":"minecraft:damaged",
                      "on_true":{"type":"minecraft:model","model":"oraxen:item/modern_blade_damaged"},
                      "on_false":{"type":"minecraft:model","model":"oraxen:item/modern_blade"}}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/modern_blade.json", """
                    {"textures":{"layer0":"oraxen:item/modern_blade"},"elements":[{
                      "from":[7,0,7],"to":[9,16,9],
                      "faces":{"north":{"texture":"#layer0","uv":[0,0,2,16]}}
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/modern_blade_damaged.json", """
                    {"textures":{"layer0":"oraxen:item/modern_blade_damaged"},"elements":[{
                      "from":[6,0,7],"to":[10,14,9],
                      "faces":{"north":{"texture":"#layer0","uv":[0,0,4,14]}}
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/item/modern_blade.png",
                    png(16, 16, 0xFF44CCFF));
            entry(zip, "assets/oraxen/textures/item/modern_blade_damaged.png",
                    png(16, 16, 0xFFFF5544));
            entry(zip, "assets/custom/models/block/sculpted_tile.json",
                    cubeModel("custom:block/sculpted_tile"));
            entry(zip, "assets/custom/textures/block/sculpted_tile.png",
                    png(16, 16, 0xFFCC7733));
            entry(zip, "assets/custom/blockstates/waxed_tile.json", """
                    {"variants":{"facing=north":
                      {"model":"custom:block/sculpted_tile","y":90}}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/lang/en_us.json", """
                    {"item.oraxen.modern_blade":"Modern Blade"}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/custom/lang/en_us.json", """
                    {"block.custom.sculpted_tile":"Sculpted Tile"}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/lang/ru_ru.json", """
                    {"item.oraxen.modern_blade":"Современный клинок"}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/custom/textures/gui/widgets/panel.png",
                    png(8, 8, 0xFF223344));
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 2, 0}, true, true, false, true, true, true, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject mappings = JsonSupport.readObject(result.mappings());
        JsonArray bladeDefinitions = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond_sword");
        assertEquals(2, bladeDefinitions.size());
        JsonObject blade = bladeDefinitions.get(0).getAsJsonObject();
        assertEquals(3072, blade.getAsJsonObject("components")
                .get("minecraft:max_damage").getAsInt());
        assertEquals(4, blade.getAsJsonObject("components")
                .getAsJsonObject("minecraft:attack_range").get("max_reach").getAsInt());
        assertEquals("eat", blade.getAsJsonObject("components")
                .getAsJsonObject("minecraft:consumable").get("animation").getAsString());
        assertEquals("minecraft:entity.generic.eat",
                blade.getAsJsonObject("components")
                        .getAsJsonObject("minecraft:consumable")
                        .get("sound").getAsString());
        JsonObject consumeEffect = blade.getAsJsonObject("components")
                .getAsJsonObject("minecraft:consumable")
                .getAsJsonArray("on_consume_effects")
                .get(0).getAsJsonObject();
        assertEquals("minecraft:apply_effects",
                consumeEffect.get("type").getAsString());
        JsonObject haste = consumeEffect.getAsJsonArray("effects")
                .get(0).getAsJsonObject();
        assertEquals("minecraft:haste", haste.get("id").getAsString());
        assertEquals(40, haste.get("duration").getAsInt());
        assertEquals("oraxen:weapons",
                blade.getAsJsonObject("components")
                        .getAsJsonObject("minecraft:use_cooldown")
                        .get("cooldown_group").getAsString());
        assertEquals(List.of(
                        "minecraft:diamond",
                        "minecraft:netherite_ingot"),
                blade.getAsJsonObject("components")
                        .getAsJsonObject("minecraft:repairable")
                        .getAsJsonArray("items").asList().stream()
                        .map(JsonElement::getAsString).toList());
        JsonObject toolRule = blade.getAsJsonObject("components")
                .getAsJsonObject("minecraft:tool")
                .getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals(List.of(
                        "minecraft:diamond_block",
                        "#minecraft:mineable/pickaxe"),
                toolRule.getAsJsonArray("blocks").asList().stream()
                        .map(JsonElement::getAsString).toList());
        assertFalse(toolRule.has("material"));
        assertFalse(toolRule.has("tags"));
        JsonObject damagedBlade = bladeDefinitions.get(1).getAsJsonObject();
        assertEquals("oraxen:modern_blade", damagedBlade.get("model").getAsString());
        assertEquals("condition", damagedBlade.getAsJsonObject("predicate")
                .get("type").getAsString());
        assertEquals("damaged", damagedBlade.getAsJsonObject("predicate")
                .get("property").getAsString());
        assertTrue(damagedBlade.getAsJsonObject("predicate")
                .get("expected").getAsBoolean());

        JsonObject blocks = JsonSupport.readObject(
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
        JsonObject group = blocks.getAsJsonObject("blocks")
                .getAsJsonObject("custom:waxed_tile");
        assertEquals("oraxen_custom_waxed_tile", group.get("name").getAsString());
        JsonObject state = group.getAsJsonObject("state_overrides")
                .getAsJsonObject("facing=north");
        assertEquals(11, state.get("light_emission").getAsInt());
        assertEquals(4.5, state.get("destructible_by_mining").getAsDouble());
        assertEquals(0.3, state.get("friction").getAsDouble());

        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath("/pack_icon.png")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/ui/custom/widgets/panel.png")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/models/oraxen/modern_blade.geo.json")));
            String damagedId = damagedBlade.get("bedrock_identifier")
                    .getAsString().substring("oraxen:".length());
            assertTrue(Files.isRegularFile(
                    pack.getPath("/models/oraxen/" + damagedId + ".geo.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/" + damagedId + ".attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/textures/items/" + damagedId + ".png")));
            assertTrue(Files.readString(pack.getPath("/texts/en_US.lang"))
                    .contains("block.custom.sculpted_tile=Sculpted Tile"));
            assertTrue(Files.readString(pack.getPath("/texts/ru_RU.lang"))
                    .contains("Современный клинок"));
            assertEquals(List.of("en_US", "ru_RU"),
                    JsonSupport.GSON.fromJson(Files.readString(
                            pack.getPath("/texts/languages.json")),
                            com.google.gson.JsonArray.class).asList().stream()
                            .map(JsonElement::getAsString).toList());
        }

        JsonObject report = JsonSupport.readObject(data.resolve("last-report.json"));
        assertEquals(2, report.get("languages").getAsInt());
        assertEquals(3, report.get("language_entries").getAsInt());
        assertTrue(report.get("validated_references").getAsInt() >= 4);
        assertEquals("69-84", report.get("java_pack_format").getAsString());
    }

    @Test
    void convertsModernEquipmentAssetsWithAllPlayerLayerTypes() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Files.writeString(oraxen.resolve("items/equipment.yml"), """
                modern_helmet:
                  displayname: "Modern Helmet"
                  material: DIAMOND_HELMET
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [modern_helmet]
                  Components:
                    equippable:
                      slot: HEAD
                      asset_id: oraxen:modern_set
                modern_leggings:
                  displayname: "Modern Leggings"
                  material: DIAMOND_LEGGINGS
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [modern_leggings]
                  Components:
                    equippable:
                      slot: LEGS
                      asset_id: oraxen:modern_set
                modern_wings:
                  displayname: "Modern Wings"
                  material: ELYTRA
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [modern_wings]
                  Components:
                    equippable:
                      slot: CHEST
                      asset_id: oraxen:modern_set
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"pack_format":75,"description":"Modern equipment assets"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/modern_helmet.png",
                    png(16, 16, 0xFF804020));
            entry(zip, "assets/oraxen/textures/modern_leggings.png",
                    png(16, 16, 0xFF804020));
            entry(zip, "assets/oraxen/textures/modern_wings.png",
                    png(16, 16, 0xFF804020));
            entry(zip, "assets/oraxen/equipment/modern_set.json", """
                    {
                      "layers":{
                        "humanoid":[
                          {
                            "texture":"oraxen:modern/base",
                            "dyeable":{"color_when_undyed":8405024}
                          },
                          {"texture":"oraxen:modern/overlay"}
                        ],
                        "humanoid_leggings":[
                          {"texture":"oraxen:modern/leggings"}
                        ],
                        "wings":[
                          {
                            "texture":"oraxen:modern/wings",
                            "use_player_texture":true
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip,
                    "assets/oraxen/textures/entity/equipment/humanoid/modern/base.png",
                    animatedPng(16, 0xFFFFFFFF, 0xFF808080));
            entry(zip,
                    "assets/oraxen/textures/entity/equipment/humanoid/modern/base.png.mcmeta",
                    """
                    {"animation":{"frametime":2,"frames":[0,1]}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip,
                    "assets/oraxen/textures/entity/equipment/humanoid/modern/overlay.png",
                    png(16, 16, 0x800000FF));
            entry(zip,
                    "assets/oraxen/textures/entity/equipment/humanoid_leggings/modern/leggings.png",
                    png(32, 32, 0xFF33CC66));
            entry(zip,
                    "assets/oraxen/textures/entity/equipment/wings/modern/wings.png",
                    png(16, 16, 0xFF55AAFF));
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 4, 0}, true, true, false, false, true, true, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);

        assertEquals(3, result.items());
        assertTrue(result.warnings().stream().anyMatch(message ->
                message.contains("Java dyeable equipment layers")));
        assertTrue(result.warnings().stream().anyMatch(message ->
                message.contains("player-specific wing textures")));
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/modern_helmet.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/modern_leggings.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/modern_wings.attachable.json")));

            Path helmetTexture =
                    pack.getPath("/textures/models/armor/modern_set_armor_layer_1.png");
            try (InputStream input = Files.newInputStream(helmetTexture)) {
                BufferedImage image = ImageIO.read(input);
                assertEquals(16, image.getWidth());
                assertEquals(32, image.getHeight());
                assertEquals(0xFF3F1F8F, image.getRGB(8, 8),
                        "The default dye color and translucent overlay must be composited");
            }
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/models/armor/modern_set_armor_layer_1_0.png")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/textures/models/armor/modern_set_armor_layer_1_1.png")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/render_controllers/oraxen/modern_helmet.render_controllers.json")));

            try (InputStream input = Files.newInputStream(pack.getPath(
                    "/textures/models/armor/modern_set_armor_layer_2.png"))) {
                BufferedImage image = ImageIO.read(input);
                assertEquals(32, image.getWidth());
                assertEquals(32, image.getHeight());
                assertEquals(0xFF33CC66, image.getRGB(16, 16));
            }
            try (InputStream input = Files.newInputStream(pack.getPath(
                    "/textures/models/armor/modern_set_elytra.png"))) {
                assertEquals(0xFF55AAFF, ImageIO.read(input).getRGB(8, 8));
            }

            JsonObject helmetAttachable = JsonSupport.readObject(
                    pack.getPath("/attachables/oraxen/modern_helmet.attachable.json"));
            JsonObject helmetDescription = helmetAttachable
                    .getAsJsonObject("minecraft:attachable")
                    .getAsJsonObject("description");
            assertEquals("controller.render.oraxen.modern_helmet.animated",
                    helmetDescription.getAsJsonArray("render_controllers")
                            .get(0).getAsString());
            assertEquals("textures/models/armor/modern_set_armor_layer_1",
                    helmetDescription.getAsJsonObject("textures")
                            .get("default").getAsString());

            JsonObject wingsAttachable = JsonSupport.readObject(
                    pack.getPath("/attachables/oraxen/modern_wings.attachable.json"));
            JsonObject wingsDescription = wingsAttachable
                    .getAsJsonObject("minecraft:attachable")
                    .getAsJsonObject("description");
            assertEquals("elytra", wingsDescription.getAsJsonObject("materials")
                    .get("default").getAsString());
            assertEquals("geometry.elytra",
                    wingsDescription.getAsJsonObject("geometry")
                            .get("default").getAsString());
        }
    }

    @Test
    void selectsBlockRenderMethodFromTextureAlpha() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Files.writeString(oraxen.resolve("items/alpha.yml"), """
                opaque_block:
                  material: PAPER
                  Pack:
                    model: block/opaque_block
                    textures: [opaque_block]
                  Mechanics:
                    noteblock:
                      custom_variation: 1
                      model: opaque_block
                cutout_block:
                  material: PAPER
                  Pack:
                    model: block/cutout_block
                    textures: [cutout_block]
                  Mechanics:
                    noteblock:
                      custom_variation: 2
                      model: cutout_block
                translucent_block:
                  material: PAPER
                  Pack:
                    model: block/translucent_block
                    textures: [translucent_block]
                  Mechanics:
                    noteblock:
                      custom_variation: 3
                      model: translucent_block
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"pack_format":75,"description":"Alpha modes"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/opaque_block.png",
                    rowPng(0xFFFF0000, 0xFF00FF00));
            entry(zip, "assets/oraxen/textures/cutout_block.png",
                    rowPng(0xFFFF0000, 0x00000000));
            entry(zip, "assets/oraxen/textures/translucent_block.png",
                    rowPng(0xFFFF0000, 0x8000FF00));
            entry(zip, "assets/oraxen/models/block/opaque_block.json",
                    cubeModel("oraxen:opaque_block"));
            entry(zip, "assets/oraxen/models/block/cutout_block.json",
                    cubeModel("oraxen:cutout_block"));
            entry(zip, "assets/oraxen/models/block/translucent_block.json",
                    cubeModel("oraxen:translucent_block"));
            entry(zip, "assets/minecraft/blockstates/note_block.json", """
                    {"variants":{
                      "instrument=banjo,note=1,powered=false":
                        {"model":"oraxen:block/opaque_block"},
                      "instrument=banjo,note=2,powered=false":
                        {"model":"oraxen:block/cutout_block"},
                      "instrument=banjo,note=3,powered=false":
                        {"model":"oraxen:block/translucent_block"}
                    }}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 5, 0}, true, true, false, false, false, false, false,
                false, false, 100, false);
        new PackConverter(temp.resolve("plugins/OraxenBedrock")).convert(config);

        JsonObject states = JsonSupport.readObject(
                        geyser.resolve("custom_mappings/oraxen-blocks.json"))
                .getAsJsonObject("blocks")
                .getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides");
        assertEquals("opaque", renderMethod(states,
                "instrument=banjo,note=1,powered=false"));
        assertEquals("alpha_test", renderMethod(states,
                "instrument=banjo,note=2,powered=false"));
        assertEquals("blend", renderMethod(states,
                "instrument=banjo,note=3,powered=false"));
    }

    @Test
    void mergesMultipartApplyModelsIntoOneBlockState() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Files.writeString(oraxen.resolve("items/multipart.yml"), """
                composite_block:
                  material: PAPER
                  Pack:
                    model: block/multipart/base
                    textures: [multipart_base]
                  Mechanics:
                    shaped_block:
                      type: BLOCK
                      model: block/multipart
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"pack_format":75,"description":"Multipart composite"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/multipart_base.png",
                    png(16, 16, 0xFFCC4422));
            entry(zip, "assets/oraxen/textures/multipart_detail.png",
                    png(16, 16, 0xFF2288CC));
            entry(zip, "assets/oraxen/models/block/multipart/base.json", """
                    {"textures":{"all":"oraxen:multipart_base"},"elements":[{
                      "from":[0,0,0],"to":[16,8,16],
                      "faces":{"north":{"texture":"#all","uv":[0,0,16,8]}}
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/multipart/detail.json", """
                    {"textures":{"all":"oraxen:multipart_detail"},"elements":[{
                      "from":[6,8,6],"to":[10,16,10],
                      "faces":{"north":{"texture":"#all","uv":[0,0,4,8]}}
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/custom/blockstates/multipart_host.json", """
                    {"multipart":[
                      {"apply":{"model":"oraxen:block/multipart/base"}},
                      {"apply":{"model":"oraxen:block/multipart/detail","y":90}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "oraxen",
                new int[]{1, 6, 0}, true, true, false, false, false, false, false,
                false, false, 100, false);
        ConversionResult result =
                new PackConverter(temp.resolve("plugins/OraxenBedrock")).convert(config);

        JsonObject states = JsonSupport.readObject(
                        geyser.resolve("custom_mappings/oraxen-blocks.json"))
                .getAsJsonObject("blocks")
                .getAsJsonObject("custom:multipart_host")
                .getAsJsonObject("state_overrides");
        assertEquals(1, states.size());
        JsonObject override = states.entrySet().iterator().next()
                .getValue().getAsJsonObject();
        JsonObject materials = override.getAsJsonObject("material_instances");
        assertEquals(2, materials.size());
        List<String> textureKeys = materials.entrySet().stream()
                .map(entry -> entry.getValue().getAsJsonObject()
                        .get("texture").getAsString())
                .toList();
        assertTrue(textureKeys.stream().anyMatch(value -> value.contains("part_0_all")));
        assertTrue(textureKeys.stream().anyMatch(value -> value.contains("part_1_all")));

        String geometryIdentifier = override.get("geometry").getAsString();
        String geometryFile = geometryIdentifier.substring(
                geometryIdentifier.lastIndexOf('.') + 1);
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            JsonArray bones = JsonSupport.readObject(
                            pack.getPath("/models/oraxen/" + geometryFile + ".geo.json"))
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                    .getAsJsonArray("bones");
            assertEquals(2, bones.size());
            assertEquals(List.of("part_0_0", "part_1_0"), bones.asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .map(bone -> bone.get("name").getAsString())
                    .toList());
            JsonObject rotatedPart = bones.get(1).getAsJsonObject();
            assertEquals(List.of(0, 270, 0),
                    rotatedPart.getAsJsonArray("rotation").asList().stream()
                            .map(JsonElement::getAsInt).toList());
        }
    }

    @Test
    void supportsMinecraft1205MetadataModernRangesAndPackOverlays() throws Exception {
        Path packFile = temp.resolve("compatibility.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(packFile))) {
            entry(zip, "pack.mcmeta", """
                    {
                      "pack":{
                        "pack_format":32,
                        "supported_formats":{"min_inclusive":32,"max_inclusive":75},
                        "description":"1.20.5 through 1.21.11"
                      },
                      "overlays":{"entries":[
                        {"formats":{"min_inclusive":32,"max_inclusive":75},
                         "directory":"compatible"},
                        {"formats":{"min_inclusive":46,"max_inclusive":75},
                         "directory":"future"}
                      ]}
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/item/versioned.png",
                    png(16, 16, 0xFFFF0000));
            entry(zip, "compatible/assets/oraxen/textures/item/versioned.png",
                    png(16, 16, 0xFF00FF00));
            entry(zip, "future/assets/oraxen/textures/item/versioned.png",
                    png(16, 16, 0xFF0000FF));
        }

        try (PackSource source = PackSource.open(packFile)) {
            assertEquals(32, source.metadata().packFormat());
            assertEquals(32, source.metadata().minFormat());
            assertEquals(75, source.metadata().maxFormat());
            assertEquals("32-75", source.metadata().description());
            assertEquals(2, source.metadata().overlays().size());
            assertEquals(1, source.metadata().activeOverlays().size());
            assertEquals(2, source.assetRoots().size());
            try (InputStream input = Files.newInputStream(
                    source.findTexture("oraxen:item/versioned"))) {
                assertEquals(0xFF00FF00, ImageIO.read(input).getRGB(8, 8));
            }
        }

        assertTrue(MinecraftVersion.parse("1.20.5-R0.1-SNAPSHOT").supported());
        assertTrue(MinecraftVersion.parse("1.21.11").supported());
        assertTrue(MinecraftVersion.parse("26.2").supported());
        assertFalse(MinecraftVersion.parse("1.20.4").supported());
    }

    @Test
    void fixesLegacyMappingsBuiltinParentsAndAssetLookupIsolation() throws Exception {
        Path oraxen = temp.resolve("plugins/Oraxen");
        Files.createDirectories(oraxen.resolve("items"));
        Files.writeString(oraxen.resolve("items/compatibility.yml"), """
                legacy_widget:
                  displayname: "Legacy Widget"
                  material: custom:widget
                  unstackable: true
                  Pack:
                    custom_model_data: 17
                    model: item/legacy_widget
                    textures: [shared]
                  Mechanics:
                    durability:
                      value: 444
                modern_cube:
                  displayname: "Modern Cube"
                  material: PAPER
                  Pack:
                    custom_model_data: 22
                    model: block/modern_cube
                    textures: [modern_cube]
                modern_stairs:
                  displayname: "Modern Stairs"
                  material: PAPER
                  excludeFromInventory: true
                  Pack:
                    generate_model: true
                    parent_model: block/stairs
                    textures: [modern_cube]
                  Mechanics:
                    shaped_block:
                      type: STAIR
                      custom_variation: 1
                modern_door:
                  displayname: "Modern Door"
                  material: PAPER
                  Pack:
                    generate_model: true
                    parent_model: item/generated
                    textures: [modern_door_icon]
                  Mechanics:
                    shaped_block:
                      type: DOOR
                      custom_variation: 1
                      textures:
                        bottom: modern_door_bottom
                        top: modern_door_top
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"pack_format":46,"description":"Minecraft 1.21.4"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/shared.png",
                    png(16, 16, 0xFFAA5500));
            entry(zip, "assets/oraxen/textures/modern_cube.png",
                    png(16, 16, 0xFF55AA00));
            entry(zip, "assets/oraxen/textures/modern_door_icon.png",
                    png(16, 16, 0xFF884422));
            entry(zip, "assets/oraxen/textures/modern_door_bottom.png",
                    png(16, 16, 0xFF995533));
            entry(zip, "assets/oraxen/textures/modern_door_top.png",
                    png(16, 16, 0xFFCC8855));
            entry(zip, "assets/oraxen/items/modern_cube.json", """
                    {"model":{"type":"minecraft:model","model":"block/modern_cube"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/modern_cube.json", """
                    {"parent":"block/cube_all","textures":{"all":"oraxen:modern_cube"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/modern_stairs.json", """
                    {"parent":"block/stairs","textures":{
                      "bottom":"oraxen:modern_cube",
                      "top":"oraxen:modern_cube",
                      "side":"oraxen:modern_cube"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/modern_stairs_inner.json", """
                    {"parent":"block/inner_stairs","textures":{
                      "bottom":"oraxen:modern_cube",
                      "top":"oraxen:modern_cube",
                      "side":"oraxen:modern_cube"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/modern_stairs_outer.json", """
                    {"parent":"block/outer_stairs","textures":{
                      "bottom":"oraxen:modern_cube",
                      "top":"oraxen:modern_cube",
                      "side":"oraxen:modern_cube"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/minecraft/blockstates/waxed_copper_stairs.json", """
                    {"variants":{
                      "facing=north,half=bottom,shape=straight,waterlogged=false":
                        {"model":"oraxen:block/modern_stairs"},
                      "facing=north,half=bottom,shape=inner_left,waterlogged=false":
                        {"model":"oraxen:block/modern_stairs_inner"},
                      "facing=north,half=bottom,shape=outer_left,waterlogged=false":
                        {"model":"oraxen:block/modern_stairs_outer"}
                    }}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/modern_door.json", """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"oraxen:modern_door_icon"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/modern_door_bottom_left.json", """
                    {"parent":"block/door_bottom_left",
                     "textures":{"bottom":"oraxen:modern_door_bottom"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/block/modern_door_top_left.json", """
                    {"parent":"block/door_top_left",
                     "textures":{"top":"oraxen:modern_door_top"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/minecraft/blockstates/waxed_copper_door.json", """
                    {"variants":{
                      "facing=north,half=lower,hinge=left,open=false,powered=false":
                        {"model":"oraxen:block/modern_door_bottom_left"},
                      "facing=north,half=upper,hinge=left,open=false,powered=false":
                        {"model":"oraxen:block/modern_door_top_left"}
                    }}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        try (PackSource source = PackSource.open(javaPack)) {
            assertNull(source.findTexture("custom:shared"),
                    "Texture fallback must not leak across namespaces");
            assertNull(source.findAsset("oraxen", "../oraxen/textures/shared.png"),
                    "Asset references with traversal segments must be rejected");
        }

        Path geyser = temp.resolve("plugins/Geyser-Spigot");
        Path data = temp.resolve("plugins/OraxenBedrock");
        BridgeConfig config = new BridgeConfig(
                temp, oraxen, geyser, javaPack, "Test", "Test pack", "bedrocktest",
                new int[]{1, 3, 0}, true, true, false, false, false, false, false,
                false, false, 100, false);

        ConversionResult result = new PackConverter(data).convert(config);
        JsonObject mappings = JsonSupport.readObject(result.mappings());
        JsonObject legacy = mappings.getAsJsonObject("items")
                .getAsJsonArray("custom:widget").get(0).getAsJsonObject();
        assertEquals("legacy", legacy.get("type").getAsString());
        assertEquals(17, legacy.get("custom_model_data").getAsInt());
        assertEquals(1, legacy.getAsJsonObject("components")
                .get("minecraft:max_stack_size").getAsInt());
        assertEquals(444, legacy.getAsJsonObject("components")
                .get("minecraft:max_damage").getAsInt());
        JsonObject modern = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").get(0).getAsJsonObject();
        assertEquals(3, mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").size());
        assertEquals("definition", modern.get("type").getAsString());
        assertEquals("oraxen:modern_cube", modern.get("model").getAsString());
        assertEquals("bedrocktest:modern_cube",
                modern.get("bedrock_identifier").getAsString());
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertAllMappedIconsResolve(mappings, pack);
            JsonObject geometry = JsonSupport.readObject(
                    pack.getPath("/models/bedrocktest/modern_cube.geo.json"));
            assertEquals("geometry.bedrocktest.modern_cube",
                    geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                            .getAsJsonObject("description").get("identifier").getAsString());
            JsonArray cubes = geometry.getAsJsonArray("minecraft:geometry").get(0)
                    .getAsJsonObject().getAsJsonArray("bones").get(0)
                    .getAsJsonObject().getAsJsonArray("cubes");
            assertEquals(1, cubes.size(),
                    "An unqualified block/cube_all parent must resolve to the Minecraft builtin");
            JsonObject stairsGeometry = JsonSupport.readObject(
                    pack.getPath("/models/bedrocktest/modern_stairs.geo.json"));
            JsonArray stairCubes = stairsGeometry.getAsJsonArray("minecraft:geometry").get(0)
                    .getAsJsonObject().getAsJsonArray("bones").get(0)
                    .getAsJsonObject().getAsJsonArray("cubes");
            assertEquals(2, stairCubes.size(),
                    "The built-in stairs parent must produce stepped geometry");

            JsonObject stateOverrides = JsonSupport.readObject(
                            geyser.resolve("custom_mappings/oraxen-blocks.json"))
                    .getAsJsonObject("blocks")
                    .getAsJsonObject("minecraft:waxed_copper_stairs")
                    .getAsJsonObject("state_overrides");
            String innerGeometry = stateOverrides
                    .getAsJsonObject(
                            "facing=north,half=bottom,shape=inner_left,waterlogged=false")
                    .get("geometry").getAsString();
            String outerGeometry = stateOverrides
                    .getAsJsonObject(
                            "facing=north,half=bottom,shape=outer_left,waterlogged=false")
                    .get("geometry").getAsString();
            assertNotEquals("geometry.bedrocktest.modern_stairs", innerGeometry);
            assertNotEquals(innerGeometry, outerGeometry);
            assertEquals(3, geometryCubeCount(pack, innerGeometry));
            assertEquals(2, geometryCubeCount(pack, outerGeometry));
        }
        JsonObject report = JsonSupport.readObject(data.resolve("last-report.json"));
        assertEquals(4, report.get("oraxen_items_scanned").getAsInt());
        assertEquals(4, report.get("items").getAsInt());
        assertEquals(2, report.get("blocks").getAsInt());
        JsonObject hiddenStairsItem = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").get(1).getAsJsonObject();
        assertEquals("none", hiddenStairsItem.getAsJsonObject("bedrock_options")
                .get("creative_category").getAsString());
        JsonObject blockMappings = JsonSupport.readObject(
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
        assertEquals("geometry.bedrocktest.modern_stairs",
                blockMappings.getAsJsonObject("blocks")
                        .getAsJsonObject("minecraft:waxed_copper_stairs")
                        .getAsJsonObject("state_overrides")
                        .getAsJsonObject(
                                "facing=north,half=bottom,shape=straight,waterlogged=false")
                        .get("geometry").getAsString());
        JsonObject doorStates = blockMappings.getAsJsonObject("blocks")
                .getAsJsonObject("minecraft:waxed_copper_door")
                .getAsJsonObject("state_overrides");
        assertEquals(2, doorStates.size());
        String lowerDoorGeometry = doorStates.getAsJsonObject(
                        "facing=north,half=lower,hinge=left,open=false,powered=false")
                .get("geometry").getAsString();
        String upperDoorGeometry = doorStates.getAsJsonObject(
                        "facing=north,half=upper,hinge=left,open=false,powered=false")
                .get("geometry").getAsString();
        assertNotEquals(lowerDoorGeometry, upperDoorGeometry);
    }

    private static byte[] cubeModel(String texture) {
        return ("""
                {"textures":{"all":"%s"},"elements":[{
                  "from":[0,0,0],"to":[16,16,16],
                  "faces":{"north":{"texture":"#all","uv":[0,0,16,16]}}
                }]}
                """.formatted(texture)).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertAllMappedIconsResolve(
            JsonObject mappings, FileSystem pack) throws IOException {
        JsonObject atlas = JsonSupport.readObject(pack.getPath(
                "/textures/item_texture.json")).getAsJsonObject("texture_data");
        for (JsonElement definitions
                : mappings.getAsJsonObject("items").asMap().values()) {
            for (JsonElement value : definitions.getAsJsonArray()) {
                JsonObject definition = value.getAsJsonObject();
                JsonObject options = definition.getAsJsonObject("bedrock_options");
                if (options == null || !options.has("icon")) continue;
                String icon = options.get("icon").getAsString();
                assertTrue(atlas.has(icon), "Missing atlas shorthand " + icon);
                JsonArray textures = atlas.getAsJsonObject(icon)
                        .getAsJsonArray("textures");
                assertNotNull(textures, "Atlas entry must use a textures array");
                assertFalse(textures.isEmpty());
                for (JsonElement texture : textures) {
                    Path path = pack.getPath("/" + texture.getAsString() + ".png");
                    assertTrue(Files.isRegularFile(path),
                            "Missing mapped icon PNG " + path);
                    try (InputStream input = Files.newInputStream(path)) {
                        assertNotNull(ImageIO.read(input),
                                "Unreadable mapped icon PNG " + path);
                    }
                }
            }
        }
    }

    private static int geometryCubeCount(FileSystem pack, String identifier)
            throws IOException {
        String file = identifier.substring(identifier.lastIndexOf('.') + 1);
        JsonObject geometry = JsonSupport.readObject(
                pack.getPath("/models/bedrocktest/" + file + ".geo.json"));
        return geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones").get(0).getAsJsonObject()
                .getAsJsonArray("cubes").size();
    }

    private static String renderMethod(JsonObject states, String state) {
        return states.getAsJsonObject(state)
                .getAsJsonObject("material_instances")
                .entrySet().iterator().next().getValue().getAsJsonObject()
                .get("render_method").getAsString();
    }

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] rowPng(int... pixels) throws IOException {
        BufferedImage image =
                new BufferedImage(pixels.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < pixels.length; x++) image.setRGB(x, 0, pixels[x]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] animatedPng(int size, int... frames) throws IOException {
        BufferedImage image = new BufferedImage(size, size * frames.length,
                BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < frames.length; frame++)
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    image.setRGB(x, frame * size + y, frames[frame]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
