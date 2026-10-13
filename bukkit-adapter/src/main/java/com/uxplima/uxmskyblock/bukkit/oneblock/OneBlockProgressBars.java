package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockDisplay;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;

/**
 * A OneBlock island's progress shown to the player who breaks its block: on a boss bar that goes once they stop
 * breaking, on the action bar, or nowhere. The server names where it shows; each player may switch it, and their
 * choice is kept with them.
 */
public final class OneBlockProgressBars {

    /** Where a player chose their progress shows, kept in their own data. */
    static final NamespacedKey CHOSEN = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:oneblock_bar"));

    private final Supplier<OneBlockConfiguration> config;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> shownAt = new ConcurrentHashMap<>();

    public OneBlockProgressBars(Supplier<OneBlockConfiguration> config, SchedulerPort scheduler, Messages messages) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Where the player's progress shows: their own choice, or the server's. On the player's thread. */
    public OneBlockDisplay.Bar shownTo(Player player) {
        String chosen = player.getPersistentDataContainer().get(CHOSEN, PersistentDataType.STRING);
        return chosen == null
                ? config.get().display().bar()
                : OneBlockDisplay.Bar.named(chosen)
                        .orElse(config.get().display().bar());
    }

    /** Moves the player's progress to the next place it may show, and tells them where. On the player's thread. */
    public void switchFor(Player player) {
        OneBlockDisplay.Bar next = shownTo(player).next();
        player.getPersistentDataContainer().set(CHOSEN, PersistentDataType.STRING, next.key());
        if (next != OneBlockDisplay.Bar.BOSSBAR) {
            hide(player);
        }
        messages.send(player, "oneblock.bar.switched", Placeholder.unparsed("bar", named(player, next)));
    }

    /** The place a progress shows, in the player's language. */
    public String named(Player player, OneBlockDisplay.Bar bar) {
        return messages.named(player, "oneblock.bar.names", bar.key(), bar.key());
    }

    /** Shows the player where the island stands after their break, where they chose. On the player's thread. */
    public void show(Player player, OneBlockPhases.Position at) {
        OneBlockDisplay.Bar where = shownTo(player);
        if (where == OneBlockDisplay.Bar.NONE) {
            return;
        }
        String phase = at.phase().key();
        Component line = messages.renderPlain(
                player,
                "oneblock.bar.line",
                Placeholder.unparsed("phase", messages.named(player, "oneblock.phases", phase, phase)),
                Placeholder.unparsed("broken", Long.toString(at.intoPhase())),
                Placeholder.unparsed("length", Long.toString(at.phaseLength())));
        if (where == OneBlockDisplay.Bar.ACTIONBAR) {
            player.sendActionBar(line);
            return;
        }
        float progress = at.phaseLength() <= 0 ? 1f : Math.min(1f, (float) at.intoPhase() / (float) at.phaseLength());
        UUID who = player.getUniqueId();
        BossBar bar = bars.computeIfAbsent(
                who, id -> BossBar.bossBar(line, progress, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10));
        bar.name(line);
        bar.progress(progress);
        player.showBossBar(bar);
        long shown = shownAt.computeIfAbsent(who, id -> new AtomicLong()).incrementAndGet();
        // Gone once the player stops breaking, unless another break showed it again since.
        scheduler.asyncAfter(
                config.get().display().linger(),
                () -> scheduler.onEntity(who, () -> {
                    AtomicLong last = shownAt.get(who);
                    if (last != null && last.get() == shown) {
                        hide(player);
                    }
                }));
    }

    /** Lets go of what is kept for a player who left; their game drops the bar with them. */
    public void forget(UUID player) {
        bars.remove(player);
        shownAt.remove(player);
    }

    /** Takes the player's boss bar away, as when they leave or switch it off. */
    public void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        shownAt.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }
}
