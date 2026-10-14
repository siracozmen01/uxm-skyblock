package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.border.IslandBorderService;
import com.uxplima.uxmskyblock.core.application.border.IslandSizeAllowance;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Each island's edge: how far it reaches, as its size tier, its box or its realm says, and how it is drawn, as the
 * client's border, a wall or nothing, the way its members chose. Built by {@link GameplayWiring} once the environment,
 * the economy and the game modes are.
 */
final class IslandEdgeWiring {

    private static final Logger LOGGER = Logger.getLogger(IslandEdgeWiring.class.getName());

    private final SchedulerPort scheduler;
    private final IslandProtectionListener protectionListener;
    private final GameplayEnvironmentWiring environment;
    private final com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges islandEdges;
    private final com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls islandEdgeWalls;
    private final IslandBorderService borderService;

    IslandEdgeWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener protectionListener,
            GameplayEnvironmentWiring environment,
            EconomicWiring economic,
            BoxedWiring boxed,
            StrangerRealmsWiring strangerRealms,
            ChunkBlockWiring chunkBlockWiring,
            IslandMembershipService membershipService,
            IslandCacheEviction cacheEviction) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        // Each island's edge is drawn the way its members chose: the client's border, a wall, or nothing. A ChunkBlock
        // island is edged by the wall around its open ground instead.
        var chunkBlock = chunkBlockWiring.service();
        this.islandEdges = new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges(
                persistence.islandEdgePort(),
                config.wallConfig(),
                island -> chunkBlock.inMemory(island).isPresent());
        this.islandEdgeWalls = new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls(
                islandEdges,
                protectionListener,
                scheduler,
                new com.uxplima.uxmskyblock.bukkit.wall.WallPanels(config.wallConfig()),
                persistence.islandStoragePort()::findLocationByIslandId);
        var border = environment.boundaryListener();
        if (border != null) {
            // A Boxed island's border is its box, which BoxedBorders draws; its island square, wider once a pearl moved
            // the box, is not drawn.
            border.useBorderFor(island ->
                    islandEdges.drawsBorder(island) && !boxed.service().isBoxed(island));
            // A pearl thrown from a Boxed island is judged by its box, which a pearl may move.
            border.leavePearlsOf(boxed.service()::isBoxed);
            islandEdges.whenChanged(island -> refreshTheBorderOn(island, border));
        }
        islandEdges.whenChanged(islandEdgeWalls::changed);
        cacheEviction.whenForgotten(islandEdgeWalls::forget);
        scheduler.async(() -> {
            try {
                int count = islandEdges.prime();
                LOGGER.fine(() -> count + " islands chose how their edge is drawn.");
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "How the islands draw their edges could not be read.", e);
            }
        });

        // The island size upgrade had five tiers, a cost for each and a radius on each, and nothing
        // read the radius: an island that paid for the top tier reached exactly as far as one that
        // had paid nothing. The edge follows the tier now, and the protection index is told, because
        // it is what answers for every block a player touches.
        // A Boxed island reaches as far as its box, wherever an ender pearl moved it, a StrangerRealms island as far as
        // its size tier and
        // its members take it, and every other island as far as its size tier.
        IslandSizeAllowance sizeAllowance = new IslandSizeAllowance(economic.upgradeService());
        this.borderService = new IslandBorderService(
                persistence.islandStoragePort(),
                islandId -> boxed.service()
                        .reach(islandId)
                        .orElseGet(() -> strangerRealms.claimRadius(islandId, sizeAllowance.applyAsInt(islandId))),
                persistence.islandMutationLock());
        boxed.whenBoxChanged(islandId -> borderService.applyAllowance(islandId).ifPresent(moved -> {
            protectionListener
                    .spatialIndex()
                    .indexIsland(moved.island(), moved.location().worldName());
            // A Boxed island's border is its box, which BoxedBorders draws; its square, wider than the box
            // once a pearl or a side moved it, is not shown, only its walls kept.
            moveTheWalls(moved);
        }));
        membershipService.whenMembersChanged(islandId -> {
            if (strangerRealms.service().isStranger(islandId)) {
                borderService.applyAllowance(islandId).ifPresent(moved -> {
                    protectionListener
                            .spatialIndex()
                            .indexIsland(moved.island(), moved.location().worldName());
                    redrawTheEdgeFor(moved);
                });
            }
        });
        economic.upgradeService().whenUpgraded((islandId, upgradeId, newTier) -> {
            if (!UpgradeId.SIZE.equals(upgradeId)) {
                return;
            }
            borderService.applyAllowance(islandId).ifPresent(moved -> {
                protectionListener
                        .spatialIndex()
                        .indexIsland(moved.island(), moved.location().worldName());
                redrawTheEdgeFor(moved);
            });
        });
    }

    /**
     * Shows the new edge to the members standing on the island when it grew.
     *
     * <p>Where a member is is a Bukkit question and the rule itself is not, so the rule lives in
     * its own class and this hands it the answer.
     */
    private void redrawTheEdgeFor(IslandBorderService.Moved moved) {
        var unused = new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeRedraw(
                        environment.boundaryService(), IslandEdgeWiring::whereTheyStand)
                .show(moved);
        moveTheWalls(moved);
    }
    /** Moves the walls drawn along an island's edge to where its edge moved. */
    private void moveTheWalls(IslandBorderService.Moved moved) {
        var after = moved.location().bounds();
        islandEdgeWalls.moved(
                moved.island().id(),
                moved.location().worldName(),
                com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(
                        after.centerX(), after.centerZ(), moved.fromRadius()),
                after);
    }
    /** Shows everybody standing on {@code island} its edge the new way, each on their own thread. */
    private void refreshTheBorderOn(
            com.uxplima.uxmskyblock.core.domain.identity.IslandId island, IslandBoundaryListener border) {
        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(online.getUniqueId()), () -> {
                org.bukkit.Location at = online.getLocation();
                if (at != null
                        && protectionListener
                                .findIslandAt(at)
                                .map(found -> found.id().equals(island))
                                .orElse(false)) {
                    border.refresh(online);
                }
            });
        }
    }
    /** How each island's edge is drawn, as its members chose. */
    public com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges islandEdges() {
        return islandEdges;
    }
    /** The walls along the edges of islands that chose a wall. */
    public com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls islandEdgeWalls() {
        return islandEdgeWalls;
    }

    private static java.util.Optional<com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeRedraw.StandingAt>
            whereTheyStand(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid playerUuid) {
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(playerUuid.value());
        if (online == null) {
            return java.util.Optional.empty();
        }
        org.bukkit.Location standing = online.getLocation();
        if (standing == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeRedraw.StandingAt(
                online.getWorld().getName(), standing.getBlockX(), standing.getBlockZ()));
    }
}
