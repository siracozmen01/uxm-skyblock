package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.core.application.tradewinds.Contract;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.PriceDrift;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.Standing;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The TradeWinds game mode, as {@code modules/tradewinds.conf} writes it: islands that are trading vessels
 * on a sea of their own.
 *
 * @param enabled whether islands can be made as TradeWinds vessels
 * @param sea the sea a new vessel is launched on
 * @param ranks the ranks a vessel climbs as it trades, which set how many rows its hold has
 * @param ports the ports vessels sail between, in the order the file writes them
 * @param icons the item each port is shown as, by its key
 * @param standing how a vessel's standing in a port moves its prices there
 * @param drift how a port's trade moves its prices, for every vessel alike
 * @param contracts the deliveries the ports offer
 * @param voyageEvents what may happen to a vessel at sea
 * @param contraband the goods only some ports take, and the customs of the rest
 */
public record TradeWindsConfiguration(
        boolean enabled,
        Sea sea,
        Ranks ranks,
        List<Port> ports,
        Map<String, String> icons,
        Standing standing,
        PriceDrift drift,
        TradeWindsContracts contracts,
        TradeWindsVoyageEvents voyageEvents,
        TradeWindsContraband contraband) {

    /**
     * The drift a file that writes none has: a tenth of a percent for each item, from half to one and a half the
     * written price, the push fading by half every six hours.
     */
    public static final PriceDrift SHIPPED_DRIFT = new PriceDrift(0.001, 0.5, 1.5, java.time.Duration.ofHours(6));

    /** The standing a file that writes none has: one percent for every 500.00 of trade, up to ten. */
    public static final Standing SHIPPED_STANDING = new Standing(50_000, 1, 10);

    /** The ports a file that writes none has, with trade routes between them. */
    public static final List<Port> SHIPPED_PORTS = List.of(
            new Port(
                    "emerald-bay",
                    60,
                    List.of(
                            new Port.Good("WHEAT", 16, 150, 250),
                            new Port.Good("PUMPKIN", 8, 400, 700),
                            new Port.Good("EMERALD", 4, 8_000, 12_000))),
            new Port(
                    "saltmarsh",
                    90,
                    List.of(
                            new Port.Good("WHEAT", 16, 400, 600),
                            new Port.Good("SUGAR_CANE", 32, 100, 180),
                            new Port.Good("GLASS", 16, 500, 800),
                            new Port.Good("GUNPOWDER", 16, 0, 300))),
            new Port(
                    "ironhaven",
                    120,
                    List.of(
                            new Port.Good("SUGAR_CANE", 32, 260, 350),
                            new Port.Good("PUMPKIN", 8, 900, 1_200),
                            new Port.Good("IRON_INGOT", 8, 1_800, 2_600),
                            new Port.Good("EMERALD", 4, 14_000, 18_000),
                            new Port.Good("GUNPOWDER", 16, 700, 1_000))));

    /**
     * The contraband a file that writes no ports has: gunpowder, sold at Saltmarsh and bought at Ironhaven, the two
     * ports that take it, while Emerald Bay sends customs aboard three arrivals in ten and a vessel caught there
     * loses 500.00 of its standing.
     */
    public static final TradeWindsContraband SHIPPED_CONTRABAND = new TradeWindsContraband(
            java.util.Set.of("GUNPOWDER"),
            java.util.Set.of("saltmarsh", "ironhaven"),
            Map.of("emerald-bay", 0.3),
            50_000);

    /**
     * The deliveries a file that writes no ports offers: wheat from Emerald Bay to Saltmarsh, and pumpkins from
     * Ironhaven to Emerald Bay, each paying whoever delivers it.
     */
    public static final TradeWindsContracts SHIPPED_CONTRACTS = new TradeWindsContracts(
            List.of(
                    new Contract(
                            "grain-run", "emerald-bay", "WHEAT", 64, "saltmarsh", java.time.Duration.ofMinutes(30)),
                    new Contract(
                            "harvest-home", "ironhaven", "PUMPKIN", 32, "emerald-bay", java.time.Duration.ofHours(1))),
            Map.of(
                    "grain-run", paying("150"),
                    "harvest-home", paying("300")),
            Map.of(
                    "grain-run", "@tradewinds.contracts.previews.grain-run",
                    "harvest-home", "@tradewinds.contracts.previews.harvest-home"),
            TradeWindsContracts.SHIPPED_AT_ONCE);

    private static TradeWindsContracts.Reward paying(String amount) {
        return new TradeWindsContracts.Reward(List.of("[give-money] " + amount), List.of());
    }

    /** The items the shipped ports are shown as. */
    public static final Map<String, String> SHIPPED_ICONS =
            Map.of("emerald-bay", "EMERALD", "saltmarsh", "PRISMARINE_SHARD", "ironhaven", "IRON_INGOT");

    /** The ranks a file that writes none has, from a dinghy's three rows to a galleon's six. */
    public static final Ranks SHIPPED_RANKS = new Ranks(List.of(
            new Ranks.Rank("dinghy", 0, 3),
            new Ranks.Rank("sloop", 500_000, 4),
            new Ranks.Rank("brigantine", 2_500_000, 5),
            new Ranks.Rank("galleon", 10_000_000, 6)));

    private static final Logger LOGGER = Logger.getLogger(TradeWindsConfiguration.class.getName());

    public TradeWindsConfiguration {
        Objects.requireNonNull(sea, "sea must not be null");
        Objects.requireNonNull(standing, "standing must not be null");
        Objects.requireNonNull(drift, "drift must not be null");
        Objects.requireNonNull(contracts, "contracts must not be null");
        Objects.requireNonNull(voyageEvents, "voyageEvents must not be null");
        Objects.requireNonNull(contraband, "contraband must not be null");
        Objects.requireNonNull(ranks, "ranks must not be null");
        ports = List.copyOf(ports);
        icons = Map.copyOf(icons);
    }

    /** The port the file writes under {@code id}, if it writes one. */
    public Optional<Port> port(String id) {
        return ports.stream().filter(port -> port.id().equals(id)).findFirst();
    }

    /** The item {@code port} is shown as. */
    public String icon(Port port) {
        return icons.getOrDefault(port.id(), "OAK_BOAT");
    }

    /**
     * The sea under a new vessel.
     *
     * @param radius how far the sea reaches from the vessel, each way
     * @param depth how deep the sea is under its surface
     * @param floor the block the sea lies on; one that does not fall
     */
    public record Sea(int radius, int depth, String floor) {

        /** The widest sea a vessel is launched on. */
        public static final int MAX_RADIUS = 128;

        /** The deepest sea. */
        public static final int MAX_DEPTH = 64;

        public static final Sea SHIPPED = new Sea(48, 12, "SANDSTONE");

        public Sea {
            Objects.requireNonNull(floor, "floor must not be null");
            if (radius < 8 || radius > MAX_RADIUS) {
                throw new IllegalArgumentException("the radius must be from 8 to " + MAX_RADIUS);
            }
            if (depth < 2 || depth > MAX_DEPTH) {
                throw new IllegalArgumentException("the depth must be from 2 to " + MAX_DEPTH);
            }
        }
    }

    public static TradeWindsConfiguration defaultConfiguration() {
        return new TradeWindsConfiguration(
                true,
                Sea.SHIPPED,
                SHIPPED_RANKS,
                SHIPPED_PORTS,
                SHIPPED_ICONS,
                SHIPPED_STANDING,
                SHIPPED_DRIFT,
                SHIPPED_CONTRACTS,
                TradeWindsVoyageEvents.SHIPPED,
                SHIPPED_CONTRABAND);
    }

    public static TradeWindsConfiguration load(ConfigurationNode root) {
        ConfigurationNode seaNode = root.node("sea");
        Sea sea;
        try {
            sea = new Sea(
                    seaNode.node("radius").getInt(Sea.SHIPPED.radius()),
                    seaNode.node("depth").getInt(Sea.SHIPPED.depth()),
                    seaNode.node("floor").getString(Sea.SHIPPED.floor()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/tradewinds.conf sea: " + e.getMessage() + ". The shipped sea is used.");
            sea = Sea.SHIPPED;
        }
        Map<String, String> icons = new LinkedHashMap<>();
        List<Port> written = ports(root.node("ports"), icons);
        TradeWindsContraband contraband = root.node("ports").virtual()
                ? SHIPPED_CONTRABAND
                : TradeWindsContraband.read(root, TradeWindsContraband.items(root.node("contraband")), written);
        List<Port> ports = TradeWindsContraband.lawful(written, contraband);
        return new TradeWindsConfiguration(
                root.node("enabled").getBoolean(true),
                sea,
                ranks(root.node("ranks")),
                ports,
                icons,
                standing(root.node("standing")),
                drift(root.node("market-drift")),
                root.node("ports").virtual()
                        ? SHIPPED_CONTRACTS
                        : TradeWindsContracts.read(root.node("ports"), root.node("contracts-at-once"), ports),
                TradeWindsVoyageEvents.read(root.node("voyage-events")),
                contraband);
    }

    /**
     * How the ports' trade moves their prices, as {@code market-drift} writes it: {@code per-item}, {@code floor},
     * {@code ceiling} and {@code half-life}. {@code enabled = false} keeps every price as written. A drift that
     * cannot be is said, and the shipped one is used.
     */
    private static PriceDrift drift(ConfigurationNode node) {
        if (!node.node("enabled").getBoolean(true)) {
            return PriceDrift.NONE;
        }
        String written = node.node("half-life").getString("");
        java.time.Duration halfLife = SHIPPED_DRIFT.halfLife();
        if (!written.isBlank()) {
            try {
                halfLife = com.uxplima.uxmlib.common.Durations.parse(written.strip());
            } catch (RuntimeException unreadable) {
                LOGGER.warning(() -> "modules/tradewinds.conf market-drift.half-life is '" + written
                        + "', which is no time, so the shipped "
                        + SHIPPED_DRIFT.halfLife().toHours() + "h is used.");
            }
        }
        try {
            return new PriceDrift(
                    node.node("per-item").getDouble(SHIPPED_DRIFT.perItem()),
                    node.node("floor").getDouble(SHIPPED_DRIFT.floor()),
                    node.node("ceiling").getDouble(SHIPPED_DRIFT.ceiling()),
                    halfLife);
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/tradewinds.conf market-drift: " + e.getMessage() + ". The shipped drift is used.");
            return SHIPPED_DRIFT;
        }
    }

    /**
     * The ranks the file writes, or the shipped ones when it writes none or any that cannot be: each rank
     * leans on the one before it, so one that cannot be leaves no ladder to climb.
     */
    private static Ranks ranks(ConfigurationNode node) {
        if (node.virtual()) {
            return SHIPPED_RANKS;
        }
        try {
            List<Ranks.Rank> ranks = new ArrayList<>();
            for (ConfigurationNode rank : node.childrenList()) {
                ranks.add(new Ranks.Rank(
                        rank.node("id").getString(""),
                        rank.node("volume").getLong(0),
                        rank.node("hold-rows").getInt(3)));
            }
            return new Ranks(ranks);
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/tradewinds.conf ranks: " + e.getMessage() + ". The shipped ranks are used.");
            return SHIPPED_RANKS;
        }
    }

    /** The standing the file writes, or the shipped one when it writes one that cannot be. */
    private static Standing standing(ConfigurationNode node) {
        try {
            return new Standing(
                    node.node("step").getLong(SHIPPED_STANDING.step()),
                    node.node("percent").getInt(SHIPPED_STANDING.percent()),
                    node.node("max-steps").getInt(SHIPPED_STANDING.maxSteps()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/tradewinds.conf standing: " + e.getMessage() + ". The shipped standing is used.");
            return SHIPPED_STANDING;
        }
    }

    /**
     * The ports the file writes. A port or a good that cannot be is left out with a warning, and the rest
     * are kept; a file that writes no ports block at all has the shipped ports.
     */
    private static List<Port> ports(ConfigurationNode node, Map<String, String> icons) {
        if (node.virtual()) {
            icons.putAll(SHIPPED_ICONS);
            return SHIPPED_PORTS;
        }
        List<Port> ports = new ArrayList<>();
        for (ConfigurationNode written : node.childrenList()) {
            String id = written.node("id").getString("");
            List<Port.Good> goods = new ArrayList<>();
            for (ConfigurationNode good : written.node("market").childrenList()) {
                String item = good.node("item").getString("").toUpperCase(java.util.Locale.ROOT);
                Material material = Material.matchMaterial(item);
                if (material == null || material.isAir()) {
                    LOGGER.warning(() -> "modules/tradewinds.conf port " + id + " trades '" + item
                            + "', which is no item. It is left out.");
                    continue;
                }
                try {
                    goods.add(new Port.Good(
                            material.name(),
                            good.node("lot").getInt(1),
                            good.node("pays").getLong(0),
                            good.node("asks").getLong(0)));
                } catch (IllegalArgumentException e) {
                    LOGGER.warning(() -> "modules/tradewinds.conf port " + id + ", " + item + ": " + e.getMessage()
                            + ". It is left out.");
                }
            }
            try {
                Port port = new Port(id, written.node("voyage-seconds").getInt(60), goods);
                if (ports.stream().anyMatch(other -> other.id().equals(id))) {
                    throw new IllegalArgumentException("another port is written under the same id");
                }
                ports.add(port);
                Material shown = Material.matchMaterial(written.node("icon").getString("OAK_BOAT"));
                icons.put(id, shown == null || shown.isAir() ? "OAK_BOAT" : shown.name());
            } catch (IllegalArgumentException e) {
                LOGGER.warning(
                        () -> "modules/tradewinds.conf port '" + id + "': " + e.getMessage() + ". It is left out.");
            }
        }
        return List.copyOf(ports);
    }
}
