package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /isadmin realms remirror}: an operator lays an island's Upside Down again from its land, the whole island or
 * one chunk, copying only the plain ground a late mirror copies.
 */
class AnOperatorLaysTheUpsideDownAgainTest extends MockBukkitHarness {

    /** Every chunk is there at once, as MockBukkit has it: the mirror's own work is what these tests read. */
    private static final UpsideDownStart.ChunkLoads LOADED =
            (world, chunkX, chunkZ) -> java.util.concurrent.CompletableFuture.completedFuture(null);

    private static final String UPSIDE_DOWN = "upside";
    private static final int Y = 70;
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final IslandId ORDINARY = IslandId.of(UUID.randomUUID());

    private final Set<IslandId> stranger = new HashSet<>(Set.of(ISLAND));
    private final List<String> regions = new ArrayList<>();
    private World land;
    private World upsideDown;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private PlayerMock operator;

    @BeforeEach
    void setUp() {
        land = server.addSimpleWorld("realms");
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        StrangerRealmsService service = new StrangerRealmsService(new StrangerRealmsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stranger);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stranger.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stranger.add(islandId);
            }

            @Override
            public int farthestReach() {
                return 0;
            }
        });
        service.prime();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(0) + ":" + call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        UpsideDownStart mirror = new UpsideDownStart(
                service,
                scheduler,
                new StrangerRealmsConfiguration.UpsideDown(16, List.of("GRASS_BLOCK:MYCELIUM")),
                () -> UPSIDE_DOWN,
                LOADED);
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findLocation(any(IslandId.class))).thenReturn(Optional.empty());
        // Blocks -2 to 18 each way: chunks -1 to 1.
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(ISLAND, "realms", 8, 8, 10)));
        when(locations.findLocation(ORDINARY))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(ORDINARY, "realms", 400, 8, 10)));
        Map<String, World> worlds = Map.of("realms", land, UPSIDE_DOWN, upsideDown);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new ModeAdminCommands(
                        List.of(new StrangerRealmsAdmin(
                                service,
                                mirror,
                                () -> UPSIDE_DOWN,
                                worlds::get,
                                UpsideDownReach.copiedOnly(List.of("GRASS_BLOCK", "STONE")))),
                        locations,
                        null,
                        scheduler,
                        Messages.bundled())
                .build(null, (source, tag) -> true)
                .orElseThrow());
        operator = server.addPlayer("Op");
        operator.setOp(true);
    }

    private String run(String line) throws Exception {
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
    @DisplayName("One chunk is laid again from the land, plain ground copied and what was built left out")
    void oneChunk() throws Exception {
        land.getBlockAt(1, Y, 1).setType(Material.GRASS_BLOCK);
        land.getBlockAt(2, Y, 1).setType(Material.DIAMOND_BLOCK);
        upsideDown.getBlockAt(3, Y, 1).setType(Material.NETHERRACK);
        land.getBlockAt(17, Y, 1).setType(Material.STONE);

        assertThat(run("isadmin realms remirror " + ISLAND.value() + " 0 0")).contains("1 chunks");

        assertThat(upsideDown.getBlockAt(1, Y, 1).getType())
                .describedAs("grass turned")
                .isEqualTo(Material.MYCELIUM);
        assertThat(upsideDown.getBlockAt(2, Y, 1).getType())
                .describedAs("a built block is not had twice")
                .isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(3, Y, 1).getType())
                .describedAs("what stood in the Upside Down is laid over")
                .isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(17, Y, 1).getType())
                .describedAs("the chunk beside it is left")
                .isEqualTo(Material.AIR);
        assertThat(run("isadmin realms remirror " + ISLAND.value() + " 5 5")).contains("outside the island");
        assertThat(run("isadmin realms remirror " + ISLAND.value() + " 0 2")).contains("outside the island");
        assertThat(run("isadmin realms remirror " + ISLAND.value() + " 0 -2")).contains("outside the island");
    }

    @Test
    @DisplayName("The whole island is laid again, every chunk on the land's own region")
    void theWholeIsland() throws Exception {
        land.getBlockAt(17, Y, -1).setType(Material.STONE);

        assertThat(run("isadmin realms remirror " + ISLAND.value())).contains("9 chunks");

        assertThat(upsideDown.getBlockAt(17, Y, -1).getType()).isEqualTo(Material.STONE);
        assertThat(regions).contains("realms:-1,-1", "realms:1,1", "realms:0,0");
    }

    @Test
    @DisplayName("An island that is not a StrangerRealms island is said to be one, and nothing is laid")
    void notRealms() throws Exception {
        assertThat(run("isadmin realms remirror " + ORDINARY.value())).contains("not a StrangerRealms island");
        assertThat(regions).isEmpty();
    }
}
