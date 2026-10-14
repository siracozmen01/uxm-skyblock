package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * {@code modules/island-effects.conf}: which upgrades put potion effects on the players of an island.
 *
 * @param enabled whether any effect is put on anyone
 * @param everySeconds how often the effects are put on again; each lasts a little longer, so none flickers
 * @param visitors whether a visitor wears them too, or only the island's members
 * @param upgrades the upgrades whose tiers name effects, as a potion and its level
 */
public record IslandEffectsConfiguration(
        boolean enabled, int everySeconds, boolean visitors, List<UpgradeId> upgrades) {

    public IslandEffectsConfiguration {
        if (everySeconds < 1) {
            throw new IllegalArgumentException("everySeconds must be at least 1");
        }
        upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades must not be null"));
    }

    public static IslandEffectsConfiguration defaultConfiguration() {
        return new IslandEffectsConfiguration(true, 3, false, List.of(UpgradeId.of("island_effects")));
    }

    public static IslandEffectsConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        ConfigurationNode node = root.node("island-effects");
        IslandEffectsConfiguration shipped = defaultConfiguration();
        if (node.virtual()) {
            return shipped;
        }
        List<UpgradeId> upgrades = new ArrayList<>();
        try {
            List<String> written = node.node("upgrades").getList(String.class);
            if (written == null) {
                upgrades.addAll(shipped.upgrades());
            } else {
                written.stream().filter(id -> !id.isBlank()).map(UpgradeId::of).forEach(upgrades::add);
            }
        } catch (SerializationException unreadable) {
            upgrades.addAll(shipped.upgrades());
        }
        return new IslandEffectsConfiguration(
                node.node("enabled").getBoolean(shipped.enabled()),
                Math.max(1, node.node("every-seconds").getInt(shipped.everySeconds())),
                node.node("visitors").getBoolean(shipped.visitors()),
                upgrades);
    }
}
