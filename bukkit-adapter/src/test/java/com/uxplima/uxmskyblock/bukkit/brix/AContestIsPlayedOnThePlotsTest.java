package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixContests;
import com.uxplima.uxmskyblock.core.application.brix.BrixContestsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A Brix build contest as it is played: an operator opens it, teams enter the plots they stand on, others give them
 * stars, and once the votes end everyone is told the places and each member is paid, then or on joining.
 */
class AContestIsPlayedOnThePlotsTest extends MockBukkitHarness {

    private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");
    private static final BrixConfiguration.Contests CONTESTS = new BrixConfiguration.Contests(
            List.of("lighthouse", "a wonky tower"),
            Duration.ofHours(26),
            Duration.ofHours(1),
            5,
            2,
            List.of("[message] entered %theme%"),
            List.of(List.of("[message] placed first on %theme%, place %place%"), List.of("[message] placed second")));

    private final Set<IslandId> plots = new HashSet<>();
    private final Map<IslandId, Island> stored = new HashMap<>();
    private final Kept kept = new Kept();
    private final Instant[] now = {START};
    private final int[] picked = {0};

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private BrixContestPlay play;

    @SuppressWarnings("NullAway.Init")
    private IslandCommandTree tree;

    @SuppressWarnings("NullAway.Init")
    private BrixService service;

    @SuppressWarnings("NullAway.Init")
    private BrixContests held;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private CommandDispatcher<CommandSourceStack> players;

    @SuppressWarnings("NullAway.Init")
    private CommandDispatcher<CommandSourceStack> admin;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock ann;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock bob;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock cem;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock op;

    @SuppressWarnings("NullAway.Init")
    private Island annPlot;

    @SuppressWarnings("NullAway.Init")
    private Island bobPlot;

    @BeforeEach
    void setUpTheContest() {
        world = server.addSimpleWorld("plots");
        ann = createMovingPlayer("Ann");
        bob = createMovingPlayer("Bob");
        cem = createMovingPlayer("Cem");
        op = createMovingPlayer("Op");
        op.setOp(true);
        annPlot = plot(ann, 8);
        bobPlot = plot(bob, 108)
                .addMember(new IslandMember(
                        new PlayerUuid(cem.getUniqueId()), new ProfileId(UUID.randomUUID()), IslandRole.MEMBER, START));
        stored.put(bobPlot.id(), bobPlot);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(annPlot, "plots");
        islands.cacheIsland(bobPlot, "plots");
        Island land = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(208, 8, 6),
                new PlayerUuid(op.getUniqueId()),
                new ProfileId(UUID.randomUUID()),
                START);
        islands.cacheIsland(land, "plots");
        service = new BrixService(new BrixPlotsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(plots);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return plots.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                plots.add(islandId);
            }
        });
        service.start(annPlot.id());
        service.start(bobPlot.id());
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any());
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any());
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        held = new BrixContests(kept, clock);
        play = new BrixContestPlay(
                held,
                service,
                islands,
                id -> Optional.ofNullable(stored.get(id)),
                id -> Optional.ofNullable(server.getOfflinePlayer(id).getName()).orElse("?"),
                scheduler,
                Messages.bundled(),
                CONTESTS,
                new InteractionEffectPlayer(scheduler, Messages.bundled()),
                () -> server.getOnlinePlayers(),
                bound -> picked[0] % bound,
                clock);
        tree = new IslandCommandTree(
                mock(CreateIslandUseCase.class),
                mock(IslandLocationService.class),
                mock(IslandFlagService.class),
                mock(IslandBankService.class),
                mock(IslandUpgradeStoragePort.class),
                mock(IslandLeaderboardService.class),
                mock(BiomeModificationPort.class),
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                islands,
                mock(PlayerSessionCoordinator.class),
                scheduler,
                Messages.bundled(),
                HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");
        tree.useBrixContests(play);
        players = new CommandDispatcher<>();
        players.register(tree.buildRoot());
        admin = new CommandDispatcher<>();
        admin.register(new ModeAdminCommands(
                        List.of(new BrixAdmin(play)),
                        mock(IslandLocationService.class),
                        null,
                        scheduler,
                        Messages.bundled())
                .build(null, (source, tag) -> true)
                .orElseThrow());
        stand(ann, 8);
        stand(bob, 108);
        stand(cem, 108);
        stand(op, 208);
    }

    private Island plot(PlayerMock owner, int x) {
        Island plot = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(x, 8, 6),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(UUID.randomUUID()),
                START);
        stored.put(plot.id(), plot);
        return plot;
    }

    private void stand(PlayerMock player, int x) {
        player.teleport(new Location(world, x + 0.5, 101, 8.5));
    }

    private static String run(CommandDispatcher<CommandSourceStack> on, PlayerMock player, String line)
            throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        on.execute(line, source);
        return said(player);
    }

    private String run(PlayerMock player, String line) throws Exception {
        return run(players, player, line);
    }

    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(next)).append('\n');
        }
        return all.toString();
    }

    @Test
    @DisplayName("Opened, entered, voted for and placed: everyone is told, and each member paid once")
    void aContestIsPlayed() throws Exception {
        assertThat(run(ann, "island contest")).contains("No build contest is under way");
        assertThat(run(ann, "island contest join")).contains("No build contest is under way");

        assertThat(run(admin, op, "isadmin brix startcontest")).contains("A build contest on lighthouse is open");
        assertThat(said(ann)).describedAs("everyone is told").contains("The theme is A lighthouse");
        assertThat(said(cem)).contains("within 1d 2h 0m");
        assertThat(run(admin, op, "isadmin brix startcontest A bridge")).contains("under way already");

        assertThat(run(ann, "island contest join")).contains("Your plot is entered in the contest on A lighthouse");
        assertThat(run(ann, "island contest join")).contains("entered already");
        assertThat(run(cem, "island contest join"))
                .describedAs("a member enters their team's plot")
                .contains("entered");
        stand(ann, 108);
        assertThat(run(ann, "island contest join"))
                .describedAs("not her team's plot")
                .contains("Only this plot's team");
        stand(ann, 8);
        assertThat(run(op, "island contest join")).describedAs("no plot").contains("Stand on a Brix plot");
        now[0] = START.plusSeconds(1800);
        assertThat(run(bob, "island contest"))
                .contains("on A lighthouse is under way. Plots entered: 2.")
                .contains("ends in 1d 1h 30m");
        assertThat(run(op, "island contest vote 4")).describedAs("not yet").contains("Stand on a Brix plot");
        stand(op, 8);
        assertThat(run(op, "island contest vote 4")).contains("only once the building ends");

        now[0] = START.plus(Duration.ofHours(26));
        play.beat();
        assertThat(said(bob)).describedAs("the votes begin").contains("The building on A lighthouse is over");
        play.beat();
        assertThat(said(bob)).describedAs("told once").isEmpty();
        assertThat(run(ann, "island contest join")).contains("only while the contest builds");
        assertThat(run(op, "island contest vote 6")).contains("Give from 1 to 5 stars");
        assertThat(run(op, "island contest vote 0")).contains("Give from 1 to 5 stars");
        assertThat(run(op, "island contest vote 2")).contains("You gave this plot 2 stars");
        assertThat(run(ann, "island contest vote 5")).describedAs("her own").contains("your own team");
        stand(ann, 108);
        assertThat(run(ann, "island contest vote 5")).contains("You gave this plot 5 stars");
        stand(op, 108);
        assertThat(run(op, "island contest vote 4")).contains("4 stars");
        stand(cem, 8);
        assertThat(run(cem, "island contest vote 3")).contains("3 stars");
        assertThat(run(cem, "island contest"))
                .contains("takes votes for 0d 1h 0m more. Plots entered: 2.")
                .contains("from 1 to 5");

        // Dee joins Bob's team once the votes have begun: the build was judged without her, so she is paid nothing.
        PlayerMock dee = createMovingPlayer("Dee");
        stored.put(
                bobPlot.id(),
                bobPlot.addMember(new IslandMember(
                        new PlayerUuid(dee.getUniqueId()),
                        new ProfileId(UUID.randomUUID()),
                        IslandRole.MEMBER,
                        START.plus(Duration.ofHours(26)).plusSeconds(1))));
        server.getPlayerList().disconnectPlayer(ann);
        now[0] = START.plus(Duration.ofHours(27)).minusMillis(1);
        play.beat();
        assertThat(said(bob)).describedAs("not before the votes end").isEmpty();
        now[0] = START.plus(Duration.ofHours(27));
        play.beat();
        String told = said(op);
        assertThat(told)
                .contains("The build contest on A lighthouse is over")
                .contains("#1 the plot of Bob: 4.5 stars, votes: 2")
                .contains("#2 the plot of Ann: 2.5 stars, votes: 2");
        assertThat(told.indexOf("#1")).isLessThan(told.indexOf("#2"));
        String bobs = said(bob);
        assertThat(bobs)
                .contains("entered A lighthouse")
                .contains("placed first on A lighthouse, place 1")
                .doesNotContain("placed second");
        assertThat(said(cem)).contains("placed first on A lighthouse");
        assertThat(said(dee)).describedAs("joined after the building").doesNotContain("entered", "placed");
        assertThat(kept.paid).describedAs("Ann was away").hasSize(4);

        ann.reconnect();
        play.onJoin(new org.bukkit.event.player.PlayerJoinEvent(ann, Component.empty()));
        assertThat(said(ann))
                .contains("entered A lighthouse")
                .contains("placed second")
                .doesNotContain("first");
        play.onJoin(new org.bukkit.event.player.PlayerJoinEvent(ann, Component.empty()));
        assertThat(said(ann)).describedAs("paid once").isEmpty();
        play.onJoin(new org.bukkit.event.player.PlayerJoinEvent(bob, Component.empty()));
        assertThat(said(bob)).describedAs("paid once").isEmpty();
        kept.stale = true;
        play.onJoin(new org.bukkit.event.player.PlayerJoinEvent(bob, Component.empty()));
        assertThat(said(bob))
                .describedAs("read before another paid it: paid by the one who marked it")
                .isEmpty();
        kept.stale = false;

        assertThat(run(op, "island contest")).contains("The last, on A lighthouse, was won by the plot of Bob");
        play.beat();
        assertThat(said(op)).describedAs("settled once").isEmpty();
    }

    @Test
    @DisplayName("An operator opens on a theme written or picked, and takes a contest off with its entries")
    void anOperatorOpensAndTakesOff() throws Exception {
        assertThat(run(admin, op, "isadmin brix stopcontest")).contains("No build contest is under way");
        picked[0] = 1;
        assertThat(run(admin, op, "isadmin brix startcontest")).contains("on a wonky tower is open");
        assertThat(said(ann))
                .describedAs("a theme the files do not word is shown as written")
                .contains("a wonky tower");
        run(ann, "island contest join");
        assertThat(run(admin, op, "isadmin brix stopcontest")).contains("on a wonky tower is taken off");
        assertThat(said(bob)).contains("was called off");
        assertThat(kept.all).isEmpty();
        assertThat(run(admin, op, "isadmin brix startcontest   The Big   Bridge  "))
                .contains("on The Big   Bridge is open");
        assertThat(run(ann, "island contest")).contains("The Big   Bridge is under way. Plots entered: 0.");
        said(bob);
        now[0] = START.plus(Duration.ofHours(26)).plusSeconds(600);
        play.beat();
        assertThat(said(bob))
                .describedAs("the building ended long before the beat, as after a restart: not told late")
                .isEmpty();
        now[0] = START.plus(Duration.ofHours(27));
        play.beat();
        assertThat(said(bob)).contains("is over, and no plot was entered");
        assertThat(run(bob, "island contest"))
                .describedAs("the last placed no plot")
                .contains("No build contest is under way.")
                .doesNotContain("could not");

        // One plot entered and voted for once, fewer than the two votes written: placed nothing, paid nothing.
        Instant opened = now[0];
        run(admin, op, "isadmin brix startcontest A tower");
        run(ann, "island contest join");
        now[0] = opened.plus(Duration.ofHours(26));
        stand(op, 8);
        run(op, "island contest vote 5");
        said(ann);
        said(bob);
        now[0] = opened.plus(Duration.ofHours(27));
        play.beat();
        assertThat(said(bob)).contains("No plot drew the 2 votes it takes to be placed");
        assertThat(said(ann)).describedAs("nothing paid").doesNotContain("entered A tower", "placed first");
    }

    @Test
    @DisplayName("The plots command opens the gallery, and says there is none while Brix gives it none")
    void thePlotsCommand() throws Exception {
        assertThat(run(ann, "island plots")).contains("There is no Brix plot yet.");
        tree.useBrixGallery(new BrixGallery(
                service, held, id -> Optional.ofNullable(stored.get(id)), scheduler, Messages.bundled(), id -> null));
        players = new CommandDispatcher<>();
        players.register(tree.buildRoot());
        assertThat(run(ann, "island plots"))
                .describedAs("two plots and no window handed to it")
                .contains("The plots window is missing");
    }

    @Test
    @DisplayName("An operator with no theme written and none configured is told to name one")
    void noTheme() throws Exception {
        BrixContestPlay bare = new BrixContestPlay(
                new BrixContests(kept, Clock.systemUTC()),
                mock(BrixService.class),
                mock(IslandProtectionListener.class),
                id -> Optional.empty(),
                id -> "?",
                mock(SchedulerPort.class),
                Messages.bundled(),
                new BrixConfiguration.Contests(
                        List.of(), Duration.ofHours(1), Duration.ofHours(1), 5, 0, List.of(), List.of()),
                new InteractionEffectPlayer(mock(SchedulerPort.class), Messages.bundled()),
                List::of,
                bound -> 0,
                Clock.systemUTC());
        bare.open(op, null);
        assertThat(said(op)).contains("Name a theme");
        bare.open(op, "  ");
        assertThat(said(op)).contains("Name a theme");
        assertThat(kept.all).isEmpty();
    }

    /** The contests in memory. */
    private static final class Kept implements BrixContestsPort {

        private final List<Contest> all = new ArrayList<>();
        private final List<IslandId> entries = new ArrayList<>();
        private final Map<List<Object>, Integer> votes = new HashMap<>();
        private final List<ContestPayout> payouts = new ArrayList<>();
        private final Set<ContestPayout> paid = new HashSet<>();
        private boolean stale;

        @Override
        public Optional<Contest> latest() {
            return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
        }

        @Override
        public Optional<Contest> find(int contest) {
            return all.stream().filter(each -> each.number() == contest).findFirst();
        }

        @Override
        public void open(Contest contest) {
            all.add(contest);
            entries.clear();
            votes.clear();
        }

        @Override
        public void close(int contest) {
            all.removeIf(each -> each.number() == contest);
            entries.clear();
        }

        @Override
        public boolean enter(int contest, IslandId island) {
            if (entries.contains(island)) {
                return false;
            }
            return entries.add(island);
        }

        @Override
        public Set<IslandId> entries(int contest) {
            return Set.copyOf(entries);
        }

        @Override
        public void vote(int contest, IslandId island, PlayerUuid voter, int stars) {
            votes.put(List.of(island, voter), stars);
        }

        @Override
        public OptionalInt voteOf(int contest, IslandId island, PlayerUuid voter) {
            Integer stars = votes.get(List.of(island, voter));
            return stars == null ? OptionalInt.empty() : OptionalInt.of(stars);
        }

        @Override
        public List<ContestStanding> standings(int contest) {
            List<ContestStanding> all = new ArrayList<>();
            for (IslandId entry : entries) {
                int stars = 0;
                int count = 0;
                for (Map.Entry<List<Object>, Integer> vote : votes.entrySet()) {
                    if (vote.getKey().get(0).equals(entry)) {
                        stars += vote.getValue();
                        count++;
                    }
                }
                all.add(new ContestStanding(entry, stars, count));
            }
            return all;
        }

        @Override
        public void settle(int contest, List<ContestPayout> owed) {
            all.replaceAll(each -> each.number() == contest ? each.settle() : each);
            payouts.addAll(owed);
        }

        @Override
        public List<ContestPayout> unpaid(PlayerUuid player) {
            return payouts.stream()
                    .filter(each -> each.player().equals(player) && (stale || !paid.contains(each)))
                    .toList();
        }

        @Override
        public boolean markPaid(ContestPayout payout) {
            return paid.add(payout);
        }
    }
}
