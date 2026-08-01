package dev.oraxenbedrock.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class PackSource implements AutoCloseable {
    public record AssetFile(String namespace, String relative, Path path) {}

    private final Path root;
    private final FileSystem zipFileSystem;
    private final JavaPackMetadata metadata;
    private final List<Path> assetRoots;
    private final Path fallbackPackDirectory;
    private final Set<String> fallbackAssets = new LinkedHashSet<>();
    private final Map<String, Optional<Path>> textureCache = new HashMap<>();
    private final Map<String, Map<String, Optional<Path>>> basenameIndexes =
            new HashMap<>();
    private List<AssetFile> effectiveAssetFiles;

    private PackSource(Path root, FileSystem zipFileSystem,
                       Path fallbackPackDirectory) throws IOException {
        this.root = root;
        this.zipFileSystem = zipFileSystem;
        this.fallbackPackDirectory = fallbackPackDirectory == null ? null
                : fallbackPackDirectory.toAbsolutePath().normalize();
        this.metadata = JavaPackMetadata.read(root);
        List<Path> roots = new java.util.ArrayList<>();
        Path baseAssets = root.resolve("assets");
        if (Files.isDirectory(baseAssets)) roots.add(baseAssets);
        for (JavaPackMetadata.Overlay overlay : metadata.activeOverlays()) {
            String directory = overlay.directory().replace('\\', '/');
            if (directory.startsWith("/") || java.util.Arrays.asList(directory.split("/"))
                    .contains("..")) continue;
            Path assets = root.resolve(directory).resolve("assets").normalize();
            if (assets.startsWith(root) && Files.isDirectory(assets)) roots.add(assets);
        }
        this.assetRoots = List.copyOf(roots);
    }

    public static PackSource open(Path configured) throws IOException {
        return open(configured, null);
    }

    public static PackSource open(Path configured, Path fallbackPackDirectory)
            throws IOException {
        if (Files.isDirectory(configured))
            return new PackSource(configured, null, fallbackPackDirectory);
        if (Files.isRegularFile(configured)) {
            FileSystem fs = FileSystems.newFileSystem(configured, Collections.emptyMap());
            try {
                return new PackSource(fs.getPath("/"), fs,
                        fallbackPackDirectory);
            } catch (IOException | RuntimeException exception) {
                try {
                    fs.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }
        throw new NoSuchFileException("Java resource pack not found: " + configured);
    }

    public Path root() {
        return root;
    }

    public JavaPackMetadata metadata() {
        return metadata;
    }

    /** Base assets first, followed by overlays in declaration order. */
    public List<Path> assetRoots() {
        return assetRoots;
    }

    /** Finds an asset with the last declared overlay taking precedence. */
    public Path findAsset(String namespace, String relative) {
        if (!validNamespace(namespace) || unsafeRelative(relative)) return null;
        for (int i = assetRoots.size() - 1; i >= 0; i--) {
            Path namespaceRoot = assetRoots.get(i).resolve(namespace).normalize();
            Path candidate = namespaceRoot.resolve(relative).normalize();
            if (!candidate.startsWith(namespaceRoot)) continue;
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return findFallbackAsset(namespace, relative);
    }

    public int fallbackAssetCount() {
        return fallbackAssets.size();
    }

    private Path findFallbackAsset(String namespace, String relative) {
        if (fallbackPackDirectory == null
                || !Files.isDirectory(fallbackPackDirectory)) return null;
        List<Path> candidates = new ArrayList<>();
        Path namespacedRoot = fallbackPackDirectory.resolve("assets")
                .resolve(namespace).normalize();
        candidates.add(namespacedRoot.resolve(relative).normalize());
        // Oraxen treats pack/models, pack/textures, pack/sounds and pack/font
        // as the flat source form of assets/oraxen/* in the generated ZIP.
        if (namespace.equals("oraxen"))
            candidates.add(fallbackPackDirectory.resolve(relative).normalize());
        for (Path candidate : candidates) {
            boolean insideNamespaced = candidate.startsWith(namespacedRoot);
            boolean insideFlat = namespace.equals("oraxen")
                    && candidate.startsWith(fallbackPackDirectory);
            if (!(insideNamespaced || insideFlat)
                    || !Files.isRegularFile(candidate)) continue;
            fallbackAssets.add(namespace + ':' + relative);
            return candidate;
        }
        return null;
    }

    /**
     * Returns the effective asset view after applying active overlays.
     * A later overlay replaces a base file at the same namespace and path;
     * files that exist only in the base pack remain visible.
     */
    public List<AssetFile> effectiveAssetFiles() throws IOException {
        if (effectiveAssetFiles != null) return effectiveAssetFiles;
        Map<String, AssetFile> effective = new LinkedHashMap<>();
        for (Path assetRoot : assetRoots) {
            if (!Files.isDirectory(assetRoot)) continue;
            try (Stream<Path> namespaces = Files.list(assetRoot)) {
                for (Path namespaceRoot : namespaces.filter(Files::isDirectory)
                        .sorted().toList()) {
                    String namespace = namespaceRoot.getFileName().toString();
                    if (!validNamespace(namespace)) continue;
                    try (Stream<Path> paths = Files.walk(namespaceRoot)) {
                        for (Path file : paths.filter(Files::isRegularFile)
                                .sorted().toList()) {
                            String relative = namespaceRoot.relativize(file).toString()
                                    .replace('\\', '/');
                            effective.put(namespace + '\0' + relative,
                                    new AssetFile(namespace, relative, file));
                        }
                    }
                }
            }
        }
        effectiveAssetFiles = List.copyOf(effective.values());
        return effectiveAssetFiles;
    }

    public Path findTexture(String reference) throws IOException {
        return findTexture(reference, "minecraft");
    }

    public Path findTexture(String reference, String defaultNamespace) throws IOException {
        String cacheKey = String.valueOf(defaultNamespace) + '\0'
                + String.valueOf(reference);
        Optional<Path> cached = textureCache.get(cacheKey);
        if (cached != null) return cached.orElse(null);
        Path result = findTextureUncached(reference, defaultNamespace);
        textureCache.put(cacheKey, Optional.ofNullable(result));
        return result;
    }

    private Path findTextureUncached(String reference, String defaultNamespace)
            throws IOException {
        if (reference == null || reference.isBlank()) return null;
        String normalized = reference.replace('\\', '/').replaceFirst("(?i)\\.png$", "");
        String namespace = defaultNamespace;
        String name = normalized;
        if (normalized.startsWith("assets/")) {
            String asset = normalized.substring("assets/".length());
            int slash = asset.indexOf('/');
            if (slash < 1 || slash == asset.length() - 1) return null;
            namespace = asset.substring(0, slash);
            name = asset.substring(slash + 1);
        } else {
            int colon = normalized.indexOf(':');
            if (colon >= 0) {
                namespace = normalized.substring(0, colon);
                name = normalized.substring(colon + 1);
            }
        }
        namespace = namespace.toLowerCase(Locale.ROOT);
        if (!validNamespace(namespace) || unsafeRelative(name)) return null;
        name = name.replaceFirst("^textures/", "");
        for (String prefix : List.of("", "item/", "items/", "block/", "blocks/", "gui/")) {
            Path exact = findAsset(namespace, "textures/" + prefix + name + ".png");
            if (exact != null) return exact;
        }
        Path namePath;
        try {
            namePath = Path.of(name);
        } catch (InvalidPathException exception) {
            return null;
        }
        String fileName = namePath.getFileName() + ".png";
        return uniqueEffectiveBasename(namespace, fileName);
    }

    /**
     * Finds a basename only when it identifies one effective resource.
     * Resources at the same relative path are replaced by later overlays;
     * different paths with the same basename remain ambiguous.
     */
    private Path uniqueEffectiveBasename(String namespace, String fileName)
            throws IOException {
        Map<String, Optional<Path>> index = basenameIndexes.get(namespace);
        if (index == null) {
            index = buildBasenameIndex(namespace);
            basenameIndexes.put(namespace, index);
        }
        return index.getOrDefault(
                fileName.toLowerCase(Locale.ROOT), Optional.empty()).orElse(null);
    }

    private Map<String, Optional<Path>> buildBasenameIndex(String namespace)
            throws IOException {
        Map<String, List<Path>> effective = new LinkedHashMap<>();
        for (Path assetRoot : assetRoots) {
            Path namespaceRoot = assetRoot.resolve(namespace).normalize();
            if (!namespaceRoot.startsWith(assetRoot) || !Files.isDirectory(namespaceRoot))
                continue;

            Map<String, List<Path>> layer = new LinkedHashMap<>();
            try (Stream<Path> paths = Files.walk(namespaceRoot)) {
                for (Path candidate : paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".png"))
                        .sorted()
                        .toList()) {
                    String relative = namespaceRoot.relativize(candidate).toString()
                            .replace('\\', '/').toLowerCase(Locale.ROOT);
                    layer.computeIfAbsent(relative, ignored -> new ArrayList<>())
                            .add(candidate);
                }
            }
            // Applying one overlay replaces the previous resource at the same
            // logical path, including any case-colliding invalid candidates.
            layer.forEach(effective::put);
        }

        Map<String, List<Path>> byBasename = new LinkedHashMap<>();
        for (List<Path> candidates : effective.values()) {
            for (Path candidate : candidates)
                byBasename.computeIfAbsent(
                        candidate.getFileName().toString().toLowerCase(Locale.ROOT),
                        ignored -> new ArrayList<>()).add(candidate);
        }
        Map<String, Optional<Path>> result = new HashMap<>();
        byBasename.forEach((name, candidates) -> result.put(name,
                candidates.size() == 1
                        ? Optional.of(candidates.get(0)) : Optional.empty()));
        return result;
    }

    public Stream<Path> files() throws IOException {
        return Files.walk(root).filter(Files::isRegularFile);
    }

    @Override
    public void close() throws IOException {
        if (zipFileSystem != null) zipFileSystem.close();
    }

    private static boolean validNamespace(String namespace) {
        return namespace != null && namespace.matches("[a-z0-9_.-]+");
    }

    private static boolean unsafeRelative(String value) {
        if (value == null || value.isBlank() || value.startsWith("/")) return true;
        return java.util.Arrays.stream(value.split("/")).anyMatch(".."::equals);
    }
}
