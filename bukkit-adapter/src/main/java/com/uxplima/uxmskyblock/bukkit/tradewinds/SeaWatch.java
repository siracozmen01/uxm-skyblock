package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsVoyageEvents;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.MoneyText;
import com.uxplima.uxmskyblock.bukkit.loot.LootEntry;
import com.uxplima.uxmskyblock.bukkit.loot.LootFiller;
import com.uxplima.uxmskyblock.bukkit.loot.LootTable;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards.Struck;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import org.jspecify.annotations.Nullable;

/**
 * What the crew of a vessel at sea meets, told to those of them online: a storm, pirates paid or boarding, and a
 * find stowed in the hold or lost for want of room. Each fires its effect list of {@code modules/effects.conf}.
 *
 * <p>Boarders come next to each of the crew standing on the vessel. Called off the main thread, now and then.
 */
public final class SeaWatch {

    private static final Logger LOGGER = Logger.getLogger(SeaWatch.class.getName());

    /** The word an effect list reads the port a voyage is bound for under. */
    private static final String PORT = "port";

    private final SeaHazards hazards;
    private final TradeWindsVoyageEvents config;
    private final Supplier<LootTables> loot;
    private final LootFiller.Vanilla vanilla;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Function<UUID, @Nullable Player> online;
    private final Function<IslandId, Optional<Island>> islandOf;
    private final Function<Location, Optional<Island>> islandAt;
    private final Random random;
    private volatile @Nullable InteractionEffectPlayer effectPlayer;
    private volatile InteractionEffects effects = InteractionEffects.none();

    /**
     * @param loot the loot tables of {@code loot/}, as they are now
     * @param online the player of a uuid when they are on this server
     * @param islandOf the island of a vessel, read from storage
     * @param islandAt the island a place lies on
     */
    @SuppressWarnings("TooManyParameters")
    public SeaWatch(
            SeaHazards hazards,
            TradeWindsVoyageEvents config,
            Supplier<LootTables> loot,
            LootFiller.Vanilla vanilla,
            SchedulerPort scheduler,
            Messages messages,
            Function<UUID, @Nullable Player> online,
            Function<IslandId, Optional<Island>> islandOf,
            Function<Location, Optional<Island>> islandAt,
            Random random) {
        this.hazards = Objects.requireNonNull(hazards, "hazards");
        this.config = Objects.requireNonNull(config, "config");
        this.loot = Objects.requireNonNull(loot, "loot");
        this.vanilla = Objects.requireNonNull(vanilla, "vanilla");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.online = Objects.requireNonNull(online, "online");
        this.islandOf = Objects.requireNonNull(islandOf, "islandOf");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Hands the watch what {@code modules/effects.conf} fires for a vessel's crew. */
    public void useEffects(InteractionEffectPlayer player, InteractionEffects written) {
        this.effects = Objects.requireNonNull(written, "written");
        this.effectPlayer = Objects.requireNonNull(player, "player");
    }

    /** Rolls what a voyage just begun holds. Off the main thread. */
    public void sailed(IslandId vessel, String portId, Instant sailed, Instant arrives) {
        hazards.plan(vessel, portId, sailed, arrives);
    }

    /** Lets every event whose moment came happen, and tells each one's crew online. Off the main thread. */
    public void watch() {
        for (Struck struck : hazards.strike()) {
            try {
                happened(struck);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        "A TradeWinds voyage event of " + struck.event().vessel() + " failed",
                        e);
            }
        }
    }

    private void happened(Struck struck) {
        IslandId vessel = struck.event().vessel();
        Map<String, String> says =
                new java.util.HashMap<>(Map.of(PORT, struck.event().portId()));
        switch (struck) {
            case Struck.Storm storm -> {
                says.put("seconds", Long.toString(storm.lost().toSeconds()));
                fire(
                        vessel, "vessel-storm", "tradewinds.voyage.storm", says, player -> new TagResolver[] {
                            Placeholder.unparsed("time", DurationText.of(messages, player, storm.lost()))
                        });
            }
            case Struck.Toll toll -> {
                says.put("toll", MoneyText.of(toll.paid()));
                fire(vessel, "vessel-pirates-toll", "tradewinds.voyage.toll", says, player ->
                        new TagResolver[] {Placeholder.unparsed("price", MoneyText.of(toll.paid()))});
            }
            case Struck.Boarded boarded -> {
                fire(vessel, "vessel-pirates-boarded", "tradewinds.voyage.boarded", says, SeaWatch::nothingMore);
                board(vessel);
            }
            case Struck.Find find -> found(find, says);
        }
    }

    private void found(Struck.Find find, Map<String, String> says) {
        List<ItemStack> items = roll();
        SeaHazards.Stowed stowed = hazards.stow(find, (hold, slots) -> HoldGoods.stowAll(hold, slots, items));
        says.put(
                "items",
                Integer.toString(items.stream().mapToInt(ItemStack::getAmount).sum()));
        switch (stowed) {
            case STOWED ->
                fire(find.event().vessel(), "vessel-find", "tradewinds.voyage.found", says, SeaWatch::nothingMore);
            case NO_ROOM ->
                fire(
                        find.event().vessel(),
                        "vessel-find-lost",
                        "tradewinds.voyage.find_lost",
                        says,
                        SeaWatch::nothingMore);
            case NOT_NOW -> {
                // Still waiting: it comes again on the next watch.
            }
        }
    }

    /** What a find holds: the items of a roll of its loot table, none when the table is not there. */
    List<ItemStack> roll() {
        Optional<LootTable> table = loot.get().named(config.loot());
        if (table.isEmpty()) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>();
        for (LootTable.Rolled rolled : table.get().roll(random)) {
            switch (rolled.entry()) {
                case LootEntry.Item item -> {
                    ItemStack stack = item.item().clone();
                    stack.setAmount(item.amount().pick(random));
                    items.add(stack);
                }
                case LootEntry.Vanilla server -> {
                    Location at = org.bukkit.Bukkit.getWorlds().isEmpty()
                            ? null
                            : org.bukkit.Bukkit.getWorlds().getFirst().getSpawnLocation();
                    if (at != null) {
                        items.addAll(vanilla.roll(server.key(), random, at));
                    }
                }
                case LootEntry.Actions actions -> {
                    // A find has no finder: lines written for one run for nobody.
                }
            }
        }
        return List.copyOf(items);
    }

    /** Pirates board: their creatures come next to each of the crew standing on the vessel. */
    private void board(IslandId vessel) {
        Optional<EntityType> boarder = config.boarderType();
        if (boarder.isEmpty() || config.boarders() == 0) {
            return;
        }
        eachOnline(vessel, player -> {
            Location at = player.getLocation();
            if (at == null) {
                return;
            }
            boolean aboard =
                    islandAt.apply(at).map(island -> island.id().equals(vessel)).orElse(false);
            if (aboard) {
                for (int one = 0; one < config.boarders(); one++) {
                    player.getWorld()
                            .spawnEntity(
                                    at.clone().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2), boarder.get());
                }
            }
        });
    }

    /** A line that says nothing but where the voyage is bound. */
    @SuppressWarnings("UnusedVariable")
    private static TagResolver[] nothingMore(Player player) {
        return new TagResolver[0];
    }

    /** Fires {@code interaction} for the crew online and tells them {@code key}, with {@code <port>} and what {@code more} adds. */
    private void fire(
            IslandId vessel,
            String interaction,
            String key,
            Map<String, String> says,
            Function<Player, TagResolver[]> more) {
        String portId = says.getOrDefault(PORT, "");
        eachOnline(vessel, player -> {
            TagResolver[] extra = more.apply(player);
            TagResolver[] all = java.util.Arrays.copyOf(extra, extra.length + 1);
            all[extra.length] = Names.of(messages, player, "port", "ports", portId);
            messages.send(player, key, all);
            InteractionEffectPlayer fires = effectPlayer;
            if (fires != null) {
                fires.fire(effects, interaction, player, Map.copyOf(says));
            }
        });
    }

    private void eachOnline(IslandId vessel, java.util.function.Consumer<Player> then) {
        islandOf.apply(vessel).ifPresent(island -> {
            for (IslandMember member : island.members().values()) {
                UUID uuid = member.playerUuid().value();
                Player player = online.apply(uuid);
                if (player != null) {
                    scheduler.onEntity(PlayerUuid.of(uuid), () -> then.accept(player));
                }
            }
        });
    }
}
