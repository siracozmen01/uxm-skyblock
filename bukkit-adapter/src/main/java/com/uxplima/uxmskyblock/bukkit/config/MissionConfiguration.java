package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island missions, branches, trigger specifications, and rewards.
 */
public record MissionConfiguration(boolean enabled, List<MissionDefinition> missions, java.time.ZoneId resetZone) {

    /** Missions whose days and weeks turn in the server's own zone. */
    public MissionConfiguration(boolean enabled, List<MissionDefinition> missions) {
        this(enabled, missions, java.time.ZoneId.systemDefault());
    }

    public static final boolean DEFAULT_ENABLED = true;

    public MissionConfiguration {
        missions = (missions == null) ? List.of() : List.copyOf(missions);
        Objects.requireNonNull(resetZone, "resetZone must not be null");
    }

    public static MissionConfiguration defaultConfiguration() {
        List<MissionDefinition> defaults = List.of(
                new MissionDefinition(
                        MissionId.of("farming_wheat_1"),
                        MissionBranch.FARMING,
                        "@missions.catalog.farming_wheat_1.name",
                        "@missions.catalog.farming_wheat_1.description",
                        MissionTriggerType.CROP_HARVEST,
                        "WHEAT",
                        50L,
                        new MissionReward(10L, 500L, 100L, List.of())),
                new MissionDefinition(
                        MissionId.of("farming_carrot_1"),
                        MissionBranch.FARMING,
                        "@missions.catalog.farming_carrot_1.name",
                        "@missions.catalog.farming_carrot_1.description",
                        MissionTriggerType.CROP_HARVEST,
                        "CARROT",
                        100L,
                        new MissionReward(15L, 1000L, 150L, List.of())),
                new MissionDefinition(
                        MissionId.of("mining_stone_1"),
                        MissionBranch.MINING,
                        "@missions.catalog.mining_stone_1.name",
                        "@missions.catalog.mining_stone_1.description",
                        MissionTriggerType.BLOCK_BREAK,
                        "STONE",
                        128L,
                        new MissionReward(10L, 400L, 100L, List.of())),
                new MissionDefinition(
                        MissionId.of("slayer_zombie_1"),
                        MissionBranch.SLAYER,
                        "@missions.catalog.slayer_zombie_1.name",
                        "@missions.catalog.slayer_zombie_1.description",
                        MissionTriggerType.MOB_KILL,
                        "ZOMBIE",
                        25L,
                        new MissionReward(20L, 1500L, 250L, List.of())),
                new MissionDefinition(
                        MissionId.of("builder_cobble_1"),
                        MissionBranch.BUILDER,
                        "@missions.catalog.builder_cobble_1.name",
                        "@missions.catalog.builder_cobble_1.description",
                        MissionTriggerType.BLOCK_PLACE,
                        "COBBLESTONE",
                        256L,
                        new MissionReward(15L, 800L, 150L, List.of())),
                new MissionDefinition(
                        MissionId.of("adventure_fishing_1"),
                        MissionBranch.ADVENTURE,
                        "@missions.catalog.adventure_fishing_1.name",
                        "@missions.catalog.adventure_fishing_1.description",
                        MissionTriggerType.FISHING,
                        "*",
                        20L,
                        new MissionReward(25L, 2000L, 300L, List.of())),
                new MissionDefinition(
                        MissionId.of("economy_iron_1"),
                        MissionBranch.ECONOMY,
                        "@missions.catalog.economy_iron_1.name",
                        "@missions.catalog.economy_iron_1.description",
                        MissionTriggerType.ITEM_SUBMIT,
                        "IRON_INGOT",
                        64L,
                        new MissionReward(50L, 5000L, 1000L, List.of())),
                new MissionDefinition(
                        MissionId.of("daily_cobblestone"),
                        MissionBranch.MINING,
                        "@missions.catalog.daily_cobblestone.name",
                        "@missions.catalog.daily_cobblestone.description",
                        MissionTriggerType.BLOCK_BREAK,
                        "COBBLESTONE",
                        256L,
                        new MissionReward(5L, 250L, 50L, List.of()),
                        MissionRepeat.DAILY),
                new MissionDefinition(
                        MissionId.of("daily_wheat"),
                        MissionBranch.FARMING,
                        "@missions.catalog.daily_wheat.name",
                        "@missions.catalog.daily_wheat.description",
                        MissionTriggerType.CROP_HARVEST,
                        "WHEAT",
                        64L,
                        new MissionReward(5L, 250L, 50L, List.of()),
                        MissionRepeat.DAILY),
                new MissionDefinition(
                        MissionId.of("weekly_hunter"),
                        MissionBranch.SLAYER,
                        "@missions.catalog.weekly_hunter.name",
                        "@missions.catalog.weekly_hunter.description",
                        MissionTriggerType.MOB_KILL,
                        "*",
                        100L,
                        new MissionReward(30L, 2500L, 400L, List.of()),
                        MissionRepeat.WEEKLY),
                new MissionDefinition(
                        MissionId.of("weekly_angler"),
                        MissionBranch.ADVENTURE,
                        "@missions.catalog.weekly_angler.name",
                        "@missions.catalog.weekly_angler.description",
                        MissionTriggerType.FISHING,
                        "*",
                        50L,
                        new MissionReward(25L, 2000L, 300L, List.of()),
                        MissionRepeat.WEEKLY));
        return new MissionConfiguration(DEFAULT_ENABLED, defaults);
    }

    public static MissionConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("missions");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        java.time.ZoneId zone = zoneOf(node.node("reset-zone").getString("system"));
        ConfigurationNode catalogNode = node.node("catalog");
        if (catalogNode.virtual() || catalogNode.empty()) {
            return new MissionConfiguration(enabled, defaultConfiguration().missions(), zone);
        }

        List<MissionDefinition> list = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                catalogNode.childrenMap().entrySet()) {
            String missionKey = String.valueOf(entry.getKey());
            ConfigurationNode mNode = entry.getValue();

            String branchRaw = mNode.node("branch").getString("ADVENTURE");
            MissionBranch branch;
            try {
                branch = MissionBranch.valueOf(branchRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                branch = MissionBranch.ADVENTURE;
            }

            String displayName = mNode.node("display-name").getString(missionKey);
            String description = mNode.node("description").getString("");
            String triggerRaw = mNode.node("trigger-type").getString("BLOCK_BREAK");
            MissionTriggerType triggerType;
            try {
                triggerType = MissionTriggerType.valueOf(triggerRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                triggerType = MissionTriggerType.BLOCK_BREAK;
            }

            if (triggerType == MissionTriggerType.CUSTOM_MOB_KILL) {
                LOGGER.warning(() -> "The mission " + missionKey + " counts CUSTOM_MOB_KILL, which nothing on this"
                        + " server gives: no plugin here makes custom creatures, and a creature named with a name tag"
                        + " is anybody's. It is left out.");
                continue;
            }
            String targetFilter = mNode.node("target-filter").getString("");
            long requiredAmount = Math.max(1L, mNode.node("required-amount").getLong(10L));

            ConfigurationNode rewardsNode = mNode.node("rewards");
            long crystals = Math.max(0L, rewardsNode.node("crystals").getLong(0L));
            long currency =
                    Math.max(0L, rewardsNode.node("currency-minor-units").getLong(0L));
            long exp = Math.max(0L, rewardsNode.node("island-exp").getLong(0L));
            List<String> commands = new ArrayList<>();
            for (ConfigurationNode cmdNode : rewardsNode.node("commands").childrenList()) {
                String cmd = cmdNode.getString();
                if (cmd != null && !cmd.isBlank()) {
                    commands.add(cmd);
                }
            }
            MissionReward reward = new MissionReward(crystals, currency, exp, commands);
            String repeatRaw = mNode.node("repeat").getString("once");
            MissionRepeat repeat = MissionRepeat.named(repeatRaw).orElseGet(() -> {
                LOGGER.warning(() -> "The mission " + missionKey + " repeats '" + repeatRaw
                        + "', which is none of once, daily and weekly. It is finished once.");
                return MissionRepeat.ONCE;
            });

            java.util.Optional<java.util.Set<GameModeType>> modes = modes(missionKey, mNode.node("modes"));
            if (modes.isEmpty()) {
                continue;
            }
            String after = mNode.node("after").getString("").strip();

            list.add(new MissionDefinition(
                    MissionId.of(missionKey),
                    branch,
                    displayName,
                    description,
                    triggerType,
                    targetFilter,
                    requiredAmount,
                    reward,
                    repeat,
                    modes.get(),
                    after.isEmpty() || after.equals(missionKey)
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(MissionId.of(after))));
        }

        return new MissionConfiguration(enabled, Collections.unmodifiableList(list), zone);
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(MissionConfiguration.class.getName());

    /**
     * The modes a mission names, none for every mode, or nothing when it names one no mode is called: a mission
     * meant for a mode the file misspelt would otherwise be offered to every island.
     */
    static java.util.Optional<java.util.Set<GameModeType>> modes(String mission, ConfigurationNode written) {
        java.util.Set<GameModeType> modes = java.util.EnumSet.noneOf(GameModeType.class);
        // One mode may be written without the brackets.
        List<String> names = written.isList()
                ? written.childrenList().stream()
                        .map(line -> line.getString(""))
                        .toList()
                : List.of(written.getString(""));
        for (String each : names) {
            String name = each.strip();
            if (name.isEmpty()) {
                continue;
            }
            try {
                modes.add(GameModeType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                LOGGER.warning(() -> "The mission " + mission + " names the mode '" + name
                        + "', which no mode is called, so it is left out.");
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(modes);
    }

    /** The zone a file names, "system" for the server's own, and the server's own for one it does not know. */
    static java.time.ZoneId zoneOf(String written) {
        if (written.isBlank() || written.strip().equalsIgnoreCase("system")) {
            return java.time.ZoneId.systemDefault();
        }
        try {
            return java.time.ZoneId.of(written.strip());
        } catch (java.time.DateTimeException unknown) {
            LOGGER.warning(() -> "missions.reset-zone is '" + written
                    + "', which is no time zone. Missions turn over in the server's own zone.");
            return java.time.ZoneId.systemDefault();
        }
    }
}
