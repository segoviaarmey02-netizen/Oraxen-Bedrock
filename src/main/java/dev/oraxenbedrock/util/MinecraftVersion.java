package dev.oraxenbedrock.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, future-proof Minecraft version parser (also accepts 26.x naming). */
public record MinecraftVersion(int major, int minor, int patch, String source)
        implements Comparable<MinecraftVersion> {
    private static final Pattern VERSION =
            Pattern.compile("(?<!\\d)(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    public static final MinecraftVersion MINIMUM =
            new MinecraftVersion(1, 20, 5, "1.20.5");

    public static MinecraftVersion parse(String value) {
        Matcher matcher = VERSION.matcher(value == null ? "" : value);
        if (!matcher.find()) return new MinecraftVersion(0, 0, 0, value);
        return new MinecraftVersion(Integer.parseInt(matcher.group(1)),
                number(matcher.group(2)), number(matcher.group(3)), value);
    }

    public boolean supported() {
        return compareTo(MINIMUM) >= 0;
    }

    @Override
    public int compareTo(MinecraftVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        return result;
    }

    @Override
    public String toString() {
        return major + "." + minor + (patch == 0 ? "" : "." + patch);
    }

    private static int number(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }
}
