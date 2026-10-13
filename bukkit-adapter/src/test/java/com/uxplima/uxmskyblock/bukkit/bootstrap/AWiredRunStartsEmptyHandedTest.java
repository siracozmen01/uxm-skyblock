package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * As the Parkour mode is wired, a run that starts on the start plate empties the course's inventory the
 * runner holds: the team does not run its own course with what creative gave it.
 */
class AWiredRunStartsEmptyHandedTest extends MockBukkitHarness {

    private static final int Y = 100;

    private final Set<IslandId> courses = new HashSet<>();

    @Test
    @DisplayName("Stepping on the start plate with the course's items in hand starts the run empty handed")
    void theRunStartsEmptyHanded() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        PlayerMock runner = createPlayer("Builder");
        Island course = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(course, "skyblock");
        ConfigurationWiring config = mock(ConfigurationWiring.class);
        when(config.parkourConfig()).thenReturn(ParkourConfiguration.defaultConfiguration());
        when(config.messages()).thenReturn(Messages.bundled());
        when(config.effectsConfig()).thenReturn(InteractionEffects.none());
        PersistenceBootstrap persistence = mock(PersistenceBootstrap.class);
        when(persistence.parkourPort()).thenReturn(port());
        when(persistence.parkourSeasonsPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonsPort.class));
        when(persistence.islandSeasonStoragePort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort.class));
        when(persistence.parkourDirectoryPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.parkour.ParkourDirectoryPort.class));
        when(persistence.parkourTagsPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort.class));
        when(persistence.parkourRewardsPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort.class));
        when(persistence.parkourSplitsPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort.class));
        when(persistence.parkourGhostsPort())
                .thenReturn(mock(com.uxplima.uxmskyblock.core.application.parkour.ParkourGhostsPort.class));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        ParkourWiring wiring = new ParkourWiring(
                config,
                persistence,
                scheduler,
                islands,
                player -> true,
                (running, at, whose, tick) -> Optional.empty());
        wiring.service().start(course.id());
        world.getBlockAt(8, Y, 8).setType(Material.EMERALD_BLOCK);
        world.getBlockAt(8, Y + 1, 8).setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        runner.teleport(new Location(world, 8.5, Y + 1, 8.5));
        // Sealed on the way onto the course, as the beat would have done, with creative's pearls in hand.
        new com.uxplima.uxmskyblock.bukkit.creative.SealedInventory(
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(), "parkour")
                .seal(runner);
        runner.getInventory().setItem(0, new ItemStack(Material.ENDER_PEARL, 16));

        wiring.runs()
                .onStep(new PlayerInteractEvent(
                        runner, Action.PHYSICAL, null, world.getBlockAt(8, Y + 1, 8), BlockFace.SELF));

        assertThat(wiring.runs().runningOn(runner)).hasValue(course.id());
        assertThat(runner.getInventory().isEmpty()).isTrue();
        wiring.close();
    }

    private ParkourPort port() {
        return new ParkourPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(courses);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return courses.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                courses.add(islandId);
            }

            @Override
            public OptionalLong best(IslandId islandId, PlayerUuid runner) {
                return OptionalLong.empty();
            }

            @Override
            public void finish(IslandId islandId, PlayerUuid runner, long millis) {}

            @Override
            public int clear(IslandId cleared, java.util.Optional<PlayerUuid> runner) {
                throw new UnsupportedOperationException("nothing here clears a course");
            }

            @Override
            public List<ParkourPort.Runs> mostRun(int limit) {
                return List.of();
            }

            @Override
            public List<ParkourPort.Best> top(IslandId islandId, int limit) {
                return List.of();
            }
        };
    }
}
