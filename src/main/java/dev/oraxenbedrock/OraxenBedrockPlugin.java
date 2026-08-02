package dev.oraxenbedrock;

import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.config.GeyserConfigPatcher;
import dev.oraxenbedrock.model.ConversionResult;
import dev.oraxenbedrock.util.MinecraftVersion;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class OraxenBedrockPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final String PREFIX = ChatColor.AQUA + "[OraxenBedrock] " + ChatColor.RESET;
    private ConversionManager manager;
    private BukkitTask watcher;
    private BukkitTask startupProbe;
    private BukkitTask oraxenPackTask;
    private Listener oraxenPackListener;
    private boolean initialPackPending;
    private volatile long[] lastConvertedPackStamp = {-1, -1};

    @Override
    public void onEnable() {
        MinecraftVersion serverVersion =
                MinecraftVersion.parse(getServer().getMinecraftVersion());
        if (!serverVersion.supported()) {
            getLogger().severe("Minecraft " + serverVersion
                    + " is not supported. Minimum version is 1.20.5.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        saveBundledOverrideReadme();
        manager = new ConversionManager(this, BridgeConfig.load(this));
        ensureGeyserCustomContent();
        getLogger().info("Compatibility mode: Minecraft " + serverVersion
                + " (supported range 1.20.5+)");

        PluginCommand command = getCommand("oraxenbedrock");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        registerOraxenPackListener();
        scheduleWatcher();
        validateEnvironment();
        if (manager.config().generateOnStartup()) {
            if (manager.inputUnchangedSinceLastConversion()
                    && hasInstalledBedrockOutput(manager.config())) {
                // Oraxen rewrites pack.zip on every startup even when its
                // content is unchanged. Geyser re-reads the Bedrock pack and
                // the mappings only during its own onEnable, so the previous
                // session's output is loaded as-is and a fresh conversion
                // would only force an unnecessary restart.
                getLogger().info("Oraxen content is unchanged since the last conversion; "
                        + "Geyser will load the existing Bedrock pack.");
            } else {
                triggerOraxenPackGeneration();
                scheduleStartupGeneration();
            }
        }
    }

    static boolean hasInstalledBedrockOutput(BridgeConfig config) {
        Path geyser = config.geyserDirectory();
        return Files.isRegularFile(geyser.resolve("packs/OraxenBedrock.mcpack"))
                && Files.isRegularFile(
                geyser.resolve("custom_mappings/oraxen-items.json"))
                && Files.isRegularFile(
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
    }

    @Override
    public void onDisable() {
        if (watcher != null) watcher.cancel();
        if (startupProbe != null) startupProbe.cancel();
        if (oraxenPackTask != null) oraxenPackTask.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("oraxenbedrock.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "generate", "convert" -> {
                sender.sendMessage(PREFIX + "Conversion started...");
                manager.generate("command by " + sender.getName(),
                        result -> sendResult(sender, result),
                        error -> sender.sendMessage(PREFIX + ChatColor.RED + "Error: " + error));
            }
            case "reload" -> {
                reloadConfig();
                manager.setConfig(BridgeConfig.load(this));
                ensureGeyserCustomContent();
                scheduleWatcher();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
            }
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage(PREFIX + "Usage: /" + label + " <generate|reload|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("generate", "reload", "status").stream()
                .filter(value -> value.startsWith(prefix)).toList();
    }

    private void sendStatus(CommandSender sender) {
        if (manager.isRunning()) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Conversion is in progress.");
            return;
        }
        ConversionResult result = manager.lastResult();
        if (result == null) {
            String error = manager.lastError();
            sender.sendMessage(PREFIX + (error == null
                    ? "The pack has not been generated yet."
                    : ChatColor.RED + "Last error: " + error));
            return;
        }
        String time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(result.finishedAt());
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Last build: " + time);
        sender.sendMessage(ChatColor.GRAY + "Items: " + result.items()
                + ", blocks: " + result.blocks() + ", textures: " + result.textures()
                + ", warnings: " + result.warnings().size());
        sender.sendMessage(ChatColor.GRAY + "Pack: " + result.pack());
    }

    private void sendResult(CommandSender sender, ConversionResult result) {
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Done: " + result.items()
                + " items, " + result.blocks() + " blocks.");
        if (!result.warnings().isEmpty())
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Warnings: "
                    + result.warnings().size() + " (see plugins/OraxenBedrock/last-report.json)");
        sender.sendMessage(PREFIX + ChatColor.YELLOW
                + "Restart Geyser or the server after changing Geyser mappings.");
    }

    private void scheduleWatcher() {
        if (watcher != null) watcher.cancel();
        if (!manager.config().watch()) return;
        long period = manager.config().debounceTicks();
        watcher = getServer().getScheduler().runTaskTimerAsynchronously(
                this, () -> {
                    if (!manager.pollForChanges()) return;
                    getServer().getScheduler().runTask(this, () -> {
                        if (startupProbe != null || oraxenPackTask != null
                                || manager.isRunning()) return;
                        if (!triggerOraxenPackGeneration()) {
                            if (oraxenPackListener == null) {
                                // Legacy Oraxen regenerates pack.zip
                                // synchronously on its own reload, so the
                                // archive on disk is current; convert it.
                                manager.generate("Oraxen files changed", null, null);
                            } else {
                                getLogger().warning("Oraxen source files changed, but this Oraxen "
                                        + "version could not be asked to regenerate pack.zip. "
                                        + "Reload Oraxen; its pack-generated event will trigger conversion.");
                            }
                            return;
                        }
                        schedulePackConversion("Oraxen files changed", false);
                    });
                }, period, period);
    }

    private boolean triggerOraxenPackGeneration() {
        Plugin oraxen = getServer().getPluginManager().getPlugin("Oraxen");
        if (oraxen == null) return false;
        try {
            Class<?> pluginClass = Class.forName(
                    "io.th0rgal.oraxen.OraxenPlugin", false,
                    oraxen.getClass().getClassLoader());
            Method get = pluginClass.getMethod("get");
            Object pluginInstance = get.invoke(null);
            if (pluginInstance == null) return false;
            Method getResourcePack = pluginClass.getMethod("getResourcePack");
            Object resourcePack = getResourcePack.invoke(pluginInstance);
            if (resourcePack == null) return false;
            Method generate = resourcePack.getClass().getMethod("generate");
            generate.invoke(resourcePack);
            getLogger().info("Requested a fresh Oraxen Java pack through the Oraxen API.");
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            getLogger().warning("This Oraxen version does not expose the pack generation API; "
                    + "waiting for the existing pack write instead.");
            return false;
        }
    }

    private void scheduleStartupGeneration() {
        schedulePackConversion("Oraxen pack write completed", true);
    }

    private void schedulePackConversion(String reason, boolean initial) {
        if (startupProbe != null) startupProbe.cancel();
        initialPackPending = initial;
        Path pack = manager.config().javaPack();
        // Oraxen regenerates pack.zip asynchronously after its own onEnable.
        // The archive left over from the previous run is not a fresh input:
        // converting it would ship a Bedrock pack built from stale Oraxen
        // content. Wait until Oraxen rewrites the archive (observed as a
        // size/timestamp change), or reports completion through the
        // OraxenPackGeneratedEvent hook, before the initial conversion.
        long[] baseline = fileStamp(pack);
        long[] observed = {-1, -1};
        int[] stableChecks = {0};
        int[] attempts = {0};
        // Without the event hook Oraxen generated synchronously inside its
        // own onEnable, so the pack already on disk is current; with the hook
        // allow a longer window for the asynchronous rewrite to start.
        int grace = oraxenPackListener == null ? 30 : 150;
        getLogger().warning((initial ? "Initial conversion is waiting"
                : "Conversion is waiting")
                + " for Oraxen to finish its Java pack: " + pack);
        startupProbe = getServer().getScheduler().runTaskTimer(this, () -> {
            attempts[0]++;
            long[] current = fileStamp(pack);
            boolean exists = current[0] > 0;
            if (!exists) {
                if (attempts[0] >= 600) {
                    startupProbe.cancel();
                    startupProbe = null;
                    getLogger().severe("Oraxen did not create its Java pack within 10 minutes: "
                            + pack);
                }
                return;
            }
            boolean fresh = baseline[0] <= 0
                    || current[0] != baseline[0] || current[1] != baseline[1];
            if (fresh) {
                boolean stable = current[0] == observed[0]
                        && current[1] == observed[1];
                stableChecks[0] = stable ? stableChecks[0] + 1 : 0;
                observed[0] = current[0];
                observed[1] = current[1];
                if (stableChecks[0] >= 1) {
                    startupProbe.cancel();
                    startupProbe = null;
                    initialPackPending = false;
                    lastConvertedPackStamp = observed[0] == -1
                            ? fileStamp(pack) : observed;
                    manager.generate(reason, null, null);
                    logGeyserRestartRequired(initial);
                }
                return;
            }
            // The pack exists but Oraxen has not rewritten it since this
            // plugin enabled. Only Oraxen versions without the pack-generated
            // event generate synchronously inside their own onEnable, so the
            // archive on disk is already current there; modern Oraxen always
            // reports its asynchronous rewrite through the event hook, which
            // cancels this probe. Converting an unchanged archive on modern
            // Oraxen would ship stale content.
            if (attempts[0] >= grace && oraxenPackListener == null) {
                startupProbe.cancel();
                startupProbe = null;
                initialPackPending = false;
                lastConvertedPackStamp = fileStamp(pack);
                manager.generate(reason, null, null);
                logGeyserRestartRequired(initial);
            }
        }, 2L, 2L);
    }

    private void logGeyserRestartRequired(boolean initial) {
        if (initial)
            getLogger().warning("The first Bedrock pack was generated after Geyser startup; "
                    + "restart the server once so Geyser loads the new mappings.");
        else
            getLogger().warning("The Bedrock pack changed after Geyser startup; restart Geyser "
                    + "or the server so it reloads the pack and mappings.");
    }

    private long[] fileStamp(Path pack) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(pack, BasicFileAttributes.class);
            return new long[]{attributes.size(),
                    attributes.lastModifiedTime().toMillis()};
        } catch (IOException ignored) {
            return new long[]{-1, -1};
        }
    }

    @SuppressWarnings("unchecked")
    private void registerOraxenPackListener() {
        Plugin oraxen = getServer().getPluginManager().getPlugin("Oraxen");
        if (oraxen == null) return;
        try {
            Class<?> raw = Class.forName(
                    "io.th0rgal.oraxen.api.events.OraxenPackGeneratedEvent",
                    false, oraxen.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) return;
            Class<? extends Event> eventType =
                    (Class<? extends Event>) raw.asSubclass(Event.class);
            oraxenPackListener = new Listener() {};
            getServer().getPluginManager().registerEvent(
                    eventType, oraxenPackListener, EventPriority.MONITOR,
                    (listener, event) -> scheduleAfterOraxenPackWrite(), this);
        } catch (ClassNotFoundException | LinkageError exception) {
            getLogger().warning("This Oraxen version does not expose the pack-generated event; "
                    + "file watching will be used instead.");
        }
    }

    private void scheduleAfterOraxenPackWrite() {
        if (oraxenPackTask != null) oraxenPackTask.cancel();
        boolean firstPackWasPending = startupProbe != null && initialPackPending;
        if (startupProbe != null) {
            startupProbe.cancel();
            startupProbe = null;
        }
        initialPackPending = false;
        Path pack = manager.config().javaPack();
        long[] lastSize = {-1};
        long[] lastModified = {-1};
        int[] stableChecks = {0};
        int[] attempts = {0};

        // Current Oraxen fires OraxenPackGeneratedEvent before obfuscation and
        // asynchronous ZIP writing. Wait for a readable central directory and
        // two stable observations instead of racing the writer after one tick.
        oraxenPackTask = getServer().getScheduler().runTaskTimer(this, () -> {
            attempts[0]++;
            long size = -1;
            long modified = -1;
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(pack, BasicFileAttributes.class);
                size = attributes.size();
                modified = attributes.lastModifiedTime().toMillis();
            } catch (IOException ignored) {
                // The next probe retries while Oraxen creates/replaces the ZIP.
            }
            boolean unchanged = size > 0 && size == lastSize[0]
                    && modified == lastModified[0];
            stableChecks[0] = unchanged && isJavaPackReady(pack)
                    ? stableChecks[0] + 1 : 0;
            lastSize[0] = size;
            lastModified[0] = modified;
            if (stableChecks[0] >= 1) {
                oraxenPackTask.cancel();
                oraxenPackTask = null;
                // The startup conversion may already have consumed this exact
                // archive; do not convert the same file twice.
                long[] stamp = {size, modified};
                if (stamp[0] == lastConvertedPackStamp[0]
                        && stamp[1] == lastConvertedPackStamp[1]) {
                    getLogger().info("Oraxen pack write completed; the archive was already converted.");
                    return;
                }
                // Oraxen regenerates pack.zip on every startup, but Geyser
                // loads its pack only once, so converting an archive whose
                // source content is unchanged would force an unnecessary
                // server restart.
                if (manager.inputUnchangedSinceLastConversion()) {
                    getLogger().info("Oraxen pack write completed but its content is unchanged; "
                            + "keeping the existing Bedrock pack.");
                    return;
                }
                lastConvertedPackStamp = stamp;
                manager.generate("Oraxen pack write completed", null, null);
                if (firstPackWasPending)
                    getLogger().warning("The first Bedrock pack was generated after Geyser startup; "
                            + "restart the server once so Geyser loads the new mappings.");
                return;
            }
            if (attempts[0] >= 6000) {
                oraxenPackTask.cancel();
                oraxenPackTask = null;
                getLogger().severe("Oraxen did not finish a readable Java pack within 10 minutes: "
                        + pack);
            }
        }, 2L, 2L);
    }

    static boolean isJavaPackReady(Path pack) {
        if (Files.isDirectory(pack)) {
            try (var paths = Files.walk(pack)) {
                return paths.filter(Files::isRegularFile)
                        .map(path -> pack.relativize(path).toString().replace('\\', '/'))
                        .anyMatch(OraxenBedrockPlugin::isAssetEntry);
            } catch (IOException ignored) {
                return false;
            }
        }
        if (!Files.isRegularFile(pack)) return false;
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isAssetEntry(entry.getName())) return true;
            }
            return false;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isAssetEntry(String rawName) {
        String name = rawName.replace('\\', '/').replaceFirst("^/+", "");
        if (name.startsWith("assets/") || name.contains("/assets/")) return true;
        // The uncompressed Oraxen pack directory uses the same flat folders
        // that Oraxen later places under assets/minecraft in pack.zip.
        return List.of("models/", "textures/", "lang/", "font/", "sounds/")
                .stream().anyMatch(directory -> name.startsWith(directory)
                        || name.contains("/" + directory));
    }

    private void ensureGeyserCustomContent() {
        if (!manager.config().autoEnableGeyserCustomContent()) return;
        Path geyserConfig = manager.config().geyserDirectory().resolve("config.yml");
        try {
            GeyserConfigPatcher.Result result =
                    GeyserConfigPatcher.enable(geyserConfig);
            if (result == GeyserConfigPatcher.Result.UPDATED)
                getLogger().info("Enabled gameplay.enable-custom-content in " + geyserConfig);
            else if (result == GeyserConfigPatcher.Result.CONFIG_MISSING)
                getLogger().warning("Geyser config does not exist yet; after its first start, "
                        + "make sure gameplay.enable-custom-content is true: " + geyserConfig);
        } catch (IOException exception) {
            getLogger().severe("Could not enable Geyser custom content in "
                    + geyserConfig + ": " + exception.getMessage());
        }
    }

    private void validateEnvironment() {
        BridgeConfig c = manager.config();
        if (!c.oraxenDirectory().toFile().isDirectory())
            getLogger().warning("Oraxen directory does not exist yet: " + c.oraxenDirectory());
        if (!c.javaPack().toFile().exists())
            getLogger().warning("Oraxen Java pack does not exist yet: " + c.javaPack());
        File geyser = c.geyserDirectory().toFile();
        if (!geyser.isDirectory())
            getLogger().warning("Geyser directory was not found; it will be created at " + geyser);
    }

    private void saveBundledOverrideReadme() {
        File readme = new File(getDataFolder(), "overrides/README.txt");
        if (!readme.exists()) saveResource("overrides/README.txt", false);
    }
}
