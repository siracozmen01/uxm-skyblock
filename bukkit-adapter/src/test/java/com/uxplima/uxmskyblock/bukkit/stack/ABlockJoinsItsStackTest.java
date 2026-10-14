package com.uxplima.uxmskyblock.bukkit.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * SuperiorSkyblock2 and IridiumSkyblock stack valuable blocks so an island's worth can grow without building a
 * warehouse of them; here every block was a block.
 */
class ABlockJoinsItsStackTest extends MockBukkitHarness {

    private final IslandWorthService worth = mock(IslandWorthService.class);
    private final IslandProtectionListener islands = mock(IslandProtectionListener.class);
    private StackingConfiguration rules =
            new StackingConfiguration(true, EnumSet.of(Material.DIAMOND_BLOCK, Material.GOLD_BLOCK), 3);
    private World world;
    private PlayerMock player;
    private Island island;
    private BlockStackListener listener;
    private Block base;

    /** What the stack did around it: each line it showed, as place and size, and each block it gave back. */
    private final List<String> shown = new ArrayList<>();

    private final List<String> givenBack = new ArrayList<>();

    private final StackWorld around = new StackWorld() {
        @Override
        public void show(Block block, int size) {
            shown.add(block.getX() + "," + block.getY() + "," + block.getZ() + "=" + size);
        }

        @Override
        public void giveBack(Block block, org.bukkit.entity.Player breaker) {
            givenBack.add(block.getType() + " to " + breaker.getName());
        }
    };

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        player = createPlayer("Stacker");
        player.setGameMode(GameMode.SURVIVAL);
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        when(islands.findIslandAt(any(org.bukkit.Location.class))).thenReturn(Optional.of(island));
        listener = new BlockStackListener(() -> rules, islands, worth, around);
        base = world.getBlockAt(4, 64, 4);
        base.setType(Material.DIAMOND_BLOCK);
    }

    /** A diamond block from the player's hand placed on top of {@code against}. */
    private BlockPlaceEvent placeOn(Block against, Material kind) {
        player.getInventory().setItemInMainHand(new ItemStack(kind, 5));
        Block placed = against.getRelative(BlockFace.UP);
        placed.setType(kind);
        return new BlockPlaceEvent(
                placed,
                placed.getState(),
                against,
                player.getInventory().getItemInMainHand(),
                player,
                true,
                EquipmentSlot.HAND);
    }

    @Test
    @DisplayName("A block placed on its kind joins the stack: one taken from the hand, one more counted toward worth")
    void aBlockJoins() {
        BlockPlaceEvent event = placeOn(base, Material.DIAMOND_BLOCK);
        listener.onPlace(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(BlockStacks.size(base)).isEqualTo(2);
        assertThat(player.getInventory().getItemInMainHand().getAmount()).isEqualTo(4);
        verify(worth).recordBlockPlace(island.id(), "minecraft:diamond_block", 1);
        assertThat(shown).containsExactly("4,64,4=2");
    }

    @Test
    @DisplayName("Sneaking, another kind, a kind that does not stack, a full stack and no island place the usual way")
    void theUsualWay() {
        player.setSneaking(true);
        BlockPlaceEvent sneaking = placeOn(base, Material.DIAMOND_BLOCK);
        listener.onPlace(sneaking);
        assertThat(sneaking.isCancelled()).isFalse();
        player.setSneaking(false);

        BlockPlaceEvent other = placeOn(base, Material.GOLD_BLOCK);
        listener.onPlace(other);
        assertThat(other.isCancelled()).isFalse();

        Block dirt = world.getBlockAt(8, 64, 8);
        dirt.setType(Material.DIRT);
        BlockPlaceEvent plain = placeOn(dirt, Material.DIRT);
        listener.onPlace(plain);
        assertThat(plain.isCancelled()).isFalse();

        BlockStacks.set(base, 3);
        BlockPlaceEvent full = placeOn(base, Material.DIAMOND_BLOCK);
        listener.onPlace(full);
        assertThat(full.isCancelled()).isFalse();

        BlockStacks.set(base, 1);
        when(islands.findIslandAt(any(org.bukkit.Location.class))).thenReturn(Optional.empty());
        BlockPlaceEvent wild = placeOn(base, Material.DIAMOND_BLOCK);
        listener.onPlace(wild);
        assertThat(wild.isCancelled()).isFalse();
        verify(worth, never()).recordBlockPlace(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Stacking off in the file stacks nothing, and a creative player keeps the block")
    void offAndCreative() {
        rules = new StackingConfiguration(false, EnumSet.of(Material.DIAMOND_BLOCK), 3);
        BlockPlaceEvent off = placeOn(base, Material.DIAMOND_BLOCK);
        listener.onPlace(off);
        assertThat(off.isCancelled()).isFalse();

        rules = new StackingConfiguration(true, EnumSet.of(Material.DIAMOND_BLOCK), 3);
        player.setGameMode(GameMode.CREATIVE);
        listener.onPlace(placeOn(base, Material.DIAMOND_BLOCK));
        assertThat(player.getInventory().getItemInMainHand().getAmount()).isEqualTo(5);
        assertThat(BlockStacks.size(base)).isEqualTo(2);
    }

    @Test
    @DisplayName("Breaking a stack gives back one at a time and keeps the block until it is the last")
    void breakingGivesOneBack() {
        BlockStacks.set(base, 2);
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));

        BlockBreakEvent first = new BlockBreakEvent(base, player);
        listener.onBreak(first);
        assertThat(first.isCancelled()).isTrue();
        assertThat(BlockStacks.size(base)).isEqualTo(1);
        assertThat(givenBack).containsExactly("DIAMOND_BLOCK to Stacker");
        assertThat(shown).containsExactly("4,64,4=1");
        verify(worth).recordBlockBreak(island.id(), "minecraft:diamond_block", 1);

        BlockStacks.set(base, 3);
        player.setGameMode(GameMode.CREATIVE);
        listener.onBreak(new BlockBreakEvent(base, player));
        assertThat(givenBack).describedAs("creative gets nothing back").hasSize(1);
        assertThat(BlockStacks.size(base)).isEqualTo(2);
        BlockStacks.set(base, 1);

        BlockBreakEvent last = new BlockBreakEvent(base, player);
        listener.onBreak(last);
        assertThat(last.isCancelled())
                .describedAs("the last block breaks the usual way")
                .isFalse();
    }

    @Test
    @DisplayName("A piston does not move a stack and an explosion does not break one")
    void aStackStaysPut() {
        BlockStacks.set(base, 2);
        Block piston = world.getBlockAt(3, 64, 4);
        BlockPistonExtendEvent push = new BlockPistonExtendEvent(piston, List.of(base), BlockFace.EAST);
        listener.onPush(push);
        assertThat(push.isCancelled()).isTrue();

        Block plain = world.getBlockAt(9, 64, 9);
        plain.setType(Material.STONE);
        EntityExplodeEvent boom = new EntityExplodeEvent(
                player,
                base.getLocation(),
                new ArrayList<>(List.of(base, plain)),
                1f,
                org.bukkit.ExplosionResult.DESTROY);
        listener.onEntityExplode(boom);
        assertThat(boom.blockList()).containsExactly(plain);
    }

    @Test
    @DisplayName("A block placed the usual way where a stack's count was left behind starts from one")
    void aLeftCountIsCleared() {
        Block spot = world.getBlockAt(6, 64, 6);
        BlockStacks.set(spot, 9);
        spot.setType(Material.DIAMOND_BLOCK);
        BlockPlaceEvent placed = new BlockPlaceEvent(
                spot,
                spot.getState(),
                world.getBlockAt(6, 63, 6),
                new ItemStack(Material.DIAMOND_BLOCK),
                player,
                true,
                EquipmentSlot.HAND);
        listener.onPlacedPlainly(placed);
        assertThat(BlockStacks.size(spot)).isEqualTo(1);
        assertThat(shown).containsExactly("6,64,6=1");
    }

    @Test
    @DisplayName("A chunk's stacks read back by place, below zero too, and one or fewer is no stack")
    void stacksReadBack() {
        Block west = world.getBlockAt(-3, 5, 17);
        BlockStacks.set(west, 12);
        BlockStacks.set(base, 4);
        // A test world goes no lower than zero, so a stack below it is written as the server would write one.
        west.getChunk()
                .getPersistentDataContainer()
                .set(
                        new org.bukkit.NamespacedKey("uxmskyblock", "stack_13_-20_1"),
                        org.bukkit.persistence.PersistentDataType.INTEGER,
                        6);

        assertThat(BlockStacks.in(west.getChunk()))
                .containsEntry(new BlockStacks.Place(-3, 5, 17), 12)
                .containsEntry(new BlockStacks.Place(-3, -20, 17), 6);
        assertThat(BlockStacks.in(base.getChunk()))
                .containsExactlyEntriesOf(java.util.Map.of(new BlockStacks.Place(4, 64, 4), 4));
        BlockStacks.set(base, 1);
        assertThat(BlockStacks.in(base.getChunk())).isEmpty();
        assertThat(BlockStacks.size(base)).isEqualTo(1);
        assertThat(base.getChunk().getPersistentDataContainer().has(BlockStacks.keyOf(base)))
                .describedAs("a stack of one leaves nothing written")
                .isFalse();

        // An entry written as one, or as nothing at all, is no stack.
        base.getChunk()
                .getPersistentDataContainer()
                .set(BlockStacks.keyOf(base), org.bukkit.persistence.PersistentDataType.INTEGER, 1);
        assertThat(BlockStacks.in(base.getChunk())).isEmpty();
        base.getChunk()
                .getPersistentDataContainer()
                .set(BlockStacks.keyOf(base), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
        assertThat(BlockStacks.size(base)).isEqualTo(1);
    }

    @Test
    @DisplayName("A stack on the island next door, reached across the border, is not joined")
    void theNeighboursStackIsNotJoined() {
        Island neighbour = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(200, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        when(islands.findIslandAt(base.getLocation())).thenReturn(Optional.of(neighbour));
        BlockPlaceEvent across = placeOn(base, Material.DIAMOND_BLOCK);
        when(islands.findIslandAt(across.getBlockPlaced().getLocation())).thenReturn(Optional.of(island));

        listener.onPlace(across);

        assertThat(across.isCancelled()).isFalse();
        assertThat(BlockStacks.size(base)).isEqualTo(1);

        when(islands.findIslandAt(across.getBlockPlaced().getLocation())).thenReturn(Optional.empty());
        listener.onPlace(placeOn(base, Material.DIAMOND_BLOCK));
        assertThat(BlockStacks.size(base)).describedAs("from the wild").isEqualTo(1);
    }

    @Test
    @DisplayName("Every handler runs after the island's protection, which runs at HIGH, and never on a refusal")
    void afterTheProtection() throws Exception {
        for (java.lang.reflect.Method handler : BlockStackListener.class.getDeclaredMethods()) {
            org.bukkit.event.EventHandler mark = handler.getAnnotation(org.bukkit.event.EventHandler.class);
            if (mark == null) {
                continue;
            }
            assertThat(mark.ignoreCancelled()).describedAs(handler.getName()).isTrue();
            assertThat(mark.priority().getSlot())
                    .describedAs(handler.getName())
                    .isGreaterThan(org.bukkit.event.EventPriority.HIGH.getSlot());
        }
        assertThat(IslandProtectionListener.class
                        .getMethod("onBlockBreak", BlockBreakEvent.class)
                        .getAnnotation(org.bukkit.event.EventHandler.class)
                        .priority())
                .isEqualTo(org.bukkit.event.EventPriority.HIGH);
        assertThat(IslandProtectionListener.class
                        .getMethod("onBlockPlace", BlockPlaceEvent.class)
                        .getAnnotation(org.bukkit.event.EventHandler.class)
                        .priority())
                .isEqualTo(org.bukkit.event.EventPriority.HIGH);
    }
}
