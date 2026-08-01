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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class OraxenBedrockPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final String PREFIX = ChatColor.AQUA + "[OraxenBedrock] " + ChatColor.RESET;
    private ConversionManager manager;
    private BukkitTask watcher;
    private BukkitTask startupProbe;
    private BukkitTask oraxenPackTask;
    private Listener oraxenPackListener;

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
            if (manager.config().javaPack().toFile().isFile()) {
                // This plugin is declared loadbefore Geyser-Spigot. Complete
                // the initial installation before onEnable returns so Geyser
                // can discover the generated mappings during its own startup.
                manager.generateNow("server startup");
            } else {
                getLogger().warning("Initial conversion is waiting for Oraxen to finish its Java pack: "
                        + manager.config().javaPack());
                scheduleStartupProbe();
            }
        }
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
                this, manager::pollForChanges, period, period);
    }

    private void scheduleStartupProbe() {
        if (startupProbe != null) startupProbe.cancel();
        final int[] attempts = {0};
        startupProbe = getServer().getScheduler().runTaskTimer(this, () -> {
            Path pack = manager.config().javaPack();
            attempts[0]++;
            try {
                if (Files.isRegularFile(pack) && Files.size(pack) > 0) {
                    startupProbe.cancel();
                    startupProbe = null;
                    manager.generate("Oraxen pack became ready", null, null);
                    getLogger().warning("The first Bedrock pack was generated after Geyser startup; "
                            + "restart the server once so Geyser loads the new mappings.");
                    return;
                }
            } catch (IOException ignored) {
                // The next probe retries while Oraxen replaces pack.zip.
            }
            if (attempts[0] >= 600) {
                startupProbe.cancel();
                startupProbe = null;
                getLogger().severe("Oraxen did not create its Java pack within 10 minutes: " + pack);
            }
        }, 20L, 20L);
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
        // Oraxen fires its event immediately before it writes pack.zip. Waiting
        // one tick guarantees that the converter opens the completed archive.
        oraxenPackTask = getServer().getScheduler().runTaskLater(this, () -> {
            oraxenPackTask = null;
            boolean firstPackWasPending = startupProbe != null;
            if (startupProbe != null) {
                startupProbe.cancel();
                startupProbe = null;
            }
            manager.generate("Oraxen pack generated", null, null);
            if (firstPackWasPending)
                getLogger().warning("The first Bedrock pack was generated after Geyser startup; "
                        + "restart the server once so Geyser loads the new mappings.");
        }, 1L);
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
