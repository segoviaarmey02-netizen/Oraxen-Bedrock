package dev.oraxenbedrock.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enables the Geyser switch required for all custom item and block mappings. */
public final class GeyserConfigPatcher {
    public enum Result { ALREADY_ENABLED, UPDATED, CONFIG_MISSING }

    private static final Pattern SETTING = Pattern.compile(
            "^(\\s*)enable-custom-content\\s*:\\s*(true|false)(\\s*(?:#.*)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GAMEPLAY = Pattern.compile(
            "^(\\s*)gameplay\\s*:\\s*(?:#.*)?$", Pattern.CASE_INSENSITIVE);

    private GeyserConfigPatcher() {}

    public static Result enable(Path config) throws IOException {
        if (!Files.isRegularFile(config)) return Result.CONFIG_MISSING;

        String input = Files.readString(config, StandardCharsets.UTF_8);
        String newline = input.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithNewline = input.endsWith("\n") || input.endsWith("\r");
        List<String> lines = new ArrayList<>(input.lines().toList());

        for (int i = 0; i < lines.size(); i++) {
            Matcher setting = SETTING.matcher(lines.get(i));
            if (!setting.matches()) continue;
            if (setting.group(2).toLowerCase(Locale.ROOT).equals("true"))
                return Result.ALREADY_ENABLED;
            lines.set(i, setting.group(1) + "enable-custom-content: true"
                    + setting.group(3));
            writeAtomically(config, String.join(newline, lines)
                    + (endsWithNewline ? newline : ""));
            return Result.UPDATED;
        }

        int gameplayLine = -1;
        int gameplayIndent = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher gameplay = GAMEPLAY.matcher(lines.get(i));
            if (!gameplay.matches()) continue;
            gameplayLine = i;
            gameplayIndent = gameplay.group(1).length();
            break;
        }
        if (gameplayLine >= 0) {
            String childIndent = " ".repeat(gameplayIndent + 2);
            lines.add(gameplayLine + 1,
                    childIndent + "enable-custom-content: true");
        } else {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank())
                lines.add("");
            lines.add("gameplay:");
            lines.add("  enable-custom-content: true");
        }
        writeAtomically(config, String.join(newline, lines) + newline);
        return Result.UPDATED;
    }

    private static void writeAtomically(Path target, String content)
            throws IOException {
        if (Files.isSymbolicLink(target)) {
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) throw new IOException(
                "Geyser config has no parent directory: " + target);
        Set<PosixFilePermission> permissions = null;
        try {
            permissions = Files.getPosixFilePermissions(target);
        } catch (UnsupportedOperationException ignored) {
            // Windows and some custom file systems do not expose POSIX modes.
        }
        Path temporary = Files.createTempFile(parent,
                ".oraxenbedrock-geyser-", ".yml");
        try {
            if (permissions != null)
                Files.setPosixFilePermissions(temporary, permissions);
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
