package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/fly.conf}: whether a member may fly on their island with {@code /is fly}, and what else it asks.
 *
 * @param enabled whether the command flies anyone
 * @param requiredUpgrade an upgrade the island must hold a tier of before its members fly, or none
 * @param permission a permission node the player must hold as well, or empty for none
 * @param spareFall whether a player set down for leaving the island is spared the fall that follows
 */
public record IslandFlyConfiguration(
        boolean enabled, @Nullable UpgradeId requiredUpgrade, String permission, boolean spareFall) {

    public IslandFlyConfiguration {
        Objects.requireNonNull(permission, "permission must not be null");
    }

    public static IslandFlyConfiguration defaultConfiguration() {
        return new IslandFlyConfiguration(
                true, null, com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions.ISLAND_FLY.node(), true);
    }

    public static IslandFlyConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        ConfigurationNode node = root.node("fly");
        IslandFlyConfiguration shipped = defaultConfiguration();
        if (node.virtual()) {
            return shipped;
        }
        String upgrade = node.node("required-upgrade").getString("");
        return new IslandFlyConfiguration(
                node.node("enabled").getBoolean(shipped.enabled()),
                upgrade == null || upgrade.isBlank() ? null : UpgradeId.of(upgrade),
                Objects.requireNonNullElse(node.node("permission").getString(shipped.permission()), "")
                        .trim(),
                node.node("spare-fall").getBoolean(shipped.spareFall()));
    }
}
