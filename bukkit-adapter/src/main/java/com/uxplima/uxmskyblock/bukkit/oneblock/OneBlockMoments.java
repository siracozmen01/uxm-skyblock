package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockMoment;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.loot.LootContainers;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * Carries out a OneBlock moment: what the phase file writes for one exact break. The block, its creature, its loot
 * and its line are put into the world on the block's own thread; the lists run for the breaker on theirs.
 */
public final class OneBlockMoments {

    /** The interaction every moment fires, besides the moment's own list. */
    static final String MOMENT = "oneblock-moment";

    /** How far above the block's top its line hangs. */
    static final double TEXT_RISE = 0.6;

    private final Supplier<OneBlockConfiguration> config;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Predicate<Player> bedrock;
    private final OneBlockPauses.Lines lines;

    public OneBlockMoments(
            Supplier<OneBlockConfiguration> config,
            SchedulerPort scheduler,
            Messages messages,
            Predicate<Player> bedrock,
            OneBlockPauses.Lines lines) {
        this.lines = Objects.requireNonNull(lines, "lines must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock must not be null");
    }

    /** What happens once {@code broken} blocks of {@code phase} are broken, if the phase file writes anything. */
    public Optional<OneBlockMoment> at(String phase, long broken) {
        return config.get().momentAt(phase, broken);
    }

    /**
     * Puts the moment into the world at the block, on the block's region: its loot table on the container it laid,
     * its creature, and its line over the block for its time. A Bedrock player, who is shown no line in the world,
     * reads it on the action bar.
     */
    void inTheWorld(OneBlockDraws draws, OneBlockMoment moment, World world, int x, int y, int z, Player breaker) {
        Block block = world.getBlockAt(x, y, z);
        String loot = moment.loot();
        if (loot != null) {
            LootContainers.give(block, loot, ThreadLocalRandom.current().nextLong());
        }
        String creature = moment.creature();
        if (creature != null) {
            draws.spawn(new Location(world, x + 0.5, y + 1, z + 0.5), creature, breaker);
        }
        String text = moment.text();
        if (text == null) {
            return;
        }
        // A catalogue key is read in each viewer's language; written text reads the same to all.
        Function<Player, Component> line;
        if (text.startsWith("@")) {
            String key = text.substring(1);
            line = viewer -> messages.renderPlain(viewer, key);
        } else {
            Component written = MiniMessage.miniMessage().deserialize(text);
            line = viewer -> written;
        }
        OneBlockPauses.Shown shown = lines.show(world, new Location(world, x + 0.5, y + 1 + TEXT_RISE, z + 0.5), line);
        scheduler.asyncAfter(
                moment.textTime(), () -> scheduler.onRegion(world.getName(), x >> 4, z >> 4, shown::remove));
        if (bedrock.test(breaker)) {
            breaker.sendActionBar(line.apply(breaker));
        }
    }

    /** Runs the lists of the moment for the breaker, on the breaker's thread: every moment's, then its own. */
    void forTheBreaker(
            InteractionEffectPlayer effects,
            InteractionEffects shipped,
            OneBlockMoment moment,
            Player breaker,
            Map<String, String> says) {
        effects.fire(shipped, "oneblock-moment", breaker, says);
        effects.fire(new InteractionEffects(Map.of(MOMENT, moment.actions())), MOMENT, breaker, says);
    }
}
