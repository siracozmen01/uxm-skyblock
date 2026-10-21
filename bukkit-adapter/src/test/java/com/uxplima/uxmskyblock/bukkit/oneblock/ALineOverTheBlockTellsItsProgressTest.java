package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A line over a OneBlock island's block tells its phase and how far into it the island is at each break, drawn
 * again when the world let go of it, and a Bedrock breaker reads it on the action bar; the operator may turn it off.
 */
class ALineOverTheBlockTellsItsProgressTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private final Set<UUID> bedrock = new HashSet<>();
    private boolean gone;
    private int removed;

    private @Nullable OneBlockConfiguration config;

    private static OneBlockConfiguration configured(String progress) throws Exception {
        return OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(progress + """

                phases = [
                    { name = "meadow", length = 4, blocks { DIRT = 1 } },
                    { name = "cave", length = 5, wait = "10s", blocks { STONE = 1 } }
                ]
                """));
    }

    private OneBlockListener listener(String progress, PlayerMock player) throws Exception {
        OneBlockConfiguration first = configured(progress);
        config = first;
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        OneBlockService service = new OneBlockService(memory(), first.phases(), new SplittableRandom(1));
        service.start(island.id(), 0, 100, 0);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
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
        OneBlockPauses.Lines drawn = (where, at, line) -> {
            lines.add("new " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + text(line.apply(player)));
            return new OneBlockPauses.Shown() {
                @Override
                public void say(java.util.function.Function<org.bukkit.entity.Player, Component> again) {
                    lines.add("say " + text(again.apply(player)));
                }

                @Override
                public void remove() {
                    removed++;
                }

                @Override
                public boolean showing() {
                    return !gone;
                }
            };
        };
        listener.useBlockLines(new OneBlockBlockLines(
                () -> java.util.Objects.requireNonNull(config),
                Messages.bundled(),
                who -> bedrock.contains(who.getUniqueId()),
                drawn));
        listener.usePauses(new OneBlockPauses(
                () -> java.util.Objects.requireNonNull(config),
                service,
                scheduler,
                Messages.bundled(),
                OneBlockDraws.PLAIN,
                who -> false,
                java.time.Clock.systemUTC(),
                drawn));
        return listener;
    }

    private static String text(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).replace('\n', ' ');
    }

    private void breakIt(OneBlockListener listener, World world, PlayerMock player) {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
    }

    @Test
    @DisplayName("The line is drawn over the block, told again at the next break, and drawn anew once it is gone")
    void told() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        PlayerMock player = createPlayer("Breaker");
        OneBlockListener listener = listener("", player);

        breakIt(listener, world, player);
        breakIt(listener, world, player);
        assertThat(lines)
                .containsExactly(
                        "new 0.5 " + (100 + 1 + OneBlockBlockLines.RISE) + " 0.5 meadow 1/4", "say meadow 2/4");
        assertThat(player.nextActionBar())
                .describedAs("a Java player reads the line in the world")
                .isNull();

        gone = true;
        bedrock.add(player.getUniqueId());
        breakIt(listener, world, player);

        assertThat(lines)
                .hasSize(3)
                .last()
                .asString()
                .isEqualTo("new 0.5 " + (100 + 1 + OneBlockBlockLines.RISE) + " 0.5 meadow 3/4");
        assertThat(text(player.nextActionBar())).isEqualTo("meadow 3/4");
    }

    @Test
    @DisplayName("While a phase waits its countdown is the line, so the progress line goes")
    void whileItWaits() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        PlayerMock player = createPlayer("Breaker");
        OneBlockListener listener = listener("", player);
        breakIt(listener, world, player);
        breakIt(listener, world, player);
        breakIt(listener, world, player);
        assertThat(removed).isZero();
        lines.clear();

        breakIt(listener, world, player);

        assertThat(removed).isEqualTo(1);
        assertThat(lines).noneMatch(line -> line.contains("cave 0/5"));
    }

    @Test
    @DisplayName("Turned off, no line is drawn")
    void off() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        PlayerMock player = createPlayer("Breaker");
        OneBlockListener listener = listener("progress { line = false }", player);
        bedrock.add(player.getUniqueId());

        breakIt(listener, world, player);

        assertThat(lines).isEmpty();
        assertThat(player.nextActionBar()).isNull();
    }

    @Test
    @DisplayName("Turned off on a reload, the line already drawn is taken away at the next break")
    void turnedOff() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        PlayerMock player = createPlayer("Breaker");
        OneBlockListener listener = listener("", player);
        breakIt(listener, world, player);

        config = configured("progress { line = false }");
        breakIt(listener, world, player);

        assertThat(removed).isEqualTo(1);
        assertThat(lines).hasSize(1);
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new java.util.HashMap<>();
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
