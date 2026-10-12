package com.uxplima.uxmskyblock.bukkit.state;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.CarriedState;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;

/**
 * The carried state store in memory, refusing what the SQL one refuses: a crossing from a group the profile does
 * not carry for, or from an inventory version that is not the stored one.
 */
public final class CarriedStatesInMemory implements CarriedStatePort {

    final Map<ProfileId, ProfileInventoryRecord> inventories = new HashMap<>();
    final Map<ProfileId, String> groups = new HashMap<>();
    final Map<ProfileId, Map<String, CarriedState>> kept = new HashMap<>();
    int crossings;
    boolean refuse;

    @Override
    public Optional<String> carriedGroup(ProfileId profileId) {
        return Optional.ofNullable(groups.get(profileId));
    }

    @Override
    public String carryFirst(ProfileId profileId, String group) {
        return groups.computeIfAbsent(profileId, ignored -> group);
    }

    @Override
    public Crossed cross(Crossing crossing) {
        crossings++;
        ProfileId profile = crossing.profileId();
        ProfileInventoryRecord stored = inventories.get(profile);
        if (refuse) {
            return new Crossed.Refused("refused");
        }
        if (!crossing.from().equals(groups.get(profile))) {
            return new Crossed.Refused("carries for " + groups.get(profile));
        }
        if (stored == null || stored.version() != crossing.leaving().version()) {
            return new Crossed.Refused("version");
        }
        Map<String, CarriedState> states = kept.computeIfAbsent(profile, ignored -> new HashMap<>());
        CarriedState coming = Optional.ofNullable(states.remove(crossing.to())).orElse(CarriedState.FRESH);
        ProfileInventoryRecord arriving = crossing.parts()
                .arriving(crossing.leaving(), coming, crossing.leaving().version() + 1);
        inventories.put(profile, arriving);
        states.put(crossing.from(), CarriedState.of(crossing.leaving(), crossing.leavingAdvancements()));
        groups.put(profile, crossing.to());
        return new Crossed.Done(
                arriving, crossing.parts().advancementsArriving(List.copyOf(crossing.leavingAdvancements()), coming));
    }
}
