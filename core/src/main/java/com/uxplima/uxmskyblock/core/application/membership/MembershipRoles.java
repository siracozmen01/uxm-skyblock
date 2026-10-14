package com.uxplima.uxmskyblock.core.application.membership;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * The roles of an island's members: one member's role changed, and what a role may do, each under the rules of who may change whom. Split out of {@link IslandMembershipService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class MembershipRoles {

    private final IslandMembershipService host;

    MembershipRoles(IslandMembershipService host) {
        this.host = host;
    }

    /** Puts {@code target} into {@code rawRoleId}, if the caller's role may. */
    public IslandMembershipService.RoleOutcome setRole(ProfileId actor, ProfileId target, String rawRoleId) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(rawRoleId, "rawRoleId must not be null");

        Optional<Island> optIsland = host.islandOf(actor);
        if (optIsland.isEmpty()) {
            return new IslandMembershipService.RoleOutcome.NoIsland();
        }
        Island island = optIsland.get();
        if (island.ownerProfileId().equals(target)) {
            return new IslandMembershipService.RoleOutcome.CannotChangeOwner();
        }
        IslandMember member = island.members().get(target);
        if (member == null) {
            return new IslandMembershipService.RoleOutcome.NotAMember();
        }

        String roleId = rawRoleId.toUpperCase(Locale.ROOT);
        IslandRole role = island.roles().get(roleId);
        if (role == null) {
            return new IslandMembershipService.RoleOutcome.UnknownRole(
                    rawRoleId.toLowerCase(Locale.ROOT), IslandMembershipService.assignableRoleNames(island));
        }
        if (role.isSystem()) {
            return new IslandMembershipService.RoleOutcome.NotAssignable(
                    rawRoleId.toLowerCase(Locale.ROOT), IslandMembershipService.assignableRoleNames(island));
        }

        // Moving somebody up and moving them down are two permissions, and which one this is is
        // the weight of the role they are going to against the weight of the one they hold. There
        // is no ROLE_MANAGE: the roles carry MEMBER_PROMOTE and MEMBER_DEMOTE separately, so an
        // island can let a moderator demote without letting them promote.
        IslandPermission needed = role.weight() >= member.role().weight()
                ? IslandPermission.MEMBER_PROMOTE
                : IslandPermission.MEMBER_DEMOTE;
        if (!IslandMembershipService.may(island, actor, needed)) {
            return new IslandMembershipService.RoleOutcome.NotAllowed();
        }
        // The permission says whether the caller moves people at all; the ranks say whom and where to.
        // Without them a co-owner could hand out the owner's role, and a moderator allowed to demote
        // could demote the co-owner above them.
        if (!IslandMembershipService.outranks(island, actor, member.role().weight())
                || !IslandMembershipService.outranks(island, actor, role.weight())) {
            return new IslandMembershipService.RoleOutcome.OutOfReach();
        }

        IslandMembershipService.RoleOutcome outcome = host.mutationLock.inside(island.id(), () -> {
            Optional<Island> optFresh = host.islandStoragePort.findIslandById(island.id());
            Optional<IslandLocation> optLocation = host.islandStoragePort.findLocationByIslandId(island.id());
            if (optFresh.isEmpty() || optLocation.isEmpty()) {
                return new IslandMembershipService.RoleOutcome.NoIsland();
            }
            Island fresh = optFresh.get();
            IslandMember current = fresh.members().get(target);
            if (current == null) {
                return new IslandMembershipService.RoleOutcome.NotAMember();
            }
            Island updated = fresh.addMember(new IslandMember(current.playerUuid(), target, role, current.joinedAt()));
            host.islandStoragePort.saveIsland(updated, optLocation.get());
            return new IslandMembershipService.RoleOutcome.Changed(target, roleId.toLowerCase(Locale.ROOT));
        });
        if (outcome instanceof IslandMembershipService.RoleOutcome.Changed) {
            host.tellRolesChanged(island.id());
        }
        return outcome;
    }
    /**
     * Turns one permission on or off for one role on the caller's island.
     *
     * <p>The design document publishes {@code /is permissions} for exactly this, and the table that
     * holds it has been written on every save since the island writer was written. Nothing could
     * move one: the roles an island was created with were the roles it died with.
     *
     * <p>The owner's role is left alone. An owner who can take a permission off their own role can
     * lock themselves out of their own island, and no message would bring it back.
     */
    public IslandMembershipService.PermissionOutcome setRolePermission(
            ProfileId actor, String rawRoleId, String rawPermission, boolean allowed) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(rawRoleId, "rawRoleId must not be null");
        Objects.requireNonNull(rawPermission, "rawPermission must not be null");

        Optional<Island> optIsland = host.islandOf(actor);
        if (optIsland.isEmpty()) {
            return new IslandMembershipService.PermissionOutcome.NoIsland();
        }
        Island island = optIsland.get();
        if (!IslandMembershipService.may(island, actor, IslandPermission.MEMBER_PROMOTE)) {
            return new IslandMembershipService.PermissionOutcome.NotAllowed();
        }

        String roleId = rawRoleId.toUpperCase(Locale.ROOT);
        IslandRole role = island.roles().get(roleId);
        if (role == null) {
            return new IslandMembershipService.PermissionOutcome.UnknownRole(
                    rawRoleId.toLowerCase(Locale.ROOT), IslandMembershipService.roleNames(island));
        }
        if (roleId.equals(IslandRole.OWNER.id())) {
            return new IslandMembershipService.PermissionOutcome.CannotChangeOwnerRole();
        }
        if (!IslandMembershipService.outranks(island, actor, role.weight())) {
            // A caller who could edit their own role, or one above it, could grant themselves anything.
            return new IslandMembershipService.PermissionOutcome.OutOfReach();
        }

        IslandPermission permission;
        try {
            permission = IslandPermission.valueOf(rawPermission.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return new IslandMembershipService.PermissionOutcome.UnknownPermission(
                    rawPermission.toLowerCase(Locale.ROOT), IslandMembershipService.permissionNames());
        }

        Set<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
        permissions.addAll(role.permissions());
        if (allowed) {
            permissions.add(permission);
        } else {
            permissions.remove(permission);
        }

        IslandMembershipService.PermissionOutcome outcome = host.mutationLock.inside(island.id(), () -> {
            Optional<Island> optFresh = host.islandStoragePort.findIslandById(island.id());
            Optional<IslandLocation> optLocation = host.islandStoragePort.findLocationByIslandId(island.id());
            if (optFresh.isEmpty() || optLocation.isEmpty()) {
                return new IslandMembershipService.PermissionOutcome.NoIsland();
            }
            IslandRole updated =
                    new IslandRole(role.id(), role.weight(), role.displayName(), permissions, role.isSystem());
            host.islandStoragePort.saveIsland(
                    IslandMembershipService.withRole(optFresh.get(), updated), optLocation.get());
            return new IslandMembershipService.PermissionOutcome.Changed(
                    roleId.toLowerCase(Locale.ROOT), permission.name().toLowerCase(Locale.ROOT), allowed);
        });
        if (outcome instanceof IslandMembershipService.PermissionOutcome.Changed) {
            host.tellRolesChanged(island.id());
        }
        return outcome;
    }
}
