package com.uxplima.uxmskyblock.bukkit.worth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import org.jspecify.annotations.Nullable;

/**
 * What each block is worth to an island, in one window drawn by {@code menus/block-values.conf}: the money it adds to
 * the island's worth, the points it adds and the levels those make, all read from {@code levels.conf}. The blocks that
 * give the most points come first. A Bedrock player gets the same file as a form.
 */
public final class BlockValues {

    /** The menu file that lists the blocks, and the list it draws them from. */
    static final String FILE = "block-values";

    static final String BLOCKS = "skyblock:block-values";

    private final Supplier<LevelConfiguration> levels;
    private final LongFunction<String> money;
    private final Messages messages;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /** @param money an amount in minor units as the server writes money */
    public BlockValues(Supplier<LevelConfiguration> levels, LongFunction<String> money, Messages messages) {
        this.levels = Objects.requireNonNull(levels, "levels must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Hands the window the engine that reads its file. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine != null) {
            engine.handedList(BLOCKS);
        }
    }

    /** Opens the window. The values are the configuration's, in memory, so this runs on the player's thread. */
    public void open(Player player) {
        List<MenuRow> rows = rows();
        if (rows.isEmpty()) {
            messages.send(player, "block_values.none");
            return;
        }
        SkyblockMenuEngine engine = menuEngine;
        if (engine == null || !engine.open(player, FILE, Map.of(), Map.of(BLOCKS, rows))) {
            messages.send(player, "block_values.no_window");
        }
    }

    /** One row per block that is worth money or points, the most points first, then the most money, then by name. */
    List<MenuRow> rows() {
        LevelConfiguration config = levels.get();
        TreeSet<String> keys = new TreeSet<>(config.blockWeights().keySet());
        keys.addAll(config.blockPrices().keySet());
        List<String> ordered = new ArrayList<>(keys);
        ordered.sort(
                Comparator.comparingLong((String key) -> config.blockWeights().getOrDefault(key, 0L))
                        .thenComparingLong(key -> config.blockPrices().getOrDefault(key, 0L))
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()));
        List<MenuRow> rows = new ArrayList<>(ordered.size());
        for (String key : ordered) {
            long points = config.blockWeights().getOrDefault(key, 0L);
            long worth = Math.round(config.blockPrices().getOrDefault(key, 0L) * config.dampingFactor());
            if (points <= 0 && worth <= 0) {
                continue;
            }
            String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            Material material = Material.matchMaterial(key);
            Map<String, String> words = new HashMap<>();
            words.put("name", "<lang:block.minecraft." + path + ">");
            words.put("material", material == null ? Material.PAPER.name() : material.name());
            words.put("worth", money.apply(worth));
            words.put("points", Long.toString(points));
            words.put(
                    "level",
                    String.format(Locale.ROOT, "%.2f", points / (double) Math.max(1, config.pointsPerLevel())));
            rows.add(new MenuRow(words, key));
        }
        return List.copyOf(rows);
    }
}
