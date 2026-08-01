package dev.oraxenbedrock.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class PackSource implements AutoCloseable {
    public record AssetFile(String namespace, String relative, Path path,
                            boolean fallback) {
        public AssetFile(String namespace, String relative, Path path) {
            this(namespace, relative, path, false);
        }
    }

    private static final List<String> FLAT_ASSET_DIRECTORIES = List.of(
            "models", "textures", "lang", "font", "sounds");

    private final Path root;
    private final FileSystem zipFileSystem;
    private final JavaPackMetadata metadata;
    private final List<Path> primaryAssetRoots;
    private final List<Path> fallbackAssetRoots;
    private final List<Path> assetRoots;
    private final Path fallbackPackDirectory;
    private final Set<Path> fallbackAssets = new LinkedHashSet<>();
    private final Map<String, Optional<Path>> textureCache = new HashMap<>();
    private final Map<String, Map<String, Optional<Path>>> basenameIndexes =
            new HashMap<>();
    private List<AssetFile> effectiveAssetFiles;

    private PackSource(Path configuredRoot, FileSystem zipFileSystem,
                       Path fallbackPackDirectory) throws IOException {
        this.zipFileSystem = zipFileSystem;
        this.root = discoverPackRoot(configuredRoot);
        this.fallbackPackDirectory = fallbackPackDirectory == null ? null
                : discoverPackRoot(fallbackPackDirectory.toAbsolutePath().normalize());

        JavaPackMetadata primaryMetadata = JavaPackMetadata.read(root);
        JavaPackMetadata fallbackMetadata = this.fallbackPackDirectory == null
                ? new JavaPackMetadata(null, null, null, List.of())
                : JavaPackMetadata.read(this.fallbackPackDirectory);
        this.metadata = hasMetadata(primaryMetadata)
                ? primaryMetadata : fallbackMetadata;
        this.primaryAssetRoots = assetRoots(root, primaryMetadata);
        this.fallbackAssetRoots = this.fallbackPackDirectory == null
                || samePath(root, this.fallbackPackDirectory)
                ? List.of()
                : assetRoots(this.fallbackPackDirectory, fallbackMetadata);
        List<Path> roots = new ArrayList<>(fallbackAssetRoots);
        roots.addAll(primaryAssetRoots);
        this.assetRoots = List.copyOf(roots);
    }

    private static List<Path> assetRoots(Path packRoot,
                                         JavaPackMetadata metadata) {
        List<Path> roots = new ArrayList<>();
        Path baseAssets = packRoot.resolve("assets");
        if (Files.isDirectory(baseAssets)) roots.add(baseAssets);
        for (JavaPackMetadata.Overlay overlay : metadata.activeOverlays()) {
            String directory = overlay.directory().replace('\\', '/');
            if (directory.startsWith("/") || java.util.Arrays.asList(directory.split("/"))
                    .contains("..")) continue;
            Path assets = packRoot.resolve(directory).resolve("assets").normalize();
            if (assets.startsWith(packRoot) && Files.isDirectory(assets)) roots.add(assets);
        }
        return List.copyOf(roots);
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

    public Path findPackFile(String relative) {
        if (unsafeRelative(relative)) return null;
        Path primary = root.resolve(relative).normalize();
        if (primary.startsWith(root) && Files.isRegularFile(primary)) return primary;
        if (fallbackPackDirectory == null) return null;
        Path fallback = fallbackPackDirectory.resolve(relative).normalize();
        if (!fallback.startsWith(fallbackPackDirectory)
                || !Files.isRegularFile(fallback)) return null;
        fallbackAssets.add(normalizedPath(fallback));
        return fallback;
    }

    /** Base assets first, followed by overlays in declaration order. */
    public List<Path> assetRoots() {
        return assetRoots;
    }

    /** Finds an asset with the last declared overlay taking precedence. */
    public Path findAsset(String namespace, String relative) {
        if (!validNamespace(namespace) || unsafeRelative(relative)) return null;
        Path primary = findInAssetRoots(primaryAssetRoots, namespace, relative);
        if (primary != null) return primary;
        primary = findFlatAsset(root, namespace, relative);
        if (primary != null) return primary;
        Path fallback = findInAssetRoots(fallbackAssetRoots, namespace, relative);
        if (fallback != null) {
            fallbackAssets.add(normalizedPath(fallback));
            return fallback;
        }
        return findFallbackAsset(namespace, relative);
    }

    private Path findInAssetRoots(List<Path> roots, String namespace,
                                  String relative) {
        for (int i = roots.size() - 1; i >= 0; i--) {
            Path namespaceRoot = roots.get(i).resolve(namespace).normalize();
            Path candidate = namespaceRoot.resolve(relative).normalize();
            if (!candidate.startsWith(namespaceRoot)) continue;
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    public int fallbackAssetCount() {
        return fallbackAssets.size();
    }

    private Path findFallbackAsset(String namespace, String relative) {
        if (fallbackPackDirectory == null
                || !Files.isDirectory(fallbackPackDirectory)) return null;
        // Current Oraxen maps its flat pack/models, pack/textures, pack/font,
        // pack/lang and pack/sounds folders into assets/minecraft. Older
        // configurations commonly referenced the same files as oraxen assets,
        // so both namespaces are accepted for exact fallback lookups.
        Path candidate = findFlatAsset(
                fallbackPackDirectory, namespace, relative);
        if (candidate != null) {
            fallbackAssets.add(normalizedPath(candidate));
            return candidate;
        }
        return null;
    }

    private Path findFlatAsset(Path packRoot, String namespace,
                               String relative) {
        if (!(namespace.equals("minecraft") || namespace.equals("oraxen")))
            return null;
        Path candidate = packRoot.resolve(relative).normalize();
        if (!candidate.startsWith(packRoot) || !Files.isRegularFile(candidate))
            return null;
        String first = relative.replace('\\', '/').split("/", 2)[0];
        return FLAT_ASSET_DIRECTORIES.contains(first) ? candidate : null;
    }

    /**
     * Returns the effective asset view after applying active overlays.
     * A later overlay replaces a base file at the same namespace and path;
     * files that exist only in the base pack remain visible.
     */
    public List<AssetFile> effectiveAssetFiles() throws IOException {
        if (effectiveAssetFiles != null) return effectiveAssetFiles;
        Map<String, AssetFile> effective = new LinkedHashMap<>();
        if (fallbackPackDirectory != null
                && !samePath(root, fallbackPackDirectory))
            collectFlatAssets(fallbackPackDirectory, effective, true);
        for (Path assetRoot : fallbackAssetRoots)
            collectAssetRoot(assetRoot, effective, true);
        collectFlatAssets(root, effective, false);
        for (Path assetRoot : primaryAssetRoots)
            collectAssetRoot(assetRoot, effective, false);
        effective.values().stream().filter(AssetFile::fallback)
                .map(AssetFile::path).map(PackSource::normalizedPath)
                .forEach(fallbackAssets::add);
        effectiveAssetFiles = List.copyOf(effective.values());
        return effectiveAssetFiles;
    }

    private void collectFlatAssets(Path packRoot,
            Map<String, AssetFile> effective, boolean fallback)
            throws IOException {
        if (!Files.isDirectory(packRoot)) return;
        for (String directory : FLAT_ASSET_DIRECTORIES) {
            Path flatRoot = packRoot.resolve(directory);
            if (!Files.isDirectory(flatRoot)) continue;
            try (Stream<Path> paths = Files.walk(flatRoot)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .sorted().toList()) {
                    String relative = directory + "/" + flatRoot.relativize(file)
                            .toString().replace('\\', '/');
                    String key = "minecraft\0" + relative;
                    effective.put(key,
                            new AssetFile("minecraft", relative, file, fallback));
                }
            }
        }
    }

    private void collectAssetRoot(Path assetRoot,
                                  Map<String, AssetFile> effective,
                                  boolean fallback) throws IOException {
        if (!Files.isDirectory(assetRoot)) return;
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
                        String key = namespace + '\0' + relative;
                        effective.put(key,
                                new AssetFile(namespace, relative, file, fallback));
                    }
                }
            }
        }
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
        Map<String, List<Path>> byBasename = new LinkedHashMap<>();
        for (AssetFile asset : effectiveAssetFiles()) {
            if (!asset.namespace().equals(namespace)
                    || !asset.path().getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".png")) continue;
            byBasename.computeIfAbsent(
                    asset.path().getFileName().toString().toLowerCase(Locale.ROOT),
                    ignored -> new ArrayList<>()).add(asset.path());
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

    private static boolean hasMetadata(JavaPackMetadata metadata) {
        return metadata.packFormat() != null || metadata.minFormat() != null
                || metadata.maxFormat() != null || !metadata.overlays().isEmpty();
    }

    private static boolean samePath(Path left, Path right) {
        return left.toAbsolutePath().normalize()
                .equals(right.toAbsolutePath().normalize());
    }

    private static Path normalizedPath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * ZIP tools often wrap a resource pack in one top-level directory. Locate
     * that sole pack root instead of silently treating the archive as empty.
     */
    private static Path discoverPackRoot(Path startingRoot) throws IOException {
        Path normalized = startingRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || looksLikePackRoot(normalized))
            return normalized;

        List<Path> metadataRoots;
        try (Stream<Path> paths = Files.walk(normalized)) {
            metadataRoots = paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(normalized))
                    .filter(path -> Files.isRegularFile(path.resolve("pack.mcmeta")))
                    .sorted(Comparator.comparingInt(path ->
                            normalized.relativize(path).getNameCount()))
                    .toList();
        }
        if (metadataRoots.size() == 1) return metadataRoots.get(0);

        List<Path> contentRoots;
        try (Stream<Path> paths = Files.walk(normalized)) {
            contentRoots = paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(normalized))
                    .filter(PackSource::looksLikePackRoot)
                    .sorted(Comparator.comparingInt(path ->
                            normalized.relativize(path).getNameCount()))
                    .toList();
        }
        List<Path> topLevelRoots = contentRoots.stream()
                .filter(candidate -> contentRoots.stream().noneMatch(other ->
                        !other.equals(candidate) && candidate.startsWith(other)))
                .toList();
        return topLevelRoots.size() == 1 ? topLevelRoots.get(0) : normalized;
    }

    private static boolean looksLikePackRoot(Path path) {
        return Files.isRegularFile(path.resolve("pack.mcmeta"))
                || Files.isDirectory(path.resolve("assets"))
                || FLAT_ASSET_DIRECTORIES.stream()
                .anyMatch(directory -> Files.isDirectory(path.resolve(directory)));
    }
}
