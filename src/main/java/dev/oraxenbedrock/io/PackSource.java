package dev.oraxenbedrock.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PackSource implements AutoCloseable {
    private final Path root;
    private final FileSystem zipFileSystem;
    private final JavaPackMetadata metadata;
    private final List<Path> assetRoots;

    private PackSource(Path root, FileSystem zipFileSystem) throws IOException {
        this.root = root;
        this.zipFileSystem = zipFileSystem;
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
        if (Files.isDirectory(configured)) return new PackSource(configured, null);
        if (Files.isRegularFile(configured)) {
            FileSystem fs = FileSystems.newFileSystem(configured, Collections.emptyMap());
            return new PackSource(fs.getPath("/"), fs);
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
        return null;
    }

    public Path findTexture(String reference) throws IOException {
        return findTexture(reference, "minecraft");
    }

    public Path findTexture(String reference, String defaultNamespace) throws IOException {
        if (reference == null || reference.isBlank()) return null;
        String normalized = reference.replace('\\', '/').replaceFirst("(?i)\\.png$", "");
        String namespace = defaultNamespace;
        String name = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            name = normalized.substring(colon + 1);
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
        for (int i = assetRoots.size() - 1; i >= 0; i--) {
            Path namespaceRoot = assetRoots.get(i).resolve(namespace).normalize();
            if (!namespaceRoot.startsWith(assetRoots.get(i)) || !Files.isDirectory(namespaceRoot))
                continue;
            try (Stream<Path> paths = Files.walk(namespaceRoot)) {
                Path found = paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                        .sorted()
                        .findFirst().orElse(null);
                if (found != null) return found;
            }
        }
        return null;
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
