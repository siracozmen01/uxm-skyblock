package com.uxplima.uxmskyblock.core.application.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.CarriedParts;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Where a profile keeps what a player put down in each group of worlds, and which group the player carries for.
 *
 * <p>A crossing between groups is written before the player is given anything: what they put down, what they
 * pick up as the profile's own inventory, and the group they now carry for, in one transaction under the session's
 * authority and the inventory's version. A crash on either side of it leaves the profile whole.
 */
public interface CarriedStatePort {

    /** The group whose things the profile carries, or nothing before it first stood in a world a group names. */
    Optional<String> carriedGroup(ProfileId profileId);

    /**
     * Records {@code group} as the one the profile carries for, when it has none yet, and answers the group it
     * carries for after.
     */
    String carryFirst(ProfileId profileId, String group);

    /** Puts down what the player carries in {@link Crossing#from()} and picks up what they kept in the other. */
    Crossed cross(Crossing crossing);

    /**
     * One crossing between groups.
     *
     * @param leaving everything the player carries now, at the version the session last wrote
     * @param leavingAdvancements the advancements the player holds now
     * @param parts which parts each group keeps apart
     */
    record Crossing(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId node,
            long epoch,
            String from,
            String to,
            ProfileInventoryRecord leaving,
            List<String> leavingAdvancements,
            CarriedParts parts) {

        public Crossing {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(leaving, "leaving");
            Objects.requireNonNull(parts, "parts");
            leavingAdvancements = List.copyOf(leavingAdvancements);
            if (!leaving.profileId().equals(profileId)) {
                throw new IllegalArgumentException("The state of " + leaving.profileId() + " is not " + profileId);
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException("A crossing goes from one group to another, not to " + to);
            }
        }
    }

    /** How a crossing ended. */
    sealed interface Crossed {

        /** Written: the player is to be given {@code carried}, at its version, and {@code advancements}. */
        record Done(ProfileInventoryRecord carried, List<String> advancements) implements Crossed {
            public Done {
                Objects.requireNonNull(carried, "carried");
                advancements = List.copyOf(advancements);
            }
        }

        /** Nothing written: the session, the version or the group the profile carries for was not as asked. */
        record Refused(String why) implements Crossed {
            public Refused {
                Objects.requireNonNull(why, "why");
            }
        }
    }
}
