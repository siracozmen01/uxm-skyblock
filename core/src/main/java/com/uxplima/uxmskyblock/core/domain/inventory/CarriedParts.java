package com.uxplima.uxmskyblock.core.domain.inventory;

import java.util.List;

/**
 * Which parts of what a player carries each group of worlds keeps apart. A part a group does not keep apart
 * is carried into every group as it is.
 */
public record CarriedParts(
        boolean inventory,
        boolean enderChest,
        boolean experience,
        boolean health,
        boolean hunger,
        boolean effects,
        boolean advancements) {

    /** Every part kept apart. */
    public static final CarriedParts ALL = new CarriedParts(true, true, true, true, true, true, true);

    /**
     * What the player carries on arriving in a group: each part kept apart from what the group kept,
     * {@code coming}, and every other part from what they bring, {@code leaving}. Where they stand, their game
     * mode and flight are theirs and come along. The result is written at {@code version}.
     */
    @SuppressWarnings("NullAway") // effects are nullable, as ProfileInventoryRecord keeps them
    public ProfileInventoryRecord arriving(ProfileInventoryRecord leaving, CarriedState coming, long version) {
        return new ProfileInventoryRecord(
                leaving.profileId(),
                version,
                inventory ? coming.inventoryNbt() : leaving.inventoryNbt(),
                enderChest ? coming.enderchestNbt() : leaving.enderchestNbt(),
                experience ? coming.experiencePoints() : leaving.experiencePoints(),
                health ? coming.health() : leaving.health(),
                hunger ? coming.foodLevel() : leaving.foodLevel(),
                hunger ? coming.saturation() : leaving.saturation(),
                effects ? coming.effectsNbt() : leaving.activePotionEffectsNbt(),
                leaving.logoutWorld(),
                leaving.logoutX(),
                leaving.logoutY(),
                leaving.logoutZ(),
                leaving.gamemode(),
                leaving.flightAllowed());
    }

    /** The advancements the player holds on arriving: the group's own if kept apart, else those they bring. */
    public List<String> advancementsArriving(List<String> leaving, CarriedState coming) {
        return advancements ? coming.advancements() : List.copyOf(leaving);
    }
}
