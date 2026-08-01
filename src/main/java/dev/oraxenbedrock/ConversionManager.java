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
    private final Object generationLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean rerunRequested;
    private volatile BridgeConfig config;
    private volatile ConversionResult lastResult;
    private volatile String lastError;
    private volatile long fingerprint = Long.MIN_VALUE;
    private volatile long observedFingerprint = Long.MIN_VALUE;
    private volatile int stableFingerprintObservations;
    private volatile String rerunReason;

    ConversionManager(OraxenBedrockPlugin plugin, BridgeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void setConfig(BridgeConfig config) {
        synchronized (generationLock) {
            this.config = config;
            this.fingerprint = Long.MIN_VALUE;
            this.observedFingerprint = Long.MIN_VALUE;
            this.stableFingerprintObservations = 0;
            if (running.get()) {
                this.rerunRequested = true;
                this.rerunReason = "configuration reloaded";
            } else {
                this.rerunRequested = false;
                this.rerunReason = null;
            }
        }
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
        if (!beginGeneration(reason)) {
            if (failure != null)
                failure.accept("A conversion is already in progress; another pass was queued.");
            return;
        }
        BridgeConfig snapshot = config;
        plugin.getLogger().info("Starting Bedrock pack generation (" + reason + ")...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> runGeneration(snapshot, success, failure, true));
    }

    void generateNow(String reason) {
        if (!beginGeneration(reason)) return;
        BridgeConfig snapshot = config;
        plugin.getLogger().info("Starting Bedrock pack generation (" + reason + ")...");
        runGeneration(snapshot, null, null, false);
    }

    void pollForChanges() {
        if (running.get()) return;
        long current = calculateFingerprint(config);
        if (fingerprint == Long.MIN_VALUE) {
            fingerprint = current;
            observedFingerprint = current;
            stableFingerprintObservations = 0;
            return;
        }
        if (current == fingerprint) {
            observedFingerprint = current;
            stableFingerprintObservations = 0;
            return;
        }
        if (current != observedFingerprint) {
            observedFingerprint = current;
            stableFingerprintObservations = 0;
            return;
        }
        // Require two identical observations of the changed input. This makes
        // the configured polling period a real debounce window and avoids
        // opening pack.zip while Oraxen is still replacing it.
        if (++stableFingerprintObservations >= 1) {
            stableFingerprintObservations = 0;
            generate("Oraxen files changed", null, null);
        }
    }

    private long calculateFingerprint(BridgeConfig current) {
        long value = 1125899906842597L;
        value = fingerprintPath(current.oraxenDirectory().resolve("items"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("glyphs"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("sound.yml"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("sounds.yml"), value);
        Path sourcePack = current.oraxenDirectory().resolve("pack").normalize();
        value = fingerprintPath(sourcePack, value);
        if (!current.javaPack().toAbsolutePath().normalize()
                .startsWith(sourcePack.toAbsolutePath().normalize()))
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

    private void runGeneration(BridgeConfig snapshot,
                               Consumer<ConversionResult> success,
                               Consumer<String> failure,
                               boolean dispatchCallbacks) {
        long inputFingerprint = calculateFingerprint(snapshot);
        try {
            ConversionResult result =
                    new PackConverter(plugin.getDataFolder().toPath()).convert(snapshot);
            lastResult = result;
            lastError = null;
            fingerprint = inputFingerprint;
            observedFingerprint = inputFingerprint;
            stableFingerprintObservations = 0;
            long completedFingerprint = calculateFingerprint(snapshot);
            if (completedFingerprint != inputFingerprint)
                requestRerun("Oraxen files changed during conversion");
            plugin.getLogger().info("Bedrock pack ready: " + result.items() + " items, "
                    + result.blocks() + " blocks, " + result.warnings().size() + " warnings.");
            if (success != null) dispatch(success, result, dispatchCallbacks);
        } catch (Exception exception) {
            String message = errorMessage(exception);
            lastError = message;
            plugin.getLogger().severe("Bedrock conversion failed: " + message);
            if (snapshot.verbose()) exception.printStackTrace();
            if (failure != null) dispatch(failure, message, dispatchCallbacks);
        } finally {
            finishGeneration();
        }
    }

    private void requestRerun(String reason) {
        synchronized (generationLock) {
            rerunReason = reason;
            rerunRequested = true;
        }
    }

    private boolean beginGeneration(String reason) {
        synchronized (generationLock) {
            if (running.get()) {
                rerunReason = reason;
                rerunRequested = true;
                return false;
            }
            running.set(true);
            return true;
        }
    }

    private void finishGeneration() {
        String queuedReason;
        BridgeConfig queuedConfig;
        synchronized (generationLock) {
            if (!rerunRequested) {
                running.set(false);
                return;
            }
            rerunRequested = false;
            queuedReason = rerunReason;
            rerunReason = null;
            queuedConfig = config;
            // Keep running=true so a request cannot slip between consuming the
            // queued flag and dispatching this reserved follow-up pass.
        }
        String reason = queuedReason == null
                ? "queued input change" : queuedReason;
        plugin.getLogger().info("Starting Bedrock pack generation (" + reason + ")...");
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> runGeneration(queuedConfig, null, null, true));
        } catch (RuntimeException exception) {
            synchronized (generationLock) {
                running.set(false);
            }
            plugin.getLogger().severe("Could not schedule queued Bedrock conversion: "
                    + errorMessage(exception));
        }
    }

    private <T> void dispatch(Consumer<T> consumer, T value, boolean onMainThread) {
        if (onMainThread) onMain(() -> consumer.accept(value));
        else consumer.accept(value);
    }
}
