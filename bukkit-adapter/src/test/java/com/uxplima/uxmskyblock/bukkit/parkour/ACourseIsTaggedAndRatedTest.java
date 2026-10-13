package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A course's team gives it a difficulty and a category from the operator's lists; a runner who finished it, and is
 * not of its team, rates it; the course board heads its times with both.
 */
class ACourseIsTaggedAndRatedTest extends MockBukkitHarness {

    private final Map<String, Long> bests = new HashMap<>();
    private final Map<IslandId, ParkourTagsPort.Tags> tags = new HashMap<>();
    private final Map<String, Integer> stars = new HashMap<>();
    private boolean down;
    private int ratingsRead;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island course;

    @SuppressWarnings("NullAway.Init")
    private ParkourService service;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private ParkourTagging tagging;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock owner;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock member;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock runner;

    @BeforeEach
    void setUpCourse() {
        world = server.addSimpleWorld("skyblock");
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        course = Island.create(
                        IslandId.of(UUID.randomUUID()),
                        IslandBounds.fromCenterAndRadius(8, 8, 40),
                        PlayerUuid.of(ownerId),
                        new ProfileId(UUID.randomUUID()),
                        Instant.now())
                .addMember(new IslandMember(
                        PlayerUuid.of(memberId), new ProfileId(UUID.randomUUID()), IslandRole.MEMBER, Instant.now()));
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(course, "skyblock");
        service = new ParkourService(new ParkourPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.of(course.id());
            }

            @Override
            public boolean exists(IslandId islandId) {
                return true;
            }

            @Override
            public void add(IslandId islandId) {}

            @Override
            public OptionalLong best(IslandId islandId, PlayerUuid who) {
                Long best = bests.get(islandId + "/" + who);
                return best == null ? OptionalLong.empty() : OptionalLong.of(best);
            }

            @Override
            public void finish(IslandId islandId, PlayerUuid who, long millis) {}

            @Override
            public int clear(IslandId islandId, java.util.Optional<PlayerUuid> who) {
                return 0;
            }

            @Override
            public List<ParkourPort.Best> top(IslandId islandId, int limit) {
                List<ParkourPort.Best> top = new ArrayList<>();
                bests.forEach((key, millis) -> top.add(new ParkourPort.Best(
                        PlayerUuid.of(UUID.fromString(key.substring(key.indexOf('/') + 1))), millis, 1)));
                return top;
            }

            @Override
            public List<ParkourPort.Runs> mostRun(int limit) {
                return List.of();
            }
        });
        service.prime();
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
        tagging = tagging(ParkourConfiguration.Ratings.SHIPPED);
        owner = at(new PlayerMock(server, "Owner", ownerId));
        member = at(new PlayerMock(server, "Member", memberId));
        runner = at(new PlayerMock(server, "Runner", UUID.randomUUID()));
    }

    private ParkourTagging tagging(ParkourConfiguration.Ratings ratings) {
        return new ParkourTagging(
                service,
                new ParkourTagsPort() {
                    @Override
                    public Tags tags(IslandId on) {
                        failIfDown();
                        return tags.getOrDefault(on, Tags.NONE);
                    }

                    @Override
                    public void tag(IslandId on, Tags given) {
                        failIfDown();
                        tags.put(on, given);
                    }

                    @Override
                    public Rating rating(IslandId on) {
                        failIfDown();
                        ratingsRead++;
                        List<Integer> given = stars.entrySet().stream()
                                .filter(entry -> entry.getKey().startsWith(on + "/"))
                                .map(Map.Entry::getValue)
                                .toList();
                        return given.isEmpty()
                                ? Rating.NONE
                                : new Rating(
                                        given.stream()
                                                .mapToInt(Integer::intValue)
                                                .average()
                                                .orElse(0),
                                        given.size());
                    }

                    @Override
                    public void rate(IslandId on, PlayerUuid who, int given) {
                        failIfDown();
                        stars.put(on + "/" + who, given);
                    }
                },
                islands,
                scheduler,
                Messages.bundled(),
                new ParkourConfiguration.Tagging(List.of("easy", "hard", "insane"), List.of("speed", "maze")),
                ratings);
    }

    private void failIfDown() {
        if (down) {
            throw new IllegalStateException("the database is gone");
        }
    }

    private PlayerMock at(PlayerMock player) {
        server.addPlayer(player);
        player.teleport(new Location(world, 10.5, 100, 10.5));
        return player;
    }

    @Test
    @DisplayName("The team tags its course, in any case, keeping the other tag, and takes a tag off")
    void theTeamTags() {
        tagging.tag(owner, ParkourTagging.Kind.DIFFICULTY, "HARD");
        assertThat(said(owner)).contains("This course is now Hard.");
        tagging.tag(owner, ParkourTagging.Kind.CATEGORY, "speed");
        assertThat(said(owner)).contains("This course is now in Speed.");
        assertThat(tags.get(course.id())).isEqualTo(new ParkourTagsPort.Tags("hard", "speed"));

        tagging.tag(owner, ParkourTagging.Kind.DIFFICULTY, null);
        assertThat(said(owner)).contains("This course has no difficulty now.");
        assertThat(tags.get(course.id())).isEqualTo(new ParkourTagsPort.Tags(null, "speed"));
        tagging.tag(owner, ParkourTagging.Kind.CATEGORY, null);
        assertThat(said(owner)).contains("This course has no category now.");
        assertThat(tags.get(course.id())).isEqualTo(ParkourTagsPort.Tags.NONE);
    }

    @Test
    @DisplayName(
            "A member who may not change settings, a runner, a name not listed, and a player off course tag nothing")
    void othersTagNothing() {
        tagging.tag(member, ParkourTagging.Kind.DIFFICULTY, "hard");
        assertThat(said(member)).contains("Only the course's team can tag it.");
        tagging.tag(runner, ParkourTagging.Kind.DIFFICULTY, "hard");
        assertThat(said(runner)).contains("Only the course's team can tag it.");
        tagging.tag(owner, ParkourTagging.Kind.CATEGORY, "parkour");
        assertThat(said(owner)).contains("parkour is not one of them").contains("Speed, Maze");
        runner.teleport(new Location(world, 5000, 100, 5000));
        tagging.tag(runner, ParkourTagging.Kind.DIFFICULTY, "hard");
        assertThat(said(runner)).contains("not on a Parkour course");
        assertThat(tags).isEmpty();

        down = true;
        tagging.tag(owner, ParkourTagging.Kind.DIFFICULTY, "hard");
        assertThat(said(owner)).contains("could not be tagged");
    }

    @Test
    @DisplayName("A runner who finished the course rates it once, from 1 to the most stars, and its team does not")
    void aRunnerRates() {
        tagging.rate(runner, 4);
        assertThat(said(runner)).contains("Finish the course before you rate it.");
        assertThat(stars).isEmpty();

        bests.put(course.id() + "/" + PlayerUuid.of(runner.getUniqueId()), 30_000L);
        tagging.rate(runner, 4);
        assertThat(said(runner)).contains("You rated this course 4 of 5.");
        tagging.rate(runner, 2);
        assertThat(stars).containsOnly(Map.entry(course.id() + "/" + PlayerUuid.of(runner.getUniqueId()), 2));

        tagging.rate(runner, 0);
        assertThat(said(runner)).contains("Rate it from 1 to 5.");
        tagging.rate(runner, 6);
        assertThat(said(runner)).contains("Rate it from 1 to 5.");
        tagging.rate(owner, 5);
        assertThat(said(owner)).contains("You cannot rate your own course.");
        tagging.rate(member, 5);
        assertThat(said(member)).contains("You cannot rate your own course.");
        assertThat(stars).hasSize(1);

        down = true;
        tagging.rate(runner, 3);
        assertThat(said(runner)).contains("could not be kept");
        down = false;
        tagging(new ParkourConfiguration.Ratings(false, 5)).rate(runner, 3);
        assertThat(said(runner)).contains("Courses are not rated on this server.");
        assertThat(stars).containsValue(2);
    }

    @Test
    @DisplayName(
            "The course board heads its times with the tags, a name the files do not word as written, and the rating")
    void theBoardSaysThem() {
        ParkourBoard board = new ParkourBoard(service, islands, scheduler, Messages.bundled(), 10, uuid -> "Someone");
        board.about(tagging);
        tags.put(course.id(), new ParkourTagsPort.Tags("insane", null));
        board.show(runner);
        assertThat(said(runner)).contains("Difficulty insane Category none").contains("Not rated yet.");

        stars.put(course.id() + "/" + UUID.randomUUID(), 5);
        stars.put(course.id() + "/" + UUID.randomUUID(), 4);
        tags.put(course.id(), new ParkourTagsPort.Tags("easy", "maze"));
        board.show(runner);
        assertThat(said(runner)).contains("Difficulty Easy Category Maze").contains("Rated 4.5/5 by 2 runners");

        ParkourBoard unrated = new ParkourBoard(service, islands, scheduler, Messages.bundled(), 10, uuid -> "Someone");
        unrated.about(tagging(new ParkourConfiguration.Ratings(false, 5)));
        int read = ratingsRead;
        unrated.show(runner);
        assertThat(ratingsRead).describedAs("ratings off, none is read").isEqualTo(read);
        assertThat(said(runner))
                .contains("Difficulty Easy")
                .doesNotContain("Rated")
                .doesNotContain("Not rated");
    }

    @Test
    @DisplayName("The tags and ratings are read from the file, as shipped, the names in lower case")
    void theyAreRead() throws Exception {
        String shipped = new String(
                ACourseIsTaggedAndRatedTest.class
                        .getResourceAsStream("/modules/parkour.conf")
                        .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        ParkourConfiguration read =
                ParkourConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(shipped));
        assertThat(read.tagging()).isEqualTo(ParkourConfiguration.Tagging.SHIPPED);
        assertThat(read.ratings()).isEqualTo(ParkourConfiguration.Ratings.SHIPPED);
        ParkourConfiguration written =
                ParkourConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        tags {
                            difficulties = [" Mild ", "", "mild", "WILD"]
                            categories = []
                        }
                        ratings {
                            enabled = false
                            stars = 10
                        }
                        """));
        assertThat(written.tagging()).isEqualTo(new ParkourConfiguration.Tagging(List.of("mild", "wild"), List.of()));
        assertThat(written.ratings()).isEqualTo(new ParkourConfiguration.Ratings(false, 10));
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("ratings { stars = 11 }"))
                        .ratings())
                .isEqualTo(ParkourConfiguration.Ratings.SHIPPED);
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("ratings { stars = 1 }"))
                        .ratings())
                .isEqualTo(ParkourConfiguration.Ratings.SHIPPED);
    }

    /** Everything the player was told since the last look, as plain text. */
    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }
}
