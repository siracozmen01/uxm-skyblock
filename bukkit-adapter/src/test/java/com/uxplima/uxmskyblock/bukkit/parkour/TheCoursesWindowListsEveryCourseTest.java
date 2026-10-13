package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.menu.SocialWindows;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourDirectoryPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The courses window lists every course, the most run first, each with its name, owner, tags, rating, record and
 * runs, drawn by the shipped file; a click visits the island the course is on.
 */
class TheCoursesWindowListsEveryCourseTest extends MockBukkitHarness {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();

    @TempDir
    Path dataDir;

    private List<ParkourDirectoryPort.Listing> listed = List.of();
    private boolean down;
    private final List<String> visits = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private ParkourTagging tagging;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock viewer;

    @BeforeEach
    void setUpTheWindow() {
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
        tagging = new ParkourTagging(
                new ParkourService(mock(ParkourPort.class)),
                mock(ParkourTagsPort.class),
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService()),
                scheduler,
                Messages.bundled(),
                ParkourConfiguration.Tagging.SHIPPED,
                ParkourConfiguration.Ratings.SHIPPED);
        viewer = createPlayer("Viewer");
        server.getCommandMap().register("probe", new org.bukkit.command.Command("is") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                visits.add(String.join(" ", args));
                return true;
            }
        });
    }

    private ParkourCourses courses(ParkourConfiguration.Ratings ratings) {
        return new ParkourCourses(
                limit -> {
                    if (down) {
                        throw new IllegalStateException("the database is gone");
                    }
                    return listed.stream().limit(limit).toList();
                },
                tagging,
                scheduler,
                Messages.bundled(),
                ratings,
                uuid -> uuid.equals(ADA) ? "Ada" : null);
    }

    private static List<ParkourDirectoryPort.Listing> two() {
        return List.of(
                new ParkourDirectoryPort.Listing(
                        IslandId.of(UUID.randomUUID()),
                        "Sky Steps",
                        ADA,
                        new ParkourTagsPort.Tags("hard", "speed"),
                        4.5,
                        2,
                        25_000L,
                        12),
                new ParkourDirectoryPort.Listing(
                        IslandId.of(UUID.randomUUID()),
                        null,
                        BO,
                        new ParkourTagsPort.Tags(null, "nobody-words-this"),
                        0,
                        0,
                        null,
                        0));
    }

    @Test
    @DisplayName(
            "Each course is a row of its words, a visit to its owner's island, the unnamed ones named for their owner")
    void theRows() {
        List<MenuRow> rows =
                courses(ParkourConfiguration.Ratings.SHIPPED).rows(viewer, two(), Map.of(ADA, "Ada", BO, "Bo"));

        assertThat(rows.get(0).words())
                .containsEntry("name", "Sky Steps")
                .containsEntry("owner", "Ada")
                .containsEntry("difficulty", "Hard")
                .containsEntry("category", "Speed")
                .containsEntry("rating", "4.5/5")
                .containsEntry("ratings", "2")
                .containsEntry("record", "0:25.000")
                .containsEntry("runs", "12")
                .containsEntry("facts", "difficulty category rating record runs");
        assertThat(rows.get(0).handle()).isEqualTo(new SocialWindows.Place("Sky Steps", "Ada"));
        assertThat(rows.get(1).words())
                .containsEntry("name", "Bo's course")
                .containsEntry("difficulty", "none")
                .containsEntry("category", "nobody-words-this")
                .containsEntry("rating", "not yet")
                .containsEntry("record", "not yet")
                .containsEntry("runs", "0");
        assertThat(courses(ParkourConfiguration.Ratings.SHIPPED).owners(two()))
                .describedAs("a name the server never knew is the start of the id")
                .containsEntry(ADA, "Ada")
                .containsEntry(BO, BO.toString().substring(0, 8));
        ParkourDirectoryPort.Listing blank = new ParkourDirectoryPort.Listing(
                IslandId.of(UUID.randomUUID()), "  ", BO, ParkourTagsPort.Tags.NONE, 0, 0, null, 0);
        assertThat(courses(ParkourConfiguration.Ratings.SHIPPED)
                        .rows(viewer, List.of(blank), Map.of(BO, "Bo"))
                        .getFirst()
                        .words())
                .containsEntry("name", "Bo's course");
        assertThat(courses(new ParkourConfiguration.Ratings(false, 5))
                        .rows(viewer, two(), Map.of())
                        .getFirst()
                        .words())
                .describedAs("ratings off, no rating is drawn, and an owner nobody knows is a question mark")
                .containsEntry("facts", "difficulty category record runs")
                .containsEntry("owner", "?");
    }

    @Test
    @DisplayName("The shipped file draws a course's tile with every fact, and a click visits its island")
    void theFileDrawsAndVisits() throws Exception {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "parkour-courses.conf");
        ParkourCourses window = courses(ParkourConfiguration.Ratings.SHIPPED);
        window.useMenuEngine(engine);
        SocialWindows social = new SocialWindows(Messages.bundled(), line -> "is " + line);
        social.useMenuEngine(engine);
        MenuRow row = window.rows(viewer, two(), Map.of(ADA, "Ada")).getFirst();

        String tile = ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                ShippedTemplates.template("parkour-courses.conf", "courses"),
                MenuContext.of(viewer, null, 0).withEntry(row));
        assertThat(tile)
                .contains("Parkour course", "Ada's island", "Difficulty", "Hard", "Category", "Speed")
                .contains("Rated", "4.5/5", "Record", "0:25.000", "Runs", "12", "Click to go there.")
                .doesNotContain("<entry_", "%entry_");
        assertThat(ShippedTemplates.verbs(ShippedTemplates.template("parkour-courses.conf", "courses"))
                        .get(ClickKind.ANY))
                .contains("skyblock:island-visit");

        ShippedTemplates.click(
                engine,
                "skyblock:island-visit",
                MenuContext.of(viewer, null, 0).withEntry(row),
                viewer,
                ClickKind.LEFT,
                "");
        assertThat(visits).containsExactly("visit Ada");
    }

    @Test
    @DisplayName(
            "No course, courses that cannot be read, and a missing window are each said; otherwise the window opens")
    void opening() throws Exception {
        ParkourCourses window = courses(ParkourConfiguration.Ratings.SHIPPED);
        window.open(viewer);
        assertThat(said()).contains("There is no Parkour course yet.");

        listed = two();
        down = true;
        window.open(viewer);
        assertThat(said()).contains("The courses could not be read.");
        down = false;

        window.open(viewer);
        assertThat(said()).contains("The courses window is missing");

        window.useMenuEngine(ShippedTemplates.engineWith(dataDir, "parkour-courses.conf"));
        window.open(viewer);
        // The engine reads its lists off the player's thread, then draws the window on it.
        long deadline = System.currentTimeMillis() + 5000;
        String title = "";
        while (System.currentTimeMillis() < deadline && !title.contains("Parkour courses")) {
            server.getScheduler().performOneTick();
            title = PlainTextComponentSerializer.plainText()
                    .serialize(viewer.getOpenInventory().title());
            Thread.onSpinWait();
        }
        assertThat(said()).doesNotContain("missing");
        assertThat(title).contains("Parkour courses");
        assertThat(viewer.getOpenInventory().getTopInventory().getItem(10))
                .describedAs("the first course's tile")
                .isNotNull()
                .extracting(org.bukkit.inventory.ItemStack::getType)
                .isEqualTo(org.bukkit.Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
    }

    /** Everything the viewer was told since the last look, as plain text. */
    private String said() {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = viewer.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }
}
