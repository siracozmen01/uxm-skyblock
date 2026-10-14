package com.uxplima.uxmskyblock.bukkit.top;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The displays placed, kept in {@code data/top-displays.conf} so they outlive a restart, and written again each time
 * one is placed or taken away.
 */
public final class TopDisplays {

    private static final Logger LOGGER = Logger.getLogger(TopDisplays.class.getName());

    private final Path file;
    private final List<TopDisplay> displays = new CopyOnWriteArrayList<>();

    private TopDisplays(Path file) {
        this.file = file;
    }

    /** The displays written in {@code file}, none when it is not there; a line that cannot be read is left out. */
    public static TopDisplays read(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        TopDisplays read = new TopDisplays(file);
        if (!Files.exists(file)) {
            return read;
        }
        try {
            ConfigurationNode root = loader(file).load();
            for (ConfigurationNode line : root.node("displays").childrenList()) {
                try {
                    read.displays.add(new TopDisplay(
                            Objects.requireNonNull(line.node("world").getString()),
                            line.node("x").getInt(),
                            line.node("y").getInt(),
                            line.node("z").getInt(),
                            TopDisplay.Kind.named(line.node("kind").getString(""))
                                    .orElseThrow(),
                            LeaderboardCategory.valueOf(
                                    line.node("board").getString("").toUpperCase(Locale.ROOT)),
                            line.node("rank").getInt()));
                } catch (RuntimeException unreadable) {
                    LOGGER.warning(() -> file + " has a display that cannot be read, left out: " + unreadable);
                }
            }
        } catch (ConfigurateException unreadable) {
            LOGGER.warning(() -> file + " cannot be read, so no display is shown: " + unreadable.getMessage());
        }
        return read;
    }

    public List<TopDisplay> all() {
        return List.copyOf(displays);
    }

    /** Places {@code display}, in place of one that stood at the same block. */
    public void add(TopDisplay display) {
        Objects.requireNonNull(display, "display must not be null");
        displays.removeIf(other -> other.standsAt(display.world(), display.x(), display.y(), display.z()));
        displays.add(display);
        write();
    }

    /** Takes away the display at that block, if one stands there. */
    public Optional<TopDisplay> remove(String world, int x, int y, int z) {
        for (TopDisplay display : displays) {
            if (display.standsAt(world, x, y, z)) {
                displays.remove(display);
                write();
                return Optional.of(display);
            }
        }
        return Optional.empty();
    }

    private synchronized void write() {
        try {
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            if (displays.isEmpty()) {
                Files.writeString(file, "displays = []\n");
                return;
            }
            HoconConfigurationLoader loader = loader(file);
            CommentedConfigurationNode root = loader.createNode();
            for (TopDisplay display : displays) {
                CommentedConfigurationNode line = root.node("displays").appendListNode();
                line.node("world").set(display.world());
                line.node("x").set(display.x());
                line.node("y").set(display.y());
                line.node("z").set(display.z());
                line.node("kind").set(display.kind().word());
                line.node("board").set(display.board().name().toLowerCase(Locale.ROOT));
                line.node("rank").set(display.rank());
            }
            loader.save(root);
        } catch (IOException unwritten) {
            LOGGER.warning(() -> "The displays could not be written to " + file + ": " + unwritten.getMessage());
        }
    }

    private static HoconConfigurationLoader loader(Path file) {
        return HoconConfigurationLoader.builder().path(file).build();
    }
}
