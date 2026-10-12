package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmlib.condition.action.Action;
import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmlib.condition.action.ActionParser;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads what every interaction fires off the operator's file.
 *
 * <p>The lines are the family's own grammar, {@code [verb] payload}, read by the library's action
 * engine. One engine and one vocabulary across the estate: a line copied out of any other plugin's
 * file means here what it means there.
 *
 * <p>Each line is parsed on its own. The engine's own parser throws on the first line it cannot
 * read, and a list built in one call would be dropped whole, so a typo in a cosmetic line would
 * take the sound beside it with it and nothing would say so. A line that cannot be read is logged
 * and skipped, and the rest of the list still fires.
 */
public final class EffectsConfiguration {

    private static final Logger LOGGER = Logger.getLogger(EffectsConfiguration.class.getName());

    private EffectsConfiguration() {
        throw new UnsupportedOperationException("EffectsConfiguration is a way of reading a file, not a thing to hold");
    }

    /**
     * What this plugin ships with, which is what it used to do with the effects written into it.
     *
     * <p>A node whose file is missing or empty behaves as it always did rather than falling silent.
     */
    public static InteractionEffects defaultConfiguration() {
        Map<String, ActionList> defaults = new LinkedHashMap<>();
        defaults.put(
                "kinetic-ward",
                parseAll(
                        "kinetic-ward",
                        List.of("[sound] entity.player.attack.sweep 1.0 1.2", "[particle] CRIT 20 0.8")));
        defaults.put(
                "obsidian-recovery",
                parseAll(
                        "obsidian-recovery",
                        List.of("[sound] item.bucket.fill_lava 1.0 1.0", "[particle] CAMPFIRE_COSY_SMOKE 6 0.2")));
        defaults.put("limit-refused", parseAll("limit-refused", List.of("[sound] block.note_block.bass 1.0 0.5")));
        defaults.put(
                "mission-completed",
                parseAll("mission-completed", List.of("[sound] ui.toast.challenge_complete 1.0 1.0")));

        // The milestones a server decorates. The command still gives its own reply out of the
        // catalogue; this is what happens beside it.
        defaults.put(
                "island-created", parseAll("island-created", List.of("[sound] ui.toast.challenge_complete 1.0 1.2")));
        defaults.put("member-joined", parseAll("member-joined", List.of("[sound] entity.player.levelup 1.0 1.4")));
        defaults.put("upgrade-bought", parseAll("upgrade-bought", List.of("[sound] entity.player.levelup 1.0 1.0")));
        defaults.put(
                "oneblock-phase-finished",
                parseAll("oneblock-phase-finished", List.of("[sound] block.amethyst_block.chime 1.0 1.0")));
        defaults.put(
                "oneblock-phase-began",
                parseAll(
                        "oneblock-phase-began",
                        List.of("[sound] ui.toast.challenge_complete 1.0 0.8", "[particle] TOTEM_OF_UNDYING 30 0.6")));
        defaults.put(
                "acid-sea-burns",
                parseAll("acid-sea-burns", List.of("[sound] block.fire.extinguish 0.5 1.6", "[particle] SMOKE 8 0.3")));
        defaults.put("acid-rain-burns", parseAll("acid-rain-burns", List.of("[sound] block.fire.extinguish 0.3 1.9")));
        defaults.put("acid-water-drunk", parseAll("acid-water-drunk", List.of("[sound] entity.generic.drink 1.0 0.6")));
        defaults.put(
                "acid-clean-water-filled",
                parseAll("acid-clean-water-filled", List.of("[sound] item.bottle.fill 1.0 1.0")));
        defaults.put("acid-vent-fumes", parseAll("acid-vent-fumes", List.of("[particle] CAMPFIRE_COSY_SMOKE 4 0.4")));
        defaults.put("boxed-grew", parseAll("boxed-grew", List.of("[sound] entity.player.levelup 0.8 1.6")));
        defaults.put(
                "brix-plot-entered", parseAll("brix-plot-entered", List.of("[sound] block.beacon.activate 0.6 1.4")));
        defaults.put("brix-plot-left", parseAll("brix-plot-left", List.of("[sound] block.beacon.deactivate 0.6 1.4")));
        defaults.put(
                "parkour-course-entered",
                parseAll("parkour-course-entered", List.of("[sound] block.beacon.activate 0.6 1.4")));
        defaults.put(
                "parkour-course-left",
                parseAll("parkour-course-left", List.of("[sound] block.beacon.deactivate 0.6 1.4")));
        defaults.put(
                "parkour-run-started",
                parseAll("parkour-run-started", List.of("[sound] block.note_block.pling 0.8 1.2")));
        defaults.put(
                "parkour-checkpoint", parseAll("parkour-checkpoint", List.of("[sound] block.note_block.bell 0.8 1.5")));
        defaults.put(
                "parkour-finished", parseAll("parkour-finished", List.of("[sound] entity.player.levelup 0.8 1.2")));
        defaults.put(
                "parkour-record",
                parseAll(
                        "parkour-record",
                        List.of("[sound] ui.toast.challenge_complete 0.8 1.0", "[particle] TOTEM_OF_UNDYING 20 0.5")));
        defaults.put("poseidon-dry-air", parseAll("poseidon-dry-air", List.of("[sound] entity.player.breath 0.6 0.7")));
        defaults.put(
                "poseidon-sun-burns",
                parseAll(
                        "poseidon-sun-burns",
                        List.of("[sound] block.fire.extinguish 0.4 0.8", "[particle] SMOKE 6 0.3")));
        defaults.put(
                "poseidon-still-drowns",
                parseAll(
                        "poseidon-still-drowns",
                        List.of("[sound] entity.player.hurt_drown 0.8 1.0", "[particle] BUBBLE 10 0.4")));
        defaults.put(
                "loot-rare",
                parseAll(
                        "loot-rare",
                        List.of("[sound] entity.experience_orb.pickup 1.0 1.2", "[particle] HAPPY_VILLAGER 12 0.5")));
        defaults.put(
                "loot-legendary",
                parseAll(
                        "loot-legendary",
                        List.of("[sound] ui.toast.challenge_complete 1.0 1.4", "[particle] TOTEM_OF_UNDYING 30 0.6")));
        defaults.put("vessel-sailed", parseAll("vessel-sailed", List.of("[sound] entity.boat.paddle_water 1.0 0.8")));
        defaults.put(
                "upside-down-mirrored",
                parseAll("upside-down-mirrored", List.of("[sound] block.sculk.spread 0.4 0.6")));
        return new InteractionEffects(defaults);
    }

    public static InteractionEffects load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("effects");
        if (node.virtual() || !node.isMap()) {
            return defaultConfiguration();
        }

        Map<String, ActionList> byInteraction = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String interaction = String.valueOf(entry.getKey());
            List<String> written = new ArrayList<>();
            for (ConfigurationNode line : entry.getValue().childrenList()) {
                String text = line.getString();
                if (text != null && !text.isBlank()) {
                    written.add(text);
                }
            }
            // An interaction written as an empty list is one the operator switched off, which is not
            // the same as one they never mentioned, and both fire nothing.
            byInteraction.put(interaction, parseAll(interaction, written));
        }
        return new InteractionEffects(byInteraction);
    }

    /** Every line of {@code lines} the action engine reads, in order; one it cannot read is logged and skipped. */
    public static ActionList parseAll(String interaction, List<String> lines) {
        List<Action> actions = new ArrayList<>();
        for (String line : lines) {
            try {
                actions.add(ActionParser.parse(line).action());
            } catch (RuntimeException unreadable) {
                LOGGER.warning(() -> "The effect line \"" + line + "\" under " + interaction
                        + " is not something the action engine can read, so it is skipped: "
                        + unreadable.getMessage());
            }
        }
        return ActionList.of(actions);
    }
}
