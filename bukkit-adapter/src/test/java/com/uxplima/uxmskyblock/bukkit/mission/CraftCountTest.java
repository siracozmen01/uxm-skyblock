package com.uxplima.uxmskyblock.bukkit.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A craft mission counts what a craft made: once for a click, as often as the grid and the inventory allow on a shift click. */
class CraftCountTest {

    private static ItemStack stack(Material type, int amount, boolean similarToResult) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.isSimilar(any())).thenReturn(similarToResult);
        return stack;
    }

    private final ItemStack fourPlanks = stack(Material.OAK_PLANKS, 4, false);

    @Test
    @DisplayName("A click makes the result once")
    void aClick() {
        assertThat(CraftCount.made(
                        fourPlanks, new ItemStack[] {stack(Material.OAK_LOG, 9, false)}, false, new ItemStack[0]))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("A shift click makes as many as the fewest ingredient allows, and the inventory can hold")
    void aShiftClick() {
        ItemStack[] grid = {
            stack(Material.OAK_LOG, 9, false), null, stack(Material.AIR, 0, false), stack(Material.OAK_LOG, 12, false)
        };
        ItemStack[] roomy = {null, stack(Material.AIR, 0, false)};
        assertThat(CraftCount.made(fourPlanks, grid, true, roomy)).isEqualTo(36);

        ItemStack[] tight = {stack(Material.OAK_PLANKS, 50, true), stack(Material.STONE, 64, false)};
        assertThat(CraftCount.made(fourPlanks, grid, true, tight))
                .describedAs("fourteen fit, so three whole crafts")
                .isEqualTo(12);

        ItemStack[] full = {stack(Material.STONE, 64, false)};
        assertThat(CraftCount.made(fourPlanks, grid, true, full)).isZero();
    }

    @Test
    @DisplayName("No result, or an empty grid, makes nothing")
    void nothing() {
        assertThat(CraftCount.made(stack(Material.AIR, 0, false), new ItemStack[0], false, new ItemStack[0]))
                .isZero();
        assertThat(CraftCount.made(fourPlanks, new ItemStack[] {null}, true, new ItemStack[] {null}))
                .isZero();
        assertThat(CraftCount.made(stack(Material.AIR, 1, false), new ItemStack[0], false, new ItemStack[0]))
                .isZero();
    }
}
