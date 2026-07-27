package dev.oraxenbedrock.conversion;

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
                """);
        Path javaPack = oraxen.resolve("pack/pack.zip");
        Files.createDirectories(javaPack.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(javaPack))) {
            entry(zip, "pack.mcmeta", """
                    {"pack":{"pack_format":32,"description":"Minecraft 1.20.5"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/ruby.png", new byte[]{1, 2, 3});
            entry(zip, "assets/oraxen/textures/ruby_block.png",
                    animatedPng(16, 0xFFFF0000, 0xFF00FF00));
            entry(zip, "assets/oraxen/textures/ruby_block.png.mcmeta", """
                    {"animation":{"frametime":2,"interpolate":true}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/flower.png", new byte[]{7, 8, 9});
            entry(zip, "assets/oraxen/textures/ruby_sword.png", new byte[]{10, 11, 12});
            entry(zip, "assets/oraxen/textures/ruby_helmet.png", new byte[]{13, 14, 15});
            entry(zip, "assets/oraxen/textures/ruby_armor_layer_1.png", new byte[]{16, 17, 18});
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
                        {"model":"oraxen:block/ruby_block","y":90}
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
        assertEquals("oraxen:ruby", itemJson.getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond").get(0).getAsJsonObject()
                .get("model").getAsString());
        JsonObject blockJson = JsonSupport.readObject(
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
        assertTrue(blockJson.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block")
                .getAsJsonObject("state_overrides")
                .has("instrument=banjo,note=1,powered=false"));
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
        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/ruby_sword.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/ruby_helmet.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath("/textures/models/armor/ruby_armor_layer_1.png")));
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
                    {"animation":{"frametime":1}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/font/emojis.png", emoji);
            entry(zip, "assets/oraxen/models/block/crown.json", cubeModel("oraxen:crown"));
            entry(zip, "assets/oraxen/models/block/chair.json", cubeModel("oraxen:chair"));
            entry(zip, "assets/oraxen/models/block/animated_sword.json",
                    cubeModel("oraxen:animated_sword"));
            entry(zip, "assets/oraxen/models/item/night_disc.json", """
                    {"parent":"minecraft:item/generated",
                     "textures":{"layer0":"oraxen:night_disc"}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/font/default.json", ("""
                    {"providers":[
                      {"type":"bitmap","file":"oraxen:font/emojis.png","ascent":15,"height":16,
                       "chars":["%s"]}
                    ]}
                    """.formatted(Character.toString(0xE101))).getBytes(StandardCharsets.UTF_8));
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

        try (FileSystem pack = FileSystems.newFileSystem(result.pack())) {
            assertTrue(Files.isRegularFile(pack.getPath("/font/glyph_E1.png")));
            assertTrue(Files.isRegularFile(pack.getPath("/sounds/oraxen/music/night.ogg")));
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/crown.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath("/attachables/oraxen/chair.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/animated_sword.attachable.json")));
            assertTrue(Files.isRegularFile(
                    pack.getPath("/attachables/oraxen/night_disc.attachable.json")));
            assertTrue(Files.isRegularFile(pack.getPath(
                    "/models/oraxen/crown.attachable.geo.json")));
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
                assertEquals(256, page.getWidth());
                assertEquals(256, page.getHeight());
                // U+E101 is cell 1 (x=16..31) on page E1.
                assertNotEquals(0, page.getRGB(24, 8) >>> 24);
            }
        }

        JsonObject report = JsonSupport.readObject(data.resolve("last-report.json"));
        assertEquals(1, report.get("sound_events").getAsInt());
        assertEquals(1, report.get("sound_files").getAsInt());
        assertEquals(1, report.get("glyphs_and_emojis").getAsInt());
        assertEquals(1, report.get("glyph_pages").getAsInt());
        assertEquals(6, report.get("animated_textures").getAsInt());
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
            entry(zip, "assets/oraxen/items/modern_blade.json", """
                    {"model":{"type":"minecraft:condition",
                      "property":"minecraft:using_item",
                      "on_true":{"type":"minecraft:model","model":"oraxen:item/modern_blade"},
                      "on_false":{"type":"minecraft:model","model":"oraxen:item/modern_blade"}}}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/models/item/modern_blade.json", """
                    {"textures":{"layer0":"oraxen:item/modern_blade"},"elements":[{
                      "from":[7,0,7],"to":[9,16,9],
                      "faces":{"north":{"texture":"#layer0","uv":[0,0,2,16]}}
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/oraxen/textures/item/modern_blade.png",
                    png(16, 16, 0xFF44CCFF));
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
        JsonObject blade = mappings.getAsJsonObject("items")
                .getAsJsonArray("minecraft:diamond_sword").get(0).getAsJsonObject();
        assertEquals(3072, blade.getAsJsonObject("components")
                .get("minecraft:max_damage").getAsInt());
        assertEquals(4, blade.getAsJsonObject("components")
                .getAsJsonObject("minecraft:attack_range").get("max_reach").getAsInt());

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

    private static byte[] cubeModel(String texture) {
        return ("""
                {"textures":{"all":"%s"},"elements":[{
                  "from":[0,0,0],"to":[16,16,16],
                  "faces":{"north":{"texture":"#all","uv":[0,0,16,16]}}
                }]}
                """.formatted(texture)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
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
