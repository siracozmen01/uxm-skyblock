package com.uxplima.uxmskyblock.bukkit.config;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Block stacking, {@code modules/stacking.conf}: which blocks stack when one is placed on another of its kind, and
 * how high.
 *
 * @param blocks the blocks that stack
 * @param most the most blocks one stack stands for
 */
public record StackingConfiguration(boolean enabled, Set<Material> blocks, int most) {

    public StackingConfiguration {
        Objects.requireNonNull(blocks, "blocks must not be null");
        blocks = blocks.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(blocks));
        most = Math.max(2, most);
    }

    /** Whether {@code block} stacks. */
    public boolean stacks(Material block) {
        return enabled && blocks.contains(block);
    }

    public static StackingConfiguration defaultConfiguration() {
        return new StackingConfiguration(
                true,
                EnumSet.of(
                        Material.IRON_BLOCK,
                        Material.GOLD_BLOCK,
                        Material.DIAMOND_BLOCK,
                        Material.EMERALD_BLOCK,
                        Material.NETHERITE_BLOCK,
                        Material.LAPIS_BLOCK,
                        Material.REDSTONE_BLOCK,
                        Material.COAL_BLOCK,
                        Material.COPPER_BLOCK,
                        Material.AMETHYST_BLOCK),
                100_000);
    }

    public static StackingConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        StackingConfiguration shipped = defaultConfiguration();
        ConfigurationNode node = root.node("stacking");
        if (node.virtual()) {
            return shipped;
        }
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        ConfigurationNode written = node.node("blocks");
        if (written.virtual()) {
            blocks.addAll(shipped.blocks());
        } else {
            for (ConfigurationNode line : written.childrenList()) {
                Material block =
                        Material.matchMaterial(line.getString("").strip().toUpperCase(Locale.ROOT));
                if (block != null && block.isBlock()) {
                    blocks.add(block);
                }
            }
        }
        return new StackingConfiguration(
                node.node("enabled").getBoolean(shipped.enabled()),
                blocks,
                node.node("most").getInt(shipped.most()));
    }
}
