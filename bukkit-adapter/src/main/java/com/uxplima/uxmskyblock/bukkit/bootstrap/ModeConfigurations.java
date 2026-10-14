package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

/**
 * The files of the game modes and of the rules around them: OneBlock, ChunkBlock, AcidIsland, CaveBlock, SkyGrid, Boxed,
 * Poseidon, StrangerRealms, Parkour, Brix, the player trade, TradeWinds, the web map and the lifecycle rules. Split out
 * of {@link ConfigurationWiring}, which extends this, so neither file is over the size the standards draw a line at;
 * every caller still asks the wiring.
 */
abstract class ModeConfigurations extends LateConfigurations {

    private final com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration oneBlockConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration lifecycleConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration chunkBlockConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration acidIslandConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration caveBlockConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration skyGridConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration boxedConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration poseidonConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration strangerRealmsConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration parkourConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration brixConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration tradeConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration tradeWindsConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration webMapConfig;

    ModeConfigurations(
            com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration oneBlockConfig,
            com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration lifecycleConfig,
            com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration chunkBlockConfig,
            com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration acidIslandConfig,
            com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration caveBlockConfig,
            com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration skyGridConfig,
            com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration boxedConfig,
            com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration poseidonConfig,
            com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration strangerRealmsConfig,
            com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration parkourConfig,
            com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration brixConfig,
            com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration tradeConfig,
            com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration tradeWindsConfig,
            com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration webMapConfig) {
        this.oneBlockConfig = Objects.requireNonNull(oneBlockConfig, "oneBlockConfig must not be null");
        this.lifecycleConfig = Objects.requireNonNull(lifecycleConfig, "lifecycleConfig must not be null");
        this.chunkBlockConfig = Objects.requireNonNull(chunkBlockConfig, "chunkBlockConfig must not be null");
        this.acidIslandConfig = Objects.requireNonNull(acidIslandConfig, "acidIslandConfig must not be null");
        this.caveBlockConfig = Objects.requireNonNull(caveBlockConfig, "caveBlockConfig must not be null");
        this.skyGridConfig = Objects.requireNonNull(skyGridConfig, "skyGridConfig must not be null");
        this.boxedConfig = Objects.requireNonNull(boxedConfig, "boxedConfig must not be null");
        this.poseidonConfig = Objects.requireNonNull(poseidonConfig, "poseidonConfig must not be null");
        this.strangerRealmsConfig =
                Objects.requireNonNull(strangerRealmsConfig, "strangerRealmsConfig must not be null");
        this.parkourConfig = Objects.requireNonNull(parkourConfig, "parkourConfig must not be null");
        this.brixConfig = Objects.requireNonNull(brixConfig, "brixConfig must not be null");
        this.tradeConfig = Objects.requireNonNull(tradeConfig, "tradeConfig must not be null");
        this.tradeWindsConfig = Objects.requireNonNull(tradeWindsConfig, "tradeWindsConfig must not be null");
        this.webMapConfig = Objects.requireNonNull(webMapConfig, "webMapConfig must not be null");
    }

    /** The OneBlock game mode's phases, as {@code modules/oneblock.conf} writes them. */
    public com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration oneBlockConfig() {
        return oneBlockConfig;
    }
    /** What a leave, a kick, a death and a reset do, as {@code modules/lifecycle.conf} writes it. */
    public com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration lifecycleConfig() {
        return lifecycleConfig;
    }
    /** Whether islands can be ChunkBlock islands, and what each chunk costs in level. */
    public com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration chunkBlockConfig() {
        return chunkBlockConfig;
    }
    /** Whether islands can be AcidIsland islands, their sea, the hazard and clean water. */
    public com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration acidIslandConfig() {
        return acidIslandConfig;
    }
    /** Whether islands can be CaveBlock islands, the shape of their rock and the rock of each dimension. */
    public com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration caveBlockConfig() {
        return caveBlockConfig;
    }
    /** Whether islands can be SkyGrid islands, where the grid's blocks stand and which blocks they are. */
    public com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration skyGridConfig() {
        return skyGridConfig;
    }
    /** Whether islands can be Boxed islands, how big a box starts and what each advancement is worth. */
    public com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration boxedConfig() {
        return boxedConfig;
    }
    /** Whether islands can be Poseidon islands, and the ocean they lie at the bottom of. */
    public com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration poseidonConfig() {
        return poseidonConfig;
    }
    /** Whether islands can be StrangerRealms islands, and how the Upside Down mirrors them. */
    public com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration strangerRealmsConfig() {
        return strangerRealmsConfig;
    }
    /** Whether islands can be Parkour courses, what marks a course and how a run is kept. */
    public com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration parkourConfig() {
        return parkourConfig;
    }
    /** Whether islands can be Brix plots, and the ground a plot is laid on. */
    public com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration brixConfig() {
        return brixConfig;
    }
    /** Whether players may trade, and how a trade window looks. */
    public com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration tradeConfig() {
        return tradeConfig;
    }
    /** Whether islands can be TradeWinds vessels, and the sea a vessel is launched on. */
    public com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration tradeWindsConfig() {
        return tradeWindsConfig;
    }

    public com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration webMapConfig() {
        return webMapConfig;
    }
}
