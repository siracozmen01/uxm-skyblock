package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplate;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The starter presets an operator's file describes.
 *
 * <p>The four the plugin ships with were written in Java and there could never be a fifth. They are
 * the fallback now, and {@code modules/presets.conf} is where a server says what it offers.
 */
public record PresetConfiguration(
        List<StarterPreset> presets, String defaultId, SchematicPasteConfiguration paste, PresetChoices choices) {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(PresetConfiguration.class.getName());

    public PresetConfiguration {
        Objects.requireNonNull(presets, "presets must not be null");
        Objects.requireNonNull(defaultId, "defaultId must not be null");
        Objects.requireNonNull(paste, "paste must not be null");
        Objects.requireNonNull(choices, "choices must not be null");
        presets = List.copyOf(presets);
    }

    public PresetConfiguration(List<StarterPreset> presets, String defaultId, SchematicPasteConfiguration paste) {
        this(presets, defaultId, paste, PresetChoices.DEFAULT);
    }

    public PresetConfiguration(List<StarterPreset> presets, String defaultId) {
        this(presets, defaultId, SchematicPasteConfiguration.DEFAULT);
    }

    public static PresetConfiguration defaultConfiguration() {
        return defaultConfiguration(SchematicPasteConfiguration.DEFAULT, PresetChoices.DEFAULT);
    }

    private static PresetConfiguration defaultConfiguration(SchematicPasteConfiguration paste, PresetChoices choices) {
        return new PresetConfiguration(
                StarterPresetCatalog.shipped(), StarterPresetCatalog.CLASSIC.id(), paste, choices);
    }

    public static PresetConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("presets");
        ConfigurationNode entries = node.node("entries");
        if (node.virtual()) {
            return defaultConfiguration();
        }
        PresetChoices choices = PresetChoices.load(node);
        if (entries.virtual() || !entries.isMap()) {
            return defaultConfiguration(SchematicPasteConfiguration.load(node.node("paste")), choices);
        }

        List<StarterPreset> presets = new ArrayList<>();
        for (var entry : entries.childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).trim();
            if (id.isEmpty()) {
                continue;
            }
            ConfigurationNode preset = entry.getValue();
            String displayName = preset.node("display-name").getString("@presets." + id + ".name");
            String description = preset.node("description").getString("@presets." + id + ".description");
            String schematic = preset.node("schematic").getString("schematics/" + id + ".schem");
            presets.add(new StarterPreset(
                    id,
                    displayName,
                    description,
                    schematic,
                    biomeOf(preset),
                    modeOf(preset),
                    startOf(preset),
                    dimensionsOf(id, preset),
                    preset.node("world").getString("")));
        }

        if (presets.isEmpty()) {
            return defaultConfiguration(SchematicPasteConfiguration.load(node.node("paste")), choices);
        }
        String defaultId = node.node("default").getString(presets.get(0).id());
        return new PresetConfiguration(
                        presets, defaultId, SchematicPasteConfiguration.load(node.node("paste")), choices)
                .settled();
    }

    /**
     * These presets with a default that is offered and modes that offer something. A file that names modes none of
     * its kinds plays offers every mode, so a player is never left with nothing to start, and a default of a mode
     * the file leaves out gives way to the first kind it offers, so {@code /is create} on a OneBlock server makes a
     * OneBlock island though the line still says classic.
     */
    private PresetConfiguration settled() {
        List<StarterPreset> offered =
                choices.ordered(presets).stream().filter(choices::offers).toList();
        if (offered.isEmpty()) {
            LOGGER.warning(() -> "presets.modes names no game mode a kind in modules/presets.conf plays, so the"
                    + " kinds of every mode are offered.");
            return new PresetConfiguration(presets, defaultId, paste, choices.offeringEvery()).settled();
        }
        if (offered.stream().anyMatch(preset -> preset.id().equals(defaultId))) {
            return this;
        }
        return new PresetConfiguration(presets, offered.get(0).id(), paste, choices);
    }

    /** An operator who writes a biome the server does not know gets plains rather than a failed start. */
    private static IslandBiome biomeOf(ConfigurationNode preset) {
        String written = preset.node("biome").getString("PLAINS");
        try {
            return IslandBiome.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return IslandBiome.PLAINS;
        }
    }

    /** The game mode the island plays. One the plugin does not know is a skyblock island. */
    private static GameModeType modeOf(ConfigurationNode preset) {
        String written = preset.node("mode").getString("SKYBLOCK");
        try {
            return GameModeType.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return GameModeType.SKYBLOCK;
        }
    }

    /** The creation actions that build the island, in order; the starter platform when none is written. */
    private static List<String> startOf(ConfigurationNode preset) {
        List<String> start = new ArrayList<>();
        for (ConfigurationNode action : preset.node("start").childrenList()) {
            String written = action.getString("").trim();
            if (!written.isEmpty()) {
                start.add(written);
            }
        }
        return start.isEmpty() ? List.of(StarterPreset.PLATFORM) : start;
    }

    /**
     * How the preset starts each other dimension, by dimension id. A preset that writes no
     * {@code dimensions} block starts them the way the plugin ships; one that writes it starts exactly
     * the dimensions it names. An entry that names no action is left out with a warning.
     */
    private static StartTemplateBundle dimensionsOf(String presetId, ConfigurationNode preset) {
        ConfigurationNode node = preset.node("dimensions");
        if (node.virtual() || !node.isMap()) {
            return StartTemplateBundle.shipped();
        }
        Map<DimensionId, StartTemplate> templates = new LinkedHashMap<>();
        for (var entry : node.childrenMap().entrySet()) {
            String dimension = String.valueOf(entry.getKey()).trim();
            List<String> actions = new ArrayList<>();
            for (ConfigurationNode action : entry.getValue().node("start").childrenList()) {
                String written = action.getString("").trim();
                if (!written.isEmpty()) {
                    actions.add(written);
                }
            }
            if (dimension.isEmpty() || actions.isEmpty()) {
                LOGGER.warning(() -> "The preset " + presetId + " names the dimension '" + dimension
                        + "' with nothing to build, so it is left out.");
                continue;
            }
            int height = entry.getValue().node("height").getInt(StartTemplateBundle.DEFAULT_HEIGHT);
            templates.put(DimensionId.of(dimension), new StartTemplate(actions, height));
        }
        return new StartTemplateBundle(templates);
    }

    /**
     * The presets whose every creation action has a provider on this server. A preset of a game mode the
     * operator switched off names an action nobody provides, and is not offered.
     */
    public PresetConfiguration startableWith(Predicate<List<String>> provided) {
        List<StarterPreset> startable =
                presets.stream().filter(preset -> startable(preset, provided)).toList();
        if (startable.isEmpty()) {
            return defaultConfiguration(paste, choices);
        }
        return new PresetConfiguration(startable, defaultId, paste, choices).settled();
    }

    /** Whether the preset's own start and every dimension's start have providers. */
    private static boolean startable(StarterPreset preset, Predicate<List<String>> provided) {
        if (!provided.test(preset.start())) {
            return false;
        }
        for (StartTemplate template : preset.dimensions().templates().values()) {
            if (!provided.test(template.actions())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every world these presets make islands in: {@code islandWorld}, the server's own, first, then
     * each world a preset names, once each.
     */
    public List<String> worlds(String islandWorld) {
        java.util.LinkedHashSet<String> worlds = new java.util.LinkedHashSet<>();
        worlds.add(islandWorld);
        for (StarterPreset preset : presets) {
            worlds.add(preset.worldOr(islandWorld));
        }
        return List.copyOf(worlds);
    }

    public StarterPresetCatalog catalogue() {
        return new StarterPresetCatalog(presets, defaultId);
    }
}
