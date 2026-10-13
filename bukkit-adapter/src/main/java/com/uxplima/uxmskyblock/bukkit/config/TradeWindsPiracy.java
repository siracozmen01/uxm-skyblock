package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Whether a crew may plunder another vessel at sea, as {@code piracy} of {@code modules/tradewinds.conf} writes it.
 * Off as shipped.
 *
 * @param enabled whether crews may plunder at all
 * @param share the share of each stack in the target's hold a plunder takes, from 0 to 1
 * @param cooldown how long a vessel that plundered, or was plundered, waits before either again
 * @param deckRadius how far from the middle of a vessel its deck is protected: a plunder is done off it
 */
public record TradeWindsPiracy(boolean enabled, double share, Duration cooldown, int deckRadius) {

    /** Off, and when an operator turns it on: a quarter of each stack, every half hour, off an 8 block deck. */
    public static final TradeWindsPiracy SHIPPED = new TradeWindsPiracy(false, 0.25, Duration.ofMinutes(30), 8);

    private static final Logger LOGGER = Logger.getLogger(TradeWindsPiracy.class.getName());

    public TradeWindsPiracy {
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (!(share > 0 && share <= 1)) {
            throw new IllegalArgumentException("a plunder takes a share above 0, at most 1: " + share);
        }
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("a cooldown is not below nothing: " + cooldown);
        }
        if (deckRadius < 0 || deckRadius > 128) {
            throw new IllegalArgumentException("the deck reaches from 0 to 128 blocks: " + deckRadius);
        }
    }

    /** The piracy {@code node} writes, or the shipped one, said, when it writes one that cannot be. */
    static TradeWindsPiracy read(ConfigurationNode node) {
        if (node.virtual()) {
            return SHIPPED;
        }
        String written = node.node("cooldown").getString("");
        try {
            Duration cooldown =
                    written.isBlank() ? SHIPPED.cooldown() : com.uxplima.uxmlib.common.Durations.parse(written.strip());
            return new TradeWindsPiracy(
                    node.node("enabled").getBoolean(false),
                    node.node("share").getDouble(SHIPPED.share()),
                    cooldown,
                    node.node("deck-radius").getInt(SHIPPED.deckRadius()));
        } catch (RuntimeException e) {
            LOGGER.warning(() -> "modules/tradewinds.conf piracy: " + e.getMessage() + ". Piracy stays off.");
            return SHIPPED;
        }
    }
}
