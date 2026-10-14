package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
     * The blocked command {@code line} runs, a line as a player typed it after the slash, or nothing.
     *
     * <p>A command answers to more than one name: its own, its aliases, and each with its plugin's prefix. Each is
     * read through {@code namesOf}, which gives every name the command a label runs answers to, so a blocked
     * command is blocked under whichever of them the visitor types.
     *
     * @param namesOf every name of the command {@code label} runs, the label itself among them
     */
    public java.util.Optional<String> blocking(String line, java.util.function.Function<String, Set<String>> namesOf) {
        String typed = words(line);
        int space = typed.indexOf(' ');
        String label = space < 0 ? typed : typed.substring(0, space);
        String rest = space < 0 ? "" : typed.substring(space + 1);
        Set<String> typedNames = names(label, namesOf);
        for (String blocked : blockedCommands) {
            int gap = blocked.indexOf(' ');
            String blockedLabel = gap < 0 ? blocked : blocked.substring(0, gap);
            String blockedRest = gap < 0 ? "" : blocked.substring(gap + 1);
            boolean sameCommand = names(blockedLabel, namesOf).stream().anyMatch(typedNames::contains);
            boolean sameWords = blockedRest.isEmpty() || rest.equals(blockedRest) || rest.startsWith(blockedRest + " ");
            if (sameCommand && sameWords) {
                return java.util.Optional.of(blocked);
            }
        }
        return java.util.Optional.empty();
    }

    /** As {@link #blocking(String, java.util.function.Function)}, each command known by the name it is typed under. */
    public java.util.Optional<String> blocking(String line) {
        return blocking(line, label -> Set.of(label));
    }

    /** Every name {@code label} goes by, each also without a plugin's prefix. */
    private static Set<String> names(String label, java.util.function.Function<String, Set<String>> namesOf) {
        Set<String> names = new java.util.HashSet<>();
        Set<String> read = new java.util.HashSet<>(namesOf.apply(label));
        read.add(label);
        for (String name : read) {
            String lower = name.toLowerCase(Locale.ROOT);
            names.add(lower);
            int colon = lower.indexOf(':');
            if (colon >= 0) {
                names.add(lower.substring(colon + 1));
            }
        }
        return names;
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
