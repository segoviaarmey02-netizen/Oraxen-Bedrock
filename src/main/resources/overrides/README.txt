Put native Bedrock files in this directory to overwrite generated files.

Examples:
  textures/ui/...                         custom menu textures
  models/blocks/...geo.json               Bedrock block geometry
  sounds/...                              Bedrock sound files
  manifest.json                           fully custom manifest

Optional mapping fragments:
  item-mappings.json
  block-mappings.json

Mapping fragments are merged into the generated Geyser mapping file. This is
the escape hatch for native placed-furniture entities, complex blocks and item
predicates that cannot be translated losslessly from Java resource-pack JSON.
