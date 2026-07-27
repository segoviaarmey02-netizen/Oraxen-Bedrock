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

Modern Java 1.21.4+ item definitions from `assets/*/items/*.json` are resolved,
including model, composite, condition, select and range-dispatch nodes.
Conditional Java predicates have no direct Geyser mapping equivalent, so the
converter chooses their first concrete model and records that approximation in
the report. Both modern definitions and traditional `models/item` assets can
therefore drive Bedrock geometry and icons.

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

Animated Java textures with a sibling `texture.png.mcmeta` are converted too:

- block and block-backed decoration textures are registered in Bedrock
  `textures/flipbook_textures.json`;
- animated swords, tools, hats, flat items and single-texture 3D furniture are
  split into individual frames and receive a generated attachable render
  controller;
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
and the common `cube_all`, `cube_column`, `orientable`, and `cross` parents.

Equipment is pre-converted as well:

- swords, axes, pickaxes, bows, crossbows, tridents, maces, shields and tools
  receive handheld options and 3D Bedrock attachables;
- component-based helmets, chestplates, leggings and boots receive
  `equippable` mappings, armor geometry and the correct Oraxen armor layer;
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
copied to item mappings. Oraxen's advanced durability object is normalized to
the numeric maximum damage expected by Geyser.

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

Install `target/OraxenBedrock-2.5.0.jar` alongside Oraxen and Geyser, set
`gameplay.enable-custom-content: true` in Geyser, then restart the server.

Commands:

- `/oraxenbedrock generate`
- `/oraxenbedrock reload`
- `/oraxenbedrock status`

Permission: `oraxenbedrock.admin`.
