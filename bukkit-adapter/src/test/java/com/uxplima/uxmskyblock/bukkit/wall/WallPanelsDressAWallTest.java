package com.uxplima.uxmskyblock.bukkit.wall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A wall entity is never saved, lit, marked with what it walls in, and shaped as {@link PanelShape} says; taking a
 * chunk's walls down takes only its owner's.
 */
class WallPanelsDressAWallTest extends MockBukkitHarness {

    private static final WallSegment NORTH = new WallSegment(WallSegment.Side.NORTH, 0, 0, 16);

    @SuppressWarnings("unchecked")
    private static <T extends Entity> List<T> spawnInto(World world, Class<T> kind) {
        List<T> spawned = new ArrayList<>();
        when(world.spawn(any(Location.class), eq(kind), any(Consumer.class))).thenAnswer(call -> {
            T entity = mock(kind);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(entity.getPersistentDataContainer()).thenReturn(data);
            call.getArgument(2, Consumer.class).accept(entity);
            spawned.add(entity);
            return entity;
        });
        return spawned;
    }

    @Test
    @DisplayName("A colour wall is text displays of the style's colour, fixed, never saved, lit and marked")
    @SuppressWarnings("unchecked")
    void aColourWall() {
        World world = mock(World.class);
        List<TextDisplay> spawned = spawnInto(world, TextDisplay.class);

        new WallPanels(0, 128, 64).draw(world, "island-1", NORTH, WallStyle.colour("mist", 0x8A93A1, 22));

        assertThat(spawned).hasSize(4);
        TextDisplay first = spawned.getFirst();
        verify(first)
                .setBackgroundColor(
                        Color.fromARGB(WallStyle.colour("mist", 0x8A93A1, 22).argb()));
        verify(first).setBillboard(Display.Billboard.FIXED);
        verify(first).setPersistent(false);
        verify(first).setBrightness(new Display.Brightness(15, 15));
        verify(first).setDisplayHeight(64f);
        verify(first.getPersistentDataContainer()).set(WallPanels.OWNER, PersistentDataType.STRING, "island-1");
        ArgumentCaptor<Location> at = ArgumentCaptor.forClass(Location.class);
        verify(world, times(4)).spawn(at.capture(), eq(TextDisplay.class), any(Consumer.class));
        assertThat(at.getAllValues()).extracting(Location::getYaw).containsExactly(0f, 180f, 0f, 180f);
    }

    @Test
    @DisplayName("A block wall is one block display per piece, of the style's block")
    @SuppressWarnings("unchecked")
    void aBlockWall() {
        World world = mock(World.class);
        List<BlockDisplay> spawned = spawnInto(world, BlockDisplay.class);

        new WallPanels(0, 128, 64).draw(world, "island-1", NORTH, WallStyle.block("glass", "minecraft:glass"));

        assertThat(spawned).hasSize(2);
        verify(spawned.getFirst()).setBlock(any());
        verify(spawned.getFirst()).setPersistent(false);
        verify(world, never()).spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
    }

    @Test
    @DisplayName("A block the server does not know draws nothing")
    @SuppressWarnings("unchecked")
    void anUnknownBlockDrawsNothing() {
        World world = mock(World.class);
        List<BlockDisplay> spawned = spawnInto(world, BlockDisplay.class);

        new WallPanels(0, 128, 64).draw(world, "island-1", NORTH, WallStyle.block("odd", "minecraft:no_such_block"));

        assertThat(spawned).isEmpty();
        verify(world, never()).spawn(any(Location.class), any(Class.class), any(Consumer.class));
    }

    @Test
    @DisplayName("Taking a chunk's walls down takes its owner's and leaves the rest")
    void clearingTakesOnlyTheOwners() {
        Entity mine = marked("island-1");
        Entity theirs = marked("island-2");
        Entity unmarked = marked(null);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getEntities()).thenReturn(new Entity[] {mine, theirs, unmarked});

        assertThat(new WallPanels(0, 64, 64).clear(chunk, "island-1")).isEqualTo(1);

        verify(mine).remove();
        verify(theirs, never()).remove();
        verify(unmarked, never()).remove();
    }

    private static Entity marked(@org.jspecify.annotations.Nullable String owner) {
        Entity entity = mock(Entity.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.get(WallPanels.OWNER, PersistentDataType.STRING)).thenReturn(owner);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        return entity;
    }
}
