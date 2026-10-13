package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;

/**
 * A line over a OneBlock island's block: its phase and how far into it the island is, told again at each break, in
 * the server's language, while {@code progress.line} is on. A Bedrock player, who is shown no line in the world,
 * reads it on the action bar. The line is never saved; the first break after a restart draws it again.
 */
public final class OneBlockBlockLines {

    /** How far above the block's top the line hangs, over a moment's line and a wait's countdown. */
    static final double RISE = 1.2;

    private final Supplier<OneBlockConfiguration> config;
    private final Messages messages;
    private final Predicate<Player> bedrock;
    private final OneBlockPauses.Lines lines;
    private final Map<IslandId, OneBlockPauses.Shown> shown = new ConcurrentHashMap<>();

    public OneBlockBlockLines(
            Supplier<OneBlockConfiguration> config,
            Messages messages,
            Predicate<Player> bedrock,
            OneBlockPauses.Lines lines) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock must not be null");
        this.lines = Objects.requireNonNull(lines, "lines must not be null");
    }

    /** As on a server: a text display over the block. */
    public OneBlockBlockLines(Supplier<OneBlockConfiguration> config, Messages messages, Predicate<Player> bedrock) {
        this(config, messages, bedrock, OneBlockPauses::countdownOver);
    }

    /** Tells the island's line again after a break, on the block's region, and the breaker if they play on Bedrock. */
    public void show(IslandId islandId, World world, int x, int y, int z, OneBlockPhases.Position at, Player breaker) {
        if (!config.get().display().line()) {
            take(islandId);
            return;
        }
        String phase = at.phase().key();
        Component line = messages.renderPlain(
                Audience.empty(),
                "oneblock.bar.over_block",
                Placeholder.unparsed("phase", messages.named(Audience.empty(), "oneblock.phases", phase, phase)),
                Placeholder.unparsed("broken", Long.toString(at.intoPhase())),
                Placeholder.unparsed("length", Long.toString(at.phaseLength())));
        OneBlockPauses.Shown now = shown.get(islandId);
        if (now != null && now.showing()) {
            now.say(line);
        } else {
            shown.put(islandId, lines.show(world, new Location(world, x + 0.5, y + 1 + RISE, z + 0.5), line));
        }
        if (bedrock.test(breaker)) {
            breaker.sendActionBar(messages.renderPlain(
                    breaker,
                    "oneblock.bar.over_block",
                    Placeholder.unparsed("phase", messages.named(breaker, "oneblock.phases", phase, phase)),
                    Placeholder.unparsed("broken", Long.toString(at.intoPhase())),
                    Placeholder.unparsed("length", Long.toString(at.phaseLength()))));
        }
    }

    /** Takes the island's line away, as while its phase waits or when the line is turned off. On its region. */
    public void take(IslandId islandId) {
        OneBlockPauses.Shown gone = shown.remove(islandId);
        if (gone != null) {
            gone.remove();
        }
    }
}
