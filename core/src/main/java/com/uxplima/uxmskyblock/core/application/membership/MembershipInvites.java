package com.uxplima.uxmskyblock.core.application.membership;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * Asking a player onto an island and their answer: the invite held until it runs out, and the join under the island's member cap. Split out of {@link IslandMembershipService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class MembershipInvites {

    private final IslandMembershipService host;

    MembershipInvites(IslandMembershipService host) {
        this.host = host;
    }

    /** Invites {@code target} to the island {@code actor} belongs to. */
    public IslandMembershipService.InviteOutcome invite(ProfileId actor, ProfileId target) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(target, "target must not be null");

        Optional<Island> optIsland = host.islandOf(actor);
        if (optIsland.isEmpty()) {
            return new IslandMembershipService.InviteOutcome.NoIsland();
        }
        Island island = optIsland.get();
        if (!IslandMembershipService.may(island, actor, IslandPermission.MEMBER_INVITE)) {
            return new IslandMembershipService.InviteOutcome.NotAllowed();
        }
        if (island.isMember(target)) {
            return new IslandMembershipService.InviteOutcome.AlreadyAMember();
        }
        if (host.islandStoragePort.findIslandIdByProfileId(target).isPresent()) {
            return new IslandMembershipService.InviteOutcome.AlreadyOnAnotherIsland();
        }
        int allowed = host.memberAllowance.applyAsInt(island.id());
        if (island.members().size() >= allowed) {
            return new IslandMembershipService.InviteOutcome.IslandFull(allowed);
        }

        Instant expiresAt = host.clock.instant().plus(host.inviteTimeout);
        host.invites.put(target, new IslandMembershipService.PendingInvite(island.id(), actor, target, expiresAt));
        return new IslandMembershipService.InviteOutcome.Sent(target, expiresAt);
    }
    /** The invite {@code target} has been asked to answer, if one still stands. */
    public Optional<IslandMembershipService.PendingInvite> pendingInvite(ProfileId target) {
        Objects.requireNonNull(target, "target must not be null");
        IslandMembershipService.PendingInvite invite = host.invites.get(target);
        if (invite == null) {
            return Optional.empty();
        }
        if (!invite.expiresAt().isAfter(host.clock.instant())) {
            host.invites.remove(target, invite);
            return Optional.empty();
        }
        return Optional.of(invite);
    }

    IslandMembershipService.JoinOutcome acceptInside(ProfileId target, PlayerUuid targetPlayerUuid) {
        Optional<IslandMembershipService.PendingInvite> optInvite = pendingInvite(target);
        if (optInvite.isEmpty()) {
            return new IslandMembershipService.JoinOutcome.NoInvite();
        }
        if (host.islandStoragePort.findIslandIdByProfileId(target).isPresent()) {
            return new IslandMembershipService.JoinOutcome.AlreadyOnAnIsland();
        }

        IslandMembershipService.PendingInvite invite = optInvite.get();
        // The island is read inside the lock, so two players answering at the same moment do not
        // both read it as it was and both write themselves in, losing one of the two.
        return host.mutationLock.inside(invite.islandId(), () -> {
            Optional<Island> optIsland = host.islandStoragePort.findIslandById(invite.islandId());
            Optional<IslandLocation> optLocation = host.islandStoragePort.findLocationByIslandId(invite.islandId());
            if (optIsland.isEmpty() || optLocation.isEmpty()) {
                host.invites.remove(target, invite);
                return new IslandMembershipService.JoinOutcome.IslandMissing();
            }

            Island island = optIsland.get();
            int allowed = host.memberAllowance.applyAsInt(island.id());
            if (island.members().size() >= allowed) {
                return new IslandMembershipService.JoinOutcome.IslandFull(allowed);
            }

            IslandRole memberRole = island.roles().getOrDefault(IslandRole.MEMBER.id(), IslandRole.MEMBER);
            Island joined =
                    island.addMember(new IslandMember(targetPlayerUuid, target, memberRole, host.clock.instant()));
            host.islandStoragePort.saveIsland(joined, optLocation.get());
            host.invites.remove(target, invite);
            return new IslandMembershipService.JoinOutcome.Joined(island.id());
        });
    }
}
