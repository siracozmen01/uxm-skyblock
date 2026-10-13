package com.uxplima.uxmskyblock.core.domain.permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.jspecify.annotations.Nullable;

/**
 * Standard built-in domain permission keys and canonical mapping to legacy {@link IslandPermission}s.
 */
public final class StandardPermissions {

    private StandardPermissions() {}

    // World Interaction
    public static final PermissionKey BLOCK_BREAK = PermissionKey.uxm("block.break");
    public static final PermissionKey BLOCK_PLACE = PermissionKey.uxm("block.place");
    public static final PermissionKey BUCKET_USE = PermissionKey.uxm("bucket.use");
    public static final PermissionKey NATURAL_INTERACT = PermissionKey.uxm("interact.natural");
    public static final PermissionKey REDSTONE_INTERACT = PermissionKey.uxm("interact.redstone");

    // Containers
    public static final PermissionKey CHEST_OPEN = PermissionKey.uxm("container.chest.open");
    public static final PermissionKey FURNACE_USE = PermissionKey.uxm("container.furnace.use");
    public static final PermissionKey SHULKER_OPEN = PermissionKey.uxm("container.shulker.open");
    public static final PermissionKey BARREL_OPEN = PermissionKey.uxm("container.barrel.open");
    public static final PermissionKey ANVIL_USE = PermissionKey.uxm("container.anvil.use");
    public static final PermissionKey BEACON_MODIFY = PermissionKey.uxm("beacon.modify");

    // Spawner & Animals
    public static final PermissionKey SPAWNER_BREAK = PermissionKey.uxm("spawner.break");
    public static final PermissionKey SPAWNER_CHANGE_TYPE = PermissionKey.uxm("spawner.change_type");
    public static final PermissionKey SPAWNER_UPGRADE = PermissionKey.uxm("spawner.upgrade");
    public static final PermissionKey CROP_TRAMPLE_BYPASS = PermissionKey.uxm("crop.trample_bypass");
    public static final PermissionKey ANIMAL_BREED = PermissionKey.uxm("animal.breed");
    public static final PermissionKey ANIMAL_KILL = PermissionKey.uxm("animal.kill");

    // Economy
    public static final PermissionKey BANK_DEPOSIT = PermissionKey.uxm("bank.deposit");
    public static final PermissionKey BANK_WITHDRAW = PermissionKey.uxm("bank.withdraw");
    public static final PermissionKey SHOP_ACCESS = PermissionKey.uxm("shop.access");

    // Vault & Storage
    public static final PermissionKey VAULT_VIEW = PermissionKey.uxm("vault.view");
    public static final PermissionKey VAULT_DEPOSIT = PermissionKey.uxm("vault.deposit");
    public static final PermissionKey VAULT_WITHDRAW = PermissionKey.uxm("vault.withdraw");

    // Management
    public static final PermissionKey MEMBER_INVITE = PermissionKey.uxm("member.invite");
    public static final PermissionKey MEMBER_KICK = PermissionKey.uxm("member.kick");
    public static final PermissionKey MEMBER_PROMOTE = PermissionKey.uxm("member.promote");
    public static final PermissionKey MEMBER_DEMOTE = PermissionKey.uxm("member.demote");
    public static final PermissionKey SETTINGS_MODIFY = PermissionKey.uxm("settings.modify");
    public static final PermissionKey BIOME_CHANGE = PermissionKey.uxm("biome.change");
    public static final PermissionKey BOX_MOVE = PermissionKey.uxm("box.move");
    public static final PermissionKey WARP_CREATE = PermissionKey.uxm("warp.create");
    public static final PermissionKey WARP_DELETE = PermissionKey.uxm("warp.delete");

    // Communication
    public static final PermissionKey CHAT_VIEW = PermissionKey.uxm("chat.view");
    public static final PermissionKey CHAT_SEND = PermissionKey.uxm("chat.send");

    private static final Map<IslandPermission, PermissionKey> PERMISSION_MAP;

    static {
        Map<IslandPermission, PermissionKey> map = new LinkedHashMap<>();
        map.put(IslandPermission.BLOCK_BREAK, BLOCK_BREAK);
        map.put(IslandPermission.BLOCK_PLACE, BLOCK_PLACE);
        map.put(IslandPermission.BUCKET_USE, BUCKET_USE);
        map.put(IslandPermission.NATURAL_INTERACT, NATURAL_INTERACT);
        map.put(IslandPermission.REDSTONE_INTERACT, REDSTONE_INTERACT);
        map.put(IslandPermission.CHEST_OPEN, CHEST_OPEN);
        map.put(IslandPermission.FURNACE_USE, FURNACE_USE);
        map.put(IslandPermission.SHULKER_OPEN, SHULKER_OPEN);
        map.put(IslandPermission.BARREL_OPEN, BARREL_OPEN);
        map.put(IslandPermission.ANVIL_USE, ANVIL_USE);
        map.put(IslandPermission.BEACON_MODIFY, BEACON_MODIFY);
        map.put(IslandPermission.SPAWNER_BREAK, SPAWNER_BREAK);
        map.put(IslandPermission.SPAWNER_CHANGE_TYPE, SPAWNER_CHANGE_TYPE);
        map.put(IslandPermission.SPAWNER_UPGRADE, SPAWNER_UPGRADE);
        map.put(IslandPermission.CROP_TRAMPLE_BYPASS, CROP_TRAMPLE_BYPASS);
        map.put(IslandPermission.ANIMAL_BREED, ANIMAL_BREED);
        map.put(IslandPermission.ANIMAL_KILL, ANIMAL_KILL);
        map.put(IslandPermission.BANK_DEPOSIT, BANK_DEPOSIT);
        map.put(IslandPermission.BANK_WITHDRAW, BANK_WITHDRAW);
        map.put(IslandPermission.SHOP_ACCESS, SHOP_ACCESS);
        map.put(IslandPermission.VAULT_VIEW, VAULT_VIEW);
        map.put(IslandPermission.VAULT_DEPOSIT, VAULT_DEPOSIT);
        map.put(IslandPermission.VAULT_WITHDRAW, VAULT_WITHDRAW);
        map.put(IslandPermission.MEMBER_INVITE, MEMBER_INVITE);
        map.put(IslandPermission.MEMBER_KICK, MEMBER_KICK);
        map.put(IslandPermission.MEMBER_PROMOTE, MEMBER_PROMOTE);
        map.put(IslandPermission.MEMBER_DEMOTE, MEMBER_DEMOTE);
        map.put(IslandPermission.SETTINGS_MODIFY, SETTINGS_MODIFY);
        map.put(IslandPermission.BIOME_CHANGE, BIOME_CHANGE);
        map.put(IslandPermission.BOX_MOVE, BOX_MOVE);
        map.put(IslandPermission.WARP_CREATE, WARP_CREATE);
        map.put(IslandPermission.WARP_DELETE, WARP_DELETE);
        map.put(IslandPermission.CHAT_VIEW, CHAT_VIEW);
        map.put(IslandPermission.CHAT_SEND, CHAT_SEND);
        PERMISSION_MAP = Collections.unmodifiableMap(map);
    }

    public static @Nullable PermissionKey fromIslandPermission(IslandPermission permission) {
        return PERMISSION_MAP.get(permission);
    }

    public static Set<PermissionKey> allKeys() {
        return Set.copyOf(PERMISSION_MAP.values());
    }

    /**
     * Registers all standard domain permissions into the given registry.
     */
    public static void registerAll(PermissionRegistry registry) {
        for (PermissionKey key : PERMISSION_MAP.values()) {
            registry.register(key);
        }
    }
}
