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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
 * A hostile creature drawn for a OneBlock island is told to the breaker the breaks before it comes that
 * creature-warning sets, in chat and with oneblock-creature-coming; a creature that does not attack is not.
 */
class AHostileCreatureIsToldBeforeItComesTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<String> fired = new ArrayList<>();
    private World world;
    private PlayerMock player;
    private Island island;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Breaker");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
    }

    private OneBlockListener listener(String creatures) throws Exception {
        OneBlockConfiguration config = OneBlockConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("""
                        creature-warning = 2
                        phases = [ {
                            name = "meadow"
                            length = 50
                            blocks { DIRT = 1 }
                            creature-chance = 1
                            creatures { %s }
                        } ]
                        """.formatted(creatures)));
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(3));
        service.lookAhead(config.creatureWarning());
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
                new InteractionEffects(Map.of(
                        "oneblock-creature-coming",
                        ActionList.parse(List.of("[console] probe coming %creature% %breaks%")))),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.useDraws(new OneBlockDraws(config::entries, Messages.bundled()));
        return listener;
    }

    private void breakIt(OneBlockListener listener) {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        regionTasks.clear();
    }

    /** The keys of every part of {@code line} the player's own game translates. */
    private static List<String> translated(Component line) {
        List<String> keys = new ArrayList<>();
        if (line instanceof net.kyori.adventure.text.TranslatableComponent translatable) {
            keys.add(translatable.key());
        }
        line.children().forEach(child -> keys.addAll(translated(child)));
        return keys;
    }

    private List<String> heard() {
        List<String> lines = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    @Test
    @DisplayName("A hostile creature is told the breaks before it comes, in chat and with its effects")
    void told() throws Exception {
        OneBlockListener listener = listener("zombie = 1");

        breakIt(listener);
        assertThat(fired).containsExactly("coming zombie 1", "coming zombie 2");
        List<Component> lines = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            lines.add(line);
        }
        assertThat(lines)
                .describedAs("the kind is read by the player's own game, in their language")
                .allSatisfy(line -> assertThat(translated(line)).contains("entity.minecraft.zombie"));
        assertThat(lines.stream().map(PlainTextComponentSerializer.plainText()::serialize))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("in 1 breaks"))
                .anySatisfy(line -> assertThat(line).contains("in 2 breaks"));
        fired.clear();

        breakIt(listener);
        assertThat(fired).containsExactly("coming zombie 2");
    }

    @Test
    @DisplayName("A creature written with a name is told by its name, and one that does not attack is not told")
    void namedAndTame() throws Exception {
        breakIt(listener("keeper { type = \"zombie\", name = \"<red>Keeper\" }"));
        assertThat(heard()).allSatisfy(line -> assertThat(line).contains("Keeper"));
        assertThat(fired).contains("coming Keeper 2");
        fired.clear();

        breakIt(listener("cow = 1"));
        assertThat(fired).isEmpty();
        assertThat(heard()).isEmpty();
    }

    @Test
    @DisplayName("creature-warning is read, five when left out or below nothing")
    void read() throws Exception {
        for (String written : List.of("", "creature-warning = -1\n")) {
            OneBlockConfiguration config = OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                    .buildAndLoadString(written + "phases = [ { name = \"m\", length = 2, blocks { DIRT = 1 } } ]"));
            assertThat(config.creatureWarning()).isEqualTo(5);
        }
        OneBlockConfiguration none = OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString(
                        "creature-warning = 0\nphases = [ { name = \"m\", length = 2, blocks { DIRT = 1 } } ]"));
        assertThat(none.creatureWarning()).isZero();
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
