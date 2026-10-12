package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurse;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
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
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
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
 * A OneBlock phase that asks lines begins only once they hold: until then the island waits at the end of the phase
 * before, and the breaker hears what is missing; when they hold, what is to be taken is taken, given back whole if
 * any part cannot be, and the phase begins.
 */
class APhaseAsksItsLinesBeforeItBeginsTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<Runnable> asyncTasks = new ArrayList<>();
    private final Map<String, Long> carried = new HashMap<>();
    private final List<String> purseLog = new ArrayList<>();
    private final Map<String, Long> bank = new HashMap<>();
    private final List<String> bankLog = new ArrayList<>();
    private final Map<IslandId, OneBlockWaitPort.Waiting> kept = new HashMap<>();
    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new HashMap<>();
    private boolean bankRefuses;
    private boolean mayTake = true;
    private long level = 12;
    private int levelAsked;
    private World world;
    private PlayerMock player;
    private Island island;
    private OneBlockService service;
    private OneBlockListener listener;

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
        OneBlockConfiguration config =
                OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        phases = [ {
                            name = "meadow"
                            length = 2
                            blocks { DIRT = 1 }
                        }, {
                            name = "cave"
                            length = 2
                            requires = [
                                "take money 50", "have level 10", "take item oak_log 3",
                                "have permission oneblock.cave", "take money 100 island", "have currency gems 5 island",
                                "have item cobblestone 2"
                            ]
                            blocks { STONE = 1 }
                        } ]
                        """));
        service = new OneBlockService(
                memory(), OneBlockRewardPort.NONE, waitPort(), config.phases(), new SplittableRandom(3));
        service.start(island.id(), 0, 100, 0);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> asyncTasks.add(call.getArgument(0, Runnable.class)))
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class));
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of()),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.useGates(
                new OneBlockGates(() -> config, service, scheduler, Messages.bundled(), purse(), funds(), (of, who) -> {
                    levelAsked++;
                    return level;
                }));
        bank.put("money", 10_000L);
        bank.put("gems", 5L);
        carried.put("money:player", 5_000L);
        carried.put("item:minecraft:oak_log", 3L);
        carried.put("item:minecraft:cobblestone", 2L);
        player.setOp(true);
    }

    private void breakIt(Block block) {
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
    }

    private void runAll() {
        while (!regionTasks.isEmpty() || !asyncTasks.isEmpty()) {
            List<Runnable> due = new ArrayList<>(asyncTasks);
            asyncTasks.clear();
            due.forEach(Runnable::run);
            due = new ArrayList<>(regionTasks);
            regionTasks.clear();
            due.forEach(Runnable::run);
        }
    }

    private List<String> heard() {
        List<String> lines = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    private Block toTheEdge() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        breakIt(block);
        runAll();
        heard();
        return block;
    }

    @Test
    @DisplayName("Lines that do not hold keep the island at the end of the phase before, waiting, and are said")
    void waits() {
        Block block = toTheEdge();
        level = 3;
        carried.put("money:player", 10L);
        player.setOp(false);
        mayTake = false;

        breakIt(block);
        runAll();

        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isEqualTo(1);
        assertThat(block.getType())
                .describedAs("the block comes back as the phase it is")
                .isEqualTo(Material.DIRT);
        assertThat(kept.get(island.id())).isEqualTo(new OneBlockWaitPort.Waiting(island.id(), "cave", null));
        assertThat(purseLog).isEmpty();
        assertThat(bankLog).isEmpty();
        List<String> heard = heard();
        assertThat(heard.getFirst()).contains("cave").contains("waits for");
        assertThat(heard)
                .hasSize(5)
                .anySatisfy(line -> assertThat(line).contains("island level 10"))
                .anySatisfy(line -> assertThat(line).contains("50").contains("your money"))
                .anySatisfy(line -> assertThat(line).contains("100").contains("island bank"))
                .anySatisfy(line -> assertThat(line).contains("oneblock.cave"));
    }

    @Test
    @DisplayName("Lines that hold are taken, the breaker's then the bank's, and the phase begins")
    void opens() {
        Block block = toTheEdge();

        breakIt(block);
        runAll();

        assertThat(purseLog).containsExactly("take money:player 5000", "take item:minecraft:oak_log 3");
        assertThat(bankLog).containsExactly("take money 10000");
        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isEqualTo(2);
        assertThat(block.getType()).isEqualTo(Material.STONE);
        assertThat(kept).isEmpty();
        assertThat(service.waiting(island.id())).isEmpty();
        assertThat(heard()).anySatisfy(line -> assertThat(line).contains("cave").contains("begins"));
        assertThat(bank).containsEntry("money", 0L).containsEntry("gems", 5L);
    }

    @Test
    @DisplayName("A bank that refuses its part gives the breaker back what they paid, and nothing begins")
    void bankRefuses() {
        Block block = toTheEdge();
        bankRefuses = true;

        breakIt(block);
        runAll();

        assertThat(purseLog)
                .containsExactly(
                        "take money:player 5000",
                        "take item:minecraft:oak_log 3",
                        "back money:player 5000",
                        "back item:minecraft:oak_log 3");
        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isEqualTo(1);
        assertThat(service.waiting(island.id())).isPresent();
        assertThat(heard()).anySatisfy(line -> assertThat(line).contains("island bank"));
    }

    @Test
    @DisplayName("A purse that hands over less than it showed gets back what it gave, and nothing more is taken")
    void shortPurse() {
        Block block = toTheEdge();
        carried.put("item:minecraft:oak_log", 3L);
        shortItems = true;

        breakIt(block);
        runAll();

        assertThat(purseLog)
                .containsExactly(
                        "take money:player 5000",
                        "take item:minecraft:oak_log 3",
                        "back item:minecraft:oak_log 2",
                        "back money:player 5000");
        assertThat(bankLog).isEmpty();
        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isEqualTo(1);
    }

    @Test
    @DisplayName("An island being asked is not asked again by the next break, and is asked again once answered")
    void onceAtATime() {
        Block block = toTheEdge();
        level = 3;

        breakIt(block);
        breakIt(block);
        runAll();
        assertThat(levelAsked).isEqualTo(1);

        breakIt(block);
        runAll();
        assertThat(levelAsked).isEqualTo(2);
    }

    @Test
    @DisplayName("A break short of the edge asks nothing")
    void notYet() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);

        breakIt(block);
        runAll();

        assertThat(levelAsked).isZero();
        assertThat(service.waiting(island.id())).isEmpty();
    }

    @Test
    @DisplayName("An item line names the item as a player reads it")
    void itemName() {
        assertThat(OneBlockGates.itemName("minecraft:oak_log")).isEqualTo("oak log");
    }

    private boolean shortItems;

    private ChunkPurse purse() {
        return new ChunkPurse() {
            @Override
            public long carried(Player who, ChunkRequirement line) {
                return carried.getOrDefault(line.key(), 0L);
            }

            @Override
            public long take(Player who, ChunkRequirement line, long wanted) {
                purseLog.add("take " + line.key() + " " + wanted);
                long got = shortItems && line instanceof ChunkRequirement.Items ? wanted - 1 : wanted;
                carried.merge(line.key(), -got, Long::sum);
                return got;
            }

            @Override
            public void giveBack(Player who, ChunkRequirement line, long amount) {
                purseLog.add("back " + line.key() + " " + amount);
                carried.merge(line.key(), amount, Long::sum);
            }
        };
    }

    private OneBlockGates.IslandFunds funds() {
        return new OneBlockGates.IslandFunds() {
            @Override
            public long held(IslandId islandId, Requirement line) {
                return bank.getOrDefault(key(line), 0L);
            }

            @Override
            public boolean take(IslandId islandId, Player payer, Requirement line) {
                if (bankRefuses) {
                    return false;
                }
                long amount = line instanceof Requirement.Money money ? money.minor() : 0;
                bankLog.add("take " + key(line) + " " + amount);
                bank.merge(key(line), -amount, Long::sum);
                return true;
            }

            @Override
            public void giveBack(IslandId islandId, Player payer, Requirement line) {
                bankLog.add("back " + key(line));
            }

            @Override
            public boolean mayTake(Island of, Player payer) {
                return mayTake;
            }

            private String key(Requirement line) {
                return line instanceof Requirement.Currency currency ? currency.id() : "money";
            }
        };
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
