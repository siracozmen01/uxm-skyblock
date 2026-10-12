package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BiomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DiscordConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LimitConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.NotificationConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PerformanceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.RewardInboxConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SeasonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ShopConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SocialConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.ThemeSource;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * Manages the canonical configuration hierarchy and fail-closed schema validation.
 *
 * <p>Directory Topology:
 * <pre>
 * plugins/uxmSkyblock/
 * ├── config.conf            # Core platform, database, grid parameters
 * ├── modules.conf           # Master feature toggles
 * ├── modules/               # Modular subsystem configurations
 * │   ├── alliances.conf
 * │   ├── bank.conf
 * │   ├── limits.conf
 * │   └── ...
 * └── messages/              # Multi-locale catalog files
 *     ├── messages_en.conf
 *     └── messages_tr.conf
 * </pre>
 */
public final class ConfigurationWiring {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationWiring.class.getName());

    private final Path dataDir;
    private final @Nullable CommentedConfigurationNode rootNode;
    private final ServerNodeConfiguration nodeConfig;
    private final PlayerStateDurabilityConfig playerStateConfig;
    private final ModuleSettingsConfiguration moduleSettings;
    private final SeasonConfiguration seasonConfig;
    private final SocialConfiguration socialConfig;
    private final DiscordConfiguration discordConfig;
    private final AllianceConfiguration allianceConfig;
    private final ShopConfiguration shopConfig;
    private final TemporaryAccessConfiguration temporaryAccessConfig;
    private final RewardInboxConfiguration rewardConfig;
    private final WarpConfiguration warpConfig;
    private final VaultConfiguration vaultConfig;
    private final ChatConfiguration chatConfig;
    private final InactivityConfiguration inactivityConfig;
    private final MissionConfiguration missionConfig;
    private final LevelConfiguration levelConfig;
    private final BiomeConfiguration biomeConfig;
    private final InteractionEffects effectsConfig;
    private final DimensionConfiguration dimensionConfig;
    private final LimitConfiguration limitConfig;
    private final AntiAbuseConfiguration antiAbuseConfig;
    private final BoosterConfiguration boosterConfig;
    private final BankConfiguration bankConfig;
    private final SettingsConfiguration settingsConfig;
    private final ProtectionConfiguration protectionConfig;
    private final PerformanceConfiguration performanceConfig;
    private final InteractablesConfiguration interactablesConfig;
    private final WorldConfiguration worldConfig;
    private final UpgradesConfiguration upgradesConfig;
    private final GeneratorsConfiguration generatorsConfig;
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
    private final PresetConfiguration presetConfig;
    private final Messages messages;
    private final HomeConfiguration homeConfig;
    private final com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration roleConfig;
    private final NotificationConfiguration notificationConfig;

    /**
     * The catalog is configuration, so it is built here rather than in a later wiring step. The
     * listeners are constructed before the integration layer exists, and they answer a player too.
     */
    private static Messages buildMessages(@Nullable CommentedConfigurationNode rootNode, Path dataDir) {
        LanguageConfiguration language = LanguageConfiguration.load(rootNode);
        MessageProvider provider = new MessageProvider(language.defaultLanguage());
        provider.loadBundledDefaults(ConfigurationWiring.class.getClassLoader());

        Path messagesDir = dataDir.resolve("messages");
        if (Files.isDirectory(messagesDir)) {
            try (java.util.stream.Stream<Path> files = Files.list(messagesDir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("messages_") || !name.endsWith(".conf")) {
                        continue;
                    }
                    String locale = name.substring("messages_".length(), name.length() - ".conf".length());
                    try {
                        provider.loadFromFile(locale, file);
                    } catch (IOException e) {
                        LOGGER.warning("Failed loading the message catalog " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.warning("Failed listing the messages folder " + messagesDir + ": " + e.getMessage());
            }
        }
        // The same theme the menus read, so every line and every tile is painted from one file.
        return Messages.of(provider, language, ThemeSource.load(dataDir));
    }

    ConfigurationWiring(
            Path dataDir,
            @Nullable CommentedConfigurationNode rootNode,
            ServerNodeConfiguration nodeConfig,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig,
            SocialConfiguration socialConfig,
            DiscordConfiguration discordConfig,
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            BiomeConfiguration biomeConfig,
            InteractionEffects effectsConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig,
            AntiAbuseConfiguration antiAbuseConfig,
            BoosterConfiguration boosterConfig,
            BankConfiguration bankConfig,
            SettingsConfiguration settingsConfig,
            ProtectionConfiguration protectionConfig,
            PerformanceConfiguration performanceConfig,
            InteractablesConfiguration interactablesConfig,
            WorldConfiguration worldConfig,
            UpgradesConfiguration upgradesConfig,
            GeneratorsConfiguration generatorsConfig,
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
            com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration webMapConfig,
            PresetConfiguration presetConfig) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir must not be null");
        this.rootNode = rootNode;
        this.nodeConfig = Objects.requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.playerStateConfig = Objects.requireNonNull(playerStateConfig, "playerStateConfig must not be null");
        this.moduleSettings = Objects.requireNonNull(moduleSettings, "moduleSettings must not be null");
        this.seasonConfig = Objects.requireNonNull(seasonConfig, "seasonConfig must not be null");
        this.socialConfig = Objects.requireNonNull(socialConfig, "socialConfig must not be null");
        this.discordConfig = Objects.requireNonNull(discordConfig, "discordConfig must not be null");
        this.allianceConfig = Objects.requireNonNull(allianceConfig, "allianceConfig must not be null");
        this.shopConfig = Objects.requireNonNull(shopConfig, "shopConfig must not be null");
        this.temporaryAccessConfig =
                Objects.requireNonNull(temporaryAccessConfig, "temporaryAccessConfig must not be null");
        this.rewardConfig = Objects.requireNonNull(rewardConfig, "rewardConfig must not be null");
        this.warpConfig = Objects.requireNonNull(warpConfig, "warpConfig must not be null");
        this.vaultConfig = Objects.requireNonNull(vaultConfig, "vaultConfig must not be null");
        this.chatConfig = Objects.requireNonNull(chatConfig, "chatConfig must not be null");
        this.inactivityConfig = Objects.requireNonNull(inactivityConfig, "inactivityConfig must not be null");
        this.missionConfig = Objects.requireNonNull(missionConfig, "missionConfig must not be null");
        this.levelConfig = Objects.requireNonNull(levelConfig, "levelConfig must not be null");
        this.biomeConfig = Objects.requireNonNull(biomeConfig, "biomeConfig must not be null");
        this.effectsConfig = Objects.requireNonNull(effectsConfig, "effectsConfig must not be null");
        this.dimensionConfig = Objects.requireNonNull(dimensionConfig, "dimensionConfig must not be null");
        this.limitConfig = Objects.requireNonNull(limitConfig, "limitConfig must not be null");
        this.antiAbuseConfig = Objects.requireNonNull(antiAbuseConfig, "antiAbuseConfig must not be null");
        this.boosterConfig = Objects.requireNonNull(boosterConfig, "boosterConfig must not be null");
        this.bankConfig = Objects.requireNonNull(bankConfig, "bankConfig must not be null");
        this.settingsConfig = Objects.requireNonNull(settingsConfig, "settingsConfig must not be null");
        this.protectionConfig = Objects.requireNonNull(protectionConfig, "protectionConfig must not be null");
        this.performanceConfig = Objects.requireNonNull(performanceConfig, "performanceConfig must not be null");
        this.interactablesConfig = Objects.requireNonNull(interactablesConfig, "interactablesConfig must not be null");
        this.worldConfig = Objects.requireNonNull(worldConfig, "worldConfig must not be null");
        this.upgradesConfig = Objects.requireNonNull(upgradesConfig, "upgradesConfig must not be null");
        this.generatorsConfig = Objects.requireNonNull(generatorsConfig, "generatorsConfig must not be null");
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
        this.presetConfig = Objects.requireNonNull(presetConfig, "presetConfig must not be null");
        this.messages = buildMessages(rootNode, dataDir);
        this.homeConfig = HomeConfiguration.load(rootNode);
        this.roleConfig = com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration.load(rootNode);
        this.notificationConfig = rootNode != null
                ? NotificationConfiguration.load(rootNode)
                : NotificationConfiguration.defaultConfiguration();
    }

    /**
     * Reusable factory for programmatically supplied configuration records.
     */
    public static ConfigurationWiring of(
            Path dataDir,
            @Nullable CommentedConfigurationNode rootNode,
            ServerNodeConfiguration nodeConfig,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig,
            SocialConfiguration socialConfig,
            DiscordConfiguration discordConfig,
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig,
            AntiAbuseConfiguration antiAbuseConfig,
            BoosterConfiguration boosterConfig,
            BankConfiguration bankConfig,
            SettingsConfiguration settingsConfig,
            ProtectionConfiguration protectionConfig,
            PerformanceConfiguration performanceConfig,
            InteractablesConfiguration interactablesConfig,
            WorldConfiguration worldConfig,
            UpgradesConfiguration upgradesConfig,
            GeneratorsConfiguration generatorsConfig,
            PresetConfiguration presetConfig) {
        ConfigurationWiring wiring = new ConfigurationWiring(
                dataDir,
                rootNode,
                nodeConfig,
                playerStateConfig,
                moduleSettings,
                seasonConfig,
                socialConfig,
                discordConfig,
                allianceConfig,
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                BiomeConfiguration.defaultConfiguration(),
                EffectsConfiguration.defaultConfiguration(),
                dimensionConfig,
                limitConfig,
                antiAbuseConfig,
                boosterConfig,
                bankConfig,
                settingsConfig,
                protectionConfig,
                performanceConfig,
                interactablesConfig,
                worldConfig,
                upgradesConfig,
                generatorsConfig,
                com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration.defaultConfiguration(),
                com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration.defaultConfiguration(),
                presetConfig);
        wiring.validate();
        return wiring;
    }

    /**
     * Fail-closed schema and constraint validation.
     */
    public void validate() {
        // 1. Server node validation
        if (nodeConfig.nodeId() == null || nodeConfig.nodeId().value().isBlank()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: ServerNodeConfiguration nodeId must not be blank.");
        }
        if (nodeConfig.worldName() == null || nodeConfig.worldName().isBlank()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: ServerNodeConfiguration worldName must not be blank.");
        }

        // 2. Durability checkpoint interval validation
        if (playerStateConfig.ambientCheckpointInterval().isNegative()
                || playerStateConfig.ambientCheckpointInterval().isZero()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: PlayerStateDurabilityConfig ambientCheckpointInterval must be strictly positive: "
                            + playerStateConfig.ambientCheckpointInterval());
        }

        // 3. Hardware limit bounds validation
        for (com.uxplima.uxmskyblock.core.domain.limit.LimitQuota quota :
                limitConfig.quotas().values()) {
            if (quota.baseLimit() < 0 || quota.perTierBonus() < 0) {
                throw new IllegalStateException(
                        "Fail-closed configuration error: Limit quota cannot have negative base or tier bonus: "
                                + quota);
            }
        }

        // 4. Performance throttle validation
        if (performanceConfig.normalBlocksPerTick() <= 0 || performanceConfig.normalChunksPerSec() <= 0) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: Performance block/chunk quotas must be strictly positive.");
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    /** How long a notification a player has read is kept, and how often they are swept. */
    public NotificationConfiguration notificationConfig() {
        return notificationConfig;
    }

    /** How many named homes a player may keep, and which permission buys more. */
    public HomeConfiguration homeConfig() {
        return homeConfig;
    }

    /** How many roles of their own an island's owner may make, and which permission buys more. */
    public com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration roleConfig() {
        return roleConfig;
    }

    /** The words this server answers in, in every language it ships. */
    public Messages messages() {
        return messages;
    }

    public @Nullable CommentedConfigurationNode rootNode() {
        return rootNode;
    }

    public ServerNodeConfiguration nodeConfig() {
        return nodeConfig;
    }

    public PlayerStateDurabilityConfig playerStateConfig() {
        return playerStateConfig;
    }

    public ModuleSettingsConfiguration moduleSettings() {
        return moduleSettings;
    }

    public SeasonConfiguration seasonConfig() {
        return seasonConfig;
    }

    public SocialConfiguration socialConfig() {
        return socialConfig;
    }

    public DiscordConfiguration discordConfig() {
        return discordConfig;
    }

    public AllianceConfiguration allianceConfig() {
        return allianceConfig;
    }

    public ShopConfiguration shopConfig() {
        return shopConfig;
    }

    public TemporaryAccessConfiguration temporaryAccessConfig() {
        return temporaryAccessConfig;
    }

    public RewardInboxConfiguration rewardConfig() {
        return rewardConfig;
    }

    public WarpConfiguration warpConfig() {
        return warpConfig;
    }

    public VaultConfiguration vaultConfig() {
        return vaultConfig;
    }

    public ChatConfiguration chatConfig() {
        return chatConfig;
    }

    public InactivityConfiguration inactivityConfig() {
        return inactivityConfig;
    }

    public MissionConfiguration missionConfig() {
        return missionConfig;
    }

    public LevelConfiguration levelConfig() {
        return levelConfig;
    }

    /** Which biomes this server offers and what an island has to reach first. */
    public BiomeConfiguration biomeConfig() {
        return biomeConfig;
    }

    /** What every interaction fires, as the operator wrote it. */
    public InteractionEffects effectsConfig() {
        return effectsConfig;
    }

    public DimensionConfiguration dimensionConfig() {
        return dimensionConfig;
    }

    public LimitConfiguration limitConfig() {
        return limitConfig;
    }

    public AntiAbuseConfiguration antiAbuseConfig() {
        return antiAbuseConfig;
    }

    public BoosterConfiguration boosterConfig() {
        return boosterConfig;
    }

    public BankConfiguration bankConfig() {
        return bankConfig;
    }

    public SettingsConfiguration settingsConfig() {
        return settingsConfig;
    }

    public ProtectionConfiguration protectionConfig() {
        return protectionConfig;
    }

    public PerformanceConfiguration performanceConfig() {
        return performanceConfig;
    }

    public InteractablesConfiguration interactablesConfig() {
        return interactablesConfig;
    }

    public WorldConfiguration worldConfig() {
        return worldConfig;
    }

    public UpgradesConfiguration upgradesConfig() {
        return upgradesConfig;
    }

    /** The starter islands a player may choose between, as this server's file describes them. */
    public PresetConfiguration presetConfig() {
        return presetConfig;
    }

    public GeneratorsConfiguration generatorsConfig() {
        return generatorsConfig;
    }

    /**
     * Every world islands are made in: the server's own island world first, then each world a preset
     * names for islands of its own, such as a Boxed preset's generated land.
     */
    public java.util.List<String> islandWorlds() {
        return presetConfig.worlds(nodeConfig.worldName());
    }

    /** Whether islands can be Brix plots, and the ground a plot is laid on. */
    public com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration brixConfig() {
        return brixConfig;
    }

    /** Whether islands can be TradeWinds vessels, and the sea a vessel is launched on. */
    public com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration tradeWindsConfig() {
        return tradeWindsConfig;
    }

    public com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration webMapConfig() {
        return webMapConfig;
    }

    /** Whether players may trade, and how a trade window looks. */
    public com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration tradeConfig() {
        return tradeConfig;
    }

    /** Whether islands can be Parkour courses, what marks a course and how a run is kept. */
    public com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration parkourConfig() {
        return parkourConfig;
    }

    /** Whether islands can be StrangerRealms islands, and how the Upside Down mirrors them. */
    public com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration strangerRealmsConfig() {
        return strangerRealmsConfig;
    }

    /** Whether islands can be Poseidon islands, and the ocean they lie at the bottom of. */
    public com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration poseidonConfig() {
        return poseidonConfig;
    }

    /** Whether islands can be Boxed islands, how big a box starts and what each advancement is worth. */
    public com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration boxedConfig() {
        return boxedConfig;
    }

    /** Whether islands can be SkyGrid islands, where the grid's blocks stand and which blocks they are. */
    public com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration skyGridConfig() {
        return skyGridConfig;
    }

    /** Whether islands can be CaveBlock islands, the shape of their rock and the rock of each dimension. */
    public com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration caveBlockConfig() {
        return caveBlockConfig;
    }

    /** Whether islands can be AcidIsland islands, their sea, the hazard and clean water. */
    public com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration acidIslandConfig() {
        return acidIslandConfig;
    }

    /** The walls, {@code modules/walls.conf}, the shipped ones until the loader hands the file over. */
    private volatile com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration wallConfig =
            com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration.defaultConfiguration();

    public com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration wallConfig() {
        return wallConfig;
    }

    /** Hands over {@code modules/walls.conf} as the server read it. */
    public void useWallConfig(com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration config) {
        this.wallConfig = Objects.requireNonNull(config, "config must not be null");
    }

    /** What a player carries in each group of worlds, {@code modules/player-state.conf}, shipped until handed over. */
    private volatile com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration carriedStateConfig =
            com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration.defaultConfiguration();

    public com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration carriedStateConfig() {
        return carriedStateConfig;
    }

    /** Hands over {@code modules/player-state.conf} as the server read it. */
    public void useCarriedStateConfig(com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration config) {
        this.carriedStateConfig = Objects.requireNonNull(config, "config must not be null");
    }

    /** The loot tables under {@code loot/}, none until handed over. */
    private volatile com.uxplima.uxmskyblock.bukkit.loot.LootTables lootTables =
            com.uxplima.uxmskyblock.bukkit.loot.LootTables.none();

    public com.uxplima.uxmskyblock.bukkit.loot.LootTables lootTables() {
        return lootTables;
    }

    /** Hands over the loot tables as the server read them. */
    public void useLootTables(com.uxplima.uxmskyblock.bukkit.loot.LootTables tables) {
        this.lootTables = Objects.requireNonNull(tables, "tables must not be null");
    }

    /** Whether islands can be ChunkBlock islands, and what each chunk costs in level. */
    public com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration chunkBlockConfig() {
        return chunkBlockConfig;
    }

    /** What a leave, a kick, a death and a reset do, as {@code modules/lifecycle.conf} writes it. */
    public com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration lifecycleConfig() {
        return lifecycleConfig;
    }

    /** The OneBlock game mode's phases, as {@code modules/oneblock.conf} writes them. */
    public com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration oneBlockConfig() {
        return oneBlockConfig;
    }
}
