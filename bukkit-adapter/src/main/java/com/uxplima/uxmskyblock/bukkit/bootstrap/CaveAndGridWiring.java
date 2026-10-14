package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * The game modes that are only a way of building an island: CaveBlock's rock and SkyGrid's grid, and the structure
 * schematics both lay, read once the server is up. Built by {@link GameplayWiring} before the creation path, which
 * takes these starts with every other mode's.
 */
final class CaveAndGridWiring {

    /** The schematics the CaveBlock structures and the SkyGrid's small structures lay, read once the server is up. */
    private final com.uxplima.uxmskyblock.bukkit.poseidon.SchematicWreckTemplates caveTemplates =
            new com.uxplima.uxmskyblock.bukkit.poseidon.SchematicWreckTemplates(
                    new com.uxplima.uxmskyblock.bukkit.poseidon.ServerWreckTemplates(),
                    com.uxplima.uxmskyblock.bukkit.poseidon.SchematicWreckTemplates::parseQuietly);

    private final com.uxplima.uxmskyblock.bukkit.cave.CaveStructures caveStructures;
    private final com.uxplima.uxmskyblock.bukkit.grid.SkyGridStart gridStart;
    private final java.util.List<CreationActionProvider<IslandStart>> enabled;

    CaveAndGridWiring(ConfigurationWiring config, SchedulerPort scheduler) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(scheduler, "scheduler must not be null");

        this.caveStructures = new com.uxplima.uxmskyblock.bukkit.cave.CaveStructures(
                config.caveBlockConfig().structures(),
                config.caveBlockConfig().shape(),
                caveTemplates,
                new com.uxplima.uxmskyblock.bukkit.poseidon.ServerChestLoot(),
                scheduler);
        com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration cave = config.caveBlockConfig();
        com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration grid = config.skyGridConfig();
        com.uxplima.uxmskyblock.bukkit.loot.LootFiller filler = new com.uxplima.uxmskyblock.bukkit.loot.LootFiller(
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, config.messages()),
                config::effectsConfig,
                com.uxplima.uxmskyblock.bukkit.loot.LootFiller.SERVER);
        this.gridStart = new com.uxplima.uxmskyblock.bukkit.grid.SkyGridStart(
                scheduler,
                grid,
                caveTemplates,
                (inventory, table, random, at) -> config.lootTables()
                        .named(table)
                        .map(found -> {
                            filler.fill(inventory, found, random, at, null);
                            return true;
                        })
                        .orElse(false));
        this.enabled = java.util.stream.Stream.<CreationActionProvider<IslandStart>>concat(
                        cave.enabled()
                                ? java.util.stream.Stream.of(new com.uxplima.uxmskyblock.bukkit.cave.CavernStart(
                                        scheduler, cave, caveStructures))
                                : java.util.stream.Stream.empty(),
                        grid.enabled() ? java.util.stream.Stream.of(gridStart) : java.util.stream.Stream.empty())
                .toList();
    }

    /** The starts of the modes that are enabled: the cavern while CaveBlock is, the grid while SkyGrid is. */
    Stream<CreationActionProvider<IslandStart>> starts() {
        return enabled.stream();
    }

    /**
     * Writes the shipped cave structures where no file stands yet, then reads every schematic the kinds name. Off the
     * main thread; a kind whose file is not read yet is not laid.
     */
    java.util.concurrent.CompletableFuture<Integer> primeTemplates(
            com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files, int dataVersion) {
        java.util.List<java.util.concurrent.CompletableFuture<Boolean>> writes = new java.util.ArrayList<>();
        java.util.Map<String, java.util.function.IntFunction<com.uxplima.uxmlib.schematic.Schematic>> shipped =
                new java.util.LinkedHashMap<>(com.uxplima.uxmskyblock.bukkit.cave.CaveLayouts.SHIPPED);
        shipped.putAll(com.uxplima.uxmskyblock.bukkit.grid.GridLayouts.SHIPPED);
        shipped.forEach((name, layout) -> writes.add(files.writeIfMissing(
                com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics.pathOf(name), layout.apply(dataVersion))));
        java.util.List<String> keys = new java.util.ArrayList<>(caveStructures.templateKeys());
        keys.addAll(gridStart.templateKeys());
        return java.util.concurrent.CompletableFuture.allOf(
                        writes.toArray(java.util.concurrent.CompletableFuture[]::new))
                .handle((done, failure) -> null)
                .thenCompose(done -> caveTemplates.prime(files, keys));
    }
}
