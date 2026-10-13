package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.core.application.tradewinds.Contract;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * The deliveries the ports of {@code modules/tradewinds.conf} offer, what each rewards, and how many a vessel holds
 * at a time.
 *
 * @param offered every contract, in the order the file writes them, port by port
 * @param rewards what each contract runs when it is delivered, by its key
 * @param previews the words a contract's tile shows for its reward, plain words or a @key, by its key
 * @param atOnce how many contracts one vessel holds at a time
 */
public record TradeWindsContracts(
        List<Contract> offered, Map<String, Reward> rewards, Map<String, String> previews, int atOnce) {

    /** How many contracts a vessel holds at a time when the file does not say. */
    public static final int SHIPPED_AT_ONCE = 3;

    /** The most a file lets a vessel hold at a time. */
    public static final int MAX_AT_ONCE = 54;

    private static final Logger LOGGER = Logger.getLogger(TradeWindsContracts.class.getName());

    /** No contracts at all. */
    public static final TradeWindsContracts NONE = new TradeWindsContracts(List.of(), Map.of(), Map.of(), 1);

    /**
     * What a contract runs when it is delivered, written as an effect list in {@code modules/effects.conf} is. Two
     * rewards are the same when their lines are.
     */
    public static final class Reward {

        public static final Reward NONE = new Reward(List.of(), List.of());

        private final List<String> delivererLines;
        private final List<String> memberLines;
        private final ActionList deliverer;
        private final ActionList members;

        /**
         * @param deliverer run once, for the player who delivered it
         * @param members run for every member of the vessel's island who is online, the deliverer among them
         */
        public Reward(List<String> deliverer, List<String> members) {
            this.delivererLines = List.copyOf(deliverer);
            this.memberLines = List.copyOf(members);
            this.deliverer = EffectsConfiguration.parseAll("tradewinds contract reward.deliverer", delivererLines);
            this.members = EffectsConfiguration.parseAll("tradewinds contract reward.members", memberLines);
        }

        /** Run once, for the player who delivered it. */
        public ActionList deliverer() {
            return deliverer;
        }

        /** Run for every member of the vessel's island who is online. */
        public ActionList members() {
            return members;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Reward that
                    && delivererLines.equals(that.delivererLines)
                    && memberLines.equals(that.memberLines);
        }

        @Override
        public int hashCode() {
            return Objects.hash(delivererLines, memberLines);
        }

        @Override
        public String toString() {
            return "Reward[deliverer=" + delivererLines + ", members=" + memberLines + "]";
        }
    }

    public TradeWindsContracts {
        offered = List.copyOf(offered);
        rewards = Map.copyOf(rewards);
        previews = Map.copyOf(previews);
        if (atOnce < 1 || atOnce > MAX_AT_ONCE) {
            throw new IllegalArgumentException("a vessel holds from 1 to " + MAX_AT_ONCE + " contracts at a time");
        }
    }

    /** What {@code id} runs when it is delivered, nothing when the file writes nothing for it. */
    public Reward rewardOf(String id) {
        return rewards.getOrDefault(id, Reward.NONE);
    }

    /** The words {@code id}'s tile shows for its reward, when the file writes some. */
    public Optional<String> previewOf(String id) {
        return Optional.ofNullable(previews.get(id));
    }

    /**
     * The contracts each port of {@code node} writes under {@code contracts}, and {@code atOnce} from the module's
     * {@code contracts-at-once}. A contract that cannot be is left out with a warning, and the rest are kept.
     */
    static TradeWindsContracts read(ConfigurationNode ports, ConfigurationNode atOnce, List<Port> kept) {
        List<Contract> offered = new ArrayList<>();
        Map<String, Reward> rewards = new LinkedHashMap<>();
        Map<String, String> previews = new LinkedHashMap<>();
        for (ConfigurationNode port : ports.childrenList()) {
            String from = port.node("id").getString("");
            if (kept.stream().noneMatch(one -> one.id().equals(from))) {
                continue;
            }
            for (ConfigurationNode written : port.node("contracts").childrenList()) {
                String id = written.node("id").getString("");
                try {
                    Contract contract = contract(from, written, kept);
                    if (offered.stream().anyMatch(other -> other.id().equals(contract.id()))) {
                        throw new IllegalArgumentException("another contract is written under the same id");
                    }
                    rewards.put(contract.id(), reward(written.node("reward")));
                    String preview = written.node("preview").getString("");
                    if (!preview.isBlank()) {
                        previews.put(contract.id(), preview);
                    }
                    offered.add(contract);
                } catch (IllegalArgumentException | SerializationException e) {
                    LOGGER.warning(() -> "modules/tradewinds.conf port " + from + ", contract '" + id + "': "
                            + e.getMessage() + ". It is left out.");
                }
            }
        }
        int written = atOnce.getInt(SHIPPED_AT_ONCE);
        if (written < 1 || written > MAX_AT_ONCE) {
            LOGGER.warning(() -> "modules/tradewinds.conf contracts-at-once is " + written + ", not from 1 to "
                    + MAX_AT_ONCE + ", so " + SHIPPED_AT_ONCE + " is used.");
            return new TradeWindsContracts(offered, rewards, previews, SHIPPED_AT_ONCE);
        }
        return new TradeWindsContracts(offered, rewards, previews, written);
    }

    private static Contract contract(String from, ConfigurationNode written, List<Port> kept) {
        String item = written.node("item").getString("").toUpperCase(java.util.Locale.ROOT);
        Material material = Material.matchMaterial(item);
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("'" + item + "' is no item");
        }
        String to = written.node("to").getString("");
        if (kept.stream().noneMatch(one -> one.id().equals(to))) {
            throw new IllegalArgumentException("it is delivered to '" + to + "', which is no port of this file");
        }
        String within = written.node("within").getString("");
        Duration time;
        try {
            time = com.uxplima.uxmlib.common.Durations.parse(within.strip());
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("within is '" + within + "', which is no time", unreadable);
        }
        return new Contract(
                written.node("id").getString(""),
                from,
                material.name(),
                written.node("count").getInt(0),
                to,
                time);
    }

    private static Reward reward(ConfigurationNode node) throws SerializationException {
        if (node.virtual()) {
            return Reward.NONE;
        }
        return new Reward(
                node.node("deliverer").getList(String.class, List.of()),
                node.node("members").getList(String.class, List.of()));
    }
}
