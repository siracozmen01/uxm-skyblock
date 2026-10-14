package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How {@code modules/acidisland.conf} is read: every section of the sea, the tide, the offerings and the world between, each value falling back to the shipped one. Split out of {@link AcidIslandConfiguration}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class AcidIslandReader {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(AcidIslandReader.class.getName());

    private AcidIslandReader() {}

    public static AcidIslandConfiguration load(ConfigurationNode root) {
        AcidIslandConfiguration shipped = AcidIslandConfiguration.defaultConfiguration();
        AcidIslandConfiguration.Sea sea;
        ConfigurationNode seaNode = root.node("sea");
        try {
            ConfigurationNode ventNode = seaNode.node("vents");
            AcidIslandConfiguration.Vents vents = new AcidIslandConfiguration.Vents(
                    ventNode.node("chance").getDouble(AcidIslandConfiguration.Vents.SHIPPED.chance()),
                    ventNode.node("reach").getInt(AcidIslandConfiguration.Vents.SHIPPED.reach()),
                    AcidIslandConfiguration.strings(
                            ventNode.node("effects"), AcidIslandConfiguration.Vents.SHIPPED.effects()));
            sea = new AcidIslandConfiguration.Sea(
                    seaNode.node("below-island").getInt(AcidIslandConfiguration.Sea.SHIPPED.belowIsland()),
                    seaNode.node("depth").getInt(AcidIslandConfiguration.Sea.SHIPPED.depth()),
                    seaNode.node("radius").getInt(AcidIslandConfiguration.Sea.SHIPPED.radius()),
                    seaNode.node("floor")
                            .getString(AcidIslandConfiguration.Sea.SHIPPED.floor())
                            .trim(),
                    vents,
                    seaNode.node("geysers", "chance").getDouble(AcidIslandConfiguration.Sea.SHIPPED.geyserChance()),
                    new AcidIslandConfiguration.Bed(
                            AcidIslandConfiguration.strings(
                                    seaNode.node("bed", "blocks"), AcidIslandConfiguration.Bed.SHIPPED.blocks()),
                            seaNode.node("bed", "thickness").getInt(AcidIslandConfiguration.Bed.SHIPPED.thickness()),
                            seaNode.node("bed", "mounds").getInt(AcidIslandConfiguration.Bed.SHIPPED.mounds())));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea: " + e.getMessage() + ". The shipped sea is used.");
            sea = AcidIslandConfiguration.Sea.SHIPPED;
        }
        ConfigurationNode hazard = root.node("hazard");
        AcidRules rules;
        try {
            rules = new AcidRules(
                    hazard.node("water-damage").getDouble(shipped.rules().waterDamage()),
                    hazard.node("rain-damage").getDouble(shipped.rules().rainDamage()),
                    hazard.node("helmet-blocks-rain").getBoolean(shipped.rules().helmetBlocksRain()),
                    AcidIslandConfiguration.durationOf(
                            hazard.node("check-every").getString(""),
                            shipped.rules().checkEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf hazard: " + e.getMessage() + ". The shipped numbers are used.");
            rules = shipped.rules();
        }
        ConfigurationNode purification = root.node("purification");
        return new AcidIslandConfiguration(
                root.node("enabled").getBoolean(true),
                sea,
                rules,
                AcidIslandConfiguration.strings(hazard.node("water-protection"), shipped.waterProtection()),
                AcidIslandConfiguration.strings(hazard.node("water-effects"), shipped.waterEffects()),
                new AcidIslandConfiguration.Purification(
                        purification.node("furnace").getBoolean(true),
                        purification.node("brewing-with-coal").getBoolean(true)),
                seaLife(root.node("sea-life")),
                armour(hazard.node("armour")),
                cleanWater(root.node("clean-water")),
                AcidIslandConfiguration.look(seaNode.node("look")),
                buried(seaNode.node("buried")),
                tide(seaNode.node("tide")),
                offerings(seaNode.node("vents", "offerings")),
                between(seaNode.node("between")));
    }
    /** The sea between islands, as {@code sea between} writes it; one that cannot be is off, said. */
    static AcidIslandConfiguration.Between between(ConfigurationNode node) {
        try {
            return new AcidIslandConfiguration.Between(
                    node.node("enabled").getBoolean(AcidIslandConfiguration.Between.SHIPPED.enabled()),
                    node.node("world").getString(AcidIslandConfiguration.Between.SHIPPED.world()),
                    node.node("surface").getInt(AcidIslandConfiguration.Between.SHIPPED.surface()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea between: " + e.getMessage() + ". It is off.");
            return AcidIslandConfiguration.Between.SHIPPED;
        }
    }
    /**
     * The offerings, as {@code sea vents offerings} writes them; one that cannot be read is left out, said, and a
     * wait that cannot be is the shipped one.
     */
    static AcidIslandConfiguration.Offerings offerings(ConfigurationNode node) {
        if (node.virtual()) {
            return AcidIslandConfiguration.Offerings.SHIPPED;
        }
        List<AcidIslandConfiguration.Offering> read = new ArrayList<>();
        ConfigurationNode list = node.node("list");
        if (list.virtual()) {
            read.addAll(AcidIslandConfiguration.Offerings.SHIPPED.list());
        }
        int at = 0;
        for (ConfigurationNode written : list.childrenList()) {
            at++;
            int number = at;
            try {
                read.add(new AcidIslandConfiguration.Offering(
                        AcidIslandConfiguration.strings(written.node("items"), List.of()),
                        written.node("takes").getInt(1),
                        written.node("loot").getString("")));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "modules/acidisland.conf sea vents offerings, offering " + number + ": "
                        + e.getMessage() + ". It is left out.");
            }
        }
        Duration after = AcidIslandConfiguration.durationOf(
                node.node("after").getString(""), AcidIslandConfiguration.Offerings.SHIPPED.after());
        return new AcidIslandConfiguration.Offerings(
                after.isNegative() ? AcidIslandConfiguration.Offerings.SHIPPED.after() : after, read);
    }
    /** The tide, as {@code sea tide} writes it; one that cannot be is the shipped one, said. */
    static AcidIslandConfiguration.Tide tide(ConfigurationNode node) {
        try {
            return new AcidIslandConfiguration.Tide(
                    new com.uxplima.uxmskyblock.core.domain.hazard.AcidTide(
                            node.node("rise")
                                    .getInt(AcidIslandConfiguration.Tide.SHIPPED
                                            .tide()
                                            .rise()),
                            AcidIslandConfiguration.durationOf(
                                    node.node("period").getString(""),
                                    AcidIslandConfiguration.Tide.SHIPPED.tide().period())),
                    AcidIslandConfiguration.durationOf(
                            node.node("check-every").getString(""), AcidIslandConfiguration.Tide.SHIPPED.checkEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea tide: " + e.getMessage() + ". The shipped tide is used.");
            return AcidIslandConfiguration.Tide.SHIPPED;
        }
    }
    /** The structures buried in the floor, as {@code sea buried} writes them; what cannot be read is the shipped. */
    static AcidIslandConfiguration.Buried buried(ConfigurationNode node) {
        if (node.virtual()) {
            return AcidIslandConfiguration.Buried.SHIPPED;
        }
        ConfigurationNode groups = node.node("groups");
        try {
            return new AcidIslandConfiguration.Buried(
                    node.node("sink").getInt(AcidIslandConfiguration.Buried.SHIPPED.sink()),
                    groups.virtual()
                            ? AcidIslandConfiguration.Buried.SHIPPED.groups()
                            : PoseidonConfiguration.wrecks(
                                    groups,
                                    AcidIslandConfiguration.Buried.SHIPPED.groups(),
                                    "modules/acidisland.conf sea buried groups"));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf sea buried: " + e.getMessage() + ". The shipped ones are used.");
            return AcidIslandConfiguration.Buried.SHIPPED;
        }
    }
    /** What clean water does, as {@code clean-water} writes it; what cannot be read is the shipped. */
    static AcidIslandConfiguration.CleanWater cleanWater(ConfigurationNode node) {
        try {
            return new AcidIslandConfiguration.CleanWater(
                    node.node("heals").getDouble(AcidIslandConfiguration.CleanWater.SHIPPED.heals()),
                    AcidIslandConfiguration.strings(
                            node.node("effects"), AcidIslandConfiguration.CleanWater.SHIPPED.effects()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf clean-water: " + e.getMessage() + ". The shipped one is used.");
            return AcidIslandConfiguration.CleanWater.SHIPPED;
        }
    }
    /** The armour that keeps the acid off, as {@code hazard armour} writes it; what cannot be read is the shipped. */
    static AcidIslandConfiguration.Armour armour(ConfigurationNode node) {
        AcidIslandConfiguration.Armour shipped = AcidIslandConfiguration.Armour.SHIPPED;
        try {
            return new AcidIslandConfiguration.Armour(
                    AcidIslandConfiguration.strings(node.node("sets"), shipped.sets()),
                    node.node("sea").getBoolean(shipped.sea()),
                    node.node("rain").getBoolean(shipped.rain()),
                    node.node("wear").getInt(shipped.wear()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf hazard armour: " + e.getMessage() + ". The shipped armour is used.");
            return shipped;
        }
    }
    /** What the sea does to items and creatures, as {@code sea-life} writes it; what cannot be read is the shipped. */
    static AcidIslandConfiguration.SeaLife seaLife(ConfigurationNode node) {
        AcidIslandConfiguration.SeaLife shipped = AcidIslandConfiguration.SeaLife.SHIPPED;
        try {
            ConfigurationNode items = node.node("items");
            ConfigurationNode creatures = node.node("creatures");
            return new AcidIslandConfiguration.SeaLife(
                    node.node("reach").getInt(shipped.reach()),
                    items.node("dissolve").getBoolean(shipped.itemsDissolve()),
                    AcidIslandConfiguration.durationOf(items.node("after").getString(""), shipped.itemsAfter()),
                    creatures.node("burn").getBoolean(shipped.creaturesBurn()),
                    creatures.node("damage").getDouble(shipped.creatureDamage()),
                    AcidIslandConfiguration.strings(creatures.node("spared"), shipped.spared()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea-life: " + e.getMessage() + ". The shipped one is used.");
            return shipped;
        }
    }
}
