package com.uxplima.uxmskyblock.core.application.visit;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where each island's visit fee is kept. */
public interface VisitFeeStoragePort {

    /** The fee {@code island} charges a visitor, in minor units, or zero when it charges none. */
    long feeOf(IslandId island);

    /** Keeps {@code minorUnits} as the fee {@code island} charges; zero forgets it. */
    void setFee(IslandId island, long minorUnits);
}
