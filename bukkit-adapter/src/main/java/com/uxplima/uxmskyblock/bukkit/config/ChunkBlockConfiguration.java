package com.uxplima.uxmskyblock.bukkit.config;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * {@code modules/chunkblock.conf}: whether islands can be ChunkBlock islands, the level each chunk
 * beyond the first needs, the grades a chunk falls into and the node that walks through a closed chunk.
 *
 * @param bypassPermission a node whose holder is not stopped at a closed chunk, empty for nobody
 * @param tiers the grades the file names, none when every chunk is priced by the level ladder
 * @param signs whether a sign floats over each closed chunk an island can open next
 */
public record ChunkBlockConfiguration(
        boolean enabled, ChunkUnlockRules rules, String bypassPermission, ChunkTiers tiers, boolean signs) {

    private static final Logger LOGGER = Logger.getLogger(ChunkBlockConfiguration.class.getName());

    public ChunkBlockConfiguration {
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
        Objects.requireNonNull(tiers, "tiers must not be null");
    }

    /** A server whose chunks are all priced by the level ladder. */
    public ChunkBlockConfiguration(boolean enabled, ChunkUnlockRules rules, String bypassPermission) {
        this(enabled, rules, bypassPermission, ChunkTiers.ladder());
    }

    /** A server whose closed chunks carry signs. */
    public ChunkBlockConfiguration(boolean enabled, ChunkUnlockRules rules, String bypassPermission, ChunkTiers tiers) {
        this(enabled, rules, bypassPermission, tiers, true);
    }

    /**
     * What the plugin ships, for a server whose file could not be read: the grades are the ones the shipped file
     * writes, and the ladder alone if even that cannot be read.
     */
    public static ChunkBlockConfiguration defaultConfiguration() {
        return new ChunkBlockConfiguration(true, ChunkUnlockRules.shipped(), "", shippedTiers());
    }

    private static ChunkTiers shippedTiers() {
        URL shipped = ChunkBlockConfiguration.class.getResource("/modules/chunkblock.conf");
        if (shipped == null) {
            return ChunkTiers.ladder();
        }
        try {
            return tiers(HoconConfigurationLoader.builder()
                    .url(shipped)
                    .build()
                    .load()
                    .node("tiers"));
        } catch (ConfigurateException e) {
            LOGGER.warning(() -> "The shipped modules/chunkblock.conf does not read: " + e.getMessage()
                    + ". Every chunk is priced by the level ladder.");
            return ChunkTiers.ladder();
        }
    }

    public static ChunkBlockConfiguration load(ConfigurationNode root) {
        ChunkUnlockRules shipped = ChunkUnlockRules.shipped();
        List<Long> levels = new ArrayList<>();
        ConfigurationNode written = root.node("unlock-levels");
        if (written.virtual()) {
            levels.addAll(shipped.levels());
        } else {
            for (ConfigurationNode level : written.childrenList()) {
                levels.add(level.getLong(0));
            }
        }
        ChunkUnlockRules rules;
        try {
            rules = new ChunkUnlockRules(levels, root.node("then-every").getLong(shipped.thenEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/chunkblock.conf: " + e.getMessage() + ". The shipped levels are used.");
            rules = shipped;
        }
        return new ChunkBlockConfiguration(
                root.node("enabled").getBoolean(true),
                rules,
                root.node("bypass-permission").getString("").trim(),
                tiers(root.node("tiers")),
                root.node("signs").getBoolean(true));
    }

    /**
     * Every grade under {@code tiers} that reads, in the order of their names. The file keeps no order of its own,
     * and a chunk's grade is drawn against the others of its ring, so a fixed order keeps every chunk's grade the
     * same from one start to the next.
     */
    static ChunkTiers tiers(ConfigurationNode written) {
        List<ChunkTier> tiers = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                written.childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey());
            tier(id, entry.getValue()).ifPresent(tiers::add);
        }
        tiers.sort(Comparator.comparing(ChunkTier::id));
        return new ChunkTiers(tiers);
    }

    private static Optional<ChunkTier> tier(String id, ConfigurationNode node) {
        String where = "the grade " + id;
        Optional<int[]> rings = rings(node.node("rings").getString(""));
        if (rings.isEmpty()) {
            LOGGER.warning(() -> "modules/chunkblock.conf: " + where + " names no rings, such as \"1-2\", \"3\" or"
                    + " \"6+\", so it is left out.");
            return Optional.empty();
        }
        try {
            return Optional.of(new ChunkTier(
                    id,
                    node.node("name").getString("@chunkblock.tiers." + id),
                    rings.get()[0],
                    rings.get()[1],
                    node.node("weight").getInt(1),
                    ChunkCostLines.read(where, lines(node.node("cost"))),
                    ChunkCostLines.read(where, lines(node.node("bypass"))),
                    lines(node.node("rewards")),
                    node.node("points").getLong(0)));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/chunkblock.conf: " + e.getMessage() + ", so it is left out.");
            return Optional.empty();
        }
    }

    /** {@code "1-2"} as one to two, {@code "3"} as three alone, and {@code "6+"} as six and every ring past it. */
    static Optional<int[]> rings(String written) {
        String rings = written.strip();
        try {
            if (rings.endsWith("+")) {
                return Optional.of(new int[] {
                    Integer.parseInt(rings.substring(0, rings.length() - 1).strip()), Integer.MAX_VALUE
                });
            }
            int dash = rings.indexOf('-');
            if (dash > 0) {
                return Optional.of(new int[] {
                    Integer.parseInt(rings.substring(0, dash).strip()),
                    Integer.parseInt(rings.substring(dash + 1).strip())
                });
            }
            int ring = Integer.parseInt(rings);
            return Optional.of(new int[] {ring, ring});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static List<String> lines(ConfigurationNode node) {
        List<String> lines = new ArrayList<>();
        for (ConfigurationNode line : node.childrenList()) {
            String written = line.getString("");
            if (!written.isBlank()) {
                lines.add(written);
            }
        }
        return lines;
    }
}
