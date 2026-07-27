package dev.oraxenbedrock;

import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.model.ConversionResult;
import dev.oraxenbedrock.util.MinecraftVersion;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class OraxenBedrockPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final String PREFIX = ChatColor.AQUA + "[OraxenBedrock] " + ChatColor.RESET;
    private ConversionManager manager;
    private BukkitTask watcher;

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
        getLogger().info("Compatibility mode: Minecraft " + serverVersion
                + " (supported range 1.20.5+)");

        PluginCommand command = getCommand("oraxenbedrock");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        scheduleWatcher();
        validateEnvironment();
        if (manager.config().generateOnStartup()) {
            getServer().getScheduler().runTaskLater(this,
                    () -> manager.generate("server startup", null, null), 40L);
        }
    }

    @Override
    public void onDisable() {
        if (watcher != null) watcher.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("oraxenbedrock.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Недостаточно прав.");
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "generate", "convert" -> {
                sender.sendMessage(PREFIX + "Конвертация запущена...");
                manager.generate("command by " + sender.getName(),
                        result -> sendResult(sender, result),
                        error -> sender.sendMessage(PREFIX + ChatColor.RED + "Ошибка: " + error));
            }
            case "reload" -> {
                reloadConfig();
                manager.setConfig(BridgeConfig.load(this));
                scheduleWatcher();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Конфигурация перезагружена.");
            }
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage(PREFIX + "Использование: /" + label + " <generate|reload|status>");
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
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Конвертация выполняется.");
            return;
        }
        ConversionResult result = manager.lastResult();
        if (result == null) {
            String error = manager.lastError();
            sender.sendMessage(PREFIX + (error == null
                    ? "Пак ещё не генерировался."
                    : ChatColor.RED + "Последняя ошибка: " + error));
            return;
        }
        String time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(result.finishedAt());
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Последняя сборка: " + time);
        sender.sendMessage(ChatColor.GRAY + "Предметы: " + result.items()
                + ", блоки: " + result.blocks() + ", текстуры: " + result.textures()
                + ", предупреждения: " + result.warnings().size());
        sender.sendMessage(ChatColor.GRAY + "Пак: " + result.pack());
    }

    private void sendResult(CommandSender sender, ConversionResult result) {
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Готово: " + result.items()
                + " предметов, " + result.blocks() + " блоков.");
        if (!result.warnings().isEmpty())
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Есть предупреждения: "
                    + result.warnings().size() + " (см. plugins/OraxenBedrock/last-report.json)");
        sender.sendMessage(PREFIX + ChatColor.YELLOW
                + "После изменения Geyser mappings требуется перезапуск Geyser/сервера.");
    }

    private void scheduleWatcher() {
        if (watcher != null) watcher.cancel();
        if (!manager.config().watch()) return;
        long period = manager.config().debounceTicks();
        watcher = getServer().getScheduler().runTaskTimerAsynchronously(
                this, manager::pollForChanges, period, period);
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
