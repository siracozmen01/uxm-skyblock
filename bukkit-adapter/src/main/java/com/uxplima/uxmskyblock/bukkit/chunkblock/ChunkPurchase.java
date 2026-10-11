package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks.Outcome;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * A player paying for a chunk of their island: one line of its price, every line they can, or its bypass.
 *
 * <p>What the player hands in is taken on their own thread and recorded off it, and what the chunk did not need by
 * then goes back to them on their thread again. What the island pays comes out of its bank, which only a member
 * whose role may take from the bank can spend. When a payment opens the chunk, the player hears it, and the first time
 * it opens its grade's rewards fire for them.
 */
public final class ChunkPurchase {

    private final ChunkUnlocks unlocks;
    private final IslandStoragePort islands;
    private final IslandAccessService access;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final ChunkPurse purse;
    private final BiConsumer<Player, ChunkTier> rewards;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final BiFunction<IslandId, ProfileId, Long> levelOf;
    private final ServerNodeId node;

    /**
     * @param rewards fires a grade's rewards for a player, on the player's thread
     * @param levelOf the island's level as the level command works it out, read off the main thread
     * @param node this server, which the island bank checks holds the island
     */
    public ChunkPurchase(
            ChunkUnlocks unlocks,
            IslandStoragePort islands,
            IslandAccessService access,
            SchedulerPort scheduler,
            Messages messages,
            ChunkPurse purse,
            BiConsumer<Player, ChunkTier> rewards,
            Function<UUID, Optional<ProfileId>> activeProfile,
            BiFunction<IslandId, ProfileId, Long> levelOf,
            ServerNodeId node) {
        this.unlocks = Objects.requireNonNull(unlocks, "unlocks must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.purse = Objects.requireNonNull(purse, "purse must not be null");
        this.rewards = Objects.requireNonNull(rewards, "rewards must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.levelOf = Objects.requireNonNull(levelOf, "levelOf must not be null");
        this.node = Objects.requireNonNull(node, "node must not be null");
    }

    public ChunkUnlocks unlocks() {
        return unlocks;
    }

    /** Pays every line of {@code chunk}'s price the player and, where their role lets them, the island can. */
    public void payAll(Player player, ChunkPos chunk, Runnable then) {
        pay(player, chunk, Optional.empty(), then);
    }

    /** Pays the one line {@code line} of {@code chunk}'s price, as far as the payer can. */
    public void payLine(Player player, ChunkPos chunk, ChunkRequirement line, Runnable then) {
        pay(player, chunk, Optional.of(line), then);
    }

    private void pay(Player player, ChunkPos chunk, Optional<ChunkRequirement> only, Runnable then) {
        withIsland(player, at -> {
            Optional<ChunkUnlocks.Offer> read =
                    unlocks.offer(at.islandId(), chunk, at.island().bounds());
            if (read.isEmpty()) {
                finish(player, at, Round.refused(Outcome.NOT_CHUNKBLOCK), then);
                return;
            }
            ChunkUnlocks.Offer offer = read.get();
            Round round = new Round(offer.tier());
            List<ChunkRequirement> owing = offer.tier().cost().stream()
                    .filter(ChunkRequirement::paid)
                    .filter(line -> only.isEmpty() || only.get().equals(line))
                    .filter(line -> offer.remaining(line) > 0)
                    .toList();
            if (only.isPresent() && owing.isEmpty()) {
                round.note(offer.tier().cost().contains(only.get()) ? Outcome.ALREADY_PAID : Outcome.NOT_ON_COST);
            }
            ChunkUnlocks.IslandPayer payer = new ChunkUnlocks.IslandPayer(player.getUniqueId(), node);
            for (ChunkRequirement line : owing) {
                if (ChunkPurse.handedIn(line)) {
                    continue;
                }
                if (!access.canWithdrawBank(at.island(), at.profile())) {
                    round.unpermitted = true;
                    continue;
                }
                round.add(
                        unlocks.payFromIsland(at.islandId(), chunk, at.island().bounds(), line, payer, at.level()));
            }
            List<ChunkRequirement> handed =
                    owing.stream().filter(ChunkPurse::handedIn).toList();
            if (handed.isEmpty() || round.opened.isPresent()) {
                finish(player, at, round, chunk, then);
                return;
            }
            Map<ChunkRequirement, Long> wanted = new LinkedHashMap<>();
            handed.forEach(line -> wanted.put(line, offer.remaining(line)));
            onPlayer(player, () -> {
                Map<ChunkRequirement, Long> taken = new LinkedHashMap<>();
                wanted.forEach((line, amount) -> taken.put(line, purse.take(player, line, amount)));
                scheduler.async(() -> {
                    Map<ChunkRequirement, Long> back = new LinkedHashMap<>();
                    taken.forEach((line, amount) -> {
                        if (amount == 0) {
                            round.note(Outcome.NOTHING_TAKEN);
                            return;
                        }
                        ChunkUnlocks.Result result =
                                unlocks.handIn(at.islandId(), chunk, at.island().bounds(), line, amount, at.level());
                        round.add(result);
                        if (result.excess() > 0) {
                            back.put(line, result.excess());
                        }
                    });
                    onPlayer(player, () -> back.forEach((line, amount) -> purse.giveBack(player, line, amount)));
                    finish(player, at, round, chunk, then);
                });
            });
        });
    }

    /**
     * Opens {@code chunk} by its grade's bypass: every line of it at once, in full, or nothing. What the player hands
     * in is taken first and goes back when the chunk does not open.
     */
    public void bypass(Player player, ChunkPos chunk, Runnable then) {
        withIsland(player, at -> {
            Optional<ChunkUnlocks.Offer> read =
                    unlocks.offer(at.islandId(), chunk, at.island().bounds());
            if (read.isEmpty()) {
                finish(player, at, Round.refused(Outcome.NOT_CHUNKBLOCK), then);
                return;
            }
            List<ChunkRequirement> bypass = read.get().tier().bypass();
            if (bypass.isEmpty()) {
                finish(player, at, Round.refused(Outcome.NO_BYPASS), then);
                return;
            }
            boolean fromIsland = bypass.stream().anyMatch(line -> !ChunkPurse.handedIn(line));
            if (fromIsland && !access.canWithdrawBank(at.island(), at.profile())) {
                Round round = new Round(read.get().tier());
                round.unpermitted = true;
                finish(player, at, round, then);
                return;
            }
            List<ChunkRequirement> handed =
                    bypass.stream().filter(ChunkPurse::handedIn).toList();
            onPlayer(player, () -> {
                if (handed.stream().anyMatch(line -> purse.carried(player, line) < line.amount())) {
                    messages.send(player, "chunkblock.bypass_short");
                    then.run();
                    return;
                }
                List<ChunkRequirement> taken = new ArrayList<>();
                for (ChunkRequirement line : handed) {
                    long got = purse.take(player, line, line.amount());
                    if (got < line.amount()) {
                        purse.giveBack(player, line, got);
                        taken.forEach(back -> purse.giveBack(player, back, back.amount()));
                        messages.send(player, "chunkblock.bypass_short");
                        then.run();
                        return;
                    }
                    taken.add(line);
                }
                scheduler.async(() -> {
                    ChunkUnlocks.Result result = unlocks.bypass(
                            at.islandId(),
                            chunk,
                            at.island().bounds(),
                            taken,
                            new ChunkUnlocks.IslandPayer(player.getUniqueId(), node));
                    if (result.outcome() != Outcome.OPENED) {
                        onPlayer(player, () -> taken.forEach(line -> purse.giveBack(player, line, line.amount())));
                    }
                    Round round = new Round(read.get().tier());
                    round.add(result);
                    finish(player, at, round, then);
                });
            });
        });
    }

    /** After a round of payments: opens the chunk if they left nothing but the level to meet, and says how it went. */
    private void finish(Player player, At at, Round round, ChunkPos chunk, Runnable then) {
        if (round.opened.isEmpty()) {
            ChunkUnlocks.Result opened =
                    unlocks.open(at.islandId(), chunk, at.island().bounds(), at.level());
            if (opened.outcome() == Outcome.OPENED || opened.outcome() == Outcome.LEVEL_TOO_LOW) {
                round.add(opened);
            }
        }
        if (round.levelShort) {
            unlocks.offer(at.islandId(), chunk, at.island().bounds())
                    .ifPresent(offer -> round.needed = offer.tier().cost().stream()
                            .filter(ChunkRequirement.Level.class::isInstance)
                            .mapToLong(line -> ((ChunkRequirement.Level) line).of(offer.ladder()))
                            .max()
                            .orElse(0));
        }
        finish(player, at, round, then);
    }

    private void finish(Player player, At at, Round round, Runnable then) {
        onPlayer(player, () -> {
            ChunkTier tier = round.opened.orElse(round.tier);
            TagResolver named = Placeholder.unparsed("tier", tier == null ? "" : messages.words(player, tier.name()));
            if (round.opened.isPresent()) {
                messages.send(player, "chunkblock.opened", named);
                if (round.rewards) {
                    rewards.accept(player, round.opened.get());
                }
            } else if (round.levelShort) {
                messages.send(
                        player,
                        "chunkblock.level_too_low",
                        Placeholder.unparsed("island_level", Long.toString(round.needed)),
                        Placeholder.unparsed("current", Long.toString(at.level())));
            } else if (round.paid) {
                messages.send(player, "chunkblock.paid", named);
            } else if (round.unpermitted) {
                messages.send(player, "chunkblock.no_bank_right");
            } else {
                messages.send(player, key(round.refusal.orElse(Outcome.NOTHING_TAKEN)), named);
            }
            then.run();
        });
    }

    /** The language key that says {@code outcome} to the payer. */
    static String key(Outcome outcome) {
        return switch (outcome) {
            case OPENED -> "chunkblock.opened";
            case PAID -> "chunkblock.paid";
            case ALREADY_PAID -> "chunkblock.already_paid";
            case NOTHING_TAKEN -> "chunkblock.nothing_taken";
            case LEVEL_TOO_LOW -> "chunkblock.level_too_low";
            case NOT_ON_COST -> "chunkblock.not_on_cost";
            case NO_BYPASS -> "chunkblock.no_bypass";
            case BANK_REFUSED -> "chunkblock.bank_refused";
            case FAILED -> "chunkblock.failed";
            case ALREADY_OPEN -> "chunkblock.already_open";
            case NOT_BESIDE_TERRITORY -> "chunkblock.not_beside";
            case OUTSIDE_ISLAND -> "chunkblock.outside_island";
            case NOT_CHUNKBLOCK -> "chunkblock.not_chunkblock";
            case TAKEN -> "chunkblock.taken";
        };
    }

    /** Finds the player's island and its level off the main thread, then runs {@code task} there. */
    private void withIsland(Player player, java.util.function.Consumer<At> task) {
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            tell(player, "error.session_not_active");
            return;
        }
        scheduler.async(() -> {
            Optional<Island> island =
                    islands.findIslandIdByProfileId(profile.get()).flatMap(islands::findIslandById);
            if (island.isEmpty()) {
                tell(player, "error.no_island");
                return;
            }
            IslandId islandId = island.get().id();
            task.accept(new At(islandId, island.get(), profile.get(), levelOf.apply(islandId, profile.get())));
        });
    }

    private void onPlayer(Player player, Runnable task) {
        scheduler.onEntity(new PlayerUuid(player.getUniqueId()), task);
    }

    private void tell(Player player, String key) {
        onPlayer(player, () -> messages.send(player, key));
    }

    /** The payer's island, as one round of payments reads it. */
    private record At(IslandId islandId, Island island, ProfileId profile, long level) {}

    /** What one round of payments came to, gathered across its lines. */
    private static final class Round {

        private final @org.jspecify.annotations.Nullable ChunkTier tier;
        private Optional<ChunkTier> opened = Optional.empty();
        private Optional<Outcome> refusal = Optional.empty();
        private boolean paid;
        private boolean rewards;
        private boolean levelShort;
        private boolean unpermitted;
        private long needed;

        Round(@org.jspecify.annotations.Nullable ChunkTier tier) {
            this.tier = tier;
        }

        static Round refused(Outcome outcome) {
            Round round = new Round(null);
            round.note(outcome);
            return round;
        }

        synchronized void add(ChunkUnlocks.Result result) {
            if (result.opened().isPresent()) {
                opened = result.opened();
                rewards |= result.rewards();
            }
            switch (result.outcome()) {
                case PAID, OPENED -> paid = true;
                case LEVEL_TOO_LOW -> {
                    paid |= result.taken() > 0;
                    levelShort = true;
                }
                default -> note(result.outcome());
            }
        }

        synchronized void note(Outcome outcome) {
            if (refusal.isEmpty()) {
                refusal = Optional.of(outcome);
            }
        }
    }
}
