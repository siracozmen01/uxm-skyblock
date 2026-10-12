package com.uxplima.uxmskyblock.bukkit.bootstrap;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;

/**
 * Registers Bukkit event listeners for Skyblock subsystems based on enabled feature modules and configurations.
 */
public final class BootstrapEventRegistrar {

    private BootstrapEventRegistrar() {}

    public static void registerEvents(
            PluginManager pm,
            JavaPlugin plugin,
            FeatureModuleWiring featureModuleWiring,
            GameplayWiring gameplayWiring,
            AuthorityWiring authorityWiring,
            ConfigurationWiring configWiring) {
        CatalogPermissions.registerAll(pm);
        // What a player carries is kept apart for each group of worlds, as modules/player-state.conf says.
        var scheduler = gameplayWiring.scheduler();
        java.util.concurrent.atomic.AtomicReference<com.uxplima.uxmskyblock.bukkit.state.PlayerStateListener> carried =
                new java.util.concurrent.atomic.AtomicReference<>();
        var coordinator = authorityWiring.sessionCoordinator();
        com.uxplima.uxmskyblock.bukkit.state.PlayerStates states =
                new com.uxplima.uxmskyblock.bukkit.state.PlayerStates(
                        configWiring::carriedStateConfig,
                        () -> presetWorlds(configWiring),
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(),
                        new com.uxplima.uxmskyblock.bukkit.state.ServerAdvancements(),
                        authorityWiring.carriedStates(),
                        new com.uxplima.uxmskyblock.bukkit.state.PlayerStates.Sessions() {
                            @Override
                            public com.uxplima.uxmskyblock.bukkit.session.@org.jspecify.annotations.Nullable ActiveSession session(java.util.UUID player) {
                                return coordinator.getActiveSession(player);
                            }

                            @Override
                            public boolean inPlay(java.util.UUID player) {
                                return coordinator.inPlay(player);
                            }

                            @Override
                            public com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node() {
                                return coordinator.nodeId();
                            }
                        },
                        scheduler);
        // A seal from before the session is what the session wrote and has just given back.
        coordinator.whenSessionActive(com.uxplima.uxmskyblock.bukkit.creative.SealedInventory::forget);
        coordinator.whenSessionActive(states::sessionActive);
        coordinator.whenProfileLeft((player, profile) -> states.left(player));
        carried.set(new com.uxplima.uxmskyblock.bukkit.state.PlayerStateListener(
                states,
                player -> scheduler.onEntity(
                        player.getUniqueId(),
                        () -> java.util.Objects.requireNonNull(carried.get()).arrive(player))));
        pm.registerEvents(carried.get(), plugin);
        // Folia moves players between worlds without telling, so every player is looked at once a second.
        scheduler.repeatGlobal(
                () -> {
                    for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
                        scheduler.onEntity(
                                online.getUniqueId(),
                                () -> java.util.Objects.requireNonNull(carried.get())
                                        .arrive(online));
                    }
                },
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1));
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("core")) {
            pm.registerEvents(gameplayWiring.protectionListener(), plugin);
            pm.registerEvents(gameplayWiring.playerLifecycle(), plugin);
            gameplayWiring.arrivalWatch().observe(gameplayWiring.playerLifecycle());
            // The island permissions the protection rules never asked for. They belong with the
            // protection listener, not behind a switch for something else.
            pm.registerEvents(gameplayWiring.actionPermissionListener(), plugin);
            pm.registerEvents(authorityWiring.sessionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("chat") && gameplayWiring.chatListener() != null) {
            pm.registerEvents(gameplayWiring.chatListener(), plugin);
        }
        // The wall along the edge of an island that chose one.
        pm.registerEvents(gameplayWiring.islandEdgeWalls(), plugin);
        if (gameplayWiring.chunkBlockWiring().enabled()) {
            pm.registerEvents(gameplayWiring.chunkBlockWiring().listener(), plugin);
            var walls = gameplayWiring.chunkBlockWiring().walls();
            if (walls != null) {
                pm.registerEvents(walls, plugin);
            }
            var signs = gameplayWiring.chunkBlockWiring().signs();
            if (signs != null) {
                pm.registerEvents(signs, plugin);
            }
            gameplayWiring
                    .arrivalWatch()
                    .admit(gameplayWiring.chunkBlockWiring().listener());
        }
        if (gameplayWiring.boxedWiring().enabled()) {
            pm.registerEvents(gameplayWiring.boxedWiring().listener(), plugin);
        }
        // Whatever mode sealed a player, what they hold stays where it was made.
        pm.registerEvents(new com.uxplima.uxmskyblock.bukkit.creative.SealedInventoryGuard(), plugin);
        if (gameplayWiring.brixWiring().enabled()) {
            pm.registerEvents(gameplayWiring.brixWiring().modes(), plugin);
            pm.registerEvents(gameplayWiring.brixWiring().rules(), plugin);
        }
        if (gameplayWiring.tradeWiring().enabled()) {
            pm.registerEvents(gameplayWiring.tradeWiring().listener(), plugin);
            pm.registerEvents(gameplayWiring.tradeWiring().holdStill(), plugin);
        }
        if (gameplayWiring.tradeWindsWiring().enabled()) {
            pm.registerEvents(gameplayWiring.tradeWindsWiring().listener(), plugin);
            pm.registerEvents(gameplayWiring.tradeWindsWiring().holdStill(), plugin);
        }
        if (gameplayWiring.parkourWiring().enabled()) {
            pm.registerEvents(gameplayWiring.parkourWiring().runs(), plugin);
            pm.registerEvents(gameplayWiring.parkourWiring().modes(), plugin);
        }
        if (gameplayWiring.strangerRealmsWiring().enabled()) {
            pm.registerEvents(gameplayWiring.strangerRealmsWiring().spawns(), plugin);
            pm.registerEvents(gameplayWiring.strangerRealmsWiring().reach(), plugin);
            if (gameplayWiring.strangerRealmsWiring().config().compass().enabled()) {
                pm.registerEvents(gameplayWiring.strangerRealmsWiring().compass(), plugin);
                gameplayWiring.strangerRealmsWiring().addRecipe(plugin.getServer());
            }
            if (gameplayWiring.strangerRealmsWiring().config().glimmer().enabled()) {
                pm.registerEvents(gameplayWiring.strangerRealmsWiring().glimmer(), plugin);
            }
        }
        if (gameplayWiring.acidIslandWiring().enabled()) {
            pm.registerEvents(gameplayWiring.acidIslandWiring().waterListener(), plugin);
            gameplayWiring.acidIslandWiring().addRecipes(plugin.getServer());
        }
        if (gameplayWiring.oneBlockWiring().enabled()) {
            pm.registerEvents(gameplayWiring.oneBlockWiring().listener(), plugin);
        }
        if (gameplayWiring.vaultListener() != null) {
            pm.registerEvents(gameplayWiring.vaultListener(), plugin);
        }
        {
            pm.registerEvents(gameplayWiring.notificationListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("social")) {
            pm.registerEvents(gameplayWiring.visitRecorder(), plugin);
            gameplayWiring.arrivalWatch().observe(gameplayWiring.visitRecorder());
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("missions")
                && gameplayWiring.missionListener() != null) {
            pm.registerEvents(gameplayWiring.missionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("boundary")
                && gameplayWiring.boundaryListener() != null) {
            pm.registerEvents(gameplayWiring.boundaryListener(), plugin);
            gameplayWiring.arrivalWatch().admit(gameplayWiring.boundaryListener());
            gameplayWiring.arrivalWatch().observe(gameplayWiring.boundaryListener());
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("worth") && gameplayWiring.worthListener() != null) {
            pm.registerEvents(gameplayWiring.worthListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("dimensions")
                && gameplayWiring.dimensionListener() != null) {
            pm.registerEvents(gameplayWiring.dimensionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("limits") && gameplayWiring.limitListener() != null) {
            pm.registerEvents(gameplayWiring.limitListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("anti-abuse")
                && gameplayWiring.antiAbuseListener() != null) {
            pm.registerEvents(gameplayWiring.antiAbuseListener(), plugin);
            gameplayWiring.arrivalWatch().admit(gameplayWiring.antiAbuseListener());
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("boosters")
                && gameplayWiring.boosterListener() != null) {
            pm.registerEvents(gameplayWiring.boosterListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("bank-upkeep")
                && gameplayWiring.bankruptcyListener() != null) {
            pm.registerEvents(gameplayWiring.bankruptcyListener(), plugin);
            gameplayWiring.arrivalWatch().admit(gameplayWiring.bankruptcyListener());
        }
        if (configWiring.protectionConfig().obsidianRecoveryEnabled()
                && configWiring.settingsConfig().obsidianToLava()) {
            pm.registerEvents(gameplayWiring.obsidianRecoveryListener(), plugin);
        }
        if (configWiring.protectionConfig().voidRecoveryEnabled()
                && (configWiring.settingsConfig().voidTeleportMembers()
                        || configWiring.settingsConfig().voidTeleportVisitors())) {
            pm.registerEvents(gameplayWiring.voidProtectionListener(), plugin);
        }
        if (!configWiring.interactablesConfig().isEmpty()) {
            pm.registerEvents(gameplayWiring.categoricalInteractablesListener(), plugin);
        }
        if (configWiring.protectionConfig().kineticWardEnabled()) {
            pm.registerEvents(gameplayWiring.kineticWardListener(), plugin);
            gameplayWiring.arrivalWatch().observe(gameplayWiring.kineticWardListener());
        }
        if (configWiring.settingsConfig().disableRedstoneOffline()) {
            pm.registerEvents(gameplayWiring.redstoneOptimizationListener(), plugin);
        }
        if (!configWiring.worldConfig().suppressedStructures().isEmpty()) {
            pm.registerEvents(gameplayWiring.structureSuppressionListener(), plugin);
        }
        // Every rule about arrivals is in; the watch hears teleports on Paper and looks for them on Folia.
        pm.registerEvents(gameplayWiring.arrivalWatch(), plugin);
        gameplayWiring.startArrivals();
    }

    /** The worlds the presets make islands in: each its own group of what a player carries, unless the file says. */
    static java.util.List<String> presetWorlds(ConfigurationWiring configWiring) {
        String islandWorld = configWiring.nodeConfig().worldName();
        return configWiring.presetConfig().presets().stream()
                .map(preset -> preset.worldOr(islandWorld))
                .distinct()
                .toList();
    }
}
