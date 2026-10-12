package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
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
 * A OneBlock phase runs what its file writes when it begins, finishes and is first finished: the breaker's list once,
 * the members' list for every member online on their own thread, and the shipped list only when it writes none.
 */
class APhaseRunsItsOwnListsTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<Runnable> entityTasks = new ArrayList<>();
    private final List<String> fired = new ArrayList<>();
    private World world;
    private PlayerMock breaker;
    private OneBlockListener listener;
    private OneBlockService service;
    private Island island;
    /** Rewards storage refuses, as phase/reward, as when another node or a run before a crash recorded them. */
    private final java.util.Set<String> refused = new java.util.HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("skyblock");
        breaker = createPlayer("Breaker");
        PlayerMock mate = createPlayer("Mate");
        island = Island.create(
                        IslandId.of(UUID.randomUUID()),
                        IslandBounds.fromCenterAndRadius(0, 0, 50),
                        new PlayerUuid(breaker.getUniqueId()),
                        new ProfileId(breaker.getUniqueId()),
                        Instant.now())
                .addMember(new IslandMember(
                        new PlayerUuid(mate.getUniqueId()),
                        new ProfileId(mate.getUniqueId()),
                        IslandRole.MEMBER,
                        Instant.now()))
                .addMember(new IslandMember(
                        new PlayerUuid(UUID.randomUUID()),
                        new ProfileId(UUID.randomUUID()),
                        IslandRole.MEMBER,
                        Instant.now()));
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        OneBlockConfiguration config =
                OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        after-last-phase = "repeat"
                        phases = [ {
                            name = "meadow"
                            length = 2
                            blocks { DIRT = 1 }
                            finished {
                                breaker = [ "[player] probe finished %phase% %cycle%" ]
                                members = [ "[player] probe heard %phase%" ]
                            }
                            first-finished { breaker = [ "[player] probe first %phase%" ] }
                        }, {
                            name = "cave"
                            length = 2
                            blocks { DIRT = 1 }
                            began { members = [ "[player] probe began %phase% %blocks%" ] }
                        } ]
                        """));
        service = new OneBlockService(memory(), rewards(), config.phases(), new SplittableRandom(3));
        service.start(island.id(), 0, 100, 0);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> entityTasks.add(call.getArgument(1, Runnable.class)))
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class));
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(sender.getName() + " " + String.join(" ", args));
                return true;
            }
        });
        listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of(
                        "oneblock-phase-began",
                        ActionList.parse(List.of("[player] probe shipped-began %phase%")),
                        "oneblock-phase-finished",
                        ActionList.parse(List.of("[player] probe shipped-finished %phase%")))),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.usePhaseLists(new OneBlockPhaseLists(() -> config, scheduler, server::getPlayer));
    }

    private void breakIt(Block block) {
        listener.onBlockBreak(new BlockBreakEvent(block, breaker));
        block.setType(Material.AIR);
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
    }

    /** Runs what was handed to each player's thread, and what that handed on in turn. */
    private void membersHear() {
        while (!entityTasks.isEmpty()) {
            List<Runnable> due = new ArrayList<>(entityTasks);
            entityTasks.clear();
            due.forEach(Runnable::run);
        }
    }

    @Test
    @DisplayName(
            "A phase's own lists run in place of the shipped one: once for the breaker, then for each member online")
    void itsOwn() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);

        breakIt(block);
        breakIt(block);
        assertThat(fired)
                .describedAs("paid on the breaker's thread, once recorded")
                .isEmpty();
        membersHear();

        assertThat(fired.subList(0, 2)).containsExactly("Breaker finished meadow 0", "Breaker first meadow");
        assertThat(fired.subList(2, fired.size()))
                .describedAs("each member on their own thread, in no order")
                .containsExactlyInAnyOrder(
                        "Breaker heard meadow", "Mate heard meadow", "Breaker began cave 2", "Mate began cave 2");
    }

    @Test
    @DisplayName("A phase that writes nothing for its event runs the shipped list, and only the first round is first")
    void shippedAndFirst() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        breakIt(block);
        breakIt(block);
        membersHear();
        fired.clear();

        breakIt(block);
        breakIt(block);
        membersHear();

        assertThat(fired)
                .describedAs("round two: cave finishes with the shipped list, meadow begins with it, nothing is first")
                .containsExactly("Breaker shipped-finished cave", "Breaker shipped-began meadow");
        fired.clear();
        breakIt(block);
        breakIt(block);
        membersHear();
        assertThat(fired.getFirst()).isEqualTo("Breaker finished meadow 1");
        assertThat(fired.subList(1, fired.size()))
                .containsExactlyInAnyOrder(
                        "Breaker heard meadow", "Mate heard meadow", "Breaker began cave 2", "Mate began cave 2");
    }

    @Test
    @DisplayName(
            "A count set back pays nothing twice, and a list storage holds already is not run, nor the shipped one")
    void paidOnce() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        breakIt(block);
        breakIt(block);
        membersHear();
        fired.clear();

        service.setBroken(island.id(), 0);
        breakIt(block);
        breakIt(block);
        membersHear();
        assertThat(fired).describedAs("meadow finished again in the same pass").isEmpty();

        refused.add("meadow/finished");
        refused.add("cave/began");
        service.setBroken(island.id(), 4 * 2);
        breakIt(block);
        breakIt(block);
        membersHear();
        assertThat(fired)
                .describedAs("claimed here, but held in storage already")
                .isEmpty();
    }

    private com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort rewards() {
        return new com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort() {
            @Override
            public List<Paid> findAll() {
                return List.of();
            }

            @Override
            public List<Paid> find(IslandId islandId) {
                return List.of();
            }

            @Override
            public boolean pay(IslandId islandId, String phase, String reward, long cycle) {
                return !refused.contains(phase + "/" + reward);
            }

            @Override
            public void clear(IslandId islandId) {}
        };
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new java.util.concurrent.ConcurrentHashMap<>();
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
