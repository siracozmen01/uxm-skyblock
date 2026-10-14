package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration holder for island upgrades and progressive tier specifications.
 */
public record UpgradesConfiguration(boolean enabled, Map<UpgradeId, UpgradeDefinition> definitions) {

    public static final boolean DEFAULT_ENABLED = true;

    public UpgradesConfiguration {
        definitions = (definitions == null) ? Map.of() : Map.copyOf(definitions);
    }

    public Optional<UpgradeDefinition> getDefinition(UpgradeId id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public static UpgradesConfiguration defaultConfiguration() {
        Map<UpgradeId, UpgradeDefinition> defs = new LinkedHashMap<>();

        // 1. Island Size Upgrade
        UpgradeId sizeId = UpgradeId.SIZE;
        defs.put(
                sizeId,
                new UpgradeDefinition(
                        sizeId,
                        "@upgrades.names." + sizeId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 0L, "PRIMARY", Map.of("radius", 25.0, "size", 50.0)),
                                new UpgradeTier(2, 50_000L, "PRIMARY", Map.of("radius", 37.5, "size", 75.0)),
                                new UpgradeTier(3, 150_000L, "PRIMARY", Map.of("radius", 50.0, "size", 100.0)),
                                new UpgradeTier(4, 500_000L, "PRIMARY", Map.of("radius", 75.0, "size", 150.0)),
                                new UpgradeTier(5, 1_000_000L, "PRIMARY", Map.of("radius", 100.0, "size", 200.0)))));

        // 2. Member Limit Upgrade
        UpgradeId memberId = UpgradeId.MEMBERS;
        defs.put(
                memberId,
                new UpgradeDefinition(
                        memberId,
                        "@upgrades.names." + memberId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 0L, "PRIMARY", Map.of("max_members", 4.0)),
                                new UpgradeTier(2, 50_000L, "PRIMARY", Map.of("max_members", 8.0)),
                                new UpgradeTier(3, 100_000L, "PRIMARY", Map.of("max_members", 12.0)),
                                new UpgradeTier(4, 200_000L, "PRIMARY", Map.of("max_members", 16.0)))));

        // 3. Ore Generator Upgrade
        UpgradeId oreId = UpgradeId.ORE_GENERATOR;
        defs.put(
                oreId,
                new UpgradeDefinition(
                        oreId,
                        "@upgrades.names." + oreId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 25_000L, "PRIMARY", Map.of("tier", 1.0)),
                                new UpgradeTier(2, 75_000L, "PRIMARY", Map.of("tier", 2.0)),
                                new UpgradeTier(3, 250_000L, "PRIMARY", Map.of("tier", 3.0)),
                                new UpgradeTier(4, 1_000_000L, "PRIMARY", Map.of("tier", 4.0)))));

        // 4. Crop Growth Upgrade
        UpgradeId cropId = UpgradeId.CROP_GROWTH;
        defs.put(
                cropId,
                new UpgradeDefinition(
                        cropId,
                        "@upgrades.names." + cropId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 20_000L, "PRIMARY", Map.of("rate_multiplier", 1.10)),
                                new UpgradeTier(2, 60_000L, "PRIMARY", Map.of("rate_multiplier", 1.25)),
                                new UpgradeTier(3, 150_000L, "PRIMARY", Map.of("rate_multiplier", 1.50)))));

        // 5. Warp Slots Upgrade, read by IslandWarpService.getMaxAllowedWarps
        UpgradeId warpId = UpgradeId.WARPS;
        defs.put(
                warpId,
                new UpgradeDefinition(
                        warpId,
                        "@upgrades.names." + warpId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 25_000L, "PRIMARY", Map.of("limit", 4.0)),
                                new UpgradeTier(2, 100_000L, "PRIMARY", Map.of("limit", 6.0)),
                                new UpgradeTier(3, 300_000L, "PRIMARY", Map.of("limit", 10.0)))));

        // 6. Vault Pages Upgrade, read by IslandVaultService.getMaxAllowedPages
        UpgradeId vaultId = UpgradeId.VAULT_PAGES;
        defs.put(
                vaultId,
                new UpgradeDefinition(
                        vaultId,
                        "@upgrades.names." + vaultId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 40_000L, "PRIMARY", Map.of("limit", 2.0)),
                                new UpgradeTier(2, 120_000L, "PRIMARY", Map.of("limit", 4.0)),
                                new UpgradeTier(3, 400_000L, "PRIMARY", Map.of("limit", 6.0)))));

        // 7. Spawner Rates Upgrade
        UpgradeId spawnerId = UpgradeId.SPAWNER_RATES;
        defs.put(
                spawnerId,
                new UpgradeDefinition(
                        spawnerId,
                        "@upgrades.names." + spawnerId.key().toLowerCase(java.util.Locale.ROOT),
                        List.of(
                                new UpgradeTier(1, 30_000L, "PRIMARY", Map.of("rate_multiplier", 1.10)),
                                new UpgradeTier(2, 90_000L, "PRIMARY", Map.of("rate_multiplier", 1.25)),
                                new UpgradeTier(3, 250_000L, "PRIMARY", Map.of("rate_multiplier", 1.50)))));

        // 8. The island's potion effects, each property an effect and its level.
        UpgradeId effectsId = UpgradeId.of("island_effects");
        defs.put(
                effectsId,
                new UpgradeDefinition(
                        effectsId,
                        "@upgrades.names.island_effects",
                        List.of(
                                new UpgradeTier(1, 50_000L, "PRIMARY", Map.of("haste", 1.0)),
                                new UpgradeTier(2, 150_000L, "PRIMARY", Map.of("haste", 1.0, "speed", 1.0)),
                                new UpgradeTier(
                                        3,
                                        400_000L,
                                        "PRIMARY",
                                        Map.of("haste", 2.0, "speed", 1.0, "jump_boost", 1.0)))));

        // 9. The limit upgrades: each tier raises every quota of the limits file that names it.
        Map<String, long[]> limitCosts = new LinkedHashMap<>();
        limitCosts.put("hopper_limit", new long[] {25_000L, 75_000L, 200_000L});
        limitCosts.put("piston_limit", new long[] {25_000L, 75_000L, 200_000L});
        limitCosts.put("redstone_limit", new long[] {30_000L, 90_000L, 250_000L});
        limitCosts.put("brewing_limit", new long[] {20_000L, 60_000L, 180_000L});
        limitCosts.put("spawner_limit", new long[] {50_000L, 150_000L, 400_000L});
        limitCosts.put("villager_limit", new long[] {40_000L, 120_000L, 300_000L});
        limitCosts.put("entity_limit", new long[] {30_000L, 90_000L, 250_000L});
        limitCosts.forEach((key, costs) -> {
            List<UpgradeTier> tiers = new java.util.ArrayList<>();
            for (int i = 0; i < costs.length; i++) {
                tiers.add(new UpgradeTier(i + 1, costs[i], "PRIMARY", Map.of()));
            }
            UpgradeId id = UpgradeId.of(key);
            defs.put(id, new UpgradeDefinition(id, "@upgrades.names." + key, tiers));
        });

        return new UpgradesConfiguration(DEFAULT_ENABLED, defs);
    }

    /**
     * The role permission an upgrade's own entry asks for, or null when it names none.
     *
     * <p>Every purchase spends the island bank and asks for the withdraw permission on that
     * account. An upgrade may ask for one more, and the file is where the operator says which:
     * the spawner rates upgrade ships asking for the spawner upgrade permission, which is the only
     * thing that permission has ever had to gate.
     *
     * <p>A word the role editor does not know is ignored rather than fatal. An upgrade nobody can
     * buy because its file has a typo is worse than one that asks for the money alone.
     */
    private static @Nullable IslandPermission permissionNamedBy(ConfigurationNode node) {
        String raw = node.node("required-permission").getString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return IslandPermission.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    public static UpgradesConfiguration load(ConfigurationNode root) {
        return fromNode(root);
    }

    public static UpgradesConfiguration fromNode(ConfigurationNode root) {
        Objects.requireNonNull(root, "root configuration node must not be null");

        boolean enabled = root.node("enabled").getBoolean(DEFAULT_ENABLED);
        ConfigurationNode upgradesNode = root.node("upgrades");
        if (upgradesNode.virtual() || !upgradesNode.isMap()) {
            return defaultConfiguration();
        }

        Map<UpgradeId, UpgradeDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                upgradesNode.childrenMap().entrySet()) {
            String upgradeKey = String.valueOf(entry.getKey());
            ConfigurationNode node = entry.getValue();

            String displayName = node.node("display-name").getString(upgradeKey);
            ConfigurationNode tiersNode = node.node("tiers");
            List<UpgradeTier> tiers = new ArrayList<>();

            if (!tiersNode.virtual() && tiersNode.isList()) {
                int tierIndex = 1;
                for (ConfigurationNode tierNode : tiersNode.childrenList()) {
                    long cost = tierNode.node("cost").getLong(0L);
                    String currency = tierNode.node("currency").getString("PRIMARY");
                    Map<String, Double> props = new LinkedHashMap<>();

                    ConfigurationNode propsNode = tierNode.node("properties");
                    if (!propsNode.virtual() && propsNode.isMap()) {
                        for (Map.Entry<Object, ? extends ConfigurationNode> propEntry :
                                propsNode.childrenMap().entrySet()) {
                            props.put(
                                    String.valueOf(propEntry.getKey()),
                                    propEntry.getValue().getDouble(0.0));
                        }
                    }

                    tiers.add(new UpgradeTier(tierIndex++, cost, currency, props));
                }
            }

            UpgradeId id = UpgradeId.of(upgradeKey);
            definitions.put(id, new UpgradeDefinition(id, displayName, tiers, permissionNamedBy(node)));
        }

        return new UpgradesConfiguration(enabled, definitions);
    }
}
