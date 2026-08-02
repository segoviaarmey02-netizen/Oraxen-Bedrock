package dev.oraxenbedrock;

import dev.oraxenbedrock.config.BridgeConfig;
import dev.oraxenbedrock.conversion.PackConverter;
import dev.oraxenbedrock.model.ConversionResult;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Comparator;
import java.util.zip.CRC32;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ConversionManager {
    private static final String INPUT_FINGERPRINT_FILE = "last-input-fingerprint";
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

    boolean pollForChanges() {
        if (running.get()) return false;
        long current = calculateFingerprint(config);
        if (fingerprint == Long.MIN_VALUE) {
            fingerprint = current;
            observedFingerprint = current;
            stableFingerprintObservations = 0;
            return false;
        }
        if (current == fingerprint) {
            observedFingerprint = current;
            stableFingerprintObservations = 0;
            return false;
        }
        if (current != observedFingerprint) {
            observedFingerprint = current;
            stableFingerprintObservations = 0;
            return false;
        }
        // Require two identical observations of the changed input. This makes
        // the configured polling period a real debounce window and avoids
        // opening pack.zip while Oraxen is still replacing it.
        if (++stableFingerprintObservations >= 1) {
            stableFingerprintObservations = 0;
            return true;
        }
        return false;
    }

    private long calculateFingerprint(BridgeConfig current) {
        return calculateFingerprint(current, plugin.getDataFolder().toPath());
    }

    static long calculateFingerprint(BridgeConfig current, Path dataDirectory) {
        long value = 1125899906842597L;
        value = value * 31 + current.oraxenDirectory().toAbsolutePath()
                .normalize().toString().hashCode();
        value = value * 31 + current.javaPack().toAbsolutePath()
                .normalize().toString().hashCode();
        value = value * 31 + current.geyserDirectory().toAbsolutePath()
                .normalize().toString().hashCode();
        value = value * 31 + current.packName().hashCode();
        value = value * 31 + current.packDescription().hashCode();
        value = value * 31 + current.namespace().hashCode();
        value = value * 31 + java.util.Arrays.hashCode(current.packVersion());
        value = value * 31 + Boolean.hashCode(current.convertItems());
        value = value * 31 + Boolean.hashCode(current.convertBlocks());
        value = value * 31 + Boolean.hashCode(current.copySounds());
        value = value * 31 + Boolean.hashCode(current.copyUi());
        value = value * 31 + Boolean.hashCode(current.convertGlyphs());
        value = value * 31 + Boolean.hashCode(current.convertLanguages());
        value = value * 31 + Boolean.hashCode(current.applyOverrides());
        value = value * 31 + current.emojiCellSize();
        value = fingerprintPath(current.oraxenDirectory().resolve("items"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("glyphs"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("sound.yml"), value);
        value = fingerprintPath(current.oraxenDirectory().resolve("sounds.yml"), value);
        Path sourcePack = current.oraxenDirectory().resolve("pack").normalize();
        // Oraxen rewrites pack.zip on every startup even when its content is
        // unchanged, so the archive itself must not count as an input change.
        value = fingerprintPath(sourcePack, value, current.javaPack());
        if (!current.javaPack().toAbsolutePath().normalize()
                .startsWith(sourcePack.toAbsolutePath().normalize()))
            value = fingerprintPath(current.javaPack(), value);
        value = fingerprintPath(dataDirectory.resolve("overrides"), value);
        return value;
    }

    boolean inputUnchangedSinceLastConversion() {
        Long saved = readInputFingerprint();
        return saved != null && saved == calculateFingerprint(config);
    }

    private Long readInputFingerprint() {
        Path file = plugin.getDataFolder().toPath().resolve(INPUT_FINGERPRINT_FILE);
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private void persistInputFingerprint(long value) {
        Path file = plugin.getDataFolder().toPath().resolve(INPUT_FINGERPRINT_FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(value));
        } catch (IOException ignored) {
            // The next successful conversion tries again.
        }
    }

    private static long fingerprintPath(Path root, long seed) {
        return fingerprintPath(root, seed, null);
    }

    private static long fingerprintPath(Path root, long seed, Path excluded) {
        if (!Files.exists(root)) return seed * 31;
        try {
            if (Files.isRegularFile(root)) {
                return fingerprintFile(root, root.getFileName().toString(), seed);
            }
            Path excludedFile = excluded == null
                    ? null : excluded.toAbsolutePath().normalize();
            long hash = seed;
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .filter(path -> excludedFile == null
                                || !path.toAbsolutePath().normalize()
                                .equals(excludedFile))
                        .sorted(Comparator.comparing(path ->
                                root.relativize(path).toString().replace('\\', '/')))
                        .toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    hash = fingerprintFile(file, relative, hash);
                }
            }
            return hash;
        } catch (IOException ex) {
            return seed * 31 + 1;
        }
    }

    private static long fingerprintFile(Path file, String relative, long seed)
            throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1)
                crc.update(buffer, 0, read);
        }
        long hash = seed * 31 + relative.hashCode();
        hash = hash * 31 + Files.size(file);
        return hash * 31 + crc.getValue();
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
            persistInputFingerprint(inputFingerprint);
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
