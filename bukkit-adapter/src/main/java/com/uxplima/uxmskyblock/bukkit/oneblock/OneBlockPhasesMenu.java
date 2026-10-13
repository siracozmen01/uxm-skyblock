package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockGate;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import org.jspecify.annotations.Nullable;

/**
 * Every phase of the OneBlock files, in {@code menus/oneblock-phases.conf}: the ones an island has reached, the one
 * it is in and the ones still locked to it, each with what stands before it and what it holds. A Bedrock player gets
 * the same file as a form. A phase off the island's way, as a branch it did not pick, stays locked.
 */
public final class OneBlockPhasesMenu {

    /** The menu file that lists the phases. */
    public static final String MENU = "oneblock-phases";

    static final String PHASES = "skyblock:oneblock-phase-list";

    /** Where an island stands with one phase. */
    enum State {
        REACHED("good"),
        CURRENT("accent"),
        LOCKED("muted");

        private final String colour;

        State(String colour) {
            this.colour = colour;
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Supplier<OneBlockConfiguration> config;
    private final Messages messages;
    private volatile @Nullable SkyblockMenuEngine engine;

    public OneBlockPhasesMenu(Supplier<OneBlockConfiguration> config, Messages messages) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Hands it the engine that draws its file. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine != null) {
            engine.handedList(PHASES);
        }
    }

    /**
     * Shows the player every phase, for an island along {@code way} that has broken {@code broken} blocks, on their
     * thread.
     *
     * @return whether the window opened; without the file there is nothing to show
     */
    public boolean show(Player player, OneBlockPhases way, long broken) {
        SkyblockMenuEngine menus = engine;
        OneBlockPhases.Position at = way.positionAt(broken);
        Map<String, String> values = Map.of(
                "phase",
                messages.named(
                        player, "oneblock.phases", at.phase().key(), at.phase().key()));
        return menus != null && menus.open(player, MENU, values, Map.of(PHASES, rows(player, way, broken)));
    }

    /** One row for each phase the files hold, in their order, each standing as the island stands with it. */
    List<MenuRow> rows(Player viewer, OneBlockPhases way, long broken) {
        OneBlockConfiguration files = config.get();
        OneBlockPhases.Position at = way.positionAt(broken);
        boolean again = way.cycleOf(broken) > 0;
        List<String> along = way.phases().stream().map(OneBlockPhase::key).toList();
        List<OneBlockPhase> every = files.phases().phases();
        List<MenuRow> rows = new ArrayList<>(every.size());
        for (int number = 0; number < every.size(); number++) {
            String key = every.get(number).key();
            int on = along.indexOf(key);
            State state;
            if (key.equals(at.phase().key())) {
                state = State.CURRENT;
            } else if (on >= 0 && (on < at.index() || again)) {
                state = State.REACHED;
            } else {
                state = State.LOCKED;
            }
            OneBlockGate gate = files.gateOf(key);
            String requires = requires(viewer, gate.requires());
            String wait = gate.pause().isZero() ? "" : OneBlockPauses.clockFace(gate.pause());
            String reward = files.previewOf(key)
                    .map(written -> messages.words(viewer, written))
                    .orElse("");
            StringBuilder facts = new StringBuilder("number length state");
            if (!wait.isEmpty()) {
                facts.append(" wait");
            }
            if (!requires.isEmpty()) {
                facts.append(" requires");
            }
            if (reward.isEmpty()) {
                facts.append(" -description");
            }
            Map<String, String> words = new java.util.HashMap<>();
            words.put("phase", messages.named(viewer, "oneblock.phases", key, key));
            words.put("key", key);
            words.put("number", Integer.toString(number + 1));
            words.put("blocks", Long.toString(every.get(number).blocks()));
            words.put("icon", files.iconOf(key));
            words.put("colour", state.colour);
            words.put(
                    "state",
                    PLAIN.serialize(messages.renderPlain(viewer, "menu.oneblock_phases.states." + state.key())));
            words.put("facts", facts.toString());
            words.put("wait", wait);
            words.put("requires", requires);
            words.put("reward", reward);
            rows.add(new MenuRow(Map.copyOf(words), key));
        }
        return List.copyOf(rows);
    }

    /**
     * What a phase asks before it begins, one line each, as the breaker would be told it. A line after the first is
     * set in as far as a tile's facts are, so the lines stand under each other.
     */
    private String requires(Player viewer, List<Requirement> lines) {
        StringJoiner joined = new StringJoiner("\n    ");
        for (Requirement line : lines) {
            Component said = OneBlockGates.lineOf(messages, viewer, line);
            if (said != null) {
                joined.add(PLAIN.serialize(said).strip());
            }
        }
        return joined.toString();
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
}
