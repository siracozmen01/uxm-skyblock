package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * The goods {@code modules/tradewinds.conf} marks as contraband, the ports that take them, and the customs of the
 * ports that do not.
 *
 * @param items the items marked as contraband
 * @param takers the ports that buy contraband, by key
 * @param customs how likely customs are to board a vessel arriving at each lawful port, by its key
 * @param standingLost how much standing a vessel caught with contraband loses in the port that caught it
 */
public record TradeWindsContraband(
        Set<String> items, Set<String> takers, Map<String, Double> customs, long standingLost) {

    /** No contraband, and no customs. */
    public static final TradeWindsContraband NONE = new TradeWindsContraband(Set.of(), Set.of(), Map.of(), 0);

    private static final Logger LOGGER = Logger.getLogger(TradeWindsContraband.class.getName());

    public TradeWindsContraband {
        items = Set.copyOf(items);
        takers = Set.copyOf(takers);
        customs = Map.copyOf(customs);
        // The same checks the sea makes, said at the start rather than at the first arrival.
        new SeaHazards.Customs(items, customs, standingLost);
    }

    /** What the sea is handed: the contraband, the customs and what a vessel caught loses. */
    public SeaHazards.Customs atSea() {
        return new SeaHazards.Customs(items, customs, standingLost);
    }

    /** The items {@code contraband.items} names, each one the server has. */
    static Set<String> items(ConfigurationNode node) {
        Set<String> items = new LinkedHashSet<>();
        try {
            for (String written : node.node("items").getList(String.class, List.of())) {
                Material material = Material.matchMaterial(written.toUpperCase(Locale.ROOT));
                if (material == null || material.isAir()) {
                    LOGGER.warning(() -> "modules/tradewinds.conf contraband names '" + written
                            + "', which is no item. It is left out.");
                } else {
                    items.add(material.name());
                }
            }
        } catch (SerializationException e) {
            LOGGER.warning(() -> "modules/tradewinds.conf contraband.items cannot be read: " + e.getMessage());
        }
        return items;
    }

    /**
     * The contraband of a file, with each port's {@code takes-contraband} and {@code customs}. A chance that cannot
     * be is said and the port has no customs.
     */
    static TradeWindsContraband read(ConfigurationNode root, Set<String> items, List<Port> kept) {
        Set<String> takers = new LinkedHashSet<>();
        Map<String, Double> customs = new LinkedHashMap<>();
        for (ConfigurationNode port : root.node("ports").childrenList()) {
            String id = port.node("id").getString("");
            if (kept.stream().noneMatch(one -> one.id().equals(id))) {
                continue;
            }
            if (port.node("takes-contraband").getBoolean(false)) {
                takers.add(id);
            }
            double chance = port.node("customs").getDouble(0);
            if (!(chance >= 0 && chance <= 1)) {
                LOGGER.warning(() -> "modules/tradewinds.conf port " + id + " has customs " + chance
                        + ", not from 0 to 1, so it has none.");
            } else if (chance > 0) {
                customs.put(id, chance);
            }
        }
        long lost = root.node("contraband", "standing-lost").getLong(0);
        if (lost < 0) {
            LOGGER.warning(() -> "modules/tradewinds.conf contraband.standing-lost is below 0, so it is 0.");
            lost = 0;
        }
        return new TradeWindsContraband(items, takers, customs, lost);
    }

    /**
     * {@code ports} with what a lawful port lists to buy of the contraband taken off its market, said once each: a
     * good it also sells keeps its asking price, and one it only bought is gone.
     */
    static List<Port> lawful(List<Port> ports, TradeWindsContraband contraband) {
        List<Port> kept = new ArrayList<>();
        for (Port port : ports) {
            if (contraband.takers().contains(port.id())) {
                kept.add(port);
                continue;
            }
            List<Port.Good> goods = new ArrayList<>();
            for (Port.Good good : port.goods()) {
                if (!contraband.items().contains(good.item()) || !good.bought()) {
                    goods.add(good);
                    continue;
                }
                LOGGER.warning(() -> "modules/tradewinds.conf port " + port.id() + " buys " + good.item()
                        + ", which is contraband, and it takes no contraband. It does not buy it.");
                if (good.sold()) {
                    goods.add(new Port.Good(good.item(), good.lot(), 0, good.asks()));
                }
            }
            kept.add(new Port(port.id(), port.voyageSeconds(), goods));
        }
        return List.copyOf(kept);
    }
}
