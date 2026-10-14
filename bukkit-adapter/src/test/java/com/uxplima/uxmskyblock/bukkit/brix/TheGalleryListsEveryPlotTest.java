package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.menu.SocialWindows;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.brix.BrixContests;
import com.uxplima.uxmskyblock.core.application.brix.BrixContestsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The gallery lists every Brix plot, those of the contest under way first and best first, the rest the newest first,
 * each with its owner, its team and its standing, drawn by the shipped file; a click visits the plot.
 */
class TheGalleryListsEveryPlotTest extends MockBukkitHarness {

    private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");
    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();
    private static final UUID CY = UUID.randomUUID();

    @TempDir
    Path dataDir;

    private final Set<IslandId> plots = new HashSet<>();
    private final Map<IslandId, Island> stored = new HashMap<>();
    private final List<String> visits = new ArrayList<>();
    private final Instant[] now = {START};
    private final List<ContestStanding> standings = new ArrayList<>();
    private final List<Contest> contests = new ArrayList<>();
    private boolean down;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private BrixService service;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock viewer;

    @SuppressWarnings("NullAway.Init")
    private Island ada;

    @SuppressWarnings("NullAway.Init")
    private Island bo;

    @SuppressWarnings("NullAway.Init")
    private Island cy;

    @BeforeEach
    void setUpTheGallery() {
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
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
        // Ada's plot is the oldest, Cy's the newest; Bo's has a second member.
        ada = plot(ADA, START.minus(Duration.ofDays(3)));
        bo = plot(BO, START.minus(Duration.ofDays(2)))
                .addMember(new IslandMember(
                        new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), IslandRole.MEMBER, START));
        stored.put(bo.id(), bo);
        cy = plot(CY, START.minus(Duration.ofDays(1)));
        viewer = createPlayer("Viewer");
        server.getCommandMap().register("probe", new org.bukkit.command.Command("is") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                visits.add(String.join(" ", args));
                return true;
            }
        });
    }

    private Island plot(UUID owner, Instant made) {
        Island plot = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 10),
                new PlayerUuid(owner),
                new ProfileId(UUID.randomUUID()),
                made);
        stored.put(plot.id(), plot);
        service.start(plot.id());
        return plot;
    }

    private BrixGallery gallery() {
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
        return new BrixGallery(
                service,
                new BrixContests(new Standings(), clock),
                id -> {
                    if (down) {
                        throw new IllegalStateException("the database is gone");
                    }
                    return Optional.ofNullable(stored.get(id));
                },
                scheduler,
                Messages.bundled(),
                uuid -> uuid.equals(ADA) ? "Ada" : uuid.equals(BO) ? "Bo" : null);
    }

    private void contestUnderWay() {
        contests.add(new Contest(1, "lighthouse", START, START.plusSeconds(60), START.plusSeconds(120), false));
        // Bo's plot leads; Ada's is entered with no vote yet; Cy's is not entered.
        standings.add(new ContestStanding(ada.id(), 0, 0));
        standings.add(new ContestStanding(bo.id(), 9, 2));
    }

    @Test
    @DisplayName("With no contest the plots are listed the newest first, each its owner, its team and no contest")
    void withNoContest() {
        BrixGallery gallery = gallery();
        List<BrixGallery.Listed> listed = gallery.listed();
        assertThat(listed).extracting(each -> each.island().id()).containsExactly(cy.id(), bo.id(), ada.id());
        List<MenuRow> rows = gallery.rows(viewer, listed);
        assertThat(rows.get(1).words())
                .containsEntry("name", "Bo's plot")
                .containsEntry("owner", "Bo")
                .containsEntry("team", "2")
                .containsEntry("contest", "none under way");
        assertThat(rows.get(1).handle()).isEqualTo(new SocialWindows.Place("Bo's plot", "Bo"));
        assertThat(rows.get(0).words())
                .describedAs("an owner the server never knew is the start of the id")
                .containsEntry("owner", CY.toString().substring(0, 8))
                .containsEntry("team", "1");
    }

    @Test
    @DisplayName("The plots of the contest under way come first, best first, with their stars; the rest after")
    void withAContest() {
        contestUnderWay();
        BrixGallery gallery = gallery();
        List<BrixGallery.Listed> listed = gallery.listed();
        assertThat(listed).extracting(each -> each.island().id()).containsExactly(bo.id(), ada.id(), cy.id());
        List<MenuRow> rows = gallery.rows(viewer, listed);
        assertThat(rows.get(0).words()).containsEntry("contest", "4.5 stars, votes: 2");
        assertThat(rows.get(1).words()).containsEntry("contest", "0.0 stars, votes: 0");
        assertThat(rows.get(2).words()).containsEntry("contest", "not entered");

        now[0] = START.plusSeconds(120);
        contests.set(0, contests.get(0).settle());
        assertThat(gallery.listed())
                .describedAs("a settled contest is no longer under way")
                .extracting(each -> each.island().id())
                .containsExactly(cy.id(), bo.id(), ada.id());
    }

    @Test
    @DisplayName("No more than the most are listed, and a plot whose island is gone is left out")
    void theMost() {
        stored.remove(ada.id());
        assertThat(gallery().listed()).extracting(each -> each.island().id()).containsExactly(cy.id(), bo.id());
        for (int at = 0; at < BrixGallery.MOST + 5; at++) {
            plot(UUID.randomUUID(), START.minusSeconds(at));
        }
        assertThat(gallery().listed()).hasSize(BrixGallery.MOST);
    }

    @Test
    @DisplayName("The shipped file draws a plot's tile with every fact, and a click visits the plot")
    void theFileDrawsAndVisits() throws Exception {
        contestUnderWay();
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "brix-gallery.conf");
        BrixGallery gallery = gallery();
        gallery.useMenuEngine(engine);
        SocialWindows social = new SocialWindows(Messages.bundled(), line -> "is " + line);
        social.useMenuEngine(engine);
        MenuRow row = gallery.rows(viewer, gallery.listed()).getFirst();

        String tile = ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                ShippedTemplates.template("brix-gallery.conf", "plots"),
                MenuContext.of(viewer, null, 0).withEntry(row));
        assertThat(tile)
                .contains("Bo's plot", "Brix plot", "Built by Bo's team.", "Team", "2", "Contest")
                .contains("4.5 stars, votes: 2", "Click to go there.")
                .doesNotContain("<entry_", "%entry_");
        assertThat(ShippedTemplates.verbs(ShippedTemplates.template("brix-gallery.conf", "plots"))
                        .get(ClickKind.ANY))
                .contains("skyblock:island-visit");

        ShippedTemplates.click(
                engine,
                "skyblock:island-visit",
                MenuContext.of(viewer, null, 0).withEntry(row),
                viewer,
                ClickKind.LEFT,
                "");
        assertThat(visits).containsExactly("visit Bo");
    }

    @Test
    @DisplayName("No plot, plots that cannot be read, and a missing window are each said; otherwise the window opens")
    void opening() throws Exception {
        plots.clear();
        BrixService empty = service;
        service = new BrixService(new BrixPlotsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.of();
            }

            @Override
            public boolean exists(IslandId islandId) {
                return false;
            }

            @Override
            public void add(IslandId islandId) {}
        });
        gallery().open(viewer);
        assertThat(said()).contains("There is no Brix plot yet.");
        service = empty;

        down = true;
        gallery().open(viewer);
        assertThat(said()).contains("The plots could not be read.");
        down = false;

        BrixGallery gallery = gallery();
        gallery.open(viewer);
        assertThat(said()).contains("The plots window is missing");

        gallery.useMenuEngine(ShippedTemplates.engineWith(dataDir, "brix-gallery.conf"));
        gallery.open(viewer);
        // The engine reads its lists off the player's thread, then draws the window on it.
        long deadline = System.currentTimeMillis() + 5000;
        String title = "";
        while (System.currentTimeMillis() < deadline && !title.contains("Brix plots")) {
            server.getScheduler().performOneTick();
            title = PlainTextComponentSerializer.plainText()
                    .serialize(viewer.getOpenInventory().title());
            Thread.onSpinWait();
        }
        assertThat(said()).doesNotContain("missing");
        assertThat(title).contains("Brix plots");
        assertThat(viewer.getOpenInventory().getTopInventory().getItem(10))
                .describedAs("the first plot's tile")
                .isNotNull()
                .extracting(org.bukkit.inventory.ItemStack::getType)
                .isEqualTo(org.bukkit.Material.BRICKS);
    }

    private String said() {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = viewer.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }

    /** The contests as the gallery reads them: one under way or settled, with its standings. */
    private final class Standings implements BrixContestsPort {

        @Override
        public Optional<Contest> latest() {
            return contests.isEmpty() ? Optional.empty() : Optional.of(contests.get(contests.size() - 1));
        }

        @Override
        public Optional<Contest> find(int contest) {
            return latest();
        }

        @Override
        public void open(Contest contest) {}

        @Override
        public void close(int contest) {}

        @Override
        public boolean enter(int contest, IslandId island) {
            return false;
        }

        @Override
        public Set<IslandId> entries(int contest) {
            return Set.of();
        }

        @Override
        public void vote(int contest, IslandId island, PlayerUuid voter, int stars) {}

        @Override
        public OptionalInt voteOf(int contest, IslandId island, PlayerUuid voter) {
            return OptionalInt.empty();
        }

        @Override
        public List<ContestStanding> standings(int contest) {
            return List.copyOf(standings);
        }

        @Override
        public void settle(int contest, List<ContestPayout> payouts) {}

        @Override
        public List<ContestPayout> unpaid(PlayerUuid player) {
            return List.of();
        }

        @Override
        public boolean markPaid(ContestPayout payout) {
            return false;
        }
    }
}
