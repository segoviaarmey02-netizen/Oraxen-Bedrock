package dev.oraxenbedrock;

import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.conversion.PackConverter;
import dev.oraxenbedrock.model.ConversionResult;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;
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
            if (failure != null) failure.accept("A conversion is already in progress.");
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
                String message = errorMessage(ex);
                lastError = message;
                plugin.getLogger().severe("Bedrock conversion failed: " + message);
                if (snapshot.verbose()) ex.printStackTrace();
                if (failure != null) onMain(() -> failure.accept(message));
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
        value = fingerprintPath(plugin.getDataFolder().toPath().resolve("overrides"), value);
        return value;
    }

    private long fingerprintPath(Path root, long seed) {
        if (!Files.exists(root)) return seed * 31;
        try {
            if (Files.isRegularFile(root)) {
                BasicFileAttributes a = Files.readAttributes(root, BasicFileAttributes.class);
                return seed * 31 + a.lastModifiedTime().toMillis() * 17 + a.size();
            }
            long hash = seed;
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path ->
                                root.relativize(path).toString().replace('\\', '/')))
                        .toList()) {
                    BasicFileAttributes attributes =
                            Files.readAttributes(file, BasicFileAttributes.class);
                    hash = hash * 31
                            + root.relativize(file).toString().replace('\\', '/').hashCode();
                    hash = hash * 31 + attributes.lastModifiedTime().toMillis();
                    hash = hash * 31 + attributes.size();
                }
            }
            return hash;
        } catch (IOException ex) {
            return seed * 31 + 1;
        }
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private void onMain(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
