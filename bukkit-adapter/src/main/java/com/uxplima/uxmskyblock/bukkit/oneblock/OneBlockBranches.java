package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import org.jspecify.annotations.Nullable;

/**
 * The way on from a OneBlock phase that branches. An island at its end waits there, and each break of its block
 * shows the breaker the phases it may go on to, in {@code menus/oneblock-branch.conf}; a Bedrock player gets the same
 * file as a form. A click picks one. With no pick in the time {@code choose-within} sets, the first is taken at the
 * next break. A pick is stored, so the island keeps its way.
 */
public final class OneBlockBranches {

    /** The menu file that offers the ways on. */
    public static final String MENU = "oneblock-branch";

    static final String WAYS = "skyblock:oneblock-ways";

    /** The verb a way's tile runs to pick it. */
    static final String PICK = "skyblock:oneblock-pick";

    /** One way on: the phase the island ends, and the phase it may go on to. */
    record Way(IslandId islandId, String phase, String chosen) {}

    private final Supplier<OneBlockConfiguration> config;
    private final OneBlockService service;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Clock clock;
    private final Map<IslandId, Instant> pickBy = new ConcurrentHashMap<>();
    private volatile @Nullable SkyblockMenuEngine engine;

    public OneBlockBranches(
            Supplier<OneBlockConfiguration> config,
            OneBlockService service,
            SchedulerPort scheduler,
            Messages messages,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Hands it the engine that draws its file, and teaches the engine what a click on a way does. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(WAYS);
        engine.action(
                PICK,
                ctx -> MenuRow.handle(ctx.context(), Way.class).ifPresent(way -> {
                    ctx.player().closeInventory();
                    choose(ctx.player(), way, false);
                }));
    }

    /**
     * Offers the breaker the ways on from {@code phase}, at its end, on their thread. Once the time to pick is up, the
     * first is taken instead.
     *
     * @return whether the way was taken now, so the break goes on into the phase that follows
     */
    public boolean offer(Island island, Player player, String phase, List<String> options) {
        IslandId islandId = island.id();
        Instant now = clock.instant();
        Instant by =
                pickBy.computeIfAbsent(islandId, id -> now.plus(config.get().chooseWithin()));
        if (!now.isBefore(by)) {
            return choose(player, new Way(islandId, phase, options.getFirst()), true);
        }
        String left = OneBlockPauses.clockFace(java.time.Duration.between(now, by));
        SkyblockMenuEngine menus = engine;
        if (menus != null && menus.showing(player, MENU)) {
            return false;
        }
        Map<String, String> values = Map.of("phase", title(player, phase), "time", left);
        if (menus != null && menus.open(player, MENU, values, Map.of(WAYS, rows(player, islandId, phase, options)))) {
            return false;
        }
        // The operator took the file away: the ways are said, and the first is taken when the time is up.
        messages.send(
                player,
                "oneblock.branch.offer",
                Placeholder.unparsed("phase", title(player, phase)),
                Placeholder.unparsed("time", left),
                Placeholder.unparsed("first", title(player, options.getFirst())));
        for (String option : options) {
            messages.sendPlain(player, "oneblock.branch.way", Placeholder.unparsed("way", title(player, option)));
        }
        return false;
    }

    /** One row for each way on: the phase's name, its title and how long it lasts. */
    List<MenuRow> rows(Player player, IslandId islandId, String phase, List<String> options) {
        List<MenuRow> rows = new ArrayList<>(options.size());
        for (String option : options) {
            long blocks = config.get().phases().phases().stream()
                    .filter(one -> one.key().equals(option))
                    .mapToLong(OneBlockPhase::blocks)
                    .findFirst()
                    .orElse(0);
            rows.add(new MenuRow(
                    Map.of("phase", title(player, option), "key", option, "blocks", Long.toString(blocks)),
                    new Way(islandId, phase, option)));
        }
        return List.copyOf(rows);
    }

    /** Picks the way for the island and writes it off the region; {@code taken} says the time ran out. */
    boolean choose(Player player, Way way, boolean taken) {
        if (!service.pick(way.islandId(), way.phase(), way.chosen())) {
            return false;
        }
        pickBy.remove(way.islandId());
        scheduler.async(() -> service.storePick(way.islandId(), way.phase()));
        messages.send(
                player,
                taken ? "oneblock.branch.taken" : "oneblock.branch.picked",
                Placeholder.unparsed("phase", title(player, way.phase())),
                Placeholder.unparsed("way", title(player, way.chosen())));
        return true;
    }

    private String title(Player player, String phase) {
        return messages.named(player, "oneblock.phases", phase, phase);
    }
}
