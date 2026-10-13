package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/boxed.conf}: whether islands can be Boxed islands, how big a box starts, what each
 * advancement is worth to it, how often a player's view of their box is brought up to date, and the
 * node that walks out of a box.
 *
 * @param borderEvery how often each player on a Boxed island is shown where their box ends
 * @param bypassPermission a node whose holder is not kept inside a box, empty for nobody
 * @param land the land a new island plays on
 * @param visitorsEarn whether an advancement a player makes while on another island grows their own box
 * @param buy the growth an island buys
 */
public record BoxedConfiguration(
        boolean enabled,
        BoxRules rules,
        Duration borderEvery,
        String bypassPermission,
        BoxedLand land,
        boolean visitorsEarn,
        BoxedBuy buy) {

    private static final Logger LOGGER = Logger.getLogger(BoxedConfiguration.class.getName());

    public BoxedConfiguration {
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(borderEvery, "borderEvery must not be null");
        Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
        Objects.requireNonNull(land, "land must not be null");
        Objects.requireNonNull(buy, "buy must not be null");
        if (borderEvery.toMillis() < 50) {
            throw new IllegalArgumentException("the border is brought up to date at most once a tick");
        }
    }

    public static BoxedConfiguration defaultConfiguration() {
        return new BoxedConfiguration(
                true, BoxRules.shipped(), Duration.ofSeconds(1), "", BoxedLand.SHIPPED, false, BoxedBuy.SHIPPED);
    }

    public static BoxedConfiguration load(ConfigurationNode root) {
        BoxRules shipped = BoxRules.shipped();
        ConfigurationNode box = root.node("box");
        Map<String, Integer> worth = new HashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                root.node("advancements").childrenMap().entrySet()) {
            worth.put(
                    String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT),
                    entry.getValue().getInt(0));
        }
        List<String> ignored = new ArrayList<>();
        ConfigurationNode ignore = root.node("ignore");
        if (ignore.virtual()) {
            ignored.addAll(shipped.ignored());
        } else {
            for (ConfigurationNode prefix : ignore.childrenList()) {
                String written = prefix.getString("").trim().toLowerCase(Locale.ROOT);
                if (!written.isEmpty()) {
                    ignored.add(written);
                }
            }
        }
        BoxRules rules;
        try {
            rules = new BoxRules(
                    box.node("start-radius").getInt(shipped.startRadius()),
                    box.node("blocks-per-advancement").getInt(shipped.blocksPerAdvancement()),
                    box.node("max-radius").getInt(shipped.maxRadius()),
                    worth,
                    ignored,
                    scoring(root.node("scoring"), shipped.scoring()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/boxed.conf: " + e.getMessage() + ". The shipped box is used.");
            rules = shipped;
        }
        Duration every = seconds(root.node("border-every").getString(""), Duration.ofSeconds(1));
        return new BoxedConfiguration(
                root.node("enabled").getBoolean(true),
                rules,
                every.toMillis() < 50 ? Duration.ofSeconds(1) : every,
                root.node("bypass-permission").getString("").trim(),
                BoxedLand.read(root.node("land")),
                root.node("visitors-earn").getBoolean(false),
                BoxedBuy.read(root.node("buy")));
    }

    /** What an advancement with no worth of its own is worth, as {@code node} writes it. */
    private static AdvancementWorth scoring(ConfigurationNode node, AdvancementWorth shipped) {
        if (node.virtual()) {
            return shipped;
        }
        String by = node.node("by").getString("depth").strip().toLowerCase(Locale.ROOT);
        if (!by.equals("depth") && !by.equals("flat")) {
            throw new IllegalArgumentException("scoring by is depth or flat, not " + by);
        }
        return new AdvancementWorth(
                by.equals("depth"),
                node.node("root").getInt(shipped.root()),
                node.node("recipes").getInt(shipped.recipes()),
                node.node("hidden").getInt(shipped.hidden()));
    }

    /** A window written as {@code 500ms}, {@code 2s} or a plain number of seconds. */
    private static Duration seconds(String written, Duration fallback) {
        String raw = written.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            if (raw.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2).trim()));
            }
            String number =
                    raw.endsWith("s") ? raw.substring(0, raw.length() - 1).trim() : raw;
            return Duration.ofMillis(Math.round(Double.parseDouble(number) * 1000));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
