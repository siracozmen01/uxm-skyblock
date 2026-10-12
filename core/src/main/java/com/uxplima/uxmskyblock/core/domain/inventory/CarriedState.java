package com.uxplima.uxmskyblock.core.domain.inventory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * What a player put down on leaving one group of worlds, kept for the profile until they come back: the
 * inventory, the ender chest, experience, health, hunger, effects and the advancements earned there.
 *
 * <p>A profile keeps one for every group of worlds it is not standing in. The group it stands in is what the
 * player carries, written as the profile's own inventory.
 */
@SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
public record CarriedState(
        byte[] inventoryNbt,
        byte[] enderchestNbt,
        int experiencePoints,
        double health,
        int foodLevel,
        float saturation,
        byte @Nullable [] effectsNbt,
        List<String> advancements) {

    /** What a player finds in a group they never stood in: nothing carried, full health, a full stomach. */
    public static final CarriedState FRESH =
            new CarriedState(new byte[0], new byte[0], 0, 20.0, 20, 5.0f, null, List.of());

    public CarriedState {
        Objects.requireNonNull(inventoryNbt, "inventoryNbt");
        Objects.requireNonNull(enderchestNbt, "enderchestNbt");
        inventoryNbt = inventoryNbt.clone();
        enderchestNbt = enderchestNbt.clone();
        effectsNbt = effectsNbt == null ? null : effectsNbt.clone();
        advancements = List.copyOf(advancements);
    }

    /** What {@code carried} holds, with the advancements earned beside it. */
    public static CarriedState of(ProfileInventoryRecord carried, List<String> advancements) {
        return new CarriedState(
                carried.inventoryNbt(),
                carried.enderchestNbt(),
                carried.experiencePoints(),
                carried.health(),
                carried.foodLevel(),
                carried.saturation(),
                carried.activePotionEffectsNbt(),
                advancements);
    }

    @Override
    public byte[] inventoryNbt() {
        return inventoryNbt.clone();
    }

    @Override
    public byte[] enderchestNbt() {
        return enderchestNbt.clone();
    }

    @Override
    public byte @Nullable [] effectsNbt() {
        return effectsNbt == null ? null : effectsNbt.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CarriedState that
                && experiencePoints == that.experiencePoints
                && Double.compare(health, that.health) == 0
                && foodLevel == that.foodLevel
                && Float.compare(saturation, that.saturation) == 0
                && Arrays.equals(inventoryNbt, that.inventoryNbt)
                && Arrays.equals(enderchestNbt, that.enderchestNbt)
                && Arrays.equals(effectsNbt, that.effectsNbt)
                && advancements.equals(that.advancements);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(experiencePoints, health, foodLevel, saturation, advancements);
        result = 31 * result + Arrays.hashCode(inventoryNbt);
        result = 31 * result + Arrays.hashCode(enderchestNbt);
        return 31 * result + Arrays.hashCode(effectsNbt);
    }

    @Override
    public String toString() {
        return "CarriedState[" + inventoryNbt.length + " bytes carried, " + experiencePoints + " experience, "
                + advancements.size() + " advancements]";
    }
}
