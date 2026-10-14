package com.uxplima.uxmskyblock.core.application.visit;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * The fee an island charges a visitor who comes by {@code /is visit}. A member whose role may change the island's
 * settings sets it, up to the most the operator allows; the money goes to the island's bank.
 */
public final class IslandVisitFeeService {

    private final VisitFeeStoragePort storage;
    private final IslandAccessService access;

    public IslandVisitFeeService(VisitFeeStoragePort storage, IslandAccessService access) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
    }

    /** The fee {@code island} charges, in minor units, or zero. */
    public long feeOf(IslandId island) {
        return storage.feeOf(Objects.requireNonNull(island, "island must not be null"));
    }

    /**
     * Sets the fee {@code island} charges to {@code minorUnits}; zero charges nothing.
     *
     * @param most the most an island may charge, in minor units
     * @throws SecurityException when {@code actor}'s role may not change the island's settings
     * @throws VisitFeeOutOfRangeException when the fee is below zero or above {@code most}
     */
    public void setFee(Island island, ProfileId actor, long minorUnits, long most) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (!access.checkPermission(island, actor, IslandPermission.SETTINGS_MODIFY)) {
            throw new SecurityException("Profile " + actor + " may not set the visit fee of island " + island.id());
        }
        if (minorUnits < 0 || minorUnits > most) {
            throw new VisitFeeOutOfRangeException(minorUnits, Math.max(0, most));
        }
        storage.setFee(island.id(), minorUnits);
    }
}
