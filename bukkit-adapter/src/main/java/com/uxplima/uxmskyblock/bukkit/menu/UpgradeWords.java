package com.uxplima.uxmskyblock.bukkit.menu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.MoneyText;
import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;

/**
 * What each upgrade does on the tier an island stands on, what the next tier would do, and what it costs, in the
 * words of the reader, so an upgrade's tile says what is bought before it is bought.
 *
 * <p>The upgrades window said only the tier: "Tier 2" on the ore generator told a player nothing about which ores
 * it turns up, or what tier 3 would add for the money. For every upgrade the operator's file defines this gives
 * {@code <id>_now}, {@code <id>_next}, {@code <id>_cost} and {@code <id>_max}, spelled
 * {@code <argument_island_size_now>} and so on in the catalogue.
 *
 * <p>What a tier does is the line {@code upgrades.effects.<id>} of the catalogue, with each property of the tier as
 * a value of its own: {@code <size>}, {@code <max_members>}, {@code <rate_multiplier>}. An upgrade an operator added
 * with no line of its own lists its properties as they are named. The ore generator's line is given {@code <ores>},
 * every block it turns up with its share, read from the same rates the generator draws from.
 */
public final class UpgradeWords {

    private static final String ORE_GENERATOR = UpgradeId.ORE_GENERATOR.key().toLowerCase(Locale.ROOT);

    private final Messages messages;
    private final Supplier<Map<UpgradeId, UpgradeDefinition>> definitions;
    private final GeneratorsConfiguration generators;
    private final Map<LimitType, LimitQuota> quotas;

    public UpgradeWords(
            Messages messages,
            Supplier<Map<UpgradeId, UpgradeDefinition>> definitions,
            GeneratorsConfiguration generators) {
        this(messages, definitions, generators, Map.of());
    }

    /**
     * Words that also say what a limit upgrade allows: every quota of {@code modules/limits.conf} that names the
     * upgrade is a value of its own, {@code <hopper>} and so on, the limit an island on that tier is held to.
     */
    public UpgradeWords(
            Messages messages,
            Supplier<Map<UpgradeId, UpgradeDefinition>> definitions,
            GeneratorsConfiguration generators,
            Map<LimitType, LimitQuota> quotas) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.generators = Objects.requireNonNull(generators, "generators must not be null");
        this.quotas = Map.copyOf(Objects.requireNonNull(quotas, "quotas must not be null"));
    }

    /** The words of every upgrade for an island standing on {@code tiers}, keyed as a menu asks for them. */
    public Map<String, String> values(Player reader, Map<UpgradeId, Integer> tiers) {
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(tiers, "tiers must not be null");
        Map<String, String> values = new HashMap<>();
        String maxed = line(reader, "menu.upgrades.maxed");
        for (UpgradeDefinition definition : definitions.get().values()) {
            String id = definition.id().key().toLowerCase(Locale.ROOT);
            int tier = definition.effectiveTier(tiers.getOrDefault(definition.id(), 0));
            Optional<UpgradeTier> next = definition.getTier(tier + 1);
            values.put(
                    id + "_now", effect(reader, definition, tier).orElseGet(() -> line(reader, "menu.upgrades.none")));
            values.put(
                    id + "_next",
                    next.flatMap(candidate -> effect(reader, definition, candidate.tier()))
                            .orElse(maxed));
            values.put(
                    id + "_cost",
                    next.map(candidate -> MoneyText.of(candidate.costMinorUnits()))
                            .orElse(maxed));
            values.put(id + "_max", Integer.toString(definition.maxTier()));
            values.put(id + "_tier", Integer.toString(tier));
        }
        return Map.copyOf(values);
    }

    /** What {@code tier} of {@code definition} does, or nothing when an island on it has nothing from it yet. */
    private Optional<String> effect(Player reader, UpgradeDefinition definition, int tier) {
        String id = definition.id().key().toLowerCase(Locale.ROOT);
        boolean generator = id.equals(ORE_GENERATOR);
        Optional<UpgradeTier> held = definition.getTier(tier);
        Map<String, Integer> limits = limitsAt(definition.id(), tier);
        if (held.isEmpty() && !generator && limits.isEmpty()) {
            return Optional.empty();
        }
        List<TagResolver> resolvers = new ArrayList<>();
        limits.forEach((name, limit) -> resolvers.add(Placeholder.unparsed(name, Integer.toString(limit))));
        Map<String, Double> properties = held.map(UpgradeTier::properties).orElse(Map.of());
        properties.forEach((name, value) -> resolvers.add(Placeholder.unparsed(tagOf(name), number(value))));
        if (generator) {
            resolvers.add(Placeholder.unparsed("ores", ores(reader, tier)));
        }
        String key = "upgrades.effects." + id;
        if (messages.has(key)) {
            return Optional.of(line(reader, key, resolvers.toArray(TagResolver[]::new)));
        }
        List<String> written = new ArrayList<>();
        properties.forEach((name, value) -> written.add(name + " " + number(value)));
        limits.forEach((name, limit) -> written.add(name + " " + limit));
        return written.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", written));
    }

    /** The limit of every quota that names {@code upgrade}, as an island on {@code tier} is held to it. */
    private Map<String, Integer> limitsAt(UpgradeId upgrade, int tier) {
        Map<String, Integer> limits = new java.util.TreeMap<>();
        quotas.forEach((type, quota) -> {
            if (upgrade.equals(quota.upgradeId())) {
                limits.put(tagOf(type.name()), quota.baseLimit() + tier * quota.perTierBonus());
            }
        });
        return limits;
    }

    /** Every block a generator of {@code tier} turns up, the likeliest first, each with its share. */
    private String ores(Player reader, int tier) {
        List<String> ores = new ArrayList<>();
        generators.shares(tier).entrySet().stream()
                .sorted(Map.Entry.<Material, Double>comparingByValue().reversed())
                .forEach(share -> ores.add(
                        messages.named(reader, "upgrades.ores", share.getKey().name(), written(share.getKey())) + " "
                                + percent(share.getValue()) + "%"));
        return String.join(", ", ores);
    }

    private String line(Player reader, String key, TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(messages.renderPlain(reader, key, resolvers));
    }

    /** A property's name as a tag: lower case, and anything a tag cannot hold made an underscore. */
    private static String tagOf(String property) {
        return property.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /** {@code 25.0} as 25 and {@code 1.25} as 1.25: a number as a player writes it. */
    static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** A share rounded to one place, written without a trailing zero. */
    static String percent(double share) {
        return BigDecimal.valueOf(share)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** A block the catalogue has no name for, as its id reads: {@code ANCIENT_DEBRIS} as "Ancient debris". */
    private static String written(Material material) {
        String words = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
