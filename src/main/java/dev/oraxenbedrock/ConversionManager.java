package dev.oraxenbedrock;

import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.conversion.PackConverter;
import dev.oraxenbedrock.model.ConversionResult;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ConversionManager {
    private final OraxenBedrockPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile BridgeConfig config;
    private volatile ConversionResult lastResult;
    private volatile String lastError;
    private volatile long fingerprint = Long.MIN_VALUE;

    ConversionManager(OraxenBedrockPlugin plugin, BridgeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void setConfig(BridgeConfig config) {
        this.config = config;
        this.fingerprint = Long.MIN_VALUE;
    }

    BridgeConfig config() {
        return config;
    }

    ConversionResult lastResult() {
        return lastResult;
    }

    String lastError() {
        return lastError;
    }

    boolean isRunning() {
        return running.get();
    }

    void generate(String reason, Consumer<ConversionResult> success, Consumer<String> failure) {
        if (!running.compareAndSet(false, true)) {
            if (failure != null) failure.accept("Конвертация уже выполняется.");
            return;
        }
        BridgeConfig snapshot = config;
        plugin.getLogger().info("Starting Bedrock pack generation (" + reason + ")...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ConversionResult result = new PackConverter(plugin.getDataFolder().toPath()).convert(snapshot);
                lastResult = result;
                lastError = null;
                fingerprint = calculateFingerprint(snapshot);
                plugin.getLogger().info("Bedrock pack ready: " + result.items() + " items, "
                        + result.blocks() + " blocks, " + result.warnings().size() + " warnings.");
                if (success != null) onMain(() -> success.accept(result));
            } catch (Exception ex) {
                lastError = ex.getMessage();
                plugin.getLogger().severe("Bedrock conversion failed: " + ex.getMessage());
                if (snapshot.verbose()) ex.printStackTrace();
                if (failure != null) onMain(() -> failure.accept(ex.getMessage()));
            } finally {
                running.set(false);
            }
        });
    }

    void pollForChanges() {
        if (running.get()) return;
        long current = calculateFingerprint(config);
        if (fingerprint == Long.MIN_VALUE) {
            fingerprint = current;
            return;
        }
        if (current != fingerprint) generate("Oraxen files changed", null, null);
    }

    private long calculateFingerprint(BridgeConfig current) {
        long value = 1125899906842597L;
        value = fingerprintPath(current.oraxenDirectory().resolve("items"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("sound.yml"), value);
        value = fingerprintPath(current.javaPack(), value);
        return value;
    }

    private long fingerprintPath(Path root, long seed) {
        if (!Files.exists(root)) return seed * 31;
        try {
            if (Files.isRegularFile(root)) {
                BasicFileAttributes a = Files.readAttributes(root, BasicFileAttributes.class);
                return seed * 31 + a.lastModifiedTime().toMillis() * 17 + a.size();
            }
            final long[] hash = {seed};
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    hash[0] = hash[0] * 31 + root.relativize(file).toString().hashCode();
                    hash[0] = hash[0] * 31 + attrs.lastModifiedTime().toMillis();
                    hash[0] = hash[0] * 31 + attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
            return hash[0];
        } catch (IOException ex) {
            return seed * 31 + 1;
        }
    }

    private void onMain(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
