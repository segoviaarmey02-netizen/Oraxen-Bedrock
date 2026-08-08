package dev.oraxenbedrock.conversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackInstallTransactionTest {
    @TempDir Path temp;

    @Test
    void restoresTheWholePreviousSetWhenAnyInstallFails() throws Exception {
        for (int failedInstall = 1; failedInstall <= 3; failedInstall++) {
            int ordinal = failedInstall;
            Scenario scenario = scenario("existing-" + failedInstall, true);
            PackConverter converter = new PackConverter(
                    scenario.root().resolve("plugin-data"));

            assertThrows(IOException.class, () ->
                    converter.installOutputsTransactionally(
                            scenario.geyser(), scenario.installs(),
                            failCommit(ordinal)));

            for (int index = 0; index < scenario.destinations().size(); index++)
                assertEquals("old-" + index,
                        Files.readString(scenario.destinations().get(index)));
            assertNoTransactionDirectories(scenario.geyser());
        }
    }

    @Test
    void removesInstalledFilesWhenPreviousTargetsWereAbsent() throws Exception {
        Scenario scenario = scenario("absent", false);
        PackConverter converter = new PackConverter(
                scenario.root().resolve("plugin-data"));

        assertThrows(IOException.class, () ->
                converter.installOutputsTransactionally(
                        scenario.geyser(), scenario.installs(), failCommit(3)));

        for (Path destination : scenario.destinations())
            assertFalse(Files.exists(destination));
        assertNoTransactionDirectories(scenario.geyser());
    }

    @Test
    void successfulInstallReplacesTheSetAndRemovesStaging() throws Exception {
        Scenario scenario = scenario("success", true);
        PackConverter converter = new PackConverter(
                scenario.root().resolve("plugin-data"));

        converter.installOutputsTransactionally(
                scenario.geyser(), scenario.installs(),
                PackConverter::moveReplacing);

        for (int index = 0; index < scenario.destinations().size(); index++)
            assertEquals("new-" + index,
                    Files.readString(scenario.destinations().get(index)));
        assertNoTransactionDirectories(scenario.geyser());
    }

    private Scenario scenario(String name, boolean previous) throws IOException {
        Path root = temp.resolve(name);
        Path geyser = root.resolve("Geyser");
        List<Path> sources = List.of(
                root.resolve("generated/OraxenBedrock.mcpack"),
                root.resolve("generated/oraxen-items.json"),
                root.resolve("generated/oraxen-blocks.json"));
        List<Path> destinations = List.of(
                geyser.resolve("packs/OraxenBedrock.mcpack"),
                geyser.resolve("custom_mappings/oraxen-items.json"),
                geyser.resolve("custom_mappings/oraxen-blocks.json"));
        for (int index = 0; index < sources.size(); index++) {
            write(sources.get(index), "new-" + index);
            if (previous) write(destinations.get(index), "old-" + index);
        }
        List<PackConverter.InstallFile> installs = List.of(
                new PackConverter.InstallFile(sources.get(0), destinations.get(0)),
                new PackConverter.InstallFile(sources.get(1), destinations.get(1)),
                new PackConverter.InstallFile(sources.get(2), destinations.get(2)));
        return new Scenario(root, geyser, destinations, installs);
    }

    private PackConverter.InstallMove failCommit(int ordinal) {
        AtomicInteger commits = new AtomicInteger();
        return (source, destination) -> {
            Path parent = source.getParent();
            boolean stagedOutput = parent != null
                    && parent.getFileName().toString().equals("new")
                    && parent.getParent() != null
                    && parent.getParent().getFileName().toString()
                    .startsWith(".oraxenbedrock-install-");
            if (stagedOutput && commits.incrementAndGet() == ordinal)
                throw new IOException("Injected install failure " + ordinal);
            PackConverter.moveReplacing(source, destination);
        };
    }

    private void assertNoTransactionDirectories(Path geyser) throws IOException {
        try (Stream<Path> paths = Files.list(geyser)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".oraxenbedrock-install-")));
        }
    }

    private void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private record Scenario(
            Path root, Path geyser, List<Path> destinations,
            List<PackConverter.InstallFile> installs) {}
}
