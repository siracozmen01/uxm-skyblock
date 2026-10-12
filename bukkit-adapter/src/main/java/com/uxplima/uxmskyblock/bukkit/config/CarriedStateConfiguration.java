package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * What a player carries in each kind of world, as {@code modules/player-state.conf} says.
 *
 * <p>A server that plays several game modes kept one inventory, one level and one set of advancements for all of
 * them, so a Boxed island grew nothing for a player who had earned its advancements elsewhere, and a stack of
 * diamonds walked from a classic island onto a fresh one. Worlds now fall into groups, and a player crossing from
 * one group to another carries what they had in the group they came to.
 *
 * @param enabled whether anything is kept apart at all
 * @param groups the groups by name, each with its worlds; empty means a group for each world a preset names
 * @param keeps what a group keeps apart
 */
public record CarriedStateConfiguration(boolean enabled, Map<String, List<String>> groups, Keeps keeps) {

    private static final Logger LOGGER = Logger.getLogger(CarriedStateConfiguration.class.getName());

    /**
     * What a group keeps for a player, each on its own switch.
     *
     * @param advancements every advancement but the recipes, which unlock the same recipes everywhere
     */
    public record Keeps(
            boolean inventory,
            boolean enderChest,
            boolean experience,
            boolean health,
            boolean hunger,
            boolean effects,
            boolean advancements) {

        public static final Keeps ALL = new Keeps(true, true, true, true, true, true, true);

        /** The same parts, as a crossing keeps them apart. */
        public com.uxplima.uxmskyblock.core.domain.inventory.CarriedParts parts() {
            return new com.uxplima.uxmskyblock.core.domain.inventory.CarriedParts(
                    inventory, enderChest, experience, health, hunger, effects, advancements);
        }
    }

    public static final CarriedStateConfiguration SHIPPED = new CarriedStateConfiguration(true, Map.of(), Keeps.ALL);

    public CarriedStateConfiguration {
        Objects.requireNonNull(groups, "groups must not be null");
        Objects.requireNonNull(keeps, "keeps must not be null");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        groups.forEach((name, worlds) -> copy.put(name, List.copyOf(worlds)));
        groups = java.util.Collections.unmodifiableMap(copy);
    }

    public static CarriedStateConfiguration defaultConfiguration() {
        return SHIPPED;
    }

    /**
     * The group {@code world} belongs to, or nothing for a world no group names, where a player keeps what they
     * brought.
     *
     * @param presetWorlds the worlds the presets make islands in, each its own group when the file names none
     */
    public Optional<String> groupOf(String world, List<String> presetWorlds) {
        if (groups.isEmpty()) {
            return presetWorlds.contains(world) ? Optional.of(world) : Optional.empty();
        }
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getValue().contains(world)) {
                return Optional.of(group.getKey());
            }
        }
        return Optional.empty();
    }

    public static CarriedStateConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        Map<String, List<String>> groups = new LinkedHashMap<>();
        // Sorted by name: HOCON keeps no order, and a world two groups name must land in the same one each start.
        root.node("groups").childrenMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(String::valueOf)))
                .forEach(entry -> {
                    String name = String.valueOf(entry.getKey());
                    List<String> worlds = new ArrayList<>();
                    try {
                        for (String world : entry.getValue().getList(String.class, List.of())) {
                            if (world.isBlank()) {
                                continue;
                            }
                            if (groups.values().stream().anyMatch(named -> named.contains(world))) {
                                LOGGER.warning(() -> "modules/player-state.conf names the world " + world
                                        + " in more than one group; it stays in the first.");
                                continue;
                            }
                            worlds.add(world);
                        }
                    } catch (SerializationException e) {
                        LOGGER.warning(
                                () -> "modules/player-state.conf: the group " + name + " is not a list of worlds.");
                    }
                    if (!worlds.isEmpty()) {
                        groups.put(name, worlds);
                    }
                });
        ConfigurationNode keeps = root.node("keeps");
        Keeps all = Keeps.ALL;
        return new CarriedStateConfiguration(
                root.node("enabled").getBoolean(SHIPPED.enabled()),
                groups,
                new Keeps(
                        keeps.node("inventory").getBoolean(all.inventory()),
                        keeps.node("ender-chest").getBoolean(all.enderChest()),
                        keeps.node("experience").getBoolean(all.experience()),
                        keeps.node("health").getBoolean(all.health()),
                        keeps.node("hunger").getBoolean(all.hunger()),
                        keeps.node("effects").getBoolean(all.effects()),
                        keeps.node("advancements").getBoolean(all.advancements())));
    }
}
