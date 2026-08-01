# OraxenBedrock


Paper/Bukkit addon for Minecraft Java **1.20.5 and newer** that converts Oraxen
item and block assets into a Bedrock resource pack and installs it into Geyser.
The plugin targets Java 21 and is compiled against the Paper 1.20.6 API so the
oldest supported server family is continuously checked at build time.

`plugin.yml` declares `api-version: 1.20.5`; newer Paper/Spigot releases can
load the same JAR. Runtime startup also parses the server version and refuses
versions below 1.20.5 with a clear error instead of failing later during pack
generation.

## What is added?

- `plugins/Geyser-Spigot/packs/OraxenBedrock.mcpack`
- `plugins/Geyser-Spigot/custom_mappings/oraxen-items.json` (Geyser v2 items)
- `plugins/Geyser-Spigot/custom_mappings/oraxen-blocks.json` (block states)
- `plugins/OraxenBedrock/last-report.json` (conversion diagnostics)

The converter reads Oraxen YAML plus its generated `pack/pack.zip`. It
supports regular 2D items, generated cube/cutout blocks, Oraxen note/string/
chorus/shaped block states, named sounds, bitmap fonts/emojis, localizations,
pack icons, and namespaced GUI texture transfer. Java block states are
discovered across every namespace in Oraxen's generated
`assets/*/blockstates/*.json`, avoiding fragile hard-coded
`custom_variation` calculations.

When Oraxen resource-key obfuscation or a version overlay removes the logical
model path from the generated ZIP, lookup falls back to the uncompressed
`plugins/Oraxen/pack/models` and `pack/textures` sources. Open-ended metadata
ranges such as `min_format: 46` / `max_format: 999` use the minimum format as
their baseline overlay instead of incorrectly selecting the future sentinel.

Current unified Oraxen block declarations are supported too, including
`Mechanics.block.type`, nested `appearance.model`, hyphenated setting names,
and the related models generated for stairs, slabs, doors, trapdoors, grates,
and bulbs. Legacy `noteblock`, `stringblock`, `chorusblock`, and shaped-block
sections remain compatible.

Modern Java 1.21.4+ item definitions from `assets/*/items/*.json` are resolved,
including model, composite, condition, select and range-dispatch nodes.
Their alternate visual states become additional Geyser mappings instead of
being discarded. Supported conditions include broken, damaged, custom model
data, component presence and the fishing-rod cast state; supported selectors
include crossbow charge type, armor-trim material, context dimension and custom
model data; supported ranges include bundle fullness, damage, stack count and
custom model data. Unsupported predicate types still use their explicit
fallback/normal branch and are identified in the conversion report. Both
modern definitions and traditional `models/item` assets can therefore drive
Bedrock geometry, icons and state-dependent variants.

Current Oraxen `Pack.models` entries are converted into independent Geyser
definitions too. Furniture jukebox states, inline plant stages and other named
models such as `oraxen:item_id/active` therefore retain their own icon,
geometry and attachable. `Pack.gui_model` display-context branches are used for
the Bedrock inventory icon while the normal fallback model remains the held
3D model, which preserves the intended split for custom swords and tools.
Oraxen's `model_data_ids` appearance mode is discovered inside the vanilla
material item definition and emitted with the matching Geyser
`custom_model_data` string predicate. The numeric `model_data_float` mode is
also discovered from its generated material definition even when the assigned
CMD value was not written into the source item YAML. With FULL resource-key
obfuscation, the converter can recover the renamed model by comparing its
decoded generated textures with the original files under Oraxen's `pack`
directory. If several numeric entries use the same source texture, conversion
reports the ambiguity and refuses to guess a CMD threshold.

Oraxen 1.212+ stackable StringBlocks are mapped by their declared
`custom_variation` values, including every `stackable` level. This keeps all
stack geometries correct even when Oraxen's SIMPLE/FULL resource-key
obfuscation replaces the configured model paths in the generated blockstate.
The effective modern item model is likewise used to locate obfuscated
NoteBlock and other generated block models.

Resource-pack metadata is version-aware across the full supported range:

- classic `pack_format` used by Minecraft 1.20.5/1.20.6;
- `supported_formats` ranges;
- modern numeric or array-based `min_format` / `max_format`;
- `overlays.entries`, applied only when their declared format range includes
  the pack's target format;
- unknown future formats are accepted and reported instead of being rejected
  by a hard-coded upper version limit.

Custom audio is registered in Bedrock `sounds/sound_definitions.json`, not
merely copied. Oraxen `sound.yml` categories and streaming flags are preserved,
and items with `Components.jukebox_playable` force the matching song into the
`record` category with streaming enabled. This lets Geyser translate custom
sound events used by music discs.

Java bitmap font providers are collected into Bedrock `font/glyph_XX.png`
pages. This covers Oraxen emoji and other BMP private-use glyphs in chat,
names, lore, scoreboards, and menus. Bedrock has no glyph page for
supplementary code points above `U+FFFF`; those are reported rather than
silently producing a broken font.

Private-use emoji pages use a configurable 64 px minimum cell (1024×1024
page) by default. Small 8×9 and 16×16 pixel-art emoji are enlarged with
nearest-neighbour sampling, while declared 32/64/128 px sizes and
high-resolution sources are kept on appropriately larger page grids without
blurring. Set `conversion.emoji-cell-size` to `32`, `64`, or `128` to tune
the visual size for a specific Bedrock UI scale.

Animated Java textures with a sibling `texture.png.mcmeta` are converted too:

- block and block-backed decoration textures are registered in Bedrock
  `textures/flipbook_textures.json`;
- animated swords, tools, hats, flat items and 3D furniture are
  split into individual frames and receive a generated attachable render
  controller;
- models with several materials are packed into a power-of-two Bedrock atlas;
  independently animated materials are synchronized on a shared timeline and
  rendered without dropping static or animated layers;
- Java frame order and per-frame timing are preserved, including frame objects
  with their own `time`;
- horizontal or grid-based Java frame sheets are repacked into the vertical
  layout expected by Bedrock;
- Java interpolation is preserved for block flipbooks. Bedrock attachables
  switch complete frames and therefore cannot reproduce Java cross-fading
  exactly.

Bedrock's item inventory atlas does not reliably animate custom entries, so the
inventory icon uses frame zero while the held/equipped 2D or 3D model animates.

Standard Java 3D JSON models are converted automatically: inherited parents,
elements, per-face UV, element rotations, texture aliases, multiple materials,
texture-size-aware UV scaling, and the common `cube_all`, `cube_column`,
`orientable`, `cross`, leaves and trapdoor template parents. Java
first-person, third-person and head `display` transforms are translated into
Bedrock attachable animations, preserving item rotation, translation and scale
while held or equipped.

Oraxen shaped blocks are resolved per Java block state rather than flattened to
one model. Stairs retain straight, inner and outer corner geometry; doors retain
separate upper/lower and open/closed variants; rotations and other declared
variant transforms produce distinct Bedrock block mappings and geometries.

Equipment is pre-converted as well:

- swords, axes, pickaxes, bows, crossbows, tridents, maces, shields and tools
  receive handheld options and 3D Bedrock attachables;
- custom weapons based on neutral materials such as `PAPER` are recognized
  from their resolved `item/handheld` parent (or weapon-shaped item/component
  metadata), while an explicitly configured stack size is preserved;
- component-based helmets, chestplates, leggings and boots receive
  `equippable` mappings, armor geometry and the correct Oraxen armor layer;
- modern Java equipment assets from `assets/*/equipment/*.json` are supported,
  including `humanoid`, `humanoid_leggings` and `wings` layer selection and
  layered texture composition, while legacy `_armor_layer_1` and
  `_armor_layer_2` packs remain compatible;
- elytra receive their texture, vanilla wing geometry and gliding animation;
- component-based 3D hats receive a head-bound attachable generated from their
  Java model;
- furniture and other 3D decorations receive generic item attachables, so the
  converted model is available while held/equipped and in supported Geyser
  item render contexts;
- enchantment glint and creative inventory groups are preserved.

Supported Geyser v2 Java components (`equippable`, `food`, `consumable`,
durability, stack size, cooldown, enchantable, tool and repair data,
`attack_range`, kinetic/piercing weapons, swing animation and use effects) are
copied to item mappings. Oraxen's advanced durability object, friendly
tool-rule fields (`material`, `materials`, `tag`, `tags`), consumable effects,
sound identifiers, cooldown groups and repair holder sets are normalized to
the vanilla component structures expected by Geyser.

Block light emission, light dampening, hardness and friction are carried into
custom block state overrides. Mechanic names are matched case-insensitively,
and duplicate or sanitization-colliding item identifiers now fail with a clear
diagnostic instead of producing ambiguous mappings.

Java `assets/*/lang/*.json` and legacy `.lang` files are merged by locale and
written as Bedrock `texts/*.lang` plus `texts/languages.json`. Before
installation, the converter validates atlas textures, geometries, flipbooks,
sound files and duplicate identifiers; the checked-reference count and all
non-fatal approximations are written to `last-report.json`.

Minecraft Java shaders and the exact placement/animation behavior of runtime
display-entity furniture have no lossless Bedrock resource-pack equivalent.
Block-backed decorations are mapped normally; display-entity furniture keeps
its icon, converted geometry and attachable, while the final placed rendering
still depends on the Geyser version's display-entity translation. Optional
native Bedrock replacements can be placed in:

```text
plugins/OraxenBedrock/overrides/
```

Those files are merged into every generated pack. See the generated
`overrides/README.txt`.

## Build

Requires JDK 21+ and Maven:

```bash
mvn clean package
```

Install `target/OraxenBedrock-2.7.1.jar` alongside Oraxen and Geyser, then
restart the server. By default the bridge enables
`gameplay.enable-custom-content: true` in an existing Geyser config before
Geyser starts; this can be disabled with
`integration.auto-enable-geyser-custom-content`.
The declared plugin initialization order is Oraxen, OraxenBedrock, then
Geyser-Spigot. When Oraxen's Java pack already exists, the initial conversion
is completed before Geyser-Spigot enables so it can discover the mappings on
the same server start. Later Oraxen pack generations are detected from
`OraxenPackGeneratedEvent` after the archive has been fully written, with file
watching as a fallback. Geyser itself still requires a restart to load mapping
files changed while it is already running. On a completely fresh installation
where Oraxen creates its first `pack.zip` after Geyser startup, the bridge
generates the Bedrock files automatically and logs that one additional restart
is required.

Commands:

- `/oraxenbedrock generate`
- `/oraxenbedrock reload`
- `/oraxenbedrock status`

Permission: `oraxenbedrock.admin`.
