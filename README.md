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
- `plugins/Geyser-Spigot/extensions/geyserdisplayentity/Mappings/oraxen.yml`
  (created when GeyserDisplayEntity is installed)
- `plugins/OraxenBedrock/last-report.json` (conversion diagnostics)

The converter reads Oraxen YAML plus its generated `pack/pack.zip`. Wrapped
ZIP exports and Oraxen's uncompressed flat `pack/` folders are detected too.
Pack-change detection fingerprints normalized ZIP entries rather than archive
timestamps or entry order, so equivalent Oraxen repacks are ignored while an
actual changed asset still triggers conversion.
It supports regular 2D items, generated cube/cutout blocks, Oraxen note/string/
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
geometry and attachable. Every generated display-entity furniture state also
gets an exact GeyserDisplayEntity item mapping instead of only mapping the base
item. `Pack.gui_model` display-context branches are used for
the Bedrock inventory icon while the normal fallback model remains the held
3D model, which preserves the intended split for custom swords and tools.
When no dedicated GUI sprite exists, cube-based 3D furniture and items are
rendered into a 64×64 inventory thumbnail using all materials and the Java
`display.gui` transform. The inventory therefore shows the model silhouette
instead of exposing the first texture or an unrecognisable UV atlas.
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
merely copied. Current Oraxen `sounds.yml` list entries (including its
`minecraft` default namespace) and legacy `sound.yml` maps preserve their
categories and streaming flags, and items with
`Components.jukebox_playable` force the matching song into the
`record` category with streaming enabled. This lets Geyser translate custom
sound events used by music discs.

Java bitmap font providers are collected into Bedrock `font/glyph_XX.png`
pages. This covers Oraxen emoji and other BMP private-use glyphs in chat,
names, lore, scoreboards, and menus. Bedrock has no glyph page for
supplementary code points above `U+FFFF`; those are reported rather than
silently producing a broken font.

Oraxen's auto-assigned glyph sequence beginning at decimal `42000` (`U+A410`)
receives the configured emoji sizing by matching the exact codes saved in
`plugins/Oraxen/glyphs/*.yml`; unrelated Unicode scripts are left unchanged.
When Java font providers repeat a code point, the first provider wins, matching
Java's font-provider semantics.

Private-use emoji pages use a configurable 64 px minimum cell (1024×1024
page) by default. Bedrock applies one cell resolution to all 256 characters on
a glyph page, so every Oraxen emoji now fills the final shared cell even when a
128 px UI glyph on that page increases its resolution. Small 8×9 and 16×16
pixel art is enlarged with nearest-neighbour sampling and no longer becomes
half-size beside HD glyphs. Set `conversion.emoji-cell-size` to `32`, `64`, or
`128` to control minimum sharpness; it is not a second visual scale inside the
shared Bedrock cell.

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
while held or equipped. A malformed model section, element, face, UV or texture
reference is isolated to the affected item/model part and reported instead of
aborting conversion of the whole pack.

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
the vanilla component structures expected by Geyser. Explicit component
removals, including `consumable: false` and `!minecraft:...` keys, use Geyser's
removal syntax so inherited vanilla behavior is not left active. Modern
custom-model-data predicates receive Java's default index, resource-location
selector values are normalized, and legacy parent/child override precedence is
preserved.

Block light emission, light dampening, hardness and friction are carried into
custom block state overrides. Mechanic names are matched case-insensitively,
and duplicate or sanitization-colliding item identifiers now fail with a clear
diagnostic instead of producing ambiguous mappings.

Java `assets/*/lang/*.json` and legacy `.lang` files are merged by locale and
written as Bedrock `texts/*.lang` plus `texts/languages.json`. Before
installation, the converter validates atlas textures, geometries, flipbooks,
sound files and duplicate identifiers; the checked-reference count and all
non-fatal approximations are written to `last-report.json`.

An input with no discoverable Java assets, a configured custom-item set that
produces no mappings, or an output containing only pack scaffolding is rejected
before installation, so a valid existing Bedrock pack is never replaced by an
empty one. The pack and both Geyser mapping files are staged and installed as
one transaction; if any commit step fails, the complete previous set is
restored.

Minecraft Java shaders and the exact placement/animation behavior of runtime
display-entity furniture have no lossless Bedrock resource-pack equivalent.
Stock Geyser does not translate Java `ItemDisplay`/`BlockDisplay` entities, so
a resource pack alone cannot show Oraxen's default `DISPLAY_ENTITY` furniture
after placement. OraxenBedrock detects the GeyserDisplayEntity extension and
atomically generates its current mapping schema at:

```text
plugins/Geyser-Spigot/extensions/geyserdisplayentity/Mappings/oraxen.yml
```

Install both the extension and its official
`GeyserDisplayEntityPack.mcpack` companion in Geyser's `packs/` directory. The
converter validates the companion UUID and reports a clear warning if either
part is missing. A full server/Geyser restart is required after installation.
If no extension is desired, set the affected Oraxen furniture to
`type: ARMOR_STAND`; the generated head-bound 3D attachable supports that
fallback. Block-backed decorations are mapped normally.

Optional native Bedrock replacements can be placed in:

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

Install `target/OraxenBedrock-2.8.0.jar` alongside Oraxen and Geyser, then
restart the server. By default the bridge enables
`gameplay.enable-custom-content: true` in an existing Geyser config before
Geyser starts; this can be disabled with
`integration.auto-enable-geyser-custom-content`.
The declared plugin initialization order is Oraxen, OraxenBedrock, then
Geyser-Spigot. When Oraxen's Java pack already exists, the initial conversion
is completed before Geyser-Spigot enables so it can discover the mappings on
the same server start. Later Oraxen pack generations trigger a readiness probe
from `OraxenPackGeneratedEvent`; conversion starts only after the ZIP is
readable and stable, with file watching as a fallback. Geyser itself still
requires a restart to load mapping
files changed while it is already running. On a completely fresh installation
where Oraxen creates its first `pack.zip` after Geyser startup, the bridge
generates the Bedrock files automatically and logs that one additional restart
is required.

Commands:

- `/oraxenbedrock generate`
- `/oraxenbedrock reload`
- `/oraxenbedrock status`

Permission: `oraxenbedrock.admin`.
