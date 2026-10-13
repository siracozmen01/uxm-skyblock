package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.bukkit.plugin.java.JavaPlugin;

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
import com.uxplima.uxmskyblock.core.application.chat.LocalIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class ZeroFootprintModuleWiringTest {

    @Test
    @DisplayName("FeatureModuleWiring omits registration and instantiation of disabled modules")
    void featureModuleWiringOmitsDisabledModules() throws Exception {
        String hocon = """
                modules {
                  core = true
                  bank = true
                  upgrades = false
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
                  recycle = false
                  alliances = false
                  shop = false
                  leaderboards = false
                  "temporary-access" = false
                  rewards = false
                  "admin-freeze" = false
                }
                """;
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        ModuleSettingsConfiguration moduleSettings = ModuleSettingsConfiguration.load(root);

        ConfigurationWiring config = mock(ConfigurationWiring.class);
        when(config.moduleSettings()).thenReturn(moduleSettings);
        when(config.messages()).thenReturn(Messages.bundled());
        when(config.homeConfig()).thenReturn(HomeConfiguration.defaults());
        when(config.nodeConfig()).thenReturn(ServerNodeConfiguration.of(ServerNodeId.of("node-1"), "world", false, ""));
        when(config.bankConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.BankConfiguration.defaultConfiguration());

        PersistenceBootstrap persistence = mock(PersistenceBootstrap.class);

        when(persistence.oneBlockProgressPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort.class));
        when(persistence.lifecycleOwedEffectsPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleOwedEffectsPort.class));
        GameplayWiring gameplay = mock(GameplayWiring.class);
        when(gameplay.createIslandUseCase())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase.class));
        when(gameplay.bankService())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankService.class));
        IntegrationWiring integration = mock(IntegrationWiring.class);

        try (FeatureModuleWiring wiring = new FeatureModuleWiring(config, persistence, gameplay, integration)) {
            assertThat(wiring.bankUpkeepFeatureModule()).isNull();
            assertThat(wiring.moduleRegistry().findModule("bank-upkeep")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("chat")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("missions")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("boundary")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("worth")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("limits")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("anti-abuse")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("boosters")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("dimensions")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("upgrades")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("seasons")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("social")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("discord")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("warps")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("vault")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("inactivity")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("presets")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("recycle")).isEmpty();

            // These two are written under the names the shipped module file publishes. This test
            // used to write the names the code asked for instead, which is why the two drifting
            // apart was invisible here: the file said admin-freeze and rewards, the code asked for
            // freeze and reward-inbox, and an unknown module is treated as on.
            assertThat(wiring.moduleRegistry().findModule("admin-freeze")).isEmpty();
            assertThat(wiring.moduleRegistry().findModule("rewards")).isEmpty();

            // Enabled core modules are present
            assertThat(wiring.moduleRegistry().findModule("core")).isPresent();
            assertThat(wiring.moduleRegistry().findModule("bank")).isPresent();
        }
    }

    @Test
    @DisplayName("GameplayWiring omits instantiating listeners, menus, and reconcilers when modules are disabled")
    void gameplayWiringOmitsDisabledComponents(@TempDir Path tempDir) throws Exception {
        String hocon = """
                modules {
                  missions = false
                  boundary = false
                  worth = false
                  dimensions = false
                  limits = false
                  "anti-abuse" = false
                  boosters = false
                  "bank-upkeep" = false
                  upgrades = false
                  chat = false
                }
                """;
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        ModuleSettingsConfiguration moduleSettings = ModuleSettingsConfiguration.load(root);

        ConfigurationWiring config = createMockConfigWiring(moduleSettings);
        when(config.parkourConfig())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration(
                        false,
                        com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration.Markers.SHIPPED,
                        com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration.Runs.SHIPPED,
                        com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration.Modes.SHIPPED));
        com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration shipped =
                com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration.defaultConfiguration();
        when(config.tradeWindsConfig())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration(
                        false,
                        shipped.sea(),
                        shipped.ranks(),
                        shipped.ports(),
                        shipped.icons(),
                        shipped.standing(),
                        shipped.drift(),
                        shipped.contracts(),
                        shipped.voyageEvents()));
        try (PersistenceWiring persistenceWiring = PersistenceWiring.resolve(null, tempDir)) {
            PersistenceBootstrap persistence = persistenceWiring.bootstrap();
            AuthorityWiring authority = mock(AuthorityWiring.class);
            when(authority.serverNodeId())
                    .thenReturn(com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-test"));
            when(authority.profileTypes())
                    .thenReturn(new com.uxplima.uxmskyblock.core.application.profile.ProfileTypes(
                            mock(com.uxplima.uxmskyblock.core.application.profile.ProfileTypePort.class)));
            PlayerSessionCoordinator coordinator = mock(PlayerSessionCoordinator.class);
            when(authority.sessionCoordinator()).thenReturn(coordinator);
            when(authority.activeProfileProvider()).thenReturn(uuid -> java.util.Optional.empty());
            when(authority.nodeProcessIdentity())
                    .thenReturn(com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity.create("node-1"));

            IslandProtectionListener protectionListener = mock(IslandProtectionListener.class);
            SpatialIslandIndex spatialIndex = mock(SpatialIslandIndex.class);
            when(protectionListener.spatialIndex()).thenReturn(spatialIndex);

            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            GameplayWiring gameplay = new GameplayWiring(
                    plugin,
                    config,
                    persistence,
                    authority,
                    protectionListener,
                    mock(IslandAccessService.class),
                    mock(IslandAllianceService.class),
                    mock(TemporaryAccessService.class),
                    mock(BukkitIslandVisitorEvictionAdapter.class),
                    mock(IslandAdminFreezeService.class),
                    new DirectScheduler(),
                    mock(AdaptiveBackpressureController.class),
                    () -> null,
                    new LocalIslandChatTransportAdapter());

            assertThat(gameplay.missionsMenu()).isNull();
            assertThat(gameplay.missionListener()).isNull();
            assertThat(gameplay.boundaryListener()).isNull();
            assertThat(gameplay.chunkScanner()).isNull();
            assertThat(gameplay.worthListener()).isNull();
            assertThat(gameplay.dimensionListener()).isNull();
            assertThat(gameplay.limitListener()).isNull();
            assertThat(gameplay.limitReconciler()).isNull();
            assertThat(gameplay.antiAbuseListener()).isNull();
            assertThat(gameplay.boosterListener()).isNull();
            assertThat(gameplay.boosterMenu()).isNull();
            assertThat(gameplay.bankruptcyListener()).isNull();
            assertThat(gameplay.oreGeneratorListener()).isNull();
            assertThat(gameplay.chatListener()).isNull();
            assertThat(gameplay.leaderboardMetrics().metrics())
                    .doesNotContain(
                            com.uxplima.uxmskyblock.core.application.parkour.ParkourRunsMetric.ID,
                            com.uxplima.uxmskyblock.core.application.tradewinds.TradeVolumeMetric.ID);
        }
    }

    @Test
    @DisplayName("GameplayWiring instantiates all components when modules are enabled")
    void gameplayWiringInstantiatesEnabledComponents(@TempDir Path tempDir) throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("");
        ModuleSettingsConfiguration moduleSettings = ModuleSettingsConfiguration.load(root);

        ConfigurationWiring config = createMockConfigWiring(moduleSettings);
        try (PersistenceWiring persistenceWiring = PersistenceWiring.resolve(null, tempDir)) {
            PersistenceBootstrap persistence = persistenceWiring.bootstrap();
            AuthorityWiring authority = mock(AuthorityWiring.class);
            when(authority.serverNodeId())
                    .thenReturn(com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-test"));
            when(authority.profileTypes())
                    .thenReturn(new com.uxplima.uxmskyblock.core.application.profile.ProfileTypes(
                            mock(com.uxplima.uxmskyblock.core.application.profile.ProfileTypePort.class)));
            PlayerSessionCoordinator coordinator = mock(PlayerSessionCoordinator.class);
            when(authority.sessionCoordinator()).thenReturn(coordinator);
            when(authority.activeProfileProvider()).thenReturn(uuid -> java.util.Optional.empty());
            when(authority.nodeProcessIdentity())
                    .thenReturn(com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity.create("node-1"));

            IslandProtectionListener protectionListener = mock(IslandProtectionListener.class);
            SpatialIslandIndex spatialIndex = mock(SpatialIslandIndex.class);
            when(protectionListener.spatialIndex()).thenReturn(spatialIndex);

            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            GameplayWiring gameplay = new GameplayWiring(
                    plugin,
                    config,
                    persistence,
                    authority,
                    protectionListener,
                    mock(IslandAccessService.class),
                    mock(IslandAllianceService.class),
                    mock(TemporaryAccessService.class),
                    mock(BukkitIslandVisitorEvictionAdapter.class),
                    mock(IslandAdminFreezeService.class),
                    new DirectScheduler(),
                    mock(AdaptiveBackpressureController.class),
                    () -> null,
                    new LocalIslandChatTransportAdapter());

            assertThat(gameplay.missionsMenu()).isNotNull();
            assertThat(gameplay.missionListener()).isNotNull();
            assertThat(gameplay.boundaryListener()).isNotNull();
            assertThat(gameplay.chunkScanner()).isNotNull();
            assertThat(gameplay.worthListener()).isNotNull();
            assertThat(gameplay.dimensionListener()).isNotNull();
            assertThat(gameplay.limitListener()).isNotNull();
            assertThat(gameplay.limitReconciler()).isNotNull();
            assertThat(gameplay.antiAbuseListener()).isNotNull();
            assertThat(gameplay.boosterListener()).isNotNull();
            assertThat(gameplay.boosterMenu()).isNotNull();
            assertThat(gameplay.bankruptcyListener()).isNotNull();
            assertThat(gameplay.oreGeneratorListener()).isNotNull();
            assertThat(gameplay.chatListener()).isNotNull();
            assertThat(gameplay.leaderboardMetrics().metrics())
                    .contains(
                            com.uxplima.uxmskyblock.core.application.parkour.ParkourRunsMetric.ID,
                            com.uxplima.uxmskyblock.core.application.tradewinds.TradeVolumeMetric.ID);
            assertThat(gameplay.schematicEngine()
                            .actions()
                            .knowsAll(java.util.List.of(
                                    com.uxplima.uxmskyblock.bukkit.parkour.CourseStart.ACTION,
                                    com.uxplima.uxmskyblock.bukkit.brix.PlotStart.ACTION,
                                    com.uxplima.uxmskyblock.bukkit.tradewinds.VesselStart.ACTION)))
                    .describedAs("every enabled mode's start action is known to the islands it starts")
                    .isTrue();
        }
    }

    private static ConfigurationWiring createMockConfigWiring(ModuleSettingsConfiguration moduleSettings) {
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

        ShopConfiguration shop = mock(ShopConfiguration.class);
        when(shop.dampingFactor()).thenReturn(0.5);
        when(config.shopConfig()).thenReturn(shop);

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
        when(mission.resetZone()).thenReturn(java.time.ZoneId.of("UTC"));
        when(mission.missions()).thenReturn(java.util.List.of());
        when(config.missionConfig()).thenReturn(mission);

        SettingsConfiguration settings = mock(SettingsConfiguration.class);
        when(config.settingsConfig()).thenReturn(settings);

        LevelConfiguration level = mock(LevelConfiguration.class);
        when(level.blockWeights()).thenReturn(Map.of());
        when(level.spawnerWeights()).thenReturn(Map.of());
        when(level.basePricesMinorUnits()).thenReturn(Map.of());
        when(level.dampingFactor()).thenReturn(0.5);
        when(level.pointsPerLevel()).thenReturn(100L);
        when(config.levelConfig()).thenReturn(level);

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

        GeneratorsConfiguration generators = mock(GeneratorsConfiguration.class);
        when(config.generatorsConfig()).thenReturn(generators);
        when(config.oneBlockConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration.defaultConfiguration());
        when(config.lifecycleConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration.defaultConfiguration());
        when(config.chunkBlockConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration.defaultConfiguration());
        when(config.wallConfig())
                .thenReturn(com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration.defaultConfiguration());
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

        return config;
    }

    private static class DirectScheduler implements SchedulerPort {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    }
}
