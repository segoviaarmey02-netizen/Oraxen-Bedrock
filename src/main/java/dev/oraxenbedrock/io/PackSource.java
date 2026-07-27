package dev.oraxenbedrock.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public final class PackSource implements AutoCloseable {
    private final Path root;
    private final FileSystem zipFileSystem;

    private PackSource(Path root, FileSystem zipFileSystem) {
        this.root = root;
        this.zipFileSystem = zipFileSystem;
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
            Path exact = root.resolve("assets").resolve(namespace).resolve("textures")
                    .resolve(prefix + name + ".png");
            if (Files.isRegularFile(exact)) return exact;
        }
        String fileName = Path.of(name).getFileName() + ".png";
        Path searchRoot = Files.isDirectory(root.resolve("assets")) ? root.resolve("assets") : root;
        try (Stream<Path> paths = Files.walk(searchRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst().orElse(null);
        }
    }

    public Stream<Path> files() throws IOException {
        return Files.walk(root).filter(Files::isRegularFile);
    }

    @Override
    public void close() throws IOException {
        if (zipFileSystem != null) zipFileSystem.close();
    }
}
