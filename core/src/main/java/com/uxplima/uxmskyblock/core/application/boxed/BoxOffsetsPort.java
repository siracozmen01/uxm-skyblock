package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where it is kept how far each Boxed island's box was moved from the island's centre. */
public interface BoxOffsetsPort {

    /** Every box that was moved, by its island. A box never moved has no entry. */
    Map<IslandId, BoxOffset> findAll();

    /** Keeps the island's box {@code offset} from its centre. */
    void move(IslandId islandId, BoxOffset offset);
}
