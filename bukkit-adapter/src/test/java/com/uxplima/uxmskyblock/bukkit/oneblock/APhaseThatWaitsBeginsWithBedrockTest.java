package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockGate;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockWaitPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A OneBlock phase that sets a wait begins with bedrock and a countdown over it, which no break gets past; when its
 * time is up the phase's first block is laid. The wait is stored and goes on after a restart.
 */
class APhaseThatWaitsBeginsWithBedrockTest extends MockBukkitHarness {

    /** A clock the test moves. */
    private static final class Hands extends Clock {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Hands clock = new Hands();
    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final List<Duration> waits = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final List<String> gone = new ArrayList<>();
    private final Set<UUID> bedrock = new HashSet<>();
    private final Map<IslandId, OneBlockWaitPort.Waiting> kept = new HashMap<>();
    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new HashMap<>();
    private World world;
    private PlayerMock player;
    private Island island;
    private OneBlockConfiguration config;
    private SchedulerPort scheduler;

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Breaker");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        config = OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        phases = [ {
                            name = "meadow"
                            length = 2
                            blocks { DIRT = 1 }
                        }, {
                            name = "cave"
                            length = 2
                            wait = "30s"
                            blocks { STONE = 1 }
                        } ]
                        """));
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    waits.add(call.getArgument(0, Duration.class));
                    return later.add(call.getArgument(1, Runnable.class));
                })
                .when(scheduler)
                .asyncAfter(any(Duration.class), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
    }

    private OneBlockService service() {
        return new OneBlockService(
                memory(), OneBlockRewardPort.NONE, waitPort(), config.phases(), new SplittableRandom(3));
    }

    private OneBlockPauses pauses(OneBlockService service) {
        return new OneBlockPauses(
                () -> config,
                service,
                scheduler,
                Messages.bundled(),
                OneBlockDraws.PLAIN,
                who -> bedrock.contains(who.getUniqueId()),
                clock,
                (where, at, line) -> {
                    said.add(text(line));
                    return new OneBlockPauses.Shown() {
                        @Override
                        public void say(Component again) {
                            said.add(text(again));
                        }

                        @Override
                        public void remove() {
                            gone.add("line");
                        }
                    };
                });
    }

    private OneBlockListener listener(OneBlockService service, OneBlockPauses pauses) {
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        OneBlockListener listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of()),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.usePauses(pauses);
        return listener;
    }

    private static String text(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).replace('\n', ' ');
    }

    private boolean breakIt(OneBlockListener listener, Block block) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        if (!event.isCancelled()) {
            block.setType(Material.AIR);
        }
        runRegion();
        return !event.isCancelled();
    }

    private void runRegion() {
        while (!regionTasks.isEmpty()) {
            List<Runnable> due = new ArrayList<>(regionTasks);
            regionTasks.clear();
            due.forEach(Runnable::run);
        }
    }

    /** Lets {@code seconds} pass and runs what was waiting for them. */
    private void pass(long seconds) {
        clock.now = clock.now.plusSeconds(seconds);
        List<Runnable> due = new ArrayList<>(later);
        later.clear();
        due.forEach(Runnable::run);
        runRegion();
    }

    @Test
    @DisplayName("A phase's wait and requirement lines are read; a line it cannot ask, or no time, is left out")
    void read() throws Exception {
        assertThat(config.gateOf("cave").pause()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.gateOf("meadow")).isEqualTo(OneBlockGate.OPEN);
        OneBlockConfiguration written =
                OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        phases = [ {
                            name = "meadow"
                            length = 2
                            wait = "soon"
                            requires = [ "take money 50", "have level 10", "wait 1h", "points 2", "nonsense" ]
                            blocks { DIRT = 1 }
                        } ]
                        """));
        OneBlockGate gate = written.gateOf("meadow");
        assertThat(gate.pause()).isZero();
        assertThat(gate.requires())
                .containsExactly(
                        new Requirement.Money(5000, Requirement.Source.PLAYER, Requirement.Mode.TAKE),
                        new Requirement.Level(10));
    }

    @Test
    @DisplayName("The phase begins with bedrock and a countdown no break gets past, then lays its first block")
    void waits() {
        OneBlockService service = service();
        service.start(island.id(), 0, 100, 0);
        OneBlockPauses pauses = pauses(service);
        OneBlockListener listener = listener(service, pauses);
        bedrock.add(player.getUniqueId());
        player.teleport(new org.bukkit.Location(world, 0.5, 101, 0.5));
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);

        breakIt(listener, block);
        assertThat(block.getType()).isEqualTo(Material.DIRT);
        breakIt(listener, block);

        assertThat(block.getType()).isEqualTo(Material.BEDROCK);
        assertThat(said).hasSize(2).allSatisfy(line -> assertThat(line).contains("0:30"));
        assertThat(kept.get(island.id()))
                .isEqualTo(new OneBlockWaitPort.Waiting(island.id(), "cave", clock.now.plusSeconds(30)));
        assertThat(pauses.holds(island.id())).isTrue();
        assertThat(player.nextActionBar())
                .describedAs("a Bedrock player reads it")
                .isNotNull();

        assertThat(breakIt(listener, block)).describedAs("the bedrock stays").isFalse();
        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isEqualTo(2);

        pass(10);
        assertThat(said.getLast()).contains("0:20");
        assertThat(waits).allMatch(OneBlockPauses.TICK::equals);
        pass(20);

        assertThat(gone).containsExactly("line");
        assertThat(block.getType()).isEqualTo(Material.STONE);
        assertThat(pauses.holds(island.id())).isFalse();
        assertThat(kept).isEmpty();
        assertThat(later).describedAs("the countdown stopped").isEmpty();
        assertThat(breakIt(listener, block)).isTrue();
    }

    @Test
    @DisplayName("A wait ended by someone else takes its line down and lays nothing")
    void endedElsewhere() {
        OneBlockService service = service();
        service.start(island.id(), 0, 100, 0);
        OneBlockPauses pauses = pauses(service);
        OneBlockListener listener = listener(service, pauses);
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        breakIt(listener, block);
        breakIt(listener, block);

        service.endWait(island.id(), service.waiting(island.id()).orElseThrow());
        pass(1);

        assertThat(gone).containsExactly("line");
        assertThat(block.getType()).isEqualTo(Material.BEDROCK);
        assertThat(later).isEmpty();
    }

    @Test
    @DisplayName("A wait read back after a restart goes on at the island's block, and one in no world here waits")
    void afterARestart() {
        stored.put(island.id(), new OneBlockProgressPort.OneBlockIsland(island.id(), 0, 100, 0, 2));
        kept.put(island.id(), new OneBlockWaitPort.Waiting(island.id(), "cave", clock.now.plusSeconds(5)));
        OneBlockService service = service();
        service.prime();
        OneBlockPauses pauses = pauses(service);

        pauses.resume(service.timedWaits().getFirst(), id -> Optional.of("elsewhere"));
        runRegion();
        assertThat(said).isEmpty();

        pauses.resume(service.timedWaits().getFirst(), id -> Optional.of("skyblock"));
        runRegion();
        Block block = world.getBlockAt(0, 100, 0);
        assertThat(block.getType()).isEqualTo(Material.BEDROCK);
        assertThat(said).isNotEmpty().allSatisfy(line -> assertThat(line).contains("0:05"));

        pass(5);
        assertThat(block.getType()).isEqualTo(Material.STONE);
        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("A wait read back before its world is loaded goes on once the world loads, and once only")
    void beforeTheWorld() {
        stored.put(island.id(), new OneBlockProgressPort.OneBlockIsland(island.id(), 0, 100, 0, 2));
        kept.put(island.id(), new OneBlockWaitPort.Waiting(island.id(), "cave", clock.now.plusSeconds(5)));
        OneBlockService service = service();
        service.prime();
        OneBlockPauses pauses = pauses(service);

        pauses.resume(service.timedWaits().getFirst(), id -> Optional.of("later"));
        runRegion();
        assertThat(said).isEmpty();
        pauses.worldLoaded(world);
        runRegion();
        assertThat(said).describedAs("another world loaded").isEmpty();

        World later = server.addSimpleWorld("later");
        pauses.worldLoaded(later);
        runRegion();
        assertThat(later.getBlockAt(0, 100, 0).getType()).isEqualTo(Material.BEDROCK);
        int shown = said.size();
        assertThat(shown).isPositive();
        pauses.worldLoaded(later);
        runRegion();
        assertThat(said).hasSize(shown);
    }

    @Test
    @DisplayName("Time left reads as a clock face")
    void clockFace() {
        assertThat(OneBlockPauses.clockFace(Duration.ofSeconds(245))).isEqualTo("4:05");
        assertThat(OneBlockPauses.clockFace(Duration.ofMillis(1))).isEqualTo("0:01");
        assertThat(OneBlockPauses.clockFace(Duration.ofSeconds(3723))).isEqualTo("1:02:03");
        assertThat(OneBlockPauses.clockFace(Duration.ofSeconds(-3))).isEqualTo("0:00");
    }

    private OneBlockWaitPort waitPort() {
        return new OneBlockWaitPort() {
            @Override
            public List<Waiting> findAll() {
                return List.copyOf(kept.values());
            }

            @Override
            public Optional<Waiting> find(IslandId islandId) {
                return Optional.ofNullable(kept.get(islandId));
            }

            @Override
            public void set(Waiting waiting) {
                kept.put(waiting.islandId(), waiting);
            }

            @Override
            public void clear(IslandId islandId) {
                kept.remove(islandId);
            }
        };
    }

    private OneBlockProgressPort memory() {
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}

            @Override
            public void setBreaks(IslandId islandId, long count) {}
        };
    }
}
