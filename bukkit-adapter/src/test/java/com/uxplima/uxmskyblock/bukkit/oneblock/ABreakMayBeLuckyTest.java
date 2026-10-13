package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.Component;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A break of a OneBlock phase is lucky as often as its file's chance says, and runs the phase's lucky lists, or the
 * shipped lucky break when it writes none. Every OneBlock list knows which round of the phases the island is on, from
 * 1, so a line may run only from the second round on.
 */
class ABreakMayBeLuckyTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<String> fired = new ArrayList<>();
    private final Deque<Double> rolls = new ArrayDeque<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private OneBlockListener listener;

    @BeforeEach
    void probe() {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Breaker");
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
    }

    /** The island's listener over a meadow that may be lucky, then a cave that writes {@code cave}. */
    private void listen(String cave) throws Exception {
        OneBlockConfiguration config =
                OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        after-last-phase = "repeat"
                        phases = [ {
                            name = "meadow"
                            length = 3
                            blocks { DIRT = 1 }
                            lucky {
                                chance = 0.5
                                breaker = [
                                    "[console] probe lucky %%phase%% %%break%% %%cycle%%",
                                    "[console] probe for %%player_name%%",
                                    "[console] {if=%%cycle%% >= 2} probe second round"
                                ]
                            }
                            began { breaker = [ "[console] probe began %%phase%% %%cycle%%" ] }
                            finished { breaker = [ "[console] probe finished %%phase%% %%cycle%%" ] }
                            moments { 2 { actions = [ "[console] probe moment %%phase%% %%cycle%%" ] } }
                        }, {
                            name = "cave"
                            length = 2
                            blocks { STONE = 1 }
                            lucky { chance = 1 }
                            %s
                        } ]
                        """.formatted(cave)));
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        service.start(island.id(), 0, 100, 0);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        // A phase's lists are paid once storage took them, on the breaker's thread.
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
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of(
                        "oneblock-lucky-break",
                        ActionList.parse(List.of("[console] probe shipped %phase% %break% %cycle%")))),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.usePhaseLists(new OneBlockPhaseLists(() -> config, scheduler, uuid -> null));
        listener.usePauses(new OneBlockPauses(
                () -> config,
                service,
                scheduler,
                Messages.bundled(),
                OneBlockDraws.PLAIN,
                who -> false,
                java.time.Clock.systemUTC(),
                (where, at, line) -> new OneBlockPauses.Shown() {
                    @Override
                    public void say(Component again) {}

                    @Override
                    public void remove() {}
                }));
        listener.useMoments(
                new OneBlockMoments(() -> config, scheduler, Messages.bundled(), who -> false, (where, at, line) -> {
                    throw new AssertionError("this moment writes no line");
                }));
        listener.useLuck(() -> rolls.isEmpty() ? 1.0 : rolls.removeFirst());
    }

    private List<String> breakIt(double roll) {
        rolls.add(roll);
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
        rolls.clear();
        List<String> said = List.copyOf(fired);
        fired.clear();
        return said;
    }

    @Test
    @DisplayName("A roll under the chance is lucky and runs the phase's lists; one at or over it is not")
    void underTheChance() throws Exception {
        listen("");

        assertThat(breakIt(0.49)).containsExactly("lucky meadow 1 1", "for Breaker");
        assertThat(breakIt(0.5)).describedAs("a moment, and no luck").containsExactly("moment meadow 1");
        assertThat(breakIt(0.99)).containsExactly("finished meadow 1");
    }

    @Test
    @DisplayName("A phase that writes a chance and no lists runs the shipped lucky break")
    void shipped() throws Exception {
        listen("");
        breakIt(1);
        breakIt(1);
        breakIt(1);

        assertThat(breakIt(0.99)).containsExactly("shipped cave 1 1");
        assertThat(config("lucky { chance = 0.25 }").luckOf("meadow")).hasValue(0.25);
        OneBlockConfiguration tooMuch = config(
                "lucky { chance = 2, breaker = [ \"[message] hi\" ] }, began { breaker = [ \"[message] hi\" ] }");
        assertThat(tooMuch.luckOf("meadow")).isEmpty();
        assertThat(tooMuch.listsOf("meadow", "began"))
                .describedAs("the phase keeps its other lists, only its luck goes")
                .isPresent();
        assertThat(config("lucky { breaker = [ \"[message] hi\" ] }").luckOf("meadow"))
                .describedAs("a lucky break with no chance never happens")
                .isEmpty();
        assertThat(config("").luckOf("meadow")).isEmpty();
    }

    @Test
    @DisplayName("The break that finishes a phase is its last block's, even when the next begins with bedrock")
    void theLastBlock() throws Exception {
        listen("wait = \"10s\"");
        breakIt(1);
        breakIt(1);

        assertThat(breakIt(0)).containsExactlyInAnyOrder("finished meadow 1", "lucky meadow 3 1", "for Breaker");
    }

    @Test
    @DisplayName("Every list knows the round of the phases from 1, and a line may wait for the second round")
    void rounds() throws Exception {
        listen("");
        assertThat(breakIt(0.1)).containsExactly("lucky meadow 1 1", "for Breaker");
        assertThat(breakIt(1)).containsExactly("moment meadow 1");
        assertThat(breakIt(1)).containsExactly("finished meadow 1");
        breakIt(1);

        assertThat(breakIt(1)).describedAs("the phases go round again").containsExactly("began meadow 2");
        assertThat(breakIt(0.1)).containsExactly("lucky meadow 1 2", "for Breaker", "second round");
        assertThat(breakIt(1)).containsExactly("moment meadow 2");
    }

    private static OneBlockConfiguration config(String lucky) {
        try {
            return OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                    .buildAndLoadString("phases = [ { name = \"meadow\", length = 3, blocks { DIRT = 1 }, %s } ]"
                            .formatted(lucky)));
        } catch (org.spongepowered.configurate.ConfigurateException wrong) {
            throw new AssertionError(wrong);
        }
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new HashMap<>();
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
