package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

/**
 * The configurations the loader hands over after the wiring is built, each the shipped one until then: the files a
 * module added after the first set, read once the rest are. Split out of {@link ConfigurationWiring}, which extends
 * this, so neither file is over the size the standards draw a line at; every caller still asks the wiring.
 */
abstract class LateConfigurations {

    /** The islands' potion effects, {@code modules/island-effects.conf}, shipped until the loader hands it over. */
    volatile com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration islandEffectsConfig =
            com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration.defaultConfiguration();

    /** What an island bank pays out, from {@code modules/bank.conf}, the shipped one until the loader reads it. */
    volatile com.uxplima.uxmskyblock.bukkit.config.BankPayoutsConfiguration bankPayoutsConfig =
            com.uxplima.uxmskyblock.bukkit.config.BankPayoutsConfiguration.defaultConfiguration();

    /** Block stacking, {@code modules/stacking.conf}, the shipped one until the loader hands the file over. */
    volatile com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration stackingConfig =
            com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration.defaultConfiguration();

    /** Sign warps, {@code warps.sign} in {@code modules/warps.conf}, the shipped one until the loader reads it. */
    volatile com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration signWarpsConfig =
            com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration.defaultConfiguration();

    /** What visitors may not do, {@code modules/visitors.conf}, the shipped one until the loader reads it. */
    volatile com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration visitorsConfig =
            com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration.defaultConfiguration();

    /** Flight on islands, {@code modules/fly.conf}, the shipped one until the loader hands the file over. */
    volatile com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration flyConfig =
            com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration.defaultConfiguration();

    /** The walls, {@code modules/walls.conf}, the shipped ones until the loader hands the file over. */
    volatile com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration wallConfig =
            com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration.defaultConfiguration();

    /** What a player carries in each group of worlds, {@code modules/player-state.conf}, shipped until handed over. */
    volatile com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration carriedStateConfig =
            com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration.defaultConfiguration();

    /** The loot tables under {@code loot/}, none until handed over. */
    volatile com.uxplima.uxmskyblock.bukkit.loot.LootTables lootTables =
            com.uxplima.uxmskyblock.bukkit.loot.LootTables.none();

    public com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration islandEffectsConfig() {
        return islandEffectsConfig;
    }
    /** Hands over {@code modules/island-effects.conf} as the server read it. */
    public void useIslandEffectsConfig(com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration config) {
        this.islandEffectsConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.config.BankPayoutsConfiguration bankPayoutsConfig() {
        return bankPayoutsConfig;
    }
    /** Hands over the interest and delete refund of {@code modules/bank.conf} as the server read them. */
    public void useBankPayoutsConfig(com.uxplima.uxmskyblock.bukkit.config.BankPayoutsConfiguration config) {
        this.bankPayoutsConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration stackingConfig() {
        return stackingConfig;
    }
    /** Hands over {@code modules/stacking.conf} as the server read it. */
    public void useStackingConfig(com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration config) {
        this.stackingConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration signWarpsConfig() {
        return signWarpsConfig;
    }
    /** Hands over {@code warps.sign} as the server read it. */
    public void useSignWarpsConfig(com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration config) {
        this.signWarpsConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration visitorsConfig() {
        return visitorsConfig;
    }
    /** Hands over {@code modules/visitors.conf} as the server read it. */
    public void useVisitorsConfig(com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration config) {
        this.visitorsConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration flyConfig() {
        return flyConfig;
    }
    /** Hands over {@code modules/fly.conf} as the server read it. */
    public void useFlyConfig(com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration config) {
        this.flyConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration wallConfig() {
        return wallConfig;
    }
    /** Hands over {@code modules/walls.conf} as the server read it. */
    public void useWallConfig(com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration config) {
        this.wallConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration carriedStateConfig() {
        return carriedStateConfig;
    }
    /** Hands over {@code modules/player-state.conf} as the server read it. */
    public void useCarriedStateConfig(com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration config) {
        this.carriedStateConfig = Objects.requireNonNull(config, "config must not be null");
    }

    public com.uxplima.uxmskyblock.bukkit.loot.LootTables lootTables() {
        return lootTables;
    }
    /** Hands over the loot tables as the server read them. */
    public void useLootTables(com.uxplima.uxmskyblock.bukkit.loot.LootTables tables) {
        this.lootTables = Objects.requireNonNull(tables, "tables must not be null");
    }
}
