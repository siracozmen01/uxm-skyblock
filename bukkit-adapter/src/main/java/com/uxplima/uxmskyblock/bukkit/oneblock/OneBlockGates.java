package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurse;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.requirement.LinePayment;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import org.jspecify.annotations.Nullable;

/**
 * The lines a OneBlock phase asks before it begins. An island at the end of the phase before waits there, and each
 * break of its block asks the lines again: the island's level and bank off the region threads, then the breaker's
 * permission, money, currency and items on their own thread. When every line holds, what is to be taken is taken,
 * from the breaker first and then from the island's bank, given back whole if any part cannot be, and the phase
 * begins. The wait is stored, so the island's standing reads it after a restart.
 */
public final class OneBlockGates {

    /** The island's bank, as a phase's lines reach into it. Every call runs off the region threads. */
    public interface IslandFunds extends LinePayment.IslandFunds {}

    private final Supplier<OneBlockConfiguration> config;
    private final OneBlockService service;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final LinePayment payment;
    private final Set<IslandId> asking = ConcurrentHashMap.newKeySet();

    /** @param levelOf the island's level as the level command works it out, read off the region threads */
    public OneBlockGates(
            Supplier<OneBlockConfiguration> config,
            OneBlockService service,
            SchedulerPort scheduler,
            Messages messages,
            ChunkPurse purse,
            IslandFunds funds,
            BiFunction<Island, Player, Long> levelOf) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.payment = new LinePayment(scheduler, purse, funds, levelOf);
    }

    /** Whether the phase asks any line before it begins. */
    public boolean asks(String phase) {
        return !config.get().gateOf(phase).requires().isEmpty();
    }

    /**
     * Asks the lines of {@code phase} for the island, the breaker paying what is taken, and runs {@code opened} once
     * every line held and was paid. An island already being asked is not asked twice at once.
     */
    public void ask(Island island, Player player, String phase, Runnable opened) {
        IslandId islandId = island.id();
        if (!asking.add(islandId)) {
            return;
        }
        List<Requirement> lines = config.get().gateOf(phase).requires();
        if (service.waiting(islandId).isEmpty() && service.waitBefore(islandId, phase, null)) {
            scheduler.async(() -> service.storeWait(islandId));
        }
        payment.pay(
                island,
                player,
                lines,
                new LinePayment.Outcome() {
                    @Override
                    public void missing(List<Requirement> wanting) {
                        tell(player, phase, wanting);
                    }

                    @Override
                    public void paid() {
                        service.waiting(islandId).ifPresent(waiting -> service.endWait(islandId, waiting));
                        service.storeWait(islandId);
                        // Said before the phase begins, so the breaker hears why it does.
                        scheduler.onEntity(player.getUniqueId(), () -> {
                            messages.send(
                                    player,
                                    "oneblock.requires.met",
                                    Placeholder.unparsed("phase", title(player, phase)));
                            opened.run();
                        });
                    }
                },
                () -> asking.remove(islandId));
    }

    /** Tells the breaker the phase waits, and for which lines. */
    private void tell(Player player, String phase, List<Requirement> missing) {
        messages.send(player, "oneblock.requires.missing", Placeholder.unparsed("phase", title(player, phase)));
        for (Requirement line : missing) {
            said(player, line);
        }
    }

    private void said(Player player, Requirement line) {
        Component said = lineOf(messages, player, line);
        if (said != null) {
            player.sendMessage(said);
        }
    }

    /** One requirement line in the viewer's language, as the breaker is told it and the phases menu lists it. */
    static @Nullable Component lineOf(Messages messages, Audience viewer, Requirement line) {
        return LinePayment.lineOf(messages, viewer, line, "oneblock.requires");
    }

    /** {@code minecraft:oak_log} as {@code oak log}. */
    static String itemName(String material) {
        return LinePayment.itemName(material);
    }

    private String title(Player player, String phase) {
        return messages.named(player, "oneblock.phases", phase, phase);
    }
}
