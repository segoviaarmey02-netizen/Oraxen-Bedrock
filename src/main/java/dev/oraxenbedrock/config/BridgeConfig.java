package dev.oraxenbedrock.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record BridgeConfig(
        Path serverRoot,
        Path oraxenDirectory,
        Path geyserDirectory,
        Path javaPack,
        String packName,
        String packDescription,
        String namespace,
        int[] packVersion,
        boolean convertItems,
        boolean convertBlocks,
        boolean copySounds,
        boolean copyUi,
        boolean convertGlyphs,
        boolean convertLanguages,
        boolean applyOverrides,
        boolean generateOnStartup,
        boolean watch,
        long debounceTicks,
        boolean verbose
) {
    public static BridgeConfig load(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().getParent().getParent().normalize();
        Path oraxen = resolve(root, c.getString("paths.oraxen-directory", "plugins/Oraxen"));
        String configuredGeyser = c.getString("paths.geyser-directory", "").trim();
        Path geyser = configuredGeyser.isEmpty() ? detectGeyser(root) : resolve(root, configuredGeyser);
        Path javaPack = resolve(root, c.getString("paths.java-pack", "plugins/Oraxen/pack/pack.zip"));
        List<Integer> version = c.getIntegerList("pack.version");
        int[] semver = version.size() == 3
                ? new int[]{version.get(0), version.get(1), version.get(2)}
                : new int[]{1, 0, 0};

        return new BridgeConfig(
                root, oraxen, geyser, javaPack,
                c.getString("pack.name", "Oraxen Bedrock"),
                c.getString("pack.description", "Automatically converted Oraxen resources"),
                sanitizeNamespace(c.getString("pack.namespace", "oraxen")),
                semver,
                c.getBoolean("conversion.items", true),
                c.getBoolean("conversion.blocks", true),
                c.getBoolean("conversion.copy-sounds", true),
                c.getBoolean("conversion.copy-ui-textures", true),
                c.getBoolean("conversion.glyphs-and-emojis", true),
                c.getBoolean("conversion.localizations", true),
                c.getBoolean("conversion.apply-overrides", true),
                c.getBoolean("auto-generate.on-startup", true),
                c.getBoolean("auto-generate.watch-files", true),
                Math.max(2, c.getLong("auto-generate.debounce-seconds", 5)) * 20L,
                c.getBoolean("logging.verbose", false)
        );
    }

    private static Path resolve(Path root, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path)).normalize();
    }

    private static Path detectGeyser(Path root) {
        for (String candidate : List.of(
                "plugins/Geyser-Spigot", "plugins/Geyser-Paper", "plugins/Geyser",
                "config/Geyser-Spigot")) {
            Path path = root.resolve(candidate);
            if (path.toFile().isDirectory()) return path.normalize();
        }
        return root.resolve("plugins/Geyser-Spigot").normalize();
    }

    private static String sanitizeNamespace(String value) {
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return clean.isBlank() || clean.equals("minecraft") ? "oraxen" : clean;
    }
}
