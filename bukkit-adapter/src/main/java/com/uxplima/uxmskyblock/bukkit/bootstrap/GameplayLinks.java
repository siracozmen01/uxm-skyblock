package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * What ties the gameplay parts together once each is built: every cache that holds something per island lets an
 * erased island go, a mode's own events and a reached level or bank balance count for the missions that ask for them,
 * and an island is cleared everywhere its game mode wrote. Split out of {@link GameplayWiring}, which calls it last.
 */
final class GameplayLinks {

    private GameplayLinks() {}

    static void link(
            GameplayWiring wiring,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            TemporaryAccessService temporaryAccessService,
            IslandAdminFreezeService freezeService) {
        Objects.requireNonNull(wiring, "wiring must not be null");
        ChunkBlockWiring chunkBlockWiring = wiring.chunkBlockWiring();
        AcidIslandWiring acidIslandWiring = wiring.acidIslandWiring();
        BoxedWiring boxedWiring = wiring.boxedWiring();
        PoseidonWiring poseidonWiring = wiring.poseidonWiring();
        StrangerRealmsWiring strangerRealmsWiring = wiring.strangerRealmsWiring();
        ParkourWiring parkourWiring = wiring.parkourWiring();
        BrixWiring brixWiring = wiring.brixWiring();
        TradeWindsWiring tradeWindsWiring = wiring.tradeWindsWiring();
        OneBlockWiring oneBlockWiring = wiring.oneBlockWiring();
        // The rest of the caches register as they are built, after the wiring that owns them.
        wiring.cacheEviction().whenForgotten(wiring.environmentWiring().limitService()::clearIsland);
        wiring.cacheEviction().whenForgotten(wiring.environmentWiring().dimensionService()::resetIslandDimensions);
        // The level is what a ChunkBlock island holds its chunks with, so each score is checked against it.
        wiring.economicWiring()
                .worthService()
                .whenScored((islandId, score) -> chunkBlockWiring.onLevel(islandId, score.calculatedLevel()));
        wiring.cacheEviction().whenForgotten(wiring.economicWiring().worthService()::forgetIsland);
        wiring.cacheEviction().whenForgotten(chunkBlockWiring.service()::forget);
        // Each dimension's web keeps its own chunks per island, and lets them go with the overworld's.
        for (ChunkBlockWiring.Web web : chunkBlockWiring.dimensions()) {
            wiring.cacheEviction().whenForgotten(web.service()::forget);
        }
        wiring.cacheEviction().whenForgotten(wiring.creationWiring().missionService()::forgetIsland);
        // The line over a OneBlock is sent, not spawned, so nothing takes it away with the island but this.
        wiring.cacheEviction().whenForgotten(oneBlockWiring.blockLines()::take);
        // A mode's own events count for the missions that ask for them, when there are missions.
        com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener missions =
                wiring.creationWiring().missionListener();
        if (missions != null) {
            oneBlockWiring.listener().useMissions(missions);
            boxedWiring.listener().useMissions(missions);
            // A level and a bank balance are reached rather than counted one at a time.
            missions.useMembers(wiring.membershipService()::members, org.bukkit.Bukkit::getPlayer);
            wiring.economicWiring()
                    .worthService()
                    .whenScored((islandId, score) -> missions.reached(
                            islandId,
                            com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.LEVEL_REACH,
                            score.calculatedLevel()));
            wiring.economicWiring()
                    .bankService()
                    .whenBalanced((islandId, minor) -> missions.reached(
                            islandId,
                            com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.BANK_BALANCE,
                            java.math.BigDecimal.valueOf(minor)
                                    .movePointLeft(
                                            com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.PRIMARY_SCALE)
                                    .longValue()));
        }
        var chunkSigns = chunkBlockWiring.signs();
        if (chunkSigns != null) {
            wiring.cacheEviction().whenForgotten(chunkSigns::forget);
        }
        for (ChunkBlockWiring.Web web : chunkBlockWiring.dimensions()) {
            var signsThere = web.signs();
            if (signsThere != null) {
                wiring.cacheEviction().whenForgotten(signsThere::forget);
            }
        }
        wiring.cacheEviction().whenForgotten(acidIslandWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(boxedWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(boxedWiring::forgetLand);
        // An island is cleared over its border, everywhere its game mode wrote past it, a box grown past it and a
        // margin past all of them, on every path that removes it. Its starts say where they wrote.
        wiring.adminWiring()
                .useWipeAreas(new com.uxplima.uxmskyblock.core.application.recycle.IslandWipeAreas(
                        persistence.islandFootprintPort(),
                        boxedWiring.service()::reach,
                        clearMargin(config.rootNode())));
        wiring.creationWiring().schematicEngine().useFootprints(persistence.islandFootprintPort());
        wiring.cacheEviction().whenForgotten(poseidonWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(strangerRealmsWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(parkourWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(brixWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(tradeWindsWiring.service()::forget);
        wiring.cacheEviction().whenForgotten(tradeWindsWiring.market()::forget);
        wiring.cacheEviction().whenForgotten(tradeWindsWiring.contracts()::forget);
        // Three more that hold something for every island a player has merely walked on. Each of
        // them answers a question on the movement or interaction path, and each of them remembers
        // the answer so the path is not a query. An island id is a fresh uuid every time, so an
        // erased island's entry is never read again and nothing was letting go of it.
        wiring.cacheEviction().whenForgotten(temporaryAccessService::forgetIsland);
        wiring.cacheEviction().whenForgotten(wiring.adminWiring().antiAbuseService()::forgetIsland);
        wiring.cacheEviction().whenForgotten(wiring.economicWiring().bankruptcyService()::forgetIsland);
        wiring.cacheEviction().whenForgotten(freezeService::forgetIsland);
        wiring.cacheEviction().whenForgotten(oneBlockWiring.service()::forgetIsland);
    }

    /** How far past everything an island wrote it is cleared, as {@code grid.clear-margin} says. */
    static int clearMargin(org.spongepowered.configurate.@Nullable ConfigurationNode root) {
        return root == null ? 16 : Math.max(0, root.node("grid", "clear-margin").getInt(16));
    }

    /** The boards each enabled game mode adds: OneBlock's phases, Parkour's runs, Boxed's land and ChunkBlock's chunks. */
    static void modeBoards(
            com.uxplima.uxmskyblock.core.application.leaderboard.LeaderboardMetricRegistry metrics,
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeStandingsPort standings,
            OneBlockWiring oneBlockWiring,
            ParkourWiring parkourWiring,
            BoxedWiring boxedWiring,
            ChunkBlockWiring chunkBlockWiring) {
        if (oneBlockWiring.enabled()) {
            var oneBlock = oneBlockWiring.service();
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.oneBlock(
                    metrics,
                    standings,
                    island -> oneBlock.phasesInMemory(island).orElse(oneBlock.phases()));
        }
        if (parkourWiring.enabled()) {
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.parkour(metrics, standings);
        }
        if (boxedWiring.enabled()) {
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.boxed(
                    metrics, standings, boxedWiring.service().rules());
        }
        if (chunkBlockWiring.enabled()) {
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.chunkBlock(metrics, standings);
        }
    }

    /** The leaderboard displays operators placed, read from {@code data/top-displays.conf}, not yet drawing. */
    static com.uxplima.uxmskyblock.bukkit.top.TopDisplayBeat topDisplays(
            ConfigurationWiring config,
            com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService boards,
            com.uxplima.uxmskyblock.core.application.island.IslandLocationService locations,
            com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler,
            SentHolograms sent) {
        return new com.uxplima.uxmskyblock.bukkit.top.TopDisplayBeat(
                com.uxplima.uxmskyblock.bukkit.top.TopDisplays.read(
                        config.dataDir().resolve("data").resolve("top-displays.conf")),
                boards::getTop,
                island -> locations
                        .findIsland(island)
                        .map(found -> found.ownerPlayerUuid().value()),
                player -> java.util.Optional.ofNullable(
                        org.bukkit.Bukkit.getOfflinePlayer(player).getName()),
                config.messages(),
                scheduler,
                new com.uxplima.uxmskyblock.bukkit.top.BukkitTopPainter(
                        config.messages(), sent.regions(), sent.packets()));
    }
}
