package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Coral that spreads slowly on a Poseidon island. Around each player there, now and then, a few blocks are looked at;
 * a living coral block among them may grow a coral or a fan of its colour into the water above it, or a fan onto its
 * side. Nothing grows off the island, out of the water, or in a block the player's own thread does not own.
 */
public final class CoralGrowth {

    /** The faces a coral block grows from: up for a coral or a fan, the sides for a fan on its wall. */
    private static final List<BlockFace> FACES =
            List.of(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private static final String BLOCK = "_CORAL_BLOCK";

    private final PoseidonConfiguration.Coral config;
    private final PoseidonService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Predicate<Location> owned;
    private final Random random;

    /** @param owned whether the current thread may touch a block, as Folia counts it */
    public CoralGrowth(
            PoseidonConfiguration.Coral config,
            PoseidonService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Predicate<Location> owned,
            Random random) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.owned = Objects.requireNonNull(owned, "owned must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** Starts the beat while coral spreads. Closing what it returns stops it. */
    public AutoCloseable start() {
        if (!config.enabled()) {
            return () -> {};
        }
        return scheduler.repeatGlobal(
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> grow(player));
                    }
                },
                config.checkEvery(),
                config.checkEvery());
    }

    /** One look around one player, on their own thread. Answers how many corals grew. */
    public int grow(Player player) {
        Location at = player.getLocation();
        if (!config.enabled() || at == null) {
            return 0;
        }
        Optional<IslandId> island = islands.findIslandAt(at).map(Island::id).filter(service::isPoseidon);
        if (island.isEmpty()) {
            return 0;
        }
        int grown = 0;
        int reach = config.radius();
        for (int look = 0; look < config.samples(); look++) {
            Block block = at.getWorld()
                    .getBlockAt(
                            at.getBlockX() + random.nextInt(2 * reach + 1) - reach,
                            at.getBlockY() + random.nextInt(reach + 1) - reach / 2,
                            at.getBlockZ() + random.nextInt(2 * reach + 1) - reach);
            if (colour(block.getType()).isPresent()
                    && owned.test(block.getLocation())
                    && random.nextDouble() < config.chance()
                    && spread(block, island.get())) {
                grown++;
            }
        }
        return grown;
    }

    /** Grows from {@code coral} onto one face of it, when that face is water on the same island. */
    boolean spread(Block coral, IslandId island) {
        Optional<String> colour = colour(coral.getType());
        if (colour.isEmpty()) {
            return false;
        }
        BlockFace face = FACES.get(random.nextInt(FACES.size()));
        Block into = coral.getRelative(face);
        if (into.getType() != Material.WATER
                || !owned.test(into.getLocation())
                || islands.findIslandAt(into.getLocation())
                        .map(Island::id)
                        .filter(island::equals)
                        .isEmpty()) {
            return false;
        }
        BlockData grown;
        if (face == BlockFace.UP) {
            grown = Material.valueOf(colour.get() + (random.nextBoolean() ? "_CORAL" : "_CORAL_FAN"))
                    .createBlockData();
        } else {
            grown = Material.valueOf(colour.get() + "_CORAL_WALL_FAN").createBlockData();
            if (grown instanceof Directional fan) {
                fan.setFacing(face);
            }
        }
        if (grown instanceof Waterlogged wet) {
            wet.setWaterlogged(true);
        }
        into.setBlockData(grown, false);
        return true;
    }

    /** The colour of a living coral block, as in TUBE of TUBE_CORAL_BLOCK; none for any other block. */
    static Optional<String> colour(Material type) {
        String name = type.name().toUpperCase(Locale.ROOT);
        if (!name.endsWith(BLOCK) || name.startsWith("DEAD_")) {
            return Optional.empty();
        }
        return Optional.of(name.substring(0, name.length() - BLOCK.length()));
    }
}
