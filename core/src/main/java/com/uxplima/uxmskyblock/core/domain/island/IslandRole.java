package com.uxplima.uxmskyblock.core.domain.island;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Strongly typed dynamic Island Role.
 *
 * @param id unique role identifier (e.g. "OWNER", "MEMBER")
 * @param weight seniority weight (higher weight dominates lower weight)
 * @param displayName display title
 * @param permissions immutable set of granted permissions
 * @param isSystem whether this role is a protected anchor role (e.g. OWNER, VISITOR)
 */
public record IslandRole(String id, int weight, String displayName, Set<IslandPermission> permissions, boolean isSystem)
        implements Comparable<IslandRole> {

    public IslandRole {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");
        permissions = permissions.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    public static final IslandRole OWNER =
            new IslandRole("OWNER", 1000, "Owner", IslandPermission.ALL_PERMISSIONS, true);

    public static final IslandRole CO_OWNER = new IslandRole(
            "CO_OWNER",
            800,
            "Co-Owner",
            EnumSet.of(
                    IslandPermission.BLOCK_BREAK,
                    IslandPermission.BLOCK_PLACE,
                    IslandPermission.BUCKET_USE,
                    IslandPermission.NATURAL_INTERACT,
                    IslandPermission.REDSTONE_INTERACT,
                    IslandPermission.CHEST_OPEN,
                    IslandPermission.FURNACE_USE,
                    IslandPermission.SHULKER_OPEN,
                    IslandPermission.BARREL_OPEN,
                    IslandPermission.ANVIL_USE,
                    IslandPermission.BEACON_MODIFY,
                    IslandPermission.SPAWNER_BREAK,
                    IslandPermission.SPAWNER_CHANGE_TYPE,
                    IslandPermission.SPAWNER_UPGRADE,
                    IslandPermission.CROP_TRAMPLE_BYPASS,
                    IslandPermission.ANIMAL_BREED,
                    IslandPermission.ANIMAL_KILL,
                    IslandPermission.BANK_DEPOSIT,
                    IslandPermission.BANK_WITHDRAW,
                    IslandPermission.SHOP_ACCESS,
                    IslandPermission.MEMBER_INVITE,
                    IslandPermission.MEMBER_KICK,
                    IslandPermission.SETTINGS_MODIFY,
                    IslandPermission.BIOME_CHANGE,
                    IslandPermission.BOX_MOVE,
                    IslandPermission.WARP_CREATE,
                    IslandPermission.WARP_DELETE,
                    IslandPermission.VAULT_VIEW,
                    IslandPermission.VAULT_DEPOSIT,
                    IslandPermission.VAULT_WITHDRAW,
                    IslandPermission.CHAT_VIEW,
                    IslandPermission.ISLAND_FLY,
                    IslandPermission.CHAT_SEND),
            false);

    public static final IslandRole MODERATOR = new IslandRole(
            "MODERATOR",
            600,
            "Moderator",
            EnumSet.of(
                    IslandPermission.BLOCK_BREAK,
                    IslandPermission.BLOCK_PLACE,
                    IslandPermission.BUCKET_USE,
                    IslandPermission.NATURAL_INTERACT,
                    IslandPermission.REDSTONE_INTERACT,
                    IslandPermission.CHEST_OPEN,
                    IslandPermission.FURNACE_USE,
                    IslandPermission.SHULKER_OPEN,
                    IslandPermission.BARREL_OPEN,
                    IslandPermission.ANIMAL_BREED,
                    IslandPermission.ANIMAL_KILL,
                    IslandPermission.BANK_DEPOSIT,
                    IslandPermission.MEMBER_INVITE,
                    IslandPermission.MEMBER_KICK,
                    IslandPermission.VAULT_VIEW,
                    IslandPermission.VAULT_DEPOSIT,
                    IslandPermission.VAULT_WITHDRAW,
                    IslandPermission.CHAT_VIEW,
                    IslandPermission.ISLAND_FLY,
                    IslandPermission.CHAT_SEND),
            false);

    public static final IslandRole MEMBER = new IslandRole(
            "MEMBER",
            400,
            "Member",
            EnumSet.of(
                    IslandPermission.BLOCK_BREAK,
                    IslandPermission.BLOCK_PLACE,
                    IslandPermission.BUCKET_USE,
                    IslandPermission.NATURAL_INTERACT,
                    IslandPermission.REDSTONE_INTERACT,
                    IslandPermission.CHEST_OPEN,
                    IslandPermission.FURNACE_USE,
                    IslandPermission.SHULKER_OPEN,
                    IslandPermission.BARREL_OPEN,
                    IslandPermission.ANIMAL_BREED,
                    IslandPermission.ANIMAL_KILL,
                    IslandPermission.BANK_DEPOSIT,
                    IslandPermission.VAULT_VIEW,
                    IslandPermission.VAULT_DEPOSIT,
                    IslandPermission.CHAT_VIEW,
                    IslandPermission.ISLAND_FLY,
                    IslandPermission.CHAT_SEND),
            false);

    public static final IslandRole VISITOR = new IslandRole("VISITOR", 0, "Visitor", Collections.emptySet(), true);

    public static Optional<IslandRole> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return switch (id.trim().toUpperCase(Locale.ROOT)) {
            case "OWNER" -> Optional.of(OWNER);
            case "CO_OWNER", "COOWNER", "CO-OWNER" -> Optional.of(CO_OWNER);
            case "MODERATOR", "MOD" -> Optional.of(MODERATOR);
            case "MEMBER" -> Optional.of(MEMBER);
            case "VISITOR" -> Optional.of(VISITOR);
            default -> Optional.empty();
        };
    }

    public boolean hasPermission(IslandPermission permission) {
        return permissions.contains(permission);
    }

    public boolean canManage(IslandRole target) {
        return this.weight > target.weight();
    }

    @Override
    public int compareTo(IslandRole other) {
        return Integer.compare(this.weight, other.weight);
    }
}
