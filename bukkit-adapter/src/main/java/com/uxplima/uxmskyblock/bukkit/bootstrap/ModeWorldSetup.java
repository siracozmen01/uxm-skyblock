package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * What the modes lay in their worlds at start: spawns set on ground, the shipped platforms written, and the wreck templates read. Split out of {@link SkyblockBootstrap}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ModeWorldSetup {

    private final SkyblockBootstrap host;

    ModeWorldSetup(SkyblockBootstrap host) {
        this.host = host;
    }

    /**
     * Lays ground under every island world's spawn that has none, so a player with no island, one who has
     * just reset theirs, has somewhere to stand. The ground is the file schematics/world_spawn.schem,
     * written plain on the first start for an operator to replace with a lobby.
     */
    @SuppressWarnings("deprecation") // UnsafeValues is where the server says which version its names are of
    void groundTheSpawns() {
        com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files = host.gameplayWiring.islandSchematics();
        com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround ground =
                new com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround(files);
        CompletableFuture<Boolean> written = files == null
                ? CompletableFuture.completedFuture(false)
                : files.writeIfMissing(
                        com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround.FILE,
                        com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround.shipped(
                                Bukkit.getUnsafe().getDataVersion()));
        SchedulerPort scheduler = host.gameplayWiring.scheduler();
        var unused = written.handle((done, failure) -> {
            for (String name : host.configWiring.islandWorlds()) {
                World world = Bukkit.getWorld(name);
                if (world == null) {
                    continue;
                }
                Location spawn = world.getSpawnLocation();
                int chunkX = spawn.getBlockX() >> 4;
                int chunkZ = spawn.getBlockZ() >> 4;
                scheduler.onRegion(name, chunkX, chunkZ, () -> {
                    var unusedLaid = ground.ensure(world, task -> scheduler.onRegion(name, chunkX, chunkZ, task))
                            .thenAccept(any -> {
                                if (any) {
                                    host.plugin
                                            .getLogger()
                                            .info("Laid ground under the spawn of " + name
                                                    + ", where a player with no island stands.");
                                }
                            });
                });
            }
            return null;
        });
    }
    /**
     * Writes the platform each preset and each dimension lays as the schematic file it names, where none
     * stands yet, so the island a server ships is a file an operator can change.
     */
    @SuppressWarnings("deprecation") // UnsafeValues is where the server says which version its names are of
    void writeShippedPlatforms() {
        java.util.Map<com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType, String> dimensions =
                new java.util.EnumMap<>(com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType.class);
        host.configWiring.dimensionConfig().mappings().forEach((type, mapping) -> {
            String schematic = mapping.schematic();
            if (schematic != null && !schematic.isBlank()) {
                dimensions.put(type, schematic);
            }
        });
        var unused = host.gameplayWiring
                .schematicEngine()
                .writeShippedPlatforms(
                        host.configWiring.presetConfig().presets(),
                        dimensions,
                        Bukkit.getUnsafe().getDataVersion())
                .whenComplete((written, failure) -> {
                    if (failure != null) {
                        host.plugin
                                .getLogger()
                                .log(
                                        java.util.logging.Level.WARNING,
                                        "The shipped platforms could not be written as schematics.",
                                        failure);
                    } else if (!written.isEmpty()) {
                        host.plugin
                                .getLogger()
                                .info("Wrote the shipped platforms as schematics: " + String.join(", ", written));
                    }
                });
    }
    /** Writes the shipped temple and reads the schematics the Poseidon wreck groups name. */
    @SuppressWarnings("deprecation") // UnsafeValues is where the server says which version its names are of
    void readWreckTemplates() {
        com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files = host.gameplayWiring.islandSchematics();
        if (files == null) {
            return;
        }
        var unused = host.gameplayWiring
                .poseidonWiring()
                .primeTemplates(files, Bukkit.getUnsafe().getDataVersion())
                .thenCompose(read -> host.gameplayWiring.primeCaveTemplates(
                        files, Bukkit.getUnsafe().getDataVersion()))
                .whenComplete((read, failure) -> {
                    if (failure != null) {
                        host.plugin
                                .getLogger()
                                .log(
                                        java.util.logging.Level.WARNING,
                                        "The Poseidon templates could not be read.",
                                        failure);
                    }
                });
    }
}
