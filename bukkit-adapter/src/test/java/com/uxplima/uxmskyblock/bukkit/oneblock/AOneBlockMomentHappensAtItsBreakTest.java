package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.loot.LootContainers;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A OneBlock moment happens at the exact break its phase file writes: its block in place of the draw, its creature,
 * its loot table on the container, its line over the block for its time, and its lists for the breaker.
 */
class AOneBlockMomentHappensAtItsBreakTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final List<Duration> waits = new ArrayList<>();
    private final List<String> fired = new ArrayList<>();
    private final Set<UUID> bedrock = new HashSet<>();
    private final List<String> lines = new ArrayList<>();
    private final List<org.bukkit.entity.Entity> shown = new ArrayList<>();
    private World world;
    private PlayerMock player;
    private OneBlockListener listener;
    private OneBlockConfiguration config;
    private OneBlockService service;
    private Island island;

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
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        config = OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                phases = [ {
                    name = "meadow"
                    length = 10
                    blocks { DIRT = 1 }
                    creatures { boss { type = "zombie", name = "Boss" } }
                    moments {
                        2 { block = "CHEST", loot = "treasure", creature = "boss", text = "<gold>A chest", text-time = "5s",
                            actions = [ "[console] probe moment %phase% %break%" ] }
                        "nine" { block = "DIRT" }
                        10 { block = "DIRT" }
                        4 { block = "NOT_A_BLOCK" }
                    }
                } ]
                """));
        service = new OneBlockService(memory(), config.phases(), new SplittableRandom(3));
        service.start(island.id(), 0, 100, 0);
        SchedulerPort scheduler = mock(SchedulerPort.class);
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
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class));
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of(
                        OneBlockMoments.MOMENT,
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe every %break%")))),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.useDraws(new OneBlockDraws(() -> config.entries(), Messages.bundled()));
        listener.useMoments(new OneBlockMoments(
                () -> config,
                scheduler,
                Messages.bundled(),
                who -> bedrock.contains(who.getUniqueId()),
                (where, at, line) -> {
                    lines.add(at.getX() + " " + at.getY() + " " + at.getZ() + " "
                            + PlainTextComponentSerializer.plainText().serialize(line));
                    org.bukkit.entity.Entity entity = mock(org.bukkit.entity.Entity.class);
                    shown.add(entity);
                    return entity;
                }));
    }

    private void breakIt(Block block) {
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
    }

    @Test
    @DisplayName("Only what can be is read: a moment at no break of the phase, or with an unknown block, is left out")
    void read() {
        assertThat(config.momentAt("meadow", 2)).get().satisfies(moment -> {
            assertThat(moment.block()).isEqualTo("CHEST");
            assertThat(moment.creature()).isEqualTo("meadow/boss");
            assertThat(moment.loot()).isEqualTo("treasure");
            assertThat(moment.textTime()).isEqualTo(Duration.ofSeconds(5));
        });
        assertThat(config.moments().get("meadow")).containsOnlyKeys(2L);
        assertThat(config.momentAt("meadow", 3)).isEmpty();
        assertThat(config.momentAt("elsewhere", 2)).isEmpty();
    }

    @Test
    @DisplayName(
            "At its break the moment lays its block with its loot, brings its creature, shows its line and runs its lists")
    void happens() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);

        breakIt(block);
        assertThat(block.getType()).describedAs("one break: the draw").isEqualTo(Material.DIRT);
        assertThat(fired).isEmpty();
        breakIt(block);

        assertThat(block.getType()).isEqualTo(Material.CHEST);
        assertThat(LootContainers.waitingFor(block)).isEqualTo("treasure");
        assertThat(world.getEntitiesByClass(Zombie.class)).hasSize(1);
        assertThat(fired).containsExactly("every 2", "moment meadow 2");
        assertThat(lines).containsExactly("0.5 " + (100 + 1 + OneBlockMoments.TEXT_RISE) + " 0.5 A chest");
        assertThat(waits).containsExactly(Duration.ofSeconds(5));
        org.mockito.Mockito.verify(shown.getFirst(), org.mockito.Mockito.never())
                .remove();
        later.forEach(Runnable::run);
        regionTasks.forEach(Runnable::run);
        org.mockito.Mockito.verify(shown.getFirst()).remove();

        breakIt(block);
        assertThat(fired).describedAs("the next break is no moment").hasSize(2);
    }

    @Test
    @DisplayName("A moment had in this pass is not had again when an operator sets the count back")
    void onceInAPass() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        breakIt(block);
        breakIt(block);
        assertThat(block.getType()).isEqualTo(Material.CHEST);

        service.setBroken(island.id(), 0);
        breakIt(block);
        breakIt(block);

        assertThat(block.getType())
                .describedAs("the draw, not the moment's chest")
                .isEqualTo(Material.DIRT);
        assertThat(fired).hasSize(2);
        assertThat(world.getEntitiesByClass(Zombie.class)).hasSize(1);
    }

    @Test
    @DisplayName("A Bedrock breaker reads the moment's line on the action bar as well")
    void bedrockReadsIt() {
        bedrock.add(player.getUniqueId());
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);

        breakIt(block);
        breakIt(block);

        assertThat(player.nextActionBar()).isNotNull();
    }

    @Test
    @DisplayName("A line is a text that faces its reader, centred over the block, and never saved")
    @SuppressWarnings("unchecked")
    void theLine() {
        World mocked = mock(World.class);
        TextDisplay display = mock(TextDisplay.class);
        org.mockito.Mockito.when(mocked.spawn(
                        any(org.bukkit.Location.class),
                        org.mockito.ArgumentMatchers.eq(TextDisplay.class),
                        any(java.util.function.Consumer.class)))
                .thenAnswer(call -> {
                    call.getArgument(2, java.util.function.Consumer.class).accept(display);
                    return display;
                });

        assertThat(OneBlockMoments.textOver(
                        mocked,
                        new org.bukkit.Location(mocked, 0.5, 101.6, 0.5),
                        net.kyori.adventure.text.Component.text("x")))
                .isSameAs(display);

        org.mockito.Mockito.verify(display).text(net.kyori.adventure.text.Component.text("x"));
        org.mockito.Mockito.verify(display).setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        org.mockito.Mockito.verify(display).setPersistent(false);
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
