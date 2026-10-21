package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LimitConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PerformanceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ShopConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SocialConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.chat.LocalIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class BackupRestoreWiringTest {

    @Test
    @DisplayName("PersistenceWiring resolves LocalFilesystemStorageAdapter when no storage type configured")
    void persistenceWiringResolvesLocalStorageByDefault(@TempDir Path tempDir) {
        PersistenceWiring wiring = PersistenceWiring.resolve(null, tempDir);
        try {
            assertThat(wiring.objectStoragePort()).isInstanceOf(LocalFilesystemStorageAdapter.class);
            assertThat(wiring.storagePort()).isInstanceOf(LocalFilesystemStorageAdapter.class);
        } finally {
            wiring.close();
        }
    }

    @Test
    @DisplayName("PersistenceWiring resolves S3ObjectStorageAdapter when s3 is configured")
    void persistenceWiringResolvesS3StorageWhenConfigured(@TempDir Path tempDir) throws Exception {
        String hocon = """
                database {
                  type = "sqlite"
                }
                storage {
                  type = "s3"
                  s3 {
                    endpoint = "https://s3.us-east-1.amazonaws.com"
                    region = "us-east-1"
                    bucket = "my-skyblock-backups"
                    access-key = "test-access-key"
                    secret-key = "test-secret-key"
                    addressing-mode = "PATH"
                    provider-target = "AWS_S3"
                  }
                }
                """;
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        PersistenceWiring wiring = PersistenceWiring.resolve(root, tempDir);
        try {
            assertThat(wiring.objectStoragePort()).isInstanceOf(S3ObjectStorageAdapter.class);
        } finally {
            wiring.close();
        }
    }

    @Test
    @DisplayName("A mirrored storage type writes to the local folder and the bucket both, and publishes to each")
    void persistenceWiringResolvesAMirror(@TempDir Path tempDir) throws Exception {
        String hocon = """
                database {
                  type = "sqlite"
                }
                storage {
                  type = "mirrored"
                  local-path = "mirror-backups"
                  s3 {
                    endpoint = "https://s3.us-east-1.amazonaws.com"
                    region = "us-east-1"
                    bucket = "my-skyblock-backups"
                    access-key = "test-access-key"
                    secret-key = "test-secret-key"
                  }
                }
                """;
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        PersistenceWiring wiring = PersistenceWiring.resolve(root, tempDir);
        try {
            assertThat(wiring.objectStoragePort())
                    .isInstanceOf(com.uxplima.uxmskyblock.core.application.storage.MirroredObjectStorage.class);
            assertThat(com.uxplima.uxmskyblock.core.application.storage.MirroredObjectStorage.destinationsOf(
                            wiring.objectStoragePort()))
                    .hasSize(2)
                    .hasAtLeastOneElementOfType(LocalFilesystemStorageAdapter.class)
                    .hasAtLeastOneElementOfType(S3ObjectStorageAdapter.class);
        } finally {
            wiring.close();
        }
    }

    @Test
    @DisplayName("GameplayWiring wires ObjectStoragePort, BackupService, and IslandRestoreService")
    void gameplayWiringWiresBackupAndRestoreServices(@TempDir Path tempDir) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        // A real plugin always has a logger; the hologram fallback writes to it on a test server.
        when(plugin.getSLF4JLogger()).thenReturn(org.slf4j.LoggerFactory.getLogger("uxmSkyblock"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        String hocon = """
                modules {
                  core = true
                  bank = true
                  upgrades = true
                  biomes = false
                  seasons = false
                  social = false
                  discord = false
                  warps = false
                  vault = false
                  chat = false
                  inactivity = false
                  missions = false
                  boundary = false
                  "bank-upkeep" = false
                  worth = false
                  limits = false
                  "anti-abuse" = false
                  boosters = false
                  dimensions = false
                  presets = false
                  recycle = true
                  alliances = false
                  shop = false
                  "temporary-access" = false
                  "reward-inbox" = false
                  freeze = false
                }
                """;
        ConfigurationNode moduleNode = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        ModuleSettingsConfiguration moduleSettings = ModuleSettingsConfiguration.load(moduleNode);

        ConfigurationWiring config = mock(ConfigurationWiring.class);
        when(config.moduleSettings()).thenReturn(moduleSettings);
        when(config.messages()).thenReturn(Messages.bundled());
        when(config.homeConfig()).thenReturn(HomeConfiguration.defaults());
        when(config.nodeConfig()).thenReturn(ServerNodeConfiguration.of(ServerNodeId.of("node-1"), "world", false, ""));

        SocialConfiguration social = mock(SocialConfiguration.class);
        when(config.socialConfig()).thenReturn(social);

        UpgradesConfiguration upgrades = mock(UpgradesConfiguration.class);
        when(upgrades.definitions()).thenReturn(Map.of());
        when(config.upgradesConfig()).thenReturn(upgrades);

        ShopConfiguration shopConfig = mock(ShopConfiguration.class);
        when(shopConfig.dampingFactor()).thenReturn(0.5);
        when(config.shopConfig()).thenReturn(shopConfig);

        WarpConfiguration warp = mock(WarpConfiguration.class);
        when(warp.baseWarpLimit()).thenReturn(5);
        when(warp.searchRadius()).thenReturn(10);
        when(config.warpConfig()).thenReturn(warp);

        AllianceConfiguration alliance = mock(AllianceConfiguration.class);
        when(config.allianceConfig()).thenReturn(alliance);

        when(config.presetConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration.defaultConfiguration());

        when(config.rewardConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.RewardInboxConfiguration.defaultConfiguration());

        VaultConfiguration vault = mock(VaultConfiguration.class);
        when(vault.basePages()).thenReturn(1);
        when(vault.maxPages()).thenReturn(10);
        when(vault.leaseDuration()).thenReturn(Duration.ofMinutes(5));
        when(vault.auditLogLimit()).thenReturn(50);
        when(config.vaultConfig()).thenReturn(vault);

        ChatConfiguration chat = mock(ChatConfiguration.class);
        when(config.chatConfig()).thenReturn(chat);

        InactivityConfiguration inactivity = mock(InactivityConfiguration.class);
        when(inactivity.toPolicy()).thenReturn(InactivityPolicy.defaultPolicy());
        when(config.inactivityConfig()).thenReturn(inactivity);

        MissionConfiguration mission = mock(MissionConfiguration.class);
        when(mission.missions()).thenReturn(java.util.List.of());
        when(config.missionConfig()).thenReturn(mission);

        SettingsConfiguration settings = mock(SettingsConfiguration.class);
        when(config.settingsConfig()).thenReturn(settings);

        LevelConfiguration levelConfig = mock(LevelConfiguration.class);
        when(levelConfig.blockWeights()).thenReturn(Map.of());
        when(levelConfig.spawnerWeights()).thenReturn(Map.of());
        when(levelConfig.basePricesMinorUnits()).thenReturn(Map.of());
        when(levelConfig.dampingFactor()).thenReturn(0.5);
        when(levelConfig.pointsPerLevel()).thenReturn(100L);
        when(config.levelConfig()).thenReturn(levelConfig);

        DimensionConfiguration dimension = mock(DimensionConfiguration.class);
        when(dimension.mappings()).thenReturn(Map.of());
        when(config.dimensionConfig()).thenReturn(dimension);

        LimitConfiguration limit = mock(LimitConfiguration.class);
        when(limit.quotas())
                .thenReturn(LimitConfiguration.defaultConfiguration().quotas());
        when(limit.bypassPermission()).thenReturn("uxmskyblock.bypass.limits");
        when(config.limitConfig()).thenReturn(limit);

        AntiAbuseConfiguration antiAbuse = mock(AntiAbuseConfiguration.class);
        when(antiAbuse.quarantineDuration()).thenReturn(Duration.ofHours(1));
        when(antiAbuse.resetCooldown()).thenReturn(Duration.ofHours(1));
        when(antiAbuse.resetWindowDuration()).thenReturn(Duration.ofDays(1));
        when(antiAbuse.coopJoinCooldown()).thenReturn(Duration.ofHours(1));
        when(config.antiAbuseConfig()).thenReturn(antiAbuse);

        BoosterConfiguration booster = mock(BoosterConfiguration.class);
        when(config.boosterConfig()).thenReturn(booster);

        BankConfiguration bank = mock(BankConfiguration.class);
        when(bank.upkeepPolicy())
                .thenReturn(com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy.defaultPolicy());
        when(config.bankConfig()).thenReturn(bank);

        ProtectionConfiguration protection = mock(ProtectionConfiguration.class);
        when(protection.kineticWardRadius()).thenReturn(5.0);
        when(protection.kineticWardForce()).thenReturn(1.0);
        when(protection.kineticWardVerticalLift()).thenReturn(0.5);
        when(config.protectionConfig()).thenReturn(protection);

        PerformanceConfiguration performance = mock(PerformanceConfiguration.class);
        when(config.performanceConfig()).thenReturn(performance);

        InteractablesConfiguration interactables = mock(InteractablesConfiguration.class);
        when(config.interactablesConfig()).thenReturn(interactables);

        WorldConfiguration world = mock(WorldConfiguration.class);
        when(world.suppressedStructures()).thenReturn(Set.of());
        when(config.worldConfig()).thenReturn(world);

        GeneratorsConfiguration generatorsConfig = mock(GeneratorsConfiguration.class);
        when(generatorsConfig.tierRates()).thenReturn(Map.of());
        when(config.generatorsConfig()).thenReturn(generatorsConfig);
        when(config.oneBlockConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration.defaultConfiguration());
        when(config.lifecycleConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration.defaultConfiguration());
        when(config.chunkBlockConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration.defaultConfiguration());
        when(config.wallConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration.defaultConfiguration());
        when(config.flyConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration.defaultConfiguration());
        when(config.islandEffectsConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration.defaultConfiguration());
        when(config.parkourConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration.defaultConfiguration());
        when(config.brixConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration.defaultConfiguration());
        when(config.tradeConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration.defaultConfiguration());
        when(config.tradeWindsConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration.defaultConfiguration());
        when(config.strangerRealmsConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration.defaultConfiguration());
        when(config.poseidonConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration.defaultConfiguration());
        when(config.boxedConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration.defaultConfiguration());
        when(config.skyGridConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration.defaultConfiguration());
        when(config.caveBlockConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration.defaultConfiguration());
        when(config.acidIslandConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration.defaultConfiguration());
        when(config.effectsConfig()).thenReturn(com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects.none());

        try (PersistenceWiring persistenceWiring = PersistenceWiring.resolve(null, tempDir)) {
            PersistenceBootstrap persistence = persistenceWiring.bootstrap();

            AuthorityWiring authority = mock(AuthorityWiring.class);
            when(authority.serverNodeId())
                    .thenReturn(com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-test"));
            when(authority.profileTypes())
                    .thenReturn(new com.uxplima.uxmskyblock.core.application.profile.ProfileTypes(
                            mock(com.uxplima.uxmskyblock.core.application.profile.ProfileTypePort.class)));
            PlayerSessionCoordinator sessionCoord = mock(PlayerSessionCoordinator.class);
            when(authority.sessionCoordinator()).thenReturn(sessionCoord);
            when(authority.activeProfileProvider()).thenReturn(uuid -> java.util.Optional.empty());
            when(authority.nodeProcessIdentity())
                    .thenReturn(com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity.create("node-1"));

            IslandProtectionListener protectionListener = protectionListenerWithIndex();
            when(protectionListener.spatialIndex()).thenReturn(mock(SpatialIslandIndex.class));

            IslandAccessService accessService = mock(IslandAccessService.class);
            IslandAllianceService allianceService = mock(IslandAllianceService.class);
            TemporaryAccessService temporaryAccessService = mock(TemporaryAccessService.class);
            BukkitIslandVisitorEvictionAdapter evictionAdapter = mock(BukkitIslandVisitorEvictionAdapter.class);
            IslandAdminFreezeService freezeService = mock(IslandAdminFreezeService.class);
            SchedulerPort scheduler = mock(SchedulerPort.class);
            AdaptiveBackpressureController backpressureController = mock(AdaptiveBackpressureController.class);

            ObjectStoragePort customStorage = new LocalFilesystemStorageAdapter(tempDir.resolve("custom-backups"));

            GameplayWiring gameplay = new GameplayWiring(
                    plugin,
                    config,
                    persistence,
                    authority,
                    protectionListener,
                    accessService,
                    allianceService,
                    temporaryAccessService,
                    evictionAdapter,
                    freezeService,
                    scheduler,
                    backpressureController,
                    () -> null,
                    new LocalIslandChatTransportAdapter(),
                    customStorage,
                    new com.uxplima.uxmskyblock.core.domain.storage.StorageBucket("test-bucket"));

            assertThat(gameplay.objectStoragePort()).isSameAs(customStorage);
            assertThat(gameplay.worldDimensionSnapshotPort()).isNotNull();
            assertThat(gameplay.backupService()).isNotNull();
            assertThat(gameplay.islandRestoreService()).isNotNull();
            assertThat(gameplay.backupService().storageDestinations()).contains(customStorage);
        }
    }

    @Test
    @DisplayName("IslandCommandTree exposes restoreService and backupService accessors")
    void islandCommandTreeExposesBackupRestoreAccessors() {
        IslandCommandTree commandTree = new IslandCommandTree(
                mock(com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase.class),
                mock(com.uxplima.uxmskyblock.core.application.island.IslandLocationService.class),
                mock(com.uxplima.uxmskyblock.core.application.flag.IslandFlagService.class),
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankService.class),
                mock(com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort.class),
                mock(com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService.class),
                mock(com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort.class),
                new com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog(),
                mock(com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine.class),
                protectionListenerWithIndex(),
                mock(PlayerSessionCoordinator.class),
                mock(SchedulerPort.class),
                com.uxplima.uxmskyblock.bukkit.i18n.Messages.of(
                        new com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider("en"),
                        com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration.defaults()),
                com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");

        IslandRestoreService restoreService = mock(IslandRestoreService.class);
        BackupService backupService = mock(BackupService.class);

        commandTree.setRestoreService(restoreService);
        commandTree.setBackupService(backupService);

        assertThat(commandTree.restoreService()).isSameAs(restoreService);
        assertThat(commandTree.backupService()).isSameAs(backupService);
    }

    /**
     * A protection listener whose spatial index is real.
     *
     * <p>The command tree asks it where an island is, and a bare mock answers null: the tree then
     * refuses to build and the test fails for a reason that has nothing to do with what it checks.
     */
    private static IslandProtectionListener protectionListenerWithIndex() {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        return listener;
    }
}
