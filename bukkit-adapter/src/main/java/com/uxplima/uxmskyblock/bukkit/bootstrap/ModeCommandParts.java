package com.uxplima.uxmskyblock.bukkit.bootstrap;

import com.uxplima.uxmskyblock.core.application.visit.IslandVisitFeeService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * The parts of the command tree that only exist with a game mode or module on: the mode admin commands and the visit fee. Split out of {@link IslandCommandWiring}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ModeCommandParts {

    private ModeCommandParts() {}

    /** {@code /isadmin}: a branch for each mode this server runs. */
    static com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands modeAdmin(
            ConfigurationWiring config,
            GameplayWiring gameplay,
            AuthorityWiring authority,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages) {
        java.util.List<com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands.Mode> modes =
                new java.util.ArrayList<>();
        if (gameplay.oneBlockWiring().enabled()) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockAdmin(
                    gameplay.oneBlockWiring().service(),
                    gameplay.oneBlockWiring().draws(),
                    gameplay.oneBlockWiring().biomes()));
        }
        if (gameplay.chunkBlockWiring().enabled()) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockAdmin(
                    gameplay.chunkBlockWiring().service()));
        }
        if (gameplay.tradeWindsWiring().enabled()) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.tradewinds.TradeWindsAdmin(
                    gameplay.tradeWindsWiring().market(),
                    gameplay.tradeWindsWiring().service(),
                    gameplay.tradeWindsWiring().config()));
        }
        if (gameplay.strangerRealmsWiring().enabled()) {
            modes.add(gameplay.strangerRealmsWiring().admin());
        }
        if (gameplay.parkourWiring().enabled()) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.parkour.ParkourAdmin(
                            gameplay.parkourWiring().service(),
                            name -> java.util.Optional.ofNullable(org.bukkit.Bukkit.getOfflinePlayerIfCached(name))
                                    .map(org.bukkit.OfflinePlayer::getUniqueId))
                    .seasons(gameplay.parkourWiring().seasons()));
        }
        if (gameplay.brixWiring().enabled()) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.brix.BrixAdmin(
                    gameplay.brixWiring().contests()));
        }
        // Leaderboard displays serve every mode, as the boards do.
        if (gameplay.topDisplays() != null) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.top.TopAdmin(
                    java.util.Objects.requireNonNull(gameplay.topDisplays())));
        }
        // Loot tables serve every mode, so their branch is always there.
        modes.add(new com.uxplima.uxmskyblock.bukkit.loot.LootAdmin(config::lootTables));
        if (gameplay.boxedWiring().enabled()) {
            modes.add(new com.uxplima.uxmskyblock.bukkit.boxed.BoxedAdmin(
                    gameplay.boxedWiring().service(), gameplay.boxedWiring().edgeMover()));
        }
        return new com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands(
                modes, gameplay.locationService(), authority.sessionCoordinator(), gameplay.scheduler(), messages);
    }
    /** {@code /is visitfee} and the fee {@code /is visit} asks for, paid from the wallet into the island bank. */
    static com.uxplima.uxmskyblock.bukkit.visitor.VisitFees visitFees(
            IntegrationWiring integration,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            GameplayWiring gameplay,
            ServerNodeId serverNodeId) {
        var economy = integration.economyBridge();
        var bank = gameplay.bankService();
        var locations = gameplay.locationService();
        return new com.uxplima.uxmskyblock.bukkit.visitor.VisitFees(
                new IslandVisitFeeService(persistence.visitFeeStoragePort(), gameplay.accessService()),
                locations::findIsland,
                locations::findIslandId,
                new com.uxplima.uxmskyblock.bukkit.visitor.VisitFees.Wallet() {
                    @Override
                    public boolean available() {
                        return economy.isEconomyAvailable();
                    }

                    @Override
                    public boolean take(org.bukkit.entity.Player player, double amount) {
                        return economy.withdrawWallet(player, amount);
                    }

                    @Override
                    public boolean give(org.bukkit.entity.Player player, double amount) {
                        return economy.depositWallet(player, amount);
                    }

                    @Override
                    public String format(double amount) {
                        return economy.format(amount);
                    }
                },
                (island, payer, minorUnits) -> com.uxplima.uxmskyblock.core.application.bank.IslandBankService.landed(
                        bank.depositToIsland(island, payer, minorUnits, "Visit fee", serverNodeId)),
                () -> config.visitorsConfig().mostFee(),
                gameplay.scheduler(),
                config.messages(),
                java.time.Clock.systemUTC());
    }

    /**
     * A ChunkBlock island's chunks, as the operator's menu file draws them: the panel, the unlock paid out of what
     * players carry and the island's bank, the window of each chunk's price, and the same for the island's Nether
     * and End. Null while ChunkBlock is off.
     */
    static com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable ChunkBlockPanel chunkBlock(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay,
            IntegrationWiring integration) {
        var chunkBlockPanel = gameplay.chunkBlockWiring()
                .panel(
                        integration.menuEngine(),
                        persistence.islandStoragePort(),
                        gameplay.scheduler(),
                        integration.messages(),
                        authority.sessionCoordinator()::activeProfile,
                        (islandId, profile) -> IslandCommandWiring.levelOf(gameplay, islandId, profile));
        if (chunkBlockPanel != null) {
            // Chunks are paid for by their grades, out of what players carry and the island's bank.
            var purchase = gameplay.chunkBlockWiring()
                    .purchase(
                            persistence,
                            gameplay.bankService(),
                            integration.economyBridge(),
                            gameplay.accessService(),
                            gameplay.scheduler(),
                            integration.messages(),
                            authority.sessionCoordinator()::activeProfile,
                            (islandId, profile) -> IslandCommandWiring.levelOf(gameplay, islandId, profile),
                            config.nodeConfig().nodeId());
            chunkBlockPanel.usePurchase(purchase);
            // Each closed chunk the island can open next, as a window of its price.
            var chunkWindow = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkWindow(
                    purchase,
                    gameplay.chunkBlockWiring().service(),
                    persistence.islandStoragePort(),
                    gameplay.scheduler(),
                    integration.messages(),
                    id -> integration.economyBridge().wallets().flatMap(wallets -> wallets.currency(id)),
                    authority.sessionCoordinator()::activeProfile,
                    (islandId, profile) -> IslandCommandWiring.levelOf(gameplay, islandId, profile));
            chunkWindow.useMenuEngine(integration.menuEngine());
            chunkBlockPanel.useWindow(chunkWindow);
            gameplay.chunkBlockWiring().useWindow(chunkWindow);
            // The island's Nether and End each pay for their own chunks, in a panel and windows of their own.
            java.util.Map<String, com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockPanel> panels =
                    new java.util.HashMap<>();
            java.util.Map<String, com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkWindow> windows =
                    new java.util.HashMap<>();
            for (ChunkBlockWiring.Web web : gameplay.chunkBlockWiring().dimensions()) {
                var panelThere = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockPanel(
                        web.service(),
                        persistence.islandStoragePort(),
                        gameplay.scheduler(),
                        integration.messages(),
                        authority.sessionCoordinator()::activeProfile,
                        (islandId, profile) -> IslandCommandWiring.levelOf(gameplay, islandId, profile));
                panelThere.useMenuEngine(integration.menuEngine());
                var purchaseThere = gameplay.chunkBlockWiring()
                        .purchase(
                                web,
                                persistence,
                                gameplay.bankService(),
                                integration.economyBridge(),
                                gameplay.accessService(),
                                gameplay.scheduler(),
                                integration.messages(),
                                authority.sessionCoordinator()::activeProfile,
                                (islandId, profile) -> IslandCommandWiring.levelOf(gameplay, islandId, profile),
                                config.nodeConfig().nodeId());
                panelThere.usePurchase(purchaseThere);
                var windowThere = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkWindow(
                        purchaseThere,
                        web.service(),
                        persistence.islandStoragePort(),
                        gameplay.scheduler(),
                        integration.messages(),
                        id -> integration.economyBridge().wallets().flatMap(wallets -> wallets.currency(id)),
                        authority.sessionCoordinator()::activeProfile,
                        (islandId, profile) -> IslandCommandWiring.levelOf(gameplay, islandId, profile));
                // The overworld's window answers the engine's clicks and hands this one the players below.
                windowThere.drawWith(integration.menuEngine());
                panelThere.useWindow(windowThere);
                web.window().set(windowThere::show);
                panels.put(web.world(), panelThere);
                windows.put(web.world(), windowThere);
            }
            chunkBlockPanel.useElsewhere(world -> java.util.Optional.ofNullable(panels.get(world)));
            chunkWindow.useElsewhere(world -> java.util.Optional.ofNullable(windows.get(world)));
        }
        return chunkBlockPanel;
    }
}
