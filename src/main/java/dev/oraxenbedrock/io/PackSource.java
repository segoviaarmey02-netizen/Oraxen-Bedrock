package dev.oraxenbedrock.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
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
        for (int i = assetRoots.size() - 1; i >= 0; i--) {
            Path candidate = assetRoots.get(i).resolve(namespace).resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    public Path findTexture(String reference) throws IOException {
        if (reference == null || reference.isBlank()) return null;
        String normalized = reference.replace('\\', '/').replace(".png", "");
        String namespace = "minecraft";
        String name = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            name = normalized.substring(colon + 1);
        }
        name = name.replaceFirst("^textures/", "");
        for (String prefix : List.of("", "item/", "items/", "block/", "blocks/", "gui/")) {
            Path exact = findAsset(namespace, "textures/" + prefix + name + ".png");
            if (exact != null) return exact;
        }
        String fileName = Path.of(name).getFileName() + ".png";
        for (int i = assetRoots.size() - 1; i >= 0; i--) {
            try (Stream<Path> paths = Files.walk(assetRoots.get(i))) {
                Path found = paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
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
}
