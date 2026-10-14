package com.uxplima.uxmskyblock.bukkit.visitor;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/** What {@code /is visit} asks of the island's visit fee: what is owed, whether it was agreed to, and the paying. */
public interface VisitFeeGate {

    /**
     * What {@code visitor} owes to visit {@code island}, in minor units: zero for a member, for staff who bypass the
     * island protection, while the server has no economy, and for an island that charges nothing. Reads storage, so
     * it is asked off the player's thread.
     */
    long owed(IslandId island, Player visitor, @Nullable ProfileId profile);

    /**
     * Whether {@code visitor} was shown a fee for {@code island} a moment ago that is at least {@code owed}. A fee
     * raised after it was shown is not agreed to, and is shown again.
     */
    boolean agreed(Player visitor, IslandId island, long owed);

    /** Remembers that {@code visitor} was shown {@code owed} for {@code island}, and shows it. */
    void ask(Player visitor, IslandId island, long owed, String confirmLine);

    /**
     * Takes {@code owed} from {@code visitor}'s wallet into {@code island}'s bank and then runs {@code then} on the
     * visitor's thread. A wallet that cannot pay, or a bank that refuses the money, stops the visit and says so; the
     * money a bank refused goes back to the wallet.
     */
    void pay(Player visitor, IslandId island, long owed, Runnable then);
}
