package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

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
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LimitConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PerformanceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
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
import com.uxplima.uxmskyblock.bukkit.i18n.ThemeSource;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

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

        // 1. Unpack the shared theme, the messages catalog and the menus templates
        ThemeSource.saveShared(dataDir, ConfigurationLoader.class.getClassLoader());
        // A catalogue or a menu a release restyles reaches the server that already has a copy: what the
        // operator never edited takes this release's words and layout, and what they edited stays theirs.
        for (String language : new String[] {"messages_en.conf", "messages_tr.conf"}) {
            Path catalogue = dataDir.resolve("messages").resolve(language);
            unpackResource(plugin, "messages/" + language, catalogue);
            bringUpToDate(plugin, dataDir, catalogue, "messages/" + language);
        }
        for (String menu : SHIPPED_MENUS) {
            Path file = dataDir.resolve("menus").resolve(menu);
            unpackResource(plugin, "menus/" + menu, file);
            bringUpToDate(plugin, dataDir, file, "menus/" + menu);
        }

        // 2. Load root config.conf
        Path configFile = dataDir.resolve("config.conf");
        unpackResource(plugin, "config.conf", configFile);
        bringUpToDate(plugin, dataDir, configFile, "config.conf");
        // The command tree reads this file later in the boot, and gets the release's new words from here.
        Path commandsFile = dataDir.resolve("commands.conf");
        unpackResource(plugin, "commands.conf", commandsFile);
        bringUpToDate(plugin, dataDir, commandsFile, "commands.conf");
        // How every menu asks for a word, read when the menu engine is built: an anvil, a chat line, a
        // sign or a dialog, chosen for each place. It was read and never written, so no operator found it.
        Path textInputFile = dataDir.resolve("text-input.conf");
        unpackResource(plugin, "text-input.conf", textInputFile);
        bringUpToDate(plugin, dataDir, textInputFile, "text-input.conf");
        CommentedConfigurationNode root = loadHocon(configFile);

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
        unpackResource(plugin, "modules.conf", modulesFile);
        bringUpToDate(plugin, dataDir, modulesFile, "modules.conf");
        CommentedConfigurationNode modulesRoot = loadHocon(modulesFile);
        ModuleSettingsConfiguration moduleSettings = modulesRoot != null
                ? ModuleSettingsConfiguration.load(modulesRoot)
                : ModuleSettingsConfiguration.empty();

        // 4. Load modular subsystem configurations
        SeasonConfiguration seasonConfig = loadConfig(
                plugin, dataDir, "seasons.conf", SeasonConfiguration::load, SeasonConfiguration.defaultConfiguration());
        SocialConfiguration socialConfig = loadConfig(
                plugin, dataDir, "social.conf", SocialConfiguration::load, SocialConfiguration.defaultConfiguration());
        DiscordConfiguration discordConfig = loadConfig(
                plugin,
                dataDir,
                "discord.conf",
                DiscordConfiguration::load,
                DiscordConfiguration.defaultConfiguration());
        AllianceConfiguration allianceConfig = loadConfig(
                plugin,
                dataDir,
                "alliances.conf",
                AllianceConfiguration::load,
                AllianceConfiguration.defaultConfiguration());
        ShopConfiguration shopConfig = loadConfig(
                plugin, dataDir, "shop.conf", ShopConfiguration::load, ShopConfiguration.defaultConfiguration());
        TemporaryAccessConfiguration temporaryAccessConfig = loadConfig(
                plugin,
                dataDir,
                "temporary-access.conf",
                TemporaryAccessConfiguration::load,
                TemporaryAccessConfiguration.defaultConfiguration());
        RewardInboxConfiguration rewardConfig = loadConfig(
                plugin,
                dataDir,
                "rewards.conf",
                RewardInboxConfiguration::load,
                RewardInboxConfiguration.defaultConfiguration());
        WarpConfiguration warpConfig = loadConfig(
                plugin, dataDir, "warps.conf", WarpConfiguration::load, WarpConfiguration.defaultConfiguration());
        VaultConfiguration vaultConfig = loadConfig(
                plugin, dataDir, "vault.conf", VaultConfiguration::load, VaultConfiguration.defaultConfiguration());
        ChatConfiguration chatConfig = loadConfig(
                plugin, dataDir, "chat.conf", ChatConfiguration::load, ChatConfiguration.defaultConfiguration());
        InactivityConfiguration inactivityConfig = loadConfig(
                plugin,
                dataDir,
                "inactivity.conf",
                InactivityConfiguration::load,
                InactivityConfiguration.defaultConfiguration());
        MissionConfiguration missionConfig = loadConfig(
                plugin,
                dataDir,
                "missions.conf",
                MissionConfiguration::load,
                MissionConfiguration.defaultConfiguration());
        LevelConfiguration levelConfig = loadConfig(
                plugin, dataDir, "levels.conf", LevelConfiguration::load, LevelConfiguration.defaultConfiguration());
        BiomeConfiguration biomeConfig = loadConfig(
                plugin, dataDir, "biomes.conf", BiomeConfiguration::load, BiomeConfiguration.defaultConfiguration());
        InteractionEffects effectsConfig = loadConfig(
                plugin,
                dataDir,
                "effects.conf",
                EffectsConfiguration::load,
                EffectsConfiguration.defaultConfiguration());
        DimensionConfiguration dimensionConfig = loadConfig(
                plugin,
                dataDir,
                "dimensions.conf",
                DimensionConfiguration::load,
                DimensionConfiguration.defaultConfiguration());
        LimitConfiguration limitConfig = loadConfig(
                plugin, dataDir, "limits.conf", LimitConfiguration::load, LimitConfiguration.defaultConfiguration());
        AntiAbuseConfiguration antiAbuseConfig = loadConfig(
                plugin,
                dataDir,
                "anti_abuse.conf",
                AntiAbuseConfiguration::load,
                AntiAbuseConfiguration.defaultConfiguration());
        BoosterConfiguration boosterConfig = loadConfig(
                plugin,
                dataDir,
                "boosters.conf",
                BoosterConfiguration::load,
                BoosterConfiguration.defaultConfiguration());
        BankConfiguration bankConfig = loadConfig(
                plugin, dataDir, "bank.conf", BankConfiguration::load, BankConfiguration.defaultConfiguration());
        SettingsConfiguration settingsConfig = loadConfig(
                plugin,
                dataDir,
                "settings.conf",
                SettingsConfiguration::load,
                SettingsConfiguration.defaultConfiguration());
        ProtectionConfiguration protectionConfig = loadConfig(
                plugin,
                dataDir,
                "protection.conf",
                ProtectionConfiguration::load,
                ProtectionConfiguration.defaultConfiguration());
        PerformanceConfiguration performanceConfig = loadConfig(
                plugin,
                dataDir,
                "performance.conf",
                PerformanceConfiguration::load,
                PerformanceConfiguration.defaultConfiguration());
        InteractablesConfiguration interactablesConfig = loadConfig(
                plugin,
                dataDir,
                "interactables.conf",
                InteractablesConfiguration::load,
                InteractablesConfiguration.defaultConfiguration());
        WorldConfiguration worldConfig = loadConfig(
                plugin, dataDir, "world.conf", WorldConfiguration::load, WorldConfiguration.defaultConfiguration());
        UpgradesConfiguration upgradesConfig = loadConfig(
                plugin,
                dataDir,
                "upgrades.conf",
                UpgradesConfiguration::load,
                UpgradesConfiguration.defaultConfiguration());
        PresetConfiguration presetConfig = loadConfig(
                plugin, dataDir, "presets.conf", PresetConfiguration::load, PresetConfiguration.defaultConfiguration());
        GeneratorsConfiguration generatorsConfig = loadConfig(
                plugin,
                dataDir,
                "generators.conf",
                GeneratorsConfiguration::load,
                GeneratorsConfiguration.defaultConfiguration());

        Path phasesFolder = dataDir.resolve("oneblock").resolve("phases");
        oneBlockPhaseFiles(plugin, dataDir, phasesFolder);
        com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration oneBlockConfig = loadConfig(
                plugin,
                dataDir,
                "oneblock.conf",
                node -> {
                    // Each line that cannot be read as written is said with its file and line.
                    var loaded = com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration.loadReporting(
                            node, phasesFolder);
                    var unused = com.uxplima.uxmskyblock.bukkit.config.OneBlockLoadReport.say(dataDir, loaded);
                    return loaded.config();
                },
                com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration lifecycleConfig = loadConfig(
                plugin,
                dataDir,
                "lifecycle.conf",
                com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.LifecycleConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration chunkBlockConfig = loadConfig(
                plugin,
                dataDir,
                "chunkblock.conf",
                com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration acidIslandConfig = loadConfig(
                plugin,
                dataDir,
                "acidisland.conf",
                com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration caveBlockConfig = loadConfig(
                plugin,
                dataDir,
                "caveblock.conf",
                com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration skyGridConfig = loadConfig(
                plugin,
                dataDir,
                "skygrid.conf",
                com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration boxedConfig = loadConfig(
                plugin,
                dataDir,
                "boxed.conf",
                com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration poseidonConfig = loadConfig(
                plugin,
                dataDir,
                "poseidon.conf",
                com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration strangerRealmsConfig = loadConfig(
                plugin,
                dataDir,
                "strangerrealms.conf",
                com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration parkourConfig = loadConfig(
                plugin,
                dataDir,
                "parkour.conf",
                com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration brixConfig = loadConfig(
                plugin,
                dataDir,
                "brix.conf",
                com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration tradeConfig = loadConfig(
                plugin,
                dataDir,
                "trade.conf",
                com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration tradeWindsConfig = loadConfig(
                plugin,
                dataDir,
                "tradewinds.conf",
                com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration.defaultConfiguration());
        com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration webMapConfig = loadConfig(
                plugin,
                dataDir,
                "webmap.conf",
                com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.WebMapConfiguration.defaultConfiguration());

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

        wiring.useWallConfig(loadConfig(
                plugin,
                dataDir,
                "walls.conf",
                com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration.defaultConfiguration()));
        wiring.useCarriedStateConfig(loadConfig(
                plugin,
                dataDir,
                "player-state.conf",
                com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration::load,
                com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration.defaultConfiguration()));
        // A loot table is the operator's once written: shipped once, never brought up to date over their edits.
        for (String table : SHIPPED_LOOT) {
            unpackResource(plugin, "loot/" + table, dataDir.resolve("loot").resolve(table));
        }
        com.uxplima.uxmskyblock.bukkit.loot.LootTables loot =
                com.uxplima.uxmskyblock.bukkit.loot.LootTables.read(dataDir.resolve("loot"));
        loot.problems().forEach(LOGGER::warning);
        wiring.useLootTables(loot);
        wiring.validate();
        return wiring;
    }

    @FunctionalInterface
    public interface ConfigParser<T> {
        T parse(CommentedConfigurationNode node);
    }

    private static <T> T loadConfig(
            JavaPlugin plugin, Path dataDir, String configName, ConfigParser<T> parser, T defaultVal) {
        Path moduleFile = dataDir.resolve("modules").resolve(configName);
        Path rootFile = dataDir.resolve(configName);

        Path targetFile;
        if (Files.exists(moduleFile)) {
            targetFile = moduleFile;
        } else if (Files.exists(rootFile)) {
            targetFile = rootFile;
        } else {
            unpackResource(plugin, "modules/" + configName, moduleFile);
            if (!Files.exists(moduleFile)) {
                unpackResource(plugin, configName, moduleFile);
            }
            targetFile = moduleFile;
        }
        if (!bringUpToDate(plugin, dataDir, targetFile, "modules/" + configName)) {
            bringUpToDate(plugin, dataDir, targetFile, configName);
        }

        if (Files.exists(targetFile)) {
            try {
                CommentedConfigurationNode node = HoconConfigurationLoader.builder()
                        .path(targetFile)
                        .build()
                        .load();
                return parser.parse(node);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse configuration from " + targetFile, e);
            }
        }
        return defaultVal;
    }

    /**
     * Adds to the operator's {@code file} what this release ships in {@code resource} and the file lacks,
     * and says so in one line. See {@link PluginSettings#bringUpToDate}.
     *
     * <p>A file that cannot be brought up to date still loads as it is: the server starts with the keys
     * the operator has, and the line says which file and why.
     */
    private static boolean bringUpToDate(JavaPlugin plugin, Path dataDir, Path file, String resource) {
        try {
            if (PluginSettings.bringUpToDate(
                    dataDir, file, resource, plugin.getClass().getClassLoader())) {
                LOGGER.info("Added what this release brings to " + dataDir.relativize(file)
                        + ". The file as it was is beside it as " + file.getFileName() + ".bak.");
                return true;
            }
        } catch (RuntimeException failed) {
            LOGGER.warning("Could not bring " + file + " up to date with this release: " + failed.getMessage()
                    + ". It loads as it is.");
        }
        return false;
    }

    /**
     * Moves phases still written inside the OneBlock file into a file each, then ships the phase files a fresh
     * server lacks. Moved first, so a phase the operator wrote is never shadowed by the shipped file of its name.
     */
    private static void oneBlockPhaseFiles(JavaPlugin plugin, Path dataDir, Path phasesFolder) {
        for (Path file :
                List.of(dataDir.resolve("modules").resolve("oneblock.conf"), dataDir.resolve("oneblock.conf"))) {
            if (!Files.exists(file)) {
                continue;
            }
            try {
                com.uxplima.uxmskyblock.bukkit.config.OneBlockPhaseFiles.moveIntoFiles(file, phasesFolder);
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.warning("The OneBlock phases written in " + file + " could not be moved into files ("
                        + e.getMessage() + "). They are read from the list as they stand.");
            }
        }
        for (String phase : com.uxplima.uxmskyblock.bukkit.config.OneBlockPhaseFiles.SHIPPED) {
            unpackResource(plugin, "oneblock/phases/" + phase + ".conf", phasesFolder.resolve(phase + ".conf"));
        }
    }

    /**
     * Writes a shipped default next to the server, once, and never over an operator's edit.
     *
     * <p>A failure here is not fatal: the subsystem falls back to its own defaults and the server
     * starts. It is not silent either. An operator whose disk was full or whose folder was read only
     * would otherwise edit a file the plugin never wrote and wonder why nothing changed.
     */
    private static void unpackResource(JavaPlugin plugin, String resourcePath, Path targetPath) {
        if (Files.exists(targetPath)) {
            return;
        }
        try {
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in != null) {
                    Files.copy(in, targetPath);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Could not write the shipped default " + resourcePath + " to " + targetPath + ": "
                    + e.getMessage() + ". The subsystem will use its built in defaults instead.");
        }
    }

    private static @Nullable CommentedConfigurationNode loadHocon(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return HoconConfigurationLoader.builder().path(file).build().load();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load configuration file: " + file, e);
        }
    }
}
