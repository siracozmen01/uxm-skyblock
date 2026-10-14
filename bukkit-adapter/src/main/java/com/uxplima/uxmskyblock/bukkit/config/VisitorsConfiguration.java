package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * What visitors may not do, {@code modules/visitors.conf}: the commands a player standing on an island they are no
 * member of cannot run.
 *
 * @param blockedCommands each a command, with the words after it that are blocked too, as {@code home set}
 */
public record VisitorsConfiguration(List<String> blockedCommands) {

    public VisitorsConfiguration {
        List<String> written = new ArrayList<>();
        for (String command : Objects.requireNonNull(blockedCommands, "blockedCommands must not be null")) {
            String line = words(command);
            if (!line.isEmpty()) {
                written.add(line);
            }
        }
        blockedCommands = List.copyOf(written);
    }

    public static VisitorsConfiguration defaultConfiguration() {
        return new VisitorsConfiguration(List.of("sethome", "setwarp"));
    }

    public static VisitorsConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        ConfigurationNode written = root.node("visitors", "blocked-commands");
        if (written.virtual()) {
            return defaultConfiguration();
        }
        List<String> commands = new ArrayList<>();
        for (ConfigurationNode line : written.childrenList()) {
            commands.add(line.getString(""));
        }
        return new VisitorsConfiguration(commands);
    }

    /**
     * The blocked command {@code line} runs, a line as a player typed it after the slash, or nothing. A command's
     * plugin prefix, as {@code essentials:sethome}, does not get it past.
     */
    public java.util.Optional<String> blocking(String line) {
        String typed = words(line);
        int space = typed.indexOf(' ');
        String label = space < 0 ? typed : typed.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            typed = typed.substring(colon + 1);
        }
        for (String blocked : blockedCommands) {
            if (typed.equals(blocked) || typed.startsWith(blocked + " ")) {
                return java.util.Optional.of(blocked);
            }
        }
        return java.util.Optional.empty();
    }

    /** {@code line} in lower case, without its slash, its words one space apart. */
    private static String words(String line) {
        String plain = line.strip().toLowerCase(Locale.ROOT);
        if (plain.startsWith("/")) {
            plain = plain.substring(1);
        }
        return String.join(" ", plain.split("\\s+", -1)).strip();
    }
}
