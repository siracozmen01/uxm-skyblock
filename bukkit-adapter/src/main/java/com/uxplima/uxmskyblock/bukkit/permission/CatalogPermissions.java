package com.uxplima.uxmskyblock.bukkit.permission;

import java.util.Objects;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Programmatic catalog of all platform-level permissions for UxMSkyblock.
 * Every permission node is registered with Paper''s {@link PluginManager} during startup
 * for full autocomplete and LuckPerms compatibility.
 */
public enum CatalogPermissions {
    ADMIN_BYPASS("uxmskyblock.admin.bypass", "Bypass all island protection and boundaries", PermissionDefault.OP),
    ADMIN_RELOAD("uxmskyblock.admin.reload", "Reload plugin configuration", PermissionDefault.OP),
    ADMIN_DOCTOR(
            "uxmskyblock.admin.doctor",
            "Ask how the plugin is, one line per thing that can be wrong",
            PermissionDefault.OP),
    ADMIN_MANAGE("uxmskyblock.admin.manage", "Administrative island management", PermissionDefault.OP),
    ADMIN_FREEZE("uxmskyblock.admin.freeze", "Freeze and unfreeze islands administratively", PermissionDefault.OP),
    ADMIN_INSPECT("uxmskyblock.admin.inspect", "Inspect and bypass frozen island quarantines", PermissionDefault.OP),
    // Two nodes the commands have always asked for and the catalogue never published, so they
    // reached no permission plugin's list and had no declared default.
    ADMIN_RESTORE("uxmskyblock.admin.restore", "Back up and restore islands and the database", PermissionDefault.OP),
    ADMIN_BOOSTER("uxmskyblock.admin.booster", "Grant island boosters administratively", PermissionDefault.OP),
    ADMIN_SCHEMATIC("uxmskyblock.admin.schematic", "Save and paste schematics", PermissionDefault.OP),
    ADMIN_ONEBLOCK_SETPHASE(
            "uxmskyblock.admin.oneblock.setphase", "Move a OneBlock island to a phase", PermissionDefault.OP),
    ADMIN_ONEBLOCK_SETCOUNT(
            "uxmskyblock.admin.oneblock.setcount", "Set a OneBlock island's count of breaks", PermissionDefault.OP),
    ADMIN_ONEBLOCK_ADDCOUNT(
            "uxmskyblock.admin.oneblock.addcount",
            "Add to or take from a OneBlock island's count of breaks",
            PermissionDefault.OP),
    ADMIN_ONEBLOCK_CHECK(
            "uxmskyblock.admin.oneblock.check", "Read where a OneBlock island stands", PermissionDefault.OP),
    ADMIN_BOXED_SETSIZE(
            "uxmskyblock.admin.boxed.setsize", "Set how far across a Boxed island's box is", PermissionDefault.OP),
    ADMIN_BOXED_GROW("uxmskyblock.admin.boxed.grow", "Push a Boxed island's box out", PermissionDefault.OP),
    ADMIN_ONEBLOCK_RESPAWN(
            "uxmskyblock.admin.oneblock.respawn", "Lay a OneBlock island's block again", PermissionDefault.OP),

    ISLAND_CREATE("uxmskyblock.island.create", "Create a new skyblock island", PermissionDefault.TRUE),
    ISLAND_DELETE("uxmskyblock.island.delete", "Delete an island", PermissionDefault.TRUE),
    ISLAND_HOME("uxmskyblock.island.home", "Teleport to island home", PermissionDefault.TRUE),
    ISLAND_SET_HOME("uxmskyblock.island.sethome", "Set island home location", PermissionDefault.TRUE),
    ISLAND_INVITE("uxmskyblock.island.invite", "Invite members to island", PermissionDefault.TRUE),
    ISLAND_KICK("uxmskyblock.island.kick", "Kick members from island", PermissionDefault.TRUE),
    ISLAND_BAN("uxmskyblock.island.ban", "Ban players from island", PermissionDefault.TRUE),
    ISLAND_UNBAN("uxmskyblock.island.unban", "Unban players from island", PermissionDefault.TRUE),
    ISLAND_BANK("uxmskyblock.island.bank", "Access island bank", PermissionDefault.TRUE),
    ISLAND_BIOME("uxmskyblock.island.biome", "Change island biome", PermissionDefault.TRUE),
    ISLAND_UPGRADE("uxmskyblock.island.upgrade", "Upgrade island perks", PermissionDefault.TRUE),
    ISLAND_TOP("uxmskyblock.island.top", "View island leaderboards", PermissionDefault.TRUE),
    CHAT_SPY("uxmskyblock.chat.spy", "Spy on island private chat channels", PermissionDefault.OP);

    private final String node;
    private final String description;
    private final PermissionDefault defaultValue;

    CatalogPermissions(String node, String description, PermissionDefault defaultValue) {
        this.node = Objects.requireNonNull(node, "node");
        this.description = Objects.requireNonNull(description, "description");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
    }

    public String node() {
        return node;
    }

    public String description() {
        return description;
    }

    public PermissionDefault defaultValue() {
        return defaultValue;
    }

    public Permission toPermission() {
        return new Permission(node, description, defaultValue);
    }

    /**
     * Registers all catalog permissions with the Bukkit {@link PluginManager} idempotently.
     *
     * @param pm the plugin manager
     */
    public static void registerAll(PluginManager pm) {
        Objects.requireNonNull(pm, "pm must not be null");
        for (CatalogPermissions cp : values()) {
            if (pm.getPermission(cp.node()) == null) {
                pm.addPermission(cp.toPermission());
            }
        }
    }
}
