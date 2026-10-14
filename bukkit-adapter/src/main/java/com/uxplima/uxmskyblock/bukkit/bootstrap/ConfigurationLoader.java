package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankPayoutsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BiomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DiscordConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LimitConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockLoadReport;
import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PerformanceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.RewardInboxConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SeasonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ShopConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SocialConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.ThemeSource;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * Reads the configuration off disk and hands back a validated {@link ConfigurationWiring}.
 *
 * <p>Two jobs lived in one class: finding files, unpacking the shipped defaults and parsing HOCON,
 * and then holding the thirty-one records that come out of it. They change for different reasons.
 * This class is the first job, and nothing else in the plugin touches a configuration file.
 *
 * <p>Directory topology:
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
public final class ConfigurationLoader {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationLoader.class.getName());

    /** The loot tables a server is shipped with, under {@code loot/}. */
    private static final List<String> SHIPPED_LOOT =
            List.of("treasure.conf", "deep_boss.conf", "skygrid.conf", "skygrid_nether.conf", "skygrid_end.conf");

    /**
     * The menu files this plugin ships, written next to the server once and never over an edit.
     *
     * <p>Adding a menu is a file here and a line in this list. A menu the operator writes themselves
     * needs neither: the engine reads every {@code menus/*.conf} it finds, shipped or not.
     */
    private static final List<String> SHIPPED_MENUS = List.of(
            "island-main.conf",
            "island-bank.conf",
            "island-upgrades.conf",
            "island-members.conf",
            "island-settings.conf",
            "island-warps.conf",
            "island-vault.conf",
            "island-missions.conf",
            "island-shop.conf",
            "island-warp-directory.conf",
            "island-reset.conf",
            "island-booster-cards.conf",
            "island-mission-list.conf",
            "tradewinds-sail.conf",
            "tradewinds-market.conf",
            "tradewinds-contracts.conf",
            "parkour-courses.conf",
            "block-values.conf",
            "brix-gallery.conf",
            "island-vault-page.conf",
            "player-trade.conf",
            "vessel-cargo.conf",
            "island-biome.conf",
            "island-homes.conf",
            "island-social.conf",
            "island-top.conf",
            "island-oneblock.conf",
            "island-chunks.conf",
            "island-roles.conf",
            "island-role-permissions.conf",
            "island-member-role.conf",
            "island-home-list.conf",
            "island-leaderboard.conf",
            "island-create.conf",
            "island-activity.conf",
            "island-activity-totals.conf",
            "island-guestbook.conf",
            "island-bookmarks.conf",
            "island-alliances.conf",
            "island-bank-currency.conf",
            "island-border.conf",
            "island-chunk.conf",
            "oneblock-branch.conf",
            "oneblock-phases.conf");

    private ConfigurationLoader() {
        throw new UnsupportedOperationException("ConfigurationLoader is a set of loading steps, not a thing to hold.");
    }

    /**
     * Unpacks default assets into the canonical directory topology, loads all HOCON configurations,
     * and performs strict fail-closed validation.
     */
    public static ConfigurationWiring loadAndValidate(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Path dataDir = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(dataDir.resolve("modules"));
            Files.createDirectories(dataDir.resolve("menus"));
            Files.createDirectories(dataDir.resolve("messages"));
            Files.createDirectories(dataDir.resolve("loot"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize plugin directories at: " + dataDir, e);
        }

        ModuleFiles files = new ModuleFiles(plugin, dataDir);
        // 1. Unpack the shared theme, the messages catalog and the menus templates
        ThemeSource.saveShared(dataDir, ConfigurationLoader.class.getClassLoader());
        // A catalogue or a menu a release restyles reaches the server that already has a copy: what the
        // operator never edited takes this release's words and layout, and what they edited stays theirs.
        for (String language : new String[] {"messages_en.conf", "messages_tr.conf"}) {
            Path catalogue = dataDir.resolve("messages").resolve(language);
            ConfigurationFiles.unpackResource(plugin, "messages/" + language, catalogue);
            ConfigurationFiles.bringUpToDate(plugin, dataDir, catalogue, "messages/" + language);
        }
        for (String menu : SHIPPED_MENUS) {
            Path file = dataDir.resolve("menus").resolve(menu);
            ConfigurationFiles.unpackResource(plugin, "menus/" + menu, file);
            ConfigurationFiles.bringUpToDate(plugin, dataDir, file, "menus/" + menu);
        }

        // 2. Load root config.conf
        Path configFile = dataDir.resolve("config.conf");
        ConfigurationFiles.unpackResource(plugin, "config.conf", configFile);
        ConfigurationFiles.bringUpToDate(plugin, dataDir, configFile, "config.conf");
        // The command tree reads this file later in the boot, and gets the release's new words from here.
        Path commandsFile = dataDir.resolve("commands.conf");
        ConfigurationFiles.unpackResource(plugin, "commands.conf", commandsFile);
        ConfigurationFiles.bringUpToDate(plugin, dataDir, commandsFile, "commands.conf");
        // How every menu asks for a word, read when the menu engine is built: an anvil, a chat line, a
        // sign or a dialog, chosen for each place. It was read and never written, so no operator found it.
        Path textInputFile = dataDir.resolve("text-input.conf");
        ConfigurationFiles.unpackResource(plugin, "text-input.conf", textInputFile);
        ConfigurationFiles.bringUpToDate(plugin, dataDir, textInputFile, "text-input.conf");
        CommentedConfigurationNode root = ConfigurationFiles.loadHocon(configFile);

        ServerNodeConfiguration nodeConfig;
        PlayerStateDurabilityConfig playerStateConfig;
        if (root != null) {
            nodeConfig = ServerNodeConfiguration.load(root);
            playerStateConfig = PlayerStateConfigurationAdapter.load(root);
        } else {
            String envNode = System.getProperty("skyblock.node.id", System.getenv("SKYBLOCK_NODE_ID"));
            String nodeId = (envNode != null && !envNode.isBlank()) ? envNode.trim() : "skyblock-node-default";
            nodeConfig = ServerNodeConfiguration.of(nodeId, "world");
            playerStateConfig = PlayerStateDurabilityConfig.defaultPolicy();
        }

        // 3. Load modules.conf
        Path modulesFile = dataDir.resolve("modules.conf");
        ConfigurationFiles.unpackResource(plugin, "modules.conf", modulesFile);
        ConfigurationFiles.bringUpToDate(plugin, dataDir, modulesFile, "modules.conf");
        CommentedConfigurationNode modulesRoot = ConfigurationFiles.loadHocon(modulesFile);
        ModuleSettingsConfiguration moduleSettings = modulesRoot != null
                ? ModuleSettingsConfiguration.load(modulesRoot)
                : ModuleSettingsConfiguration.empty();

        // 4. Load modular subsystem configurations
        SeasonConfiguration seasonConfig =
                files.read("seasons.conf", SeasonConfiguration::load, SeasonConfiguration.defaultConfiguration());
        SocialConfiguration socialConfig =
                files.read("social.conf", SocialConfiguration::load, SocialConfiguration.defaultConfiguration());
        DiscordConfiguration discordConfig =
                files.read("discord.conf", DiscordConfiguration::load, DiscordConfiguration.defaultConfiguration());
        AllianceConfiguration allianceConfig =
                files.read("alliances.conf", AllianceConfiguration::load, AllianceConfiguration.defaultConfiguration());
        ShopConfiguration shopConfig =
                files.read("shop.conf", ShopConfiguration::load, ShopConfiguration.defaultConfiguration());
        TemporaryAccessConfiguration temporaryAccessConfig = files.read(
                "temporary-access.conf",
                TemporaryAccessConfiguration::load,
                TemporaryAccessConfiguration.defaultConfiguration());
        RewardInboxConfiguration rewardConfig = files.read(
                "rewards.conf", RewardInboxConfiguration::load, RewardInboxConfiguration.defaultConfiguration());
        WarpConfiguration warpConfig =
                files.read("warps.conf", WarpConfiguration::load, WarpConfiguration.defaultConfiguration());
        VaultConfiguration vaultConfig =
                files.read("vault.conf", VaultConfiguration::load, VaultConfiguration.defaultConfiguration());
        ChatConfiguration chatConfig =
                files.read("chat.conf", ChatConfiguration::load, ChatConfiguration.defaultConfiguration());
        InactivityConfiguration inactivityConfig = files.read(
                "inactivity.conf", InactivityConfiguration::load, InactivityConfiguration.defaultConfiguration());
        MissionConfiguration missionConfig =
                files.read("missions.conf", MissionConfiguration::load, MissionConfiguration.defaultConfiguration());
        LevelConfiguration levelConfig =
                files.read("levels.conf", LevelConfiguration::load, LevelConfiguration.defaultConfiguration());
        BiomeConfiguration biomeConfig =
                files.read("biomes.conf", BiomeConfiguration::load, BiomeConfiguration.defaultConfiguration());
        InteractionEffects effectsConfig =
                files.read("effects.conf", EffectsConfiguration::load, EffectsConfiguration.defaultConfiguration());
        DimensionConfiguration dimensionConfig = files.read(
                "dimensions.conf", DimensionConfiguration::load, DimensionConfiguration.defaultConfiguration());
        LimitConfiguration limitConfig =
                files.read("limits.conf", LimitConfiguration::load, LimitConfiguration.defaultConfiguration());
        AntiAbuseConfiguration antiAbuseConfig = files.read(
                "anti_abuse.conf", AntiAbuseConfiguration::load, AntiAbuseConfiguration.defaultConfiguration());
        BoosterConfiguration boosterConfig =
                files.read("boosters.conf", BoosterConfiguration::load, BoosterConfiguration.defaultConfiguration());
        BankConfiguration bankConfig =
                files.read("bank.conf", BankConfiguration::load, BankConfiguration.defaultConfiguration());
        SettingsConfiguration settingsConfig =
                files.read("settings.conf", SettingsConfiguration::load, SettingsConfiguration.defaultConfiguration());
        ProtectionConfiguration protectionConfig = files.read(
                "protection.conf", ProtectionConfiguration::load, ProtectionConfiguration.defaultConfiguration());
        PerformanceConfiguration performanceConfig = files.read(
                "performance.conf", PerformanceConfiguration::load, PerformanceConfiguration.defaultConfiguration());
        InteractablesConfiguration interactablesConfig = files.read(
                "interactables.conf",
                InteractablesConfiguration::load,
                InteractablesConfiguration.defaultConfiguration());
        WorldConfiguration worldConfig =
                files.read("world.conf", WorldConfiguration::load, WorldConfiguration.defaultConfiguration());
        UpgradesConfiguration upgradesConfig =
                files.read("upgrades.conf", UpgradesConfiguration::load, UpgradesConfiguration.defaultConfiguration());
        PresetConfiguration presetConfig =
                files.read("presets.conf", PresetConfiguration::load, PresetConfiguration.defaultConfiguration());
        GeneratorsConfiguration generatorsConfig = files.read(
                "generators.conf", GeneratorsConfiguration::load, GeneratorsConfiguration.defaultConfiguration());

        Path phasesFolder = dataDir.resolve("oneblock").resolve("phases");
        ConfigurationFiles.oneBlockPhaseFiles(plugin, dataDir, phasesFolder);
        OneBlockConfiguration oneBlockConfig = files.read(
                "oneblock.conf",
                node -> {
                    // Each line that cannot be read as written is said with its file and line.
                    var loaded = OneBlockConfiguration.loadReporting(node, phasesFolder);
                    var unused = OneBlockLoadReport.say(dataDir, loaded);
                    return loaded.config();
                },
                OneBlockConfiguration.defaultConfiguration());
        LifecycleConfiguration lifecycleConfig = files.read(
                "lifecycle.conf", LifecycleConfiguration::load, LifecycleConfiguration.defaultConfiguration());
        ChunkBlockConfiguration chunkBlockConfig = files.read(
                "chunkblock.conf", ChunkBlockConfiguration::load, ChunkBlockConfiguration.defaultConfiguration());
        AcidIslandConfiguration acidIslandConfig = files.read(
                "acidisland.conf", AcidIslandConfiguration::load, AcidIslandConfiguration.defaultConfiguration());
        CaveBlockConfiguration caveBlockConfig = files.read(
                "caveblock.conf", CaveBlockConfiguration::load, CaveBlockConfiguration.defaultConfiguration());
        SkyGridConfiguration skyGridConfig =
                files.read("skygrid.conf", SkyGridConfiguration::load, SkyGridConfiguration.defaultConfiguration());
        BoxedConfiguration boxedConfig =
                files.read("boxed.conf", BoxedConfiguration::load, BoxedConfiguration.defaultConfiguration());
        PoseidonConfiguration poseidonConfig =
                files.read("poseidon.conf", PoseidonConfiguration::load, PoseidonConfiguration.defaultConfiguration());
        StrangerRealmsConfiguration strangerRealmsConfig = files.read(
                "strangerrealms.conf",
                StrangerRealmsConfiguration::load,
                StrangerRealmsConfiguration.defaultConfiguration());
        ParkourConfiguration parkourConfig =
                files.read("parkour.conf", ParkourConfiguration::load, ParkourConfiguration.defaultConfiguration());
        BrixConfiguration brixConfig =
                files.read("brix.conf", BrixConfiguration::load, BrixConfiguration.defaultConfiguration());
        TradeConfiguration tradeConfig =
                files.read("trade.conf", TradeConfiguration::load, TradeConfiguration.defaultConfiguration());
        TradeWindsConfiguration tradeWindsConfig = files.read(
                "tradewinds.conf", TradeWindsConfiguration::load, TradeWindsConfiguration.defaultConfiguration());
        WebMapConfiguration webMapConfig =
                files.read("webmap.conf", WebMapConfiguration::load, WebMapConfiguration.defaultConfiguration());

        ConfigurationWiring wiring = new ConfigurationWiring(
                dataDir,
                root,
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
                biomeConfig,
                effectsConfig,
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
                oneBlockConfig,
                lifecycleConfig,
                chunkBlockConfig,
                acidIslandConfig,
                caveBlockConfig,
                skyGridConfig,
                boxedConfig,
                poseidonConfig,
                strangerRealmsConfig,
                parkourConfig,
                brixConfig,
                tradeConfig,
                tradeWindsConfig,
                webMapConfig,
                presetConfig);

        wiring.useWallConfig(
                files.read("walls.conf", WallConfiguration::load, WallConfiguration.defaultConfiguration()));
        wiring.useIslandEffectsConfig(files.read(
                "island-effects.conf",
                IslandEffectsConfiguration::load,
                IslandEffectsConfiguration.defaultConfiguration()));
        wiring.useBankPayoutsConfig(files.read(
                "bank.conf", BankPayoutsConfiguration::load, BankPayoutsConfiguration.defaultConfiguration()));
        wiring.useStackingConfig(
                files.read("stacking.conf", StackingConfiguration::load, StackingConfiguration.defaultConfiguration()));
        wiring.useSignWarpsConfig(
                files.read("warps.conf", SignWarpsConfiguration::load, SignWarpsConfiguration.defaultConfiguration()));
        wiring.useVisitorsConfig(
                files.read("visitors.conf", VisitorsConfiguration::load, VisitorsConfiguration.defaultConfiguration()));
        wiring.useFlyConfig(
                files.read("fly.conf", IslandFlyConfiguration::load, IslandFlyConfiguration.defaultConfiguration()));
        wiring.useCarriedStateConfig(files.read(
                "player-state.conf",
                CarriedStateConfiguration::load,
                CarriedStateConfiguration.defaultConfiguration()));
        // A loot table is the operator's once written: shipped once, never brought up to date over their edits.
        for (String table : SHIPPED_LOOT) {
            ConfigurationFiles.unpackResource(
                    plugin, "loot/" + table, dataDir.resolve("loot").resolve(table));
        }
        LootTables loot = LootTables.read(dataDir.resolve("loot"));
        loot.problems().forEach(LOGGER::warning);
        wiring.useLootTables(loot);
        wiring.validate();
        return wiring;
    }

    /** Reads one module file of this server: unpacked, brought up to date and parsed, or the shipped value. */
    private record ModuleFiles(JavaPlugin plugin, Path dataDir) {
        <T> T read(String name, ConfigParser<T> parser, T fallback) {
            return ConfigurationFiles.loadConfig(plugin, dataDir, name, parser, fallback);
        }
    }

    @FunctionalInterface
    public interface ConfigParser<T> {
        T parse(CommentedConfigurationNode node);
    }
}
