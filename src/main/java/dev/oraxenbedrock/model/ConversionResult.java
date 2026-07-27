package dev.oraxenbedrock.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ConversionResult(
        Instant finishedAt,
        int items,
        int blocks,
        int textures,
        Path pack,
        Path mappings,
        List<String> warnings
) {
}
