package com.uxplima.uxmskyblock.bukkit.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Reads the OneBlock files as an operator left them, {@code modules/oneblock.conf} and the phase files under
 * {@code oneblock/phases/}, and says each line that could not be read as written with the file it is in and the
 * line it stands on, so nothing is dropped without a word.
 */
public final class OneBlockLoadReport {

    /** The files as read, and each problem as {@code <file>:<line>: <what>}, or {@code <file>: <what>}. */
    public record Read(Optional<OneBlockConfiguration> config, List<String> problems) {
        public Read {
            Objects.requireNonNull(config, "config must not be null");
            problems = List.copyOf(problems);
        }
    }

    private static final Pattern PHASE = Pattern.compile("^OneBlock phase '([^']+)'");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    private OneBlockLoadReport() {}

    /**
     * Reads the files under {@code dataDir}. A module file that will not parse at all gives no configuration, so the
     * one in use stays, and says why.
     */
    public static Read read(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        Path module = moduleOf(dataDir);
        String shown = shown(dataDir, module);
        ConfigurationNode root;
        try {
            root = HoconConfigurationLoader.builder().path(module).build().load();
        } catch (ConfigurateException unreadable) {
            return new Read(Optional.empty(), List.of(shown + ": " + unreadable.getMessage()));
        }
        OneBlockConfiguration.Loaded loaded = OneBlockConfiguration.loadReporting(
                root, dataDir.resolve("oneblock").resolve("phases"));
        List<String> problems = new ArrayList<>();
        for (String said : loaded.problems()) {
            problems.add(placed(dataDir, shown, said));
        }
        return new Read(Optional.of(loaded.config()), problems);
    }

    /**
     * Says in the console how the files read at the start went: each line that could not be read as written, with
     * its file and line, and how many phases there are.
     *
     * @return the lines said, each problem placed in its file
     */
    public static List<String> say(Path dataDir, OneBlockConfiguration.Loaded loaded) {
        Objects.requireNonNull(loaded, "loaded must not be null");
        String shown = shown(dataDir, moduleOf(dataDir));
        List<String> placed = new ArrayList<>();
        for (String said : loaded.problems()) {
            placed.add(placed(dataDir, shown, said));
        }
        placed.forEach(LOGGER::warning);
        int phases = loaded.config().phases().phases().size();
        LOGGER.info(() -> "OneBlock: " + phases + " phases read, " + placed.size()
                + (placed.size() == 1 ? " line" : " lines") + " not read as written.");
        return placed;
    }

    private static Path moduleOf(Path dataDir) {
        Path module = dataDir.resolve("modules").resolve("oneblock.conf");
        return !Files.exists(module) && Files.exists(dataDir.resolve("oneblock.conf"))
                ? dataDir.resolve("oneblock.conf")
                : module;
    }

    private static String shown(Path dataDir, Path module) {
        return dataDir.relativize(module).toString().replace('\\', '/');
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(OneBlockLoadReport.class.getName());

    /**
     * {@code said} with the file it is about, and the line of the first word it quotes there: the phase's own file
     * for a phase that has one, the module file otherwise.
     */
    static String placed(Path dataDir, String module, String said) {
        String file = module;
        @org.jspecify.annotations.Nullable String phase = null;
        Matcher named = PHASE.matcher(said);
        if (said.startsWith("oneblock/phases/")) {
            // A phase file that cannot be read at all says so with its name, and has no line to point at.
            return said;
        }
        if (named.find()) {
            phase = named.group(1);
            if (Files.exists(dataDir.resolve("oneblock").resolve("phases").resolve(phase + ".conf"))) {
                file = "oneblock/phases/" + phase + ".conf";
            }
        }
        int line = lineOf(dataDir.resolve(file), said, phase);
        return line > 0 ? file + ":" + line + ": " + said : file + ": " + said;
    }

    /** The first line of {@code path} that holds a word {@code said} quotes, other than the phase's name. */
    private static int lineOf(Path path, String said, @org.jspecify.annotations.Nullable String phase) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return 0;
        }
        Matcher quoted = QUOTED.matcher(said);
        while (quoted.find()) {
            String word = quoted.group(1);
            if (word.equals(phase) || word.isBlank()) {
                continue;
            }
            String lower = word.toLowerCase(Locale.ROOT);
            for (int at = 0; at < lines.size(); at++) {
                String text = lines.get(at);
                if (!text.stripLeading().startsWith("#")
                        && text.toLowerCase(Locale.ROOT).contains(lower)) {
                    return at + 1;
                }
            }
        }
        return 0;
    }
}
