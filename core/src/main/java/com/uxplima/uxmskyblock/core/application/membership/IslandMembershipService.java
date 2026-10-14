package com.uxplima.uxmskyblock.core.application.membership;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * Who belongs to an island.
 *
 * <p>This is a skyblock and a team is the point of one, and there was no way to make one. The
 * domain has carried {@link Island#addMember} and {@link Island#removeMember} since it was written,
 * the roles carry MEMBER_INVITE and MEMBER_KICK, the MEMBERS upgrade raises a cap, and nothing
 * called any of it: every island on every server was a solo island and the whole team surface was
 * types with nothing driving them.
 *
 * <p>An invite lives in memory and expires. It does not survive a restart, which is the honest
 * shape for something a player answers within a minute, and it means no table and no migration
 * stand between a server and a working team.
 *
 * <p>Every permission question here asks the permission rather than naming a role, so an island
 * with three roles and one with nine behave the same.
 */
public final class IslandMembershipService {

    /** Asking a player onto an island and their answer: the invite held until it runs out, and the join under the island's member cap. */
    private final MembershipInvites invitesFlow = new MembershipInvites(this);

    /** The roles of an island's members: one member's role changed, and what a role may do, each under the rules of who may change whom. */
    private final MembershipRoles roles = new MembershipRoles(this);

    /** How long an unanswered invite stands. */
    public static final Duration DEFAULT_INVITE_TIMEOUT = Duration.ofMinutes(5);

    /** An invitation waiting for an answer. */
    public record PendingInvite(IslandId islandId, ProfileId inviter, ProfileId target, Instant expiresAt) {

        public PendingInvite {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(inviter, "inviter must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    /** What a request to invite somebody came back with. */
    public sealed interface InviteOutcome {

        /** The invite stands until {@code expiresAt}. */
        record Sent(ProfileId target, Instant expiresAt) implements InviteOutcome {}

        /** The caller's role on this island does not carry MEMBER_INVITE. */
        record NotAllowed() implements InviteOutcome {}

        /** The caller has no island to invite anybody to. */
        record NoIsland() implements InviteOutcome {}

        /** The target already belongs to this island. */
        record AlreadyAMember() implements InviteOutcome {}

        /** The target belongs to some other island and would have to leave it first. */
        record AlreadyOnAnotherIsland() implements InviteOutcome {}

        /** The island holds as many members as its MEMBERS upgrade allows. */
        record IslandFull(int allowed) implements InviteOutcome {}
    }

    /** What a request to join came back with. */
    public sealed interface JoinOutcome {

        /** The caller is now a member of {@code islandId}. */
        record Joined(IslandId islandId) implements JoinOutcome {}

        /** Nobody has invited the caller, or the invite has expired. */
        record NoInvite() implements JoinOutcome {}

        /** The caller already belongs to an island. */
        record AlreadyOnAnIsland() implements JoinOutcome {}

        /** The island was erased between the invite and the answer. */
        record IslandMissing() implements JoinOutcome {}

        /** The island filled up between the invite and the answer. */
        record IslandFull(int allowed) implements JoinOutcome {}
    }

    /** What a request to remove somebody came back with. */
    public sealed interface RemovalOutcome {

        /** {@code target} no longer belongs to the island. */
        record Removed(IslandId islandId, ProfileId target) implements RemovalOutcome {}

        /** The caller's role does not carry MEMBER_KICK. */
        record NotAllowed() implements RemovalOutcome {}

        /** The caller has no island. */
        record NoIsland() implements RemovalOutcome {}

        /** Nobody by that name belongs to this island. */
        record NotAMember() implements RemovalOutcome {}

        /** The owner cannot be removed from their own island, by anybody including themselves. */
        record CannotRemoveOwner() implements RemovalOutcome {}

        /** The target's role is not below the caller's, so the caller may not remove them. */
        record OutOfReach() implements RemovalOutcome {}
    }

    /** What a request to change somebody's role came back with. */
    public sealed interface RoleOutcome {

        /** {@code target} now holds {@code roleId}. */
        record Changed(ProfileId target, String roleId) implements RoleOutcome {}

        /** The caller's role does not carry MEMBER_PROMOTE, or MEMBER_DEMOTE, whichever this is. */
        record NotAllowed() implements RoleOutcome {}

        /** The caller has no island. */
        record NoIsland() implements RoleOutcome {}

        /** Nobody by that name belongs to this island. */
        record NotAMember() implements RoleOutcome {}

        /** The island has no role by that name. {@code available} lists the ones it has. */
        record UnknownRole(String roleId, String available) implements RoleOutcome {}

        /** The owner's own role is not something a role command moves. */
        record CannotChangeOwner() implements RoleOutcome {}

        /** An anchor role, owner or visitor, is no role to give a member. {@code available} are. */
        record NotAssignable(String roleId, String available) implements RoleOutcome {}

        /** The target, or the role, is not below the caller's own role. */
        record OutOfReach() implements RoleOutcome {}
    }

    final IslandStoragePort islandStoragePort;
    final IslandMutationLock mutationLock;
    final ToIntFunction<IslandId> memberAllowance;
    final Duration inviteTimeout;
    final Clock clock;

    /** Invitations waiting for an answer, keyed by who has to answer. */
    final Map<ProfileId, PendingInvite> invites = new ConcurrentHashMap<>();

    private volatile KeyedMutationLock<ProfileId> profileLock = new KeyedMutationLock<>();

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandMembershipService.class.getName());

    /** Who hears that an island's members changed, after the change is written. */
    private final List<java.util.function.Consumer<IslandId>> membersChanged =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Who hears that an island's roles, or who holds which, changed, after the change is written. */
    private final List<java.util.function.Consumer<IslandId>> rolesChanged =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public IslandMembershipService(
            IslandStoragePort islandStoragePort,
            IslandMutationLock mutationLock,
            ToIntFunction<IslandId> memberAllowance,
            Duration inviteTimeout,
            Clock clock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock must not be null");
        this.memberAllowance = Objects.requireNonNull(memberAllowance, "memberAllowance must not be null");
        this.inviteTimeout = Objects.requireNonNull(inviteTimeout, "inviteTimeout must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IslandMembershipService(
            IslandStoragePort islandStoragePort,
            IslandMutationLock mutationLock,
            ToIntFunction<IslandId> memberAllowance) {
        this(islandStoragePort, mutationLock, memberAllowance, DEFAULT_INVITE_TIMEOUT, Clock.systemUTC());
    }

    /**
     * The lock island creation holds per profile, so joining and creating wait for each other.
     *
     * <p>Each held its own. A player who created an island and accepted an invite at the same moment
     * passed both checks that they had no island, and ended up owning one and belonging to another:
     * two islands for one profile, which everything that asks "whose island is this" assumes cannot
     * happen. The lock is taken before the island's, everywhere, so the two never wait on each other
     * the other way round.
     */
    public void shareProfileLock(KeyedMutationLock<ProfileId> lock) {
        this.profileLock = Objects.requireNonNull(lock, "lock must not be null");
    }

    /** Puts {@code target} on the island that invited them. */
    public JoinOutcome accept(ProfileId target, PlayerUuid targetPlayerUuid) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid must not be null");
        JoinOutcome outcome = profileLock.inside(target, () -> invitesFlow.acceptInside(target, targetPlayerUuid));
        if (outcome instanceof JoinOutcome.Joined joined) {
            tellMembersChanged(joined.islandId());
        }
        return outcome;
    }

    /**
     * Tells {@code listener} each time a player joins, leaves or is removed from an island, once the
     * change is written. It runs on the thread that made the change, outside every lock.
     */
    public void whenMembersChanged(java.util.function.Consumer<IslandId> listener) {
        membersChanged.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * Tells {@code listener} each time a member is given another role or a role is given or denied a
     * permission, once the change is written. It runs on the thread that made the change, outside
     * every lock, so a window that shows the roles can draw them again.
     */
    public void whenRolesChanged(java.util.function.Consumer<IslandId> listener) {
        rolesChanged.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    void tellRolesChanged(IslandId islandId) {
        tell(rolesChanged, islandId);
    }

    /** Tells everyone listening that the roles of {@code islandId} changed elsewhere, as an owner making one. */
    public void rolesChangedOn(IslandId islandId) {
        tellRolesChanged(Objects.requireNonNull(islandId, "islandId must not be null"));
    }

    private void tellMembersChanged(IslandId islandId) {
        tell(membersChanged, islandId);
    }

    private static void tell(List<java.util.function.Consumer<IslandId>> listeners, IslandId islandId) {
        for (java.util.function.Consumer<IslandId> listener : listeners) {
            try {
                listener.accept(islandId);
            } catch (RuntimeException e) {
                // The change is written; a listener that fails does not undo it or hide it from the others.
                LOGGER.log(java.util.logging.Level.WARNING, e, () -> "A listener to island " + islandId + " failed.");
            }
        }
    }

    /** Throws the invite away without joining. Returns whether there was one to throw away. */
    public boolean decline(ProfileId target) {
        Objects.requireNonNull(target, "target must not be null");
        return invitesFlow
                .pendingInvite(target)
                .map(invite -> invites.remove(target, invite))
                .orElse(false);
    }

    /** Removes {@code target} from the island {@code actor} belongs to. */
    public RemovalOutcome kick(ProfileId actor, ProfileId target) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(target, "target must not be null");

        Optional<Island> optIsland = islandOf(actor);
        if (optIsland.isEmpty()) {
            return new RemovalOutcome.NoIsland();
        }
        Island island = optIsland.get();
        if (!may(island, actor, IslandPermission.MEMBER_KICK)) {
            return new RemovalOutcome.NotAllowed();
        }
        IslandMember removing = island.members().get(target);
        if (removing != null
                && !island.ownerProfileId().equals(target)
                && !outranks(island, actor, removing.role().weight())) {
            return new RemovalOutcome.OutOfReach();
        }
        return told(remove(island, target));
    }

    private RemovalOutcome told(RemovalOutcome outcome) {
        if (outcome instanceof RemovalOutcome.Removed removed) {
            tellMembersChanged(removed.islandId());
        }
        return outcome;
    }

    /**
     * Takes {@code profileId} off their own island.
     *
     * <p>No permission is asked, because leaving is not something a role grants. The owner is still
     * refused: an island with no owner has nobody who may delete it, and leaving is not the verb for
     * getting rid of one.
     */
    public RemovalOutcome leave(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        Optional<Island> optIsland = islandOf(profileId);
        if (optIsland.isEmpty()) {
            return new RemovalOutcome.NoIsland();
        }
        return told(remove(optIsland.get(), profileId));
    }

    /** What a request to move a permission on a role came back with. */
    public sealed interface PermissionOutcome {

        /** The role now does, or does not, carry the permission. */
        record Changed(String roleId, String permission, boolean allowed) implements PermissionOutcome {}

        /** The caller's role does not carry MEMBER_PROMOTE, which is what editing a role needs. */
        record NotAllowed() implements PermissionOutcome {}

        /** The caller has no island. */
        record NoIsland() implements PermissionOutcome {}

        /** The island has no role by that name. {@code available} lists the ones it has. */
        record UnknownRole(String roleId, String available) implements PermissionOutcome {}

        /** No permission goes by that name. {@code available} lists the ones that do. */
        record UnknownPermission(String permission, String available) implements PermissionOutcome {}

        /** The owner's role is not something a permission command moves. */
        record CannotChangeOwnerRole() implements PermissionOutcome {}

        /** The role is not below the caller's own, so the caller may not edit it. */
        record OutOfReach() implements PermissionOutcome {}
    }

    /** Every permission a caller may name, lower case and comma separated. */
    public static String permissionNames() {
        return java.util.Arrays.stream(IslandPermission.values())
                .map(permission -> permission.name().toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * The island with one role replaced, and every member who holds it moved onto the new one.
     *
     * <p>A member carries their role rather than pointing at it, so changing what a role may do
     * without walking the members leaves everybody on the old permissions until they next log in
     * and the island is read fresh. That is a rule that applies at a time nobody can predict.
     */
    static Island withRole(Island island, IslandRole role) {
        Island updated = island.withRole(role);
        for (IslandMember member : island.members().values()) {
            if (member.role().id().equals(role.id())) {
                updated = updated.addMember(
                        new IslandMember(member.playerUuid(), member.profileId(), role, member.joinedAt()));
            }
        }
        return updated;
    }

    /** Everybody on the island, the owner first and then by the day they joined. */
    public List<IslandMember> members(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort
                .findIslandById(islandId)
                .map(island -> {
                    List<IslandMember> ordered =
                            new ArrayList<>(island.members().values());
                    ordered.sort(Comparator.comparingInt((IslandMember member) ->
                                    island.ownerProfileId().equals(member.profileId()) ? 0 : 1)
                            .thenComparing(IslandMember::joinedAt));
                    return ordered;
                })
                .orElseGet(List::of);
    }

    /**
     * The roles a member may be given on the island the caller belongs to, or nothing when they have
     * no island.
     */
    public Optional<String> assignableRoles(ProfileId actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        return islandOf(actor).map(IslandMembershipService::assignableRoleNames);
    }

    /** Every role on the island but the anchors, owner and visitor, which no member is given. */
    public static String assignableRoleNames(Island island) {
        Objects.requireNonNull(island, "island must not be null");
        return island.roles().values().stream()
                .filter(role -> !role.isSystem())
                .map(role -> role.id().toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /** The role names this island has, lower case and comma separated, for telling a caller. */
    public static String roleNames(Island island) {
        Objects.requireNonNull(island, "island must not be null");
        return island.roles().keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private RemovalOutcome remove(Island island, ProfileId target) {
        if (island.ownerProfileId().equals(target)) {
            return new RemovalOutcome.CannotRemoveOwner();
        }
        if (!island.isMember(target)) {
            return new RemovalOutcome.NotAMember();
        }
        return mutationLock.inside(island.id(), () -> {
            Optional<Island> optFresh = islandStoragePort.findIslandById(island.id());
            Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(island.id());
            if (optFresh.isEmpty() || optLocation.isEmpty()) {
                return new RemovalOutcome.NoIsland();
            }
            Island fresh = optFresh.get();
            if (!fresh.isMember(target)) {
                return new RemovalOutcome.NotAMember();
            }
            islandStoragePort.saveIsland(fresh.removeMember(target), optLocation.get());
            invites.remove(target);
            return new RemovalOutcome.Removed(island.id(), target);
        });
    }

    Optional<Island> islandOf(ProfileId profileId) {
        return islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandStoragePort::findIslandById);
    }

    /** Whether {@code actor} stands above {@code weight}: the owner above all, anybody else strictly. */
    static boolean outranks(Island island, ProfileId actor, int weight) {
        if (island.ownerProfileId().equals(actor)) {
            return true;
        }
        IslandMember member = island.members().get(actor);
        return member != null && member.role().weight() > weight;
    }

    static boolean may(Island island, ProfileId profileId, IslandPermission permission) {
        if (island.ownerProfileId().equals(profileId)) {
            return true;
        }
        IslandMember member = island.members().get(profileId);
        return member != null && member.role().permissions().contains(permission);
    }

    /** Puts {@code target} into {@code rawRoleId}, if the caller's role may. */
    public RoleOutcome setRole(ProfileId actor, ProfileId target, String rawRoleId) {
        return roles.setRole(actor, target, rawRoleId);
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
    public PermissionOutcome setRolePermission(
            ProfileId actor, String rawRoleId, String rawPermission, boolean allowed) {
        return roles.setRolePermission(actor, rawRoleId, rawPermission, allowed);
    }

    /** Invites {@code target} to the island {@code actor} belongs to. */
    public InviteOutcome invite(ProfileId actor, ProfileId target) {
        return invitesFlow.invite(actor, target);
    }

    /** The invite {@code target} has been asked to answer, if one still stands. */
    public Optional<PendingInvite> pendingInvite(ProfileId target) {
        return invitesFlow.pendingInvite(target);
    }
}
