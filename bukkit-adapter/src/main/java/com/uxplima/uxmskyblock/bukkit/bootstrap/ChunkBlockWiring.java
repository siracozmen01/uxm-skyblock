package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockStart;
import com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The ChunkBlock game mode, while the operator lets islands be ChunkBlock islands. */
public final class ChunkBlockWiring {

    private static final Logger LOGGER = Logger.getLogger(ChunkBlockWiring.class.getName());

    private final ChunkBlockConfiguration config;
    private final ChunkBlockService service;
    private final com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener;
    private final com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable TerritoryWalls walls;

    public ChunkBlockWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands) {
        this.config = Objects.requireNonNull(configuration.chunkBlockConfig(), "chunkBlockConfig must not be null");
        this.service = new ChunkBlockService(persistence.chunkTerritoryPort(), config.rules());
        this.listener = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener(
                service,
                islands,
                scheduler,
                configuration.messages(),
                persistence.islandStoragePort()::findLocationByIslandId,
                config.bypassPermission());
        // A fallen level closes chunks off the main thread; whoever stands in one is moved out.
        service.whenClosed(listener::moveOut);
        // The open ground is walled where it meets closed ground, in the style modules/walls.conf names.
        var wallConfig = configuration.wallConfig();
        this.walls = wallConfig
                .chunkBlockStyle()
                .map(style -> new com.uxplima.uxmskyblock.bukkit.chunkblock.TerritoryWalls(
                        service,
                        islands,
                        scheduler,
                        new com.uxplima.uxmskyblock.bukkit.wall.WallPanels(wallConfig),
                        style,
                        islandId -> persistence
                                .islandStoragePort()
                                .findLocationByIslandId(islandId)
                                .map(com.uxplima.uxmskyblock.core.domain.island.IslandLocation::worldName)))
                .orElse(null);
        if (walls != null) {
            service.whenOpened(walls::opened);
            service.whenClosed(walls::closed);
        }
        // Every territory into memory before anybody steps near a closed chunk, so no step is a query.
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " ChunkBlock islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The ChunkBlock islands could not be read ahead.", e);
            }
        });
    }

    public ChunkBlockService service() {
        return service;
    }

    /**
     * The panel behind the chunks command and the ChunkBlock placeholders, drawn from the operator's
     * menu file, or nothing while the operator does not let islands be ChunkBlock islands.
     */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable ChunkBlockPanel panel(
            com.uxplima.uxmskyblock.bukkit.menu.@org.jspecify.annotations.Nullable SkyblockMenuEngine engine,
            com.uxplima.uxmskyblock.core.application.island.IslandStoragePort islands,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf) {
        if (!config.enabled()) {
            return null;
        }
        var panel = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockPanel(
                service, islands, scheduler, messages, activeProfile, levelOf);
        panel.useMenuEngine(engine);
        return panel;
    }

    /**
     * Paying for chunks by their grades: what players hand in, what the island's bank pays, and the rewards opening
     * one fires.
     */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurchase purchase(
            PersistenceBootstrap persistence,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankService bank,
            com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge economy,
            com.uxplima.uxmskyblock.core.application.island.IslandAccessService access,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node) {
        var unlocks = new com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks(
                service,
                persistence.chunkPaymentPort(),
                config.tiers(),
                persistence.islandBankPort(),
                bank,
                persistence.islandAuthorityPort());
        var purse = new com.uxplima.uxmskyblock.bukkit.chunkblock.PlayerChunkPurse(
                new com.uxplima.uxmskyblock.bukkit.chunkblock.PlayerChunkPurse.Money() {
                    @Override
                    public double balance(org.bukkit.entity.Player player) {
                        return economy.balance(player);
                    }

                    @Override
                    public boolean withdraw(org.bukkit.entity.Player player, double amount) {
                        return economy.withdrawWallet(player, amount);
                    }

                    @Override
                    public boolean deposit(org.bukkit.entity.Player player, double amount) {
                        return economy.depositWallet(player, amount);
                    }
                },
                id -> economy.wallets().flatMap(wallets -> wallets.currency(id)));
        var rewards = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkRewards(
                config.tiers(), new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, messages));
        return new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurchase(
                unlocks,
                persistence.islandStoragePort(),
                access,
                scheduler,
                messages,
                purse,
                rewards,
                activeProfile,
                levelOf,
                node);
    }

    /** The edge of every territory, registered while the operator lets islands be ChunkBlock islands. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener() {
        return listener;
    }

    /** The wall around every territory's open ground, while the operator names a style for it. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable TerritoryWalls walls() {
        return walls;
    }

    public ChunkBlockConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /**
     * The action that starts an island's territory, while ChunkBlock is enabled, and none otherwise,
     * so a ChunkBlock preset is not offered.
     */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new ChunkBlockStart(service)) : List.of();
    }
}
