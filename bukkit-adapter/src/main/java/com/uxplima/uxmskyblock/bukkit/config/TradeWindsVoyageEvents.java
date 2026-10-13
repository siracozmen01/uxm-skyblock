package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * What may happen to a vessel at sea, as {@code voyage-events} of {@code modules/tradewinds.conf} writes it.
 *
 * @param chances how likely a storm, pirates and a find are, how long a storm costs and what pirates take
 * @param boarder the creature that boards when pirates are not paid
 * @param boarders how many board, next to each of the crew aboard
 * @param loot the loot table of {@code loot/} a find is rolled from
 */
public record TradeWindsVoyageEvents(SeaHazards.Chances chances, String boarder, int boarders, String loot) {

    /** The most creatures that board next to one of the crew. */
    public static final int MAX_BOARDERS = 16;

    /**
     * The events a file that writes none has: a storm one voyage in seven costing half a minute, pirates one in ten
     * asking 200.00 or three pillagers, and a find one in ten from the treasure table.
     */
    public static final TradeWindsVoyageEvents SHIPPED = new TradeWindsVoyageEvents(
            new SeaHazards.Chances(0.15, Duration.ofSeconds(30), 0.1, 20_000, 0.1), "pillager", 3, "treasure");

    private static final Logger LOGGER = Logger.getLogger(TradeWindsVoyageEvents.class.getName());

    public TradeWindsVoyageEvents {
        Objects.requireNonNull(chances, "chances must not be null");
        Objects.requireNonNull(boarder, "boarder must not be null");
        Objects.requireNonNull(loot, "loot must not be null");
        if (boarders < 0 || boarders > MAX_BOARDERS) {
            throw new IllegalArgumentException("from 0 to " + MAX_BOARDERS + " creatures board");
        }
    }

    /** The creature that boards, or none when the file names none the server has. */
    public java.util.Optional<EntityType> boarderType() {
        NamespacedKey key = NamespacedKey.fromString(boarder.toLowerCase(Locale.ROOT));
        return java.util.Optional.ofNullable(key == null ? null : Registry.ENTITY_TYPE.get(key));
    }

    /**
     * The events {@code node} writes. A part that cannot be is said, and the shipped one is used in its place; a
     * chance left out is 0, so a server writes only the events it wants.
     */
    static TradeWindsVoyageEvents read(ConfigurationNode node) {
        if (node.virtual()) {
            return SHIPPED;
        }
        ConfigurationNode storm = node.node("storm");
        ConfigurationNode pirates = node.node("pirates");
        ConfigurationNode find = node.node("find");
        SeaHazards.Chances chances;
        try {
            chances = new SeaHazards.Chances(
                    storm.node("chance").getDouble(0),
                    delay(storm.node("delay").getString("")),
                    pirates.node("chance").getDouble(0),
                    pirates.node("toll").getLong(0),
                    find.node("chance").getDouble(0));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() ->
                    "modules/tradewinds.conf voyage-events: " + e.getMessage() + ". The shipped events are used.");
            return SHIPPED;
        }
        String creature = pirates.node("creature").getString(SHIPPED.boarder());
        int many = pirates.node("boarders").getInt(0);
        if (many < 0 || many > MAX_BOARDERS) {
            LOGGER.warning(() -> "modules/tradewinds.conf voyage-events.pirates.boarders is " + many
                    + ", not from 0 to " + MAX_BOARDERS + ", so none board.");
        }
        TradeWindsVoyageEvents read = new TradeWindsVoyageEvents(
                chances,
                creature,
                many < 0 || many > MAX_BOARDERS ? 0 : many,
                find.node("loot").getString(""));
        if (read.boarders() > 0 && read.boarderType().isEmpty()) {
            LOGGER.warning(() -> "modules/tradewinds.conf voyage-events.pirates.creature is '" + creature
                    + "', which is no creature, so none board.");
        }
        return read;
    }

    private static Duration delay(String written) {
        if (written.isBlank()) {
            return Duration.ZERO;
        }
        try {
            return com.uxplima.uxmlib.common.Durations.parse(written.strip());
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("storm.delay is '" + written + "', which is no time", unreadable);
        }
    }
}
