package com.uxplima.uxmskyblock.bukkit.brix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.brix.BrixContests;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import org.jspecify.annotations.Nullable;

/**
 * The Brix build contests as players and operators meet them: {@code /is contest} says where the contest stands,
 * {@code /is contest join} enters the plot a team stands on, {@code /is contest vote} gives the plot a voter stands
 * on their stars, and {@code /isadmin brix startcontest} and {@code stopcontest} open and take one off.
 *
 * <p>A beat off the main thread tells everyone when the votes begin, places the plots once they end, tells everyone
 * the places, and pays each member of each entered plot who is online. A member who was not is paid on joining.
 */
public final class BrixContestPlay implements Listener {

    private static final Logger LOGGER = Logger.getLogger(BrixContestPlay.class.getName());

    /** How often the beat looks at the contest. */
    static final Duration BEAT = Duration.ofSeconds(20);

    /** The interaction every entered plot's members are paid under. */
    public static final String ENTERED = "brix-contest-entered";

    /** The interaction the members of the plot placed {@code n} are paid under, from 1. */
    public static String place(int place) {
        return "brix-contest-place-" + place;
    }

    private final BrixContests contests;
    private final BrixService plots;
    private final IslandProtectionListener islands;
    private final Function<IslandId, Optional<Island>> stored;
    private final Function<UUID, String> names;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final BrixConfiguration.Contests config;
    private final InteractionEffectPlayer effectPlayer;
    private final InteractionEffects rewards;
    private final Supplier<Collection<? extends Player>> online;
    private final IntUnaryOperator pick;
    private final Clock clock;
    private volatile int votesTold;

    /**
     * @param stored the island of an id, read from the database; off the main thread
     * @param names the name of a player by their id, or the id written out when the server never saw them
     * @param online the players on the server; read on the global thread
     * @param pick a number from 0 below the one given, to pick a theme
     */
    public BrixContestPlay(
            BrixContests contests,
            BrixService plots,
            IslandProtectionListener islands,
            Function<IslandId, Optional<Island>> stored,
            Function<UUID, String> names,
            SchedulerPort scheduler,
            Messages messages,
            BrixConfiguration.Contests config,
            InteractionEffectPlayer effectPlayer,
            Supplier<Collection<? extends Player>> online,
            IntUnaryOperator pick,
            Clock clock) {
        this.contests = Objects.requireNonNull(contests, "contests must not be null");
        this.plots = Objects.requireNonNull(plots, "plots must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.stored = Objects.requireNonNull(stored, "stored must not be null");
        this.names = Objects.requireNonNull(names, "names must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.online = Objects.requireNonNull(online, "online must not be null");
        this.pick = Objects.requireNonNull(pick, "pick must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Map<String, ActionList> lists = new HashMap<>();
        lists.put(ENTERED, EffectsConfiguration.parseAll("the Brix contest entered rewards", config.entered()));
        for (int at = 0; at < config.places().size(); at++) {
            lists.put(
                    place(at + 1),
                    EffectsConfiguration.parseAll(
                            "the Brix contest rewards of place " + (at + 1),
                            config.places().get(at)));
        }
        this.rewards = new InteractionEffects(lists);
    }

    /** Starts the beat; closing what it answers stops it. */
    public AutoCloseable start() {
        return scheduler.repeatAsync(this::beat, BEAT, BEAT);
    }

    /** Says where the contest stands, or how the last one ended. */
    public void show(Player player) {
        scheduler.async(() -> {
            try {
                Optional<Contest> under = contests.current();
                if (under.isEmpty()) {
                    Optional<Contest> last = contests.latest();
                    List<ContestStanding> standings = last.map(contest -> contests.standings(contest.number()))
                            .orElse(List.of());
                    if (last.isEmpty() || standings.isEmpty()) {
                        tell(player, "brix.contest.none");
                        return;
                    }
                    String owner = ownerOf(standings.get(0).island());
                    String theme = last.get().theme();
                    onEntity(
                            player,
                            () -> messages.send(
                                    player,
                                    "brix.contest.last",
                                    Placeholder.unparsed("theme", word(player, theme)),
                                    Placeholder.unparsed("owner", owner)));
                    return;
                }
                Contest contest = under.get();
                int entered = contests.standings(contest.number()).size();
                Instant now = clock.instant();
                Contest.Phase phase = contest.phaseAt(now);
                Duration left = Duration.between(
                        now, phase == Contest.Phase.BUILDING ? contest.buildsUntil() : contest.votesUntil());
                onEntity(
                        player,
                        () -> messages.send(
                                player,
                                phase == Contest.Phase.BUILDING ? "brix.contest.building" : "brix.contest.voting",
                                resolvers(player, contest.theme(), left, entered)));
            } catch (RuntimeException e) {
                failed(player, e);
            }
        });
    }

    /** Enters the plot the player stands on, when they are of its team. */
    public void join(Player player) {
        Optional<Island> plot = plotHere(player);
        if (plot.isEmpty()) {
            return;
        }
        if (!isMember(plot.get(), player)) {
            messages.send(player, "brix.not_team");
            return;
        }
        IslandId id = plot.get().id();
        scheduler.async(() -> {
            try {
                BrixContests.Outcome outcome = contests.enter(id);
                String theme = contests.current().map(Contest::theme).orElse("");
                onEntity(
                        player,
                        () -> messages.send(
                                player,
                                switch (outcome) {
                                    case DONE -> "brix.contest.joined";
                                    case ALREADY -> "brix.contest.already";
                                    case WRONG_PHASE -> "brix.contest.not_building";
                                    default -> "brix.contest.none";
                                },
                                Placeholder.unparsed("theme", word(player, theme))));
            } catch (RuntimeException e) {
                failed(player, e);
            }
        });
    }

    /** Gives the plot the player stands on their stars, when it is entered and not their own. */
    public void vote(Player player, int stars) {
        Optional<Island> plot = plotHere(player);
        if (plot.isEmpty()) {
            return;
        }
        if (isMember(plot.get(), player)) {
            messages.send(player, "brix.contest.own_plot");
            return;
        }
        IslandId id = plot.get().id();
        PlayerUuid voter = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            try {
                BrixContests.Outcome outcome = contests.vote(id, voter, stars, config.mostStars());
                onEntity(
                        player,
                        () -> messages.send(
                                player,
                                switch (outcome) {
                                    case DONE -> "brix.contest.voted";
                                    case BAD_STARS -> "brix.contest.bad_stars";
                                    case NOT_ENTERED -> "brix.contest.not_entered";
                                    case WRONG_PHASE -> "brix.contest.not_voting";
                                    default -> "brix.contest.none";
                                },
                                Placeholder.unparsed("stars", Integer.toString(stars)),
                                Placeholder.unparsed("most", Integer.toString(config.mostStars()))));
            } catch (RuntimeException e) {
                failed(player, e);
            }
        });
    }

    /** Opens a contest on {@code theme}, or on one of the configured themes when none is named. */
    public void open(Audience sender, @Nullable String theme) {
        String written = theme == null ? "" : theme.strip();
        if (written.isEmpty()) {
            if (config.themes().isEmpty()) {
                messages.send(sender, "isadmin.brix.no_theme");
                return;
            }
            written = config.themes().get(pick.applyAsInt(config.themes().size()));
        }
        String chosen = written;
        scheduler.async(() -> {
            try {
                BrixContests.Outcome outcome = contests.open(chosen, config.build(), config.vote());
                if (outcome != BrixContests.Outcome.DONE) {
                    messages.send(sender, "isadmin.brix.under_way");
                    return;
                }
                messages.send(sender, "isadmin.brix.opened", Placeholder.unparsed("theme", chosen));
                everyone(player ->
                        messages.send(player, "brix.contest.opened", resolvers(player, chosen, config.build(), 0)));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A Brix contest could not be opened.", e);
                messages.send(sender, "brix.contest.failed");
            }
        });
    }

    /** Takes the contest under way off, with its entries and votes. */
    public void close(Audience sender) {
        scheduler.async(() -> {
            try {
                Optional<Contest> under = contests.current();
                if (contests.close() != BrixContests.Outcome.DONE || under.isEmpty()) {
                    messages.send(sender, "isadmin.brix.none");
                    return;
                }
                String theme = under.get().theme();
                messages.send(sender, "isadmin.brix.closed", Placeholder.unparsed("theme", theme));
                everyone(player -> messages.send(
                        player, "brix.contest.closed", Placeholder.unparsed("theme", word(player, theme))));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Brix contest could not be taken off.", e);
                messages.send(sender, "brix.contest.failed");
            }
        });
    }

    /** Pays a member who joins what the contests owe them. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        scheduler.async(() -> pay(player));
    }

    /**
     * Tells everyone the votes have begun, once, while the building has only just ended; and once the votes end,
     * places the plots, tells everyone the places and pays whoever is online. Off the main thread.
     */
    void beat() {
        try {
            Optional<Contest> under = contests.current();
            if (under.isEmpty()) {
                return;
            }
            Contest contest = under.get();
            Instant now = clock.instant();
            if (contest.phaseAt(now) == Contest.Phase.VOTING
                    && votesTold != contest.number()
                    && Duration.between(contest.buildsUntil(), now).compareTo(BEAT.multipliedBy(2)) < 0) {
                votesTold = contest.number();
                Duration left = Duration.between(now, contest.votesUntil());
                int entered = contests.standings(contest.number()).size();
                everyone(player -> messages.send(
                        player, "brix.contest.votes_open", resolvers(player, contest.theme(), left, entered)));
            }
            Optional<BrixContests.Settled> settled =
                    contests.settleIfDue(config.places().size(), this::membersOf);
            if (settled.isPresent()) {
                announce(settled.get());
                everyone(player -> scheduler.async(() -> pay(player)));
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "The Brix contest could not be looked at.", e);
        }
    }

    private void announce(BrixContests.Settled settled) {
        String theme = settled.contest().theme();
        List<ContestStanding> standings = settled.standings();
        if (standings.isEmpty()) {
            everyone(player -> messages.send(
                    player, "brix.contest.ended_empty", Placeholder.unparsed("theme", word(player, theme))));
            return;
        }
        int shown = Math.min(standings.size(), Math.max(1, config.places().size()));
        List<String> owners = new ArrayList<>();
        for (int at = 0; at < shown; at++) {
            owners.add(ownerOf(standings.get(at).island()));
        }
        everyone(player -> {
            messages.send(player, "brix.contest.ended", Placeholder.unparsed("theme", word(player, theme)));
            for (int at = 0; at < shown; at++) {
                ContestStanding standing = standings.get(at);
                messages.sendPlain(
                        player,
                        "brix.contest.place",
                        Placeholder.unparsed("place", Integer.toString(at + 1)),
                        Placeholder.unparsed("owner", owners.get(at)),
                        Placeholder.unparsed("average", String.format(Locale.ROOT, "%.1f", standing.average())),
                        Placeholder.unparsed("votes", Integer.toString(standing.votes())));
            }
        });
    }

    /** Pays the player each payout owed, each once. Off the main thread. */
    void pay(Player player) {
        try {
            for (ContestPayout payout : contests.unpaid(PlayerUuid.of(player.getUniqueId()))) {
                if (!contests.markPaid(payout)) {
                    continue;
                }
                String theme =
                        contests.find(payout.contest()).map(Contest::theme).orElse("");
                String list = payout.place() == ContestPayout.ENTERED ? ENTERED : place(payout.place());
                String placed = payout.place() == ContestPayout.ENTERED ? "" : Integer.toString(payout.place());
                onEntity(player, () -> {
                    Map<String, String> says = Map.of("theme", word(player, theme), "place", placed);
                    effectPlayer.fire(rewards, list, player, says);
                });
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "What the Brix contests owe " + player.getName() + " could not be paid.", e);
        }
    }

    /** The members of a plot, its owner among them, read from the database. */
    private Set<PlayerUuid> membersOf(IslandId plot) {
        Set<PlayerUuid> members = new HashSet<>();
        stored.apply(plot).ifPresent(island -> {
            for (IslandMember member : island.members().values()) {
                members.add(member.playerUuid());
            }
        });
        return members;
    }

    private String ownerOf(IslandId plot) {
        return stored.apply(plot)
                .map(island -> names.apply(island.ownerPlayerUuid().value()))
                .orElse("?");
    }

    /** A theme as the player reads it: the language files' word for it, or the theme as written. */
    String word(Audience player, String theme) {
        String key = "brix.contest.themes." + theme;
        return messages.raw(player, key) == null ? theme : messages.words(player, "@" + key);
    }

    private TagResolver[] resolvers(Audience player, String theme, Duration left, int entered) {
        return new TagResolver[] {
            Placeholder.unparsed("theme", word(player, theme)),
            Placeholder.unparsed("days", Long.toString(left.toDays())),
            Placeholder.unparsed("hours", Integer.toString(left.toHoursPart())),
            Placeholder.unparsed("minutes", Integer.toString(left.toMinutesPart())),
            Placeholder.unparsed("entered", Integer.toString(entered)),
            Placeholder.unparsed("most", Integer.toString(config.mostStars()))
        };
    }

    /** Hands {@code each} every online player, each on their own thread. */
    private void everyone(java.util.function.Consumer<Player> each) {
        scheduler.onGlobal(() -> {
            for (Player one : online.get()) {
                onEntity(one, () -> each.accept(one));
            }
        });
    }

    private Optional<Island> plotHere(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        if (island.isEmpty() || !plots.isPlot(island.get().id())) {
            messages.send(player, "brix.not_plot");
            return Optional.empty();
        }
        return island;
    }

    private static boolean isMember(Island plot, Player player) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        return plot.members().values().stream()
                .anyMatch(member -> member.playerUuid().equals(who));
    }

    private void tell(Player player, String key) {
        onEntity(player, () -> messages.send(player, key));
    }

    private void failed(Player player, RuntimeException e) {
        LOGGER.log(Level.WARNING, "The Brix contest could not be reached for " + player.getName() + ".", e);
        tell(player, "brix.contest.failed");
    }

    private void onEntity(Player player, Runnable work) {
        scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), work);
    }
}
