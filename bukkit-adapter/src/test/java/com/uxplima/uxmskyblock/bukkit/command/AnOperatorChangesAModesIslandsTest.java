package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockAdmin;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /isadmin}: an operator changes a mode's islands, each word is theirs to rename, and each verb is there
 * only for whoever holds its permission.
 */
class AnOperatorChangesAModesIslandsTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final IslandId ORDINARY = IslandId.of(UUID.randomUUID());

    @TempDir
    Path dir;

    private final Map<IslandId, Long> stored = new HashMap<>();
    private final Map<IslandId, Long> boxes = new HashMap<>();
    private final List<IslandId> edgesMoved = new java.util.ArrayList<>();
    private World world;
    private OneBlockService service;
    private ModeAdminCommands admin;
    private PlayerMock operator;

    private static OneBlockPhase phase(String key, long blocks, String block) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of(block, 1.0)), WeightedPool.empty(), 0);
    }

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        stored.put(ISLAND, 12L);
        OneBlockProgressPort port = new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, 0L);
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return stored.entrySet().stream()
                        .map(entry -> new OneBlockIsland(entry.getKey(), 0, 100, 0, entry.getValue()))
                        .toList();
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId))
                        .map(count -> new OneBlockIsland(islandId, 0, 100, 0, count));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {
                if (breaks < 0) {
                    throw new IllegalArgumentException("storage takes no breaks back");
                }
                stored.merge(islandId, breaks, Long::sum);
            }

            @Override
            public void setBreaks(IslandId islandId, long count) {
                stored.put(islandId, count);
            }
        };
        service = new OneBlockService(
                port,
                new OneBlockPhases(
                        List.of(
                                phase("plains", 10, "GRASS_BLOCK"),
                                phase("cave", 20, "STONE"),
                                phase("forest", 5, "OAK_LOG")),
                        OneBlockPhases.AfterTheLast.STAY,
                        java.util.Map.of("plains", List.of("cave", "forest"), "cave", List.of())),
                new SplittableRandom(1));
        service.prime();
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findLocation(any(IslandId.class))).thenReturn(Optional.empty());
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(ISLAND, "skyblock", 0, 0, 100)));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        boxes.put(ISLAND, 3L);
        com.uxplima.uxmskyblock.core.application.boxed.BoxedService boxed =
                new com.uxplima.uxmskyblock.core.application.boxed.BoxedService(
                        new com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort() {
                            @Override
                            public Map<IslandId, Long> findAll() {
                                return Map.copyOf(boxes);
                            }

                            @Override
                            public java.util.OptionalLong find(IslandId islandId) {
                                Long held = boxes.get(islandId);
                                return held == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(held);
                            }

                            @Override
                            public void add(IslandId islandId) {
                                boxes.putIfAbsent(islandId, 0L);
                            }

                            @Override
                            public boolean earn(IslandId islandId, String advancement, int blocks) {
                                return false;
                            }

                            @Override
                            public void grant(IslandId islandId, long blocks) {
                                boxes.merge(islandId, blocks, Long::sum);
                            }
                        },
                        new com.uxplima.uxmskyblock.core.domain.boxed.BoxRules(
                                5,
                                1,
                                50,
                                Map.of(),
                                List.of(),
                                com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(1)));
        boxed.prime();
        admin = new ModeAdminCommands(
                List.of(
                        new OneBlockAdmin(service),
                        new com.uxplima.uxmskyblock.bukkit.boxed.BoxedAdmin(boxed, edgesMoved::add)),
                locations,
                null,
                scheduler,
                Messages.bundled());
        operator = server.addPlayer("Op");
        operator.setOp(true);
    }

    private CommandDispatcher<CommandSourceStack> dispatcher(
            @org.jspecify.annotations.Nullable ConfiguredCommands names) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(admin.build(names, (source, tag) -> true).orElseThrow());
        return dispatcher;
    }

    private String run(String line) throws Exception {
        return run(dispatcher(null), line);
    }

    private String run(CommandDispatcher<CommandSourceStack> dispatcher, String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(operator);
        dispatcher.execute(line, source);
        StringBuilder said = new StringBuilder();
        Component next;
        while ((next = operator.nextComponentMessage()) != null) {
            said.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(next))
                    .append('\n');
        }
        return said.toString();
    }

    @Test
    @DisplayName("A phase set moves the count to its start and lays the block from it")
    void setPhase() throws Exception {
        String said = run("isadmin oneblock setphase " + ISLAND.value() + " cave");

        assertThat(stored).containsEntry(ISLAND, 10L);
        assertThat(world.getBlockAt(0, 100, 0).getType()).isEqualTo(Material.STONE);
        assertThat(said).contains("10").contains("was 12").contains("cave");
    }

    @Test
    @DisplayName("A phase is set along the way the island picked, and one off its way is refused")
    void alongItsWay() throws Exception {
        String said = run("isadmin oneblock setphase " + ISLAND.value() + " forest");
        assertThat(said).contains("forest").contains("not on the way");
        assertThat(stored).containsEntry(ISLAND, 12L);

        service.pick(ISLAND, "plains", "forest");
        assertThat(run("isadmin oneblock setphase " + ISLAND.value() + " forest"))
                .contains("forest")
                .doesNotContain("cave");

        assertThat(stored).containsEntry(ISLAND, 10L);
        assertThat(world.getBlockAt(0, 100, 0).getType()).isEqualTo(Material.OAK_LOG);
        assertThat(run("isadmin oneblock check " + ISLAND.value()))
                .contains("forest")
                .contains("(2/2)");
    }

    @Test
    @DisplayName("A phase nobody wrote is refused with the phases there are, and nothing moves")
    void anUnknownPhase() throws Exception {
        String said = run("isadmin oneblock setphase " + ISLAND.value() + " moon");

        assertThat(said).contains("moon").contains("plains, cave");
        assertThat(stored).containsEntry(ISLAND, 12L);
    }

    @Test
    @DisplayName("A count is set, added to and taken from, read back, and the block laid again")
    void counts() throws Exception {
        run("isadmin oneblock setcount " + ISLAND.value() + " 3");
        assertThat(stored).containsEntry(ISLAND, 3L);
        assertThat(world.getBlockAt(0, 100, 0).getType()).isEqualTo(Material.GRASS_BLOCK);

        run("isadmin oneblock addcount " + ISLAND.value() + " 20");
        assertThat(stored).containsEntry(ISLAND, 23L);
        run("isadmin oneblock addcount " + ISLAND.value() + " -5");
        assertThat(stored).containsEntry(ISLAND, 18L);

        assertThat(run("isadmin oneblock check " + ISLAND.value()))
                .contains("cave")
                .contains("(2/2)")
                .contains("8 of 20 blocks")
                .contains("18 broken")
                .contains("0 100 0");

        world.getBlockAt(0, 100, 0).setType(Material.AIR);
        assertThat(run("isadmin oneblock respawn " + ISLAND.value())).contains("stone");
        assertThat(world.getBlockAt(0, 100, 0).getType()).isEqualTo(Material.STONE);
    }

    @Test
    @DisplayName("An island that is not a OneBlock island is said to be one no verb changes")
    void notOneBlock() throws Exception {
        assertThat(run("isadmin oneblock setcount " + ORDINARY.value() + " 3")).contains("not a OneBlock island");
        assertThat(run("isadmin oneblock check " + ORDINARY.value())).contains("not a OneBlock island");
        assertThat(run("isadmin oneblock respawn " + ORDINARY.value())).contains("not a OneBlock island");
        assertThat(run("isadmin oneblock check Nobody")).contains("No island was found for Nobody");
    }

    @Test
    @DisplayName("A box is set across, pushed out, kept between the smallest and the largest, and its edge moved")
    void boxes() throws Exception {
        assertThat(run("isadmin boxed setsize " + ISLAND.value() + " 31"))
                .contains("31×31")
                .contains("was 17×17");
        assertThat(boxes).describedAs("fifteen out, ten past the start").containsEntry(ISLAND, 10L);
        assertThat(edgesMoved).containsExactly(ISLAND);

        assertThat(run("isadmin boxed grow " + ISLAND.value() + " 2")).contains("35×35");
        assertThat(boxes).containsEntry(ISLAND, 12L);

        assertThat(run("isadmin boxed setsize " + ISLAND.value() + " 9")).contains("from 11 to 101 across, not 9");
        assertThat(run("isadmin boxed setsize " + ISLAND.value() + " 103")).contains("not 103");
        assertThat(run("isadmin boxed setsize " + ISLAND.value() + " 101")).contains("101×101");
        assertThat(run("isadmin boxed setsize " + ISLAND.value() + " 30"))
                .describedAs("a box is odd across, its middle block and as far each way")
                .contains("29×29");
        assertThat(run("isadmin boxed grow " + ORDINARY.value() + " 2")).contains("not a Boxed island");
        assertThat(edgesMoved).hasSize(4);
    }

    @Test
    @DisplayName("The command, the mode and the verb answer to the words the file gives them")
    void renamed() throws Exception {
        Path file = dir.resolve("commands.conf");
        Files.writeString(file, """
                commands {
                  isadmin {
                    name = "yonet"
                    subcommands {
                      oneblock {
                        name = "tekblok"
                        subcommands { setcount { name = "sayi", aliases = ["s"] }, respawn { enabled = false } }
                      }
                    }
                  }
                }
                """);
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(ConfiguredCommands.load(file));

        run(dispatcher, "yonet tekblok sayi " + ISLAND.value() + " 4");
        assertThat(stored).containsEntry(ISLAND, 4L);
        run(dispatcher, "yonet tekblok s " + ISLAND.value() + " 6");
        assertThat(stored).containsEntry(ISLAND, 6L);
        assertThat(parses(dispatcher, "yonet tekblok check x", node -> true)).isTrue();
        assertThat(parses(dispatcher, "yonet tekblok respawn x", node -> true)).isFalse();
        assertThat(parses(dispatcher, "isadmin oneblock check x", node -> true)).isFalse();
    }

    @Test
    @DisplayName("Each verb is there only for whoever holds its own permission, and the command for whoever holds one")
    void eachVerbItsPermission() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(null);
        java.util.function.Predicate<String> onlyCheck = "uxmskyblock.admin.oneblock.check"::equals;

        assertThat(parses(dispatcher, "isadmin oneblock check x", onlyCheck)).isTrue();
        assertThat(parses(dispatcher, "isadmin oneblock setcount x 1", onlyCheck))
                .isFalse();
        assertThat(parses(dispatcher, "isadmin oneblock respawn x", onlyCheck)).isFalse();
        assertThat(parses(dispatcher, "isadmin oneblock check x", node -> false))
                .isFalse();
        var root = dispatcher.getRoot().getChild("isadmin");
        assertThat(root.canUse(source(onlyCheck))).isTrue();
        assertThat(root.getChild("oneblock").canUse(source(onlyCheck))).isTrue();
        assertThat(root.canUse(source(node -> false)))
                .describedAs("nobody learns the command is there by being refused it")
                .isFalse();
        assertThat(root.getChild("oneblock").canUse(source(node -> false))).isFalse();
    }

    private static CommandSourceStack source(java.util.function.Predicate<String> holds) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenAnswer(call -> holds.test(call.getArgument(0, String.class)));
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    @Test
    @DisplayName("A server that runs no mode has no command, and a file that turns it off has none either")
    void noMode() throws Exception {
        assertThat(new ModeAdminCommands(
                                List.of(),
                                mock(IslandLocationService.class),
                                null,
                                mock(SchedulerPort.class),
                                Messages.bundled())
                        .build(null, (source, tag) -> true))
                .isEmpty();
        Path file = dir.resolve("off.conf");
        Files.writeString(file, "commands { isadmin { enabled = false } }\n");
        assertThat(admin.build(ConfiguredCommands.load(file), (source, tag) -> true))
                .isEmpty();
    }

    private static boolean parses(
            CommandDispatcher<CommandSourceStack> dispatcher, String line, java.util.function.Predicate<String> holds) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenAnswer(call -> holds.test(call.getArgument(0, String.class)));
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        var parsed = dispatcher.parse(line, source);
        return parsed.getExceptions().isEmpty()
                && !parsed.getReader().canRead()
                && parsed.getContext().getCommand() != null;
    }
}
