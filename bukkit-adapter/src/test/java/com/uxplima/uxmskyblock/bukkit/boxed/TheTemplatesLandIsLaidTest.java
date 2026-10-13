package com.uxplima.uxmskyblock.bukkit.boxed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;

import com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoxedLand;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedLandPort;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A new Boxed island is played on the template's land: it is copied around the island, ring by ring, as far as the box
 * reaches and a little past it, and a ring is written down once it stands.
 */
class TheTemplatesLandIsLaidTest extends MockBukkitHarness {

    /** The template at chunk 100, 100; the island's centre in chunk 10, -5. */
    private static final BoxedLand LAND = new BoxedLand(true, 1600, 1600, 4);

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final Map<IslandId, Long> earned = new HashMap<>();
    private final Map<IslandId, Integer> rings = new HashMap<>();
    private final List<String> copied = new ArrayList<>();
    private final List<CompletableFuture<Void>> pending = new ArrayList<>();
    private boolean holdChunks;
    private boolean otherNodeMoves;
    private int spacing = 5120;

    private final BoxedService service = new BoxedService(
            new BoxedIslandsPort() {
                @Override
                public Map<IslandId, Long> findAll() {
                    return Map.copyOf(earned);
                }

                @Override
                public OptionalLong find(IslandId id) {
                    Long blocks = earned.get(id);
                    return blocks == null ? OptionalLong.empty() : OptionalLong.of(blocks);
                }

                @Override
                public void add(IslandId id) {
                    earned.putIfAbsent(id, 0L);
                }

                @Override
                public boolean earn(IslandId id, String advancement, int blocks) {
                    return false;
                }

                @Override
                public void grant(IslandId id, long blocks) {
                    earned.merge(id, blocks, Long::sum);
                }

                @Override
                public long bought(IslandId id) {
                    return 0;
                }
            },
            new BoxRules(
                    5,
                    1,
                    100,
                    Map.of(),
                    List.of(),
                    com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(1)));

    private final BoxedLandPort port = new BoxedLandPort() {
        @Override
        public void begin(IslandId id) {
            rings.putIfAbsent(id, -1);
        }

        @Override
        public OptionalInt rings(IslandId id) {
            Integer kept = rings.get(id);
            return kept == null ? OptionalInt.empty() : OptionalInt.of(kept);
        }

        @Override
        public boolean copied(IslandId id, int from, int to) {
            if (otherNodeMoves || !Integer.valueOf(from).equals(rings.get(id))) {
                return false;
            }
            rings.put(id, to);
            return true;
        }
    };

    @Test
    @DisplayName("A new island gets the rings its starting box and a little past it want, the template's chunk on each")
    void begun() {
        service.start(islandId);

        CompletableFuture<Void> done = copier(LAND).begin(islandId);

        assertThat(done).isDone();
        // A box of 5 and 4 ahead is 9 blocks: one ring around the centre's chunk.
        assertThat(rings).containsEntry(islandId, 1);
        assertThat(copied).hasSize(9).startsWith("boxed 100,100 to 10,-5").contains("boxed 99,101 to 9,-4");
    }

    @Test
    @DisplayName(
            "The box grown wants more rings, copied from where the last stopped, and a box grown again waits its turn")
    void grown() {
        service.start(islandId);
        BoxedLandCopier copier = copier(LAND);
        copier.begin(islandId).join();
        copied.clear();

        service.grant(islandId, 20);
        holdChunks = true;
        CompletableFuture<Void> first = copier.catchUp(islandId);
        service.grant(islandId, 16);
        CompletableFuture<Void> second = copier.catchUp(islandId);
        assertThat(copier.catchUp(islandId))
                .describedAs("one wait for every ask while it copies")
                .isSameAs(second);
        CompletableFuture<Void> third = null;
        while (!pending.isEmpty()) {
            pending.removeFirst().complete(null);
            if (first.isDone() && third == null && !pending.isEmpty()) {
                // Asked again while the second look copies: it waits for that one, rather than copying beside it.
                third = copier.catchUp(islandId);
            }
        }

        assertThat(third).isNotNull().isDone();
        assertThat(first).isDone();
        assertThat(second).isDone();
        // 25 and 4 ahead is two rings; 41 and 4 ahead, three.
        assertThat(rings).containsEntry(islandId, 3);
        assertThat(copied).hasSize(16 + 24).doesNotContain("boxed 100,100 to 10,-5");
        copied.clear();
        copier.catchUp(islandId).join();
        assertThat(copied).describedAs("nothing more to copy").isEmpty();
    }

    @Test
    @DisplayName("No chunk is laid past the island's own cell of the grid, so a neighbour's land is never touched")
    void keptToItsCell() {
        service.start(islandId);
        // The centre at block 168, -72; a spacing of 64 leaves 32 each way, blocks 137 to 199 and -103 to -41.
        spacing = 64;
        service.grant(islandId, 60);

        copier(LAND).begin(islandId).join();

        assertThat(rings)
                .describedAs("every ring the box wants is written down")
                .containsEntry(islandId, 5);
        assertThat(copied)
                .describedAs("chunks 9 to 11 along x and -6 to -4 along z lie whole inside the cell")
                .hasSize(9)
                .allSatisfy(line -> assertThat(line).matches("boxed \\d+,\\d+ to (9|10|11),-[4-6]"));
    }

    @Test
    @DisplayName("A ring cut short is not written down, and is copied again whole the next time")
    void cutShort() {
        service.start(islandId);
        BoxedLandCopier copier = copier(LAND);
        holdChunks = true;
        CompletableFuture<Void> done = copier.begin(islandId);
        pending.removeFirst().complete(null);
        pending.removeFirst().completeExceptionally(new IllegalStateException("the server stopped"));

        assertThat(done).isDone();
        assertThat(rings).describedAs("ring 0 stands, ring 1 does not").containsEntry(islandId, 0);
        holdChunks = false;
        copied.clear();
        copier.catchUp(islandId).join();
        assertThat(copied).hasSize(8);
        assertThat(rings).containsEntry(islandId, 1);
    }

    @Test
    @DisplayName("World land copies nothing, another node's move stops the copy, and a forgotten island lets go")
    void notCopied() {
        service.start(islandId);
        assertThat(copier(new BoxedLand(false, 0, 0, 16)).begin(islandId)).isDone();
        assertThat(copier(LAND).catchUp(islandId)).isDone();
        assertThat(rings).isEmpty();
        assertThat(copied).isEmpty();

        otherNodeMoves = true;
        copier(LAND).begin(islandId).join();
        assertThat(copied)
                .describedAs("ring 0 copied, not written, so no further")
                .hasSize(1);
        otherNodeMoves = false;

        BoxedLandCopier copier = copier(LAND);
        holdChunks = true;
        CompletableFuture<Void> running = copier.catchUp(islandId);
        CompletableFuture<Void> waiting = copier.catchUp(islandId);
        copier.forget(islandId);
        assertThat(waiting).isDone();
        int before = copied.size();
        copier.bringUp(islandId);
        assertThat(copied).describedAs("a forgotten island starts afresh").hasSize(before + 1);
        pending.removeFirst().complete(null);
        assertThat(running).isNotDone();
    }

    @Test
    @DisplayName("The land is read from the file, and a land that cannot be is the shipped one")
    void read() throws Exception {
        assertThat(load("land { from = world, template { x = 32, z = -48 }, ahead = 0 }"))
                .isEqualTo(new BoxedLand(false, 32, -48, 0));
        assertThat(load("land { from = template }")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(load("enabled = true")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(load("land { from = sky }")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(load("land { ahead = 129 }")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(load("land { ahead = -1 }")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(load("land { template { x = 30000000 } }")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(load("land { template { z = -30000000 } }")).isEqualTo(BoxedLand.SHIPPED);
        assertThat(new BoxedLand(true, 0, 0, 0).rings(16)).isEqualTo(1);
        assertThat(new BoxedLand(true, 0, 0, 1).rings(16)).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "A chunk of the template is laid on the island: blocks, biomes, what blocks hold and the creatures that stay")
    void aChunkIsLaid() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("boxed");
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(8);
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(Biome.PLAINS);
        BlockData air = mock(BlockData.class);
        BlockData stone = mock(BlockData.class);
        BlockData dirt = mock(BlockData.class);

        ChunkSnapshot blocks = mock(ChunkSnapshot.class);
        when(blocks.getBlockData(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(blocks.getBlockData(3, 1, 4)).thenReturn(stone);
        when(blocks.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(Biome.PLAINS);
        when(blocks.getBiome(8, 0, 8)).thenReturn(Biome.DESERT);
        when(blocks.getBiome(12, 4, 0)).thenReturn(Biome.DESERT);
        BlockState chest = mock(BlockState.class);
        when(chest.getX()).thenReturn(1605);
        when(chest.getY()).thenReturn(1);
        when(chest.getZ()).thenReturn(1606);
        BlockState laidChest = mock(BlockState.class);
        when(chest.copy(any(Location.class))).thenReturn(laidChest);
        Cow cow = creature(Cow.class, false);
        EntitySnapshot cowSnapshot = mock(EntitySnapshot.class);
        when(cow.createSnapshot()).thenReturn(cowSnapshot);
        when(cow.getLocation()).thenReturn(new Location(world, 1602.5, 2, 1603.5, 90, 10));
        Zombie passing = creature(Zombie.class, true);
        Chunk template = chunk(world, 100, 100);
        when(template.getChunkSnapshot(false, true, false)).thenReturn(blocks);
        when(template.getTileEntities()).thenReturn(new BlockState[] {chest});
        when(template.getEntities()).thenReturn(new Entity[] {cow, passing, mock(Player.class)});

        Map<String, BlockData> laid = new HashMap<>();
        List<String> steps = new ArrayList<>();
        laid.put("1,1,1", dirt);
        Chunk island = chunk(world, 10, 0);
        when(island.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            String key = call.getArgument(0) + "," + call.getArgument(1) + "," + call.getArgument(2);
            Block block = mock(Block.class);
            when(block.getBlockData()).thenAnswer(ignored -> laid.getOrDefault(key, air));
            when(block.getState(false)).thenAnswer(ignored -> {
                steps.add("read " + key);
                return null;
            });
            doAnswer(set -> {
                        steps.add("write " + key);
                        return laid.put(key, set.getArgument(0));
                    })
                    .when(block)
                    .setBlockData(any(BlockData.class), anyBoolean());
            return block;
        });
        Sheep made = creature(Sheep.class, false);
        Sheep named = creature(Sheep.class, false);
        when(named.customName()).thenReturn(net.kyori.adventure.text.Component.text("Dolly"));
        Sheep leashed = creature(Sheep.class, false);
        when(leashed.isLeashed()).thenReturn(true);
        Wolf tamed = creature(Wolf.class, false);
        when(tamed.isTamed()).thenReturn(true);
        Zombie wandering = creature(Zombie.class, true);
        Player player = mock(Player.class);
        when(island.getEntities()).thenReturn(new Entity[] {made, named, leashed, tamed, wandering, player});
        when(world.getChunkAtAsync(100, 100)).thenReturn(CompletableFuture.completedFuture(template));
        when(world.getChunkAtAsync(10, 0)).thenReturn(CompletableFuture.completedFuture(island));

        new TemplateChunks(regions(), name -> name.equals("boxed") ? world : null)
                .copy("boxed", 100, 100, 10, 0)
                .join();

        assertThat(laid)
                .describedAs("only the blocks that differ are written")
                .hasSize(2)
                .containsEntry("3,1,4", stone)
                .containsEntry("1,1,1", air);
        assertThat(steps)
                .describedAs("a block written over is read first, and no other is read")
                .containsExactly("read 1,1,1", "write 1,1,1", "read 3,1,4", "write 3,1,4");
        verify(world).setBiome(168, 0, 8, Biome.DESERT);
        verify(world).setBiome(172, 4, 0, Biome.DESERT);
        verify(world, never()).setBiome(anyInt(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.eq(Biome.PLAINS));
        verify(chest).copy(new Location(world, 165, 1, 6));
        verify(laidChest).update(true, false);
        verify(cowSnapshot).createEntity(new Location(world, 162.5, 2, 3.5, 90, 10));
        verify(passing, never()).createSnapshot();
        verify(made).remove();
        for (Entity stays : new Entity[] {named, leashed, tamed, wandering, player}) {
            verify(stays, never()).remove();
        }
        assertThat(new TemplateChunks(regions(), name -> null).copy("nowhere", 0, 0, 1, 1))
                .isCompletedExceptionally();
        when(world.getChunkAtAsync(5, 5)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("gone")));
        assertThat(new TemplateChunks(regions(), name -> world).copy("boxed", 5, 5, 10, 0))
                .isCompletedExceptionally();
    }

    private static <T extends LivingEntity> T creature(Class<T> kind, boolean comesAndGoes) {
        T creature = mock(kind);
        when(creature.getRemoveWhenFarAway()).thenReturn(comesAndGoes);
        return creature;
    }

    private static Chunk chunk(World world, int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        when(chunk.getWorld()).thenReturn(world);
        return chunk;
    }

    private SchedulerPort regions() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        return scheduler;
    }

    private static BoxedLand load(String written) throws Exception {
        return BoxedConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .land();
    }

    private BoxedLandCopier copier(BoxedLand land) {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        return new BoxedLandCopier(
                port,
                service,
                id -> Optional.of(IslandLocation.fromCenterAndRadius(id, "boxed", 168, -72, 5)),
                (world, fromX, fromZ, toX, toZ) -> {
                    copied.add(world + " " + fromX + "," + fromZ + " to " + toX + "," + toZ);
                    if (!holdChunks) {
                        return CompletableFuture.completedFuture(null);
                    }
                    CompletableFuture<Void> held = new CompletableFuture<>();
                    pending.add(held);
                    return held;
                },
                scheduler,
                land,
                () -> spacing);
    }
}
