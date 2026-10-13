package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmlib.menu.runtime.MenuActionContext;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsContracts;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.ItemNames;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Contract;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractBoard;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort.Held;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is contracts}: the deliveries the port a vessel lies in offers, and those its crew holds, in
 * {@code menus/tradewinds-contracts.conf}. A click takes a contract offered here, or delivers one held that is due
 * here. A Bedrock player gets the same file as a form.
 *
 * <p>A delivery runs what the contract's file writes for it, and one the crew did not deliver in time is told to
 * its crew online. Everything the board reads or writes is read off the player's thread.
 */
public final class ContractDesk {

    private static final Logger LOGGER = Logger.getLogger(ContractDesk.class.getName());

    /** The menu file that lists the contracts, and the list it draws them from. */
    static final String FILE = "tradewinds-contracts";

    static final String CONTRACTS = "tradewinds:contracts";

    /** What a click on a contract's tile does to it. */
    record Pick(String contractId, boolean delivering) {}

    /** Where a vessel stands with one contract, as its tile says it. */
    enum Standing {
        /** Offered here and not held: a click takes it. */
        OFFERED("accent"),
        /** Held, and due at the port the vessel lies in: a click delivers it. */
        DUE_HERE("good"),
        /** Held, and due elsewhere. */
        HELD("muted");

        private final String colour;

        Standing(String colour) {
            this.colour = colour;
        }

        String key() {
            return "tradewinds.contracts.states." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final ContractBoard board;
    private final PortMarket market;
    private final TradeWindsConfiguration config;
    private final VesselsPort holds;
    private final PortMarket.Goods goods;
    private final Crew crew;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Clock clock;
    private final Function<UUID, @Nullable Player> online;
    private final Function<IslandId, Optional<Island>> islandOf;
    private volatile @Nullable SkyblockMenuEngine menuEngine;
    private volatile @Nullable InteractionEffectPlayer effectPlayer;
    private volatile InteractionEffects effects = InteractionEffects.none();

    /**
     * @param online the player of a uuid when they are on this server
     * @param islandOf the island of a vessel, read from storage
     */
    @SuppressWarnings("TooManyParameters")
    public ContractDesk(
            ContractBoard board,
            PortMarket market,
            TradeWindsConfiguration config,
            VesselsPort holds,
            PortMarket.Goods goods,
            Crew crew,
            SchedulerPort scheduler,
            Messages messages,
            Clock clock,
            Function<UUID, @Nullable Player> online,
            Function<IslandId, Optional<Island>> islandOf) {
        this.board = Objects.requireNonNull(board, "board");
        this.market = Objects.requireNonNull(market, "market");
        this.config = Objects.requireNonNull(config, "config");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.goods = Objects.requireNonNull(goods, "goods");
        this.crew = Objects.requireNonNull(crew, "crew");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.online = Objects.requireNonNull(online, "online");
        this.islandOf = Objects.requireNonNull(islandOf, "islandOf");
    }

    /** Hands the desk what {@code modules/effects.conf} fires for a vessel's crew. */
    public void useEffects(InteractionEffectPlayer player, InteractionEffects written) {
        this.effects = Objects.requireNonNull(written, "written");
        this.effectPlayer = Objects.requireNonNull(player, "player");
    }

    /** Hands the desk the engine that reads its file, and teaches it what a click on a contract does. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(CONTRACTS);
        engine.action("tradewinds:contract", this::clicked);
    }

    private void clicked(MenuActionContext ctx) {
        String vessel = ctx.context().arguments().getOrDefault("vessel", "");
        if (vessel.isEmpty()) {
            return;
        }
        IslandId id = IslandId.of(UUID.fromString(vessel));
        MenuRow.handle(ctx.context(), Pick.class).ifPresent(pick -> {
            if (pick.delivering()) {
                deliver(ctx.player(), id, pick.contractId());
            } else {
                take(ctx.player(), id, pick.contractId());
            }
        });
    }

    /** Opens the contracts of {@code player}'s vessel and of the port it lies in. */
    public void open(Player player) {
        Result<IslandId, String> aboard = crew.aboard(player);
        if (!aboard.isOk()) {
            messages.send(player, aboard.errorOrThrow());
            return;
        }
        IslandId vessel = aboard.orElseThrow();
        off(player, () -> view(vessel), seen -> {
            SkyblockMenuEngine engine = menuEngine;
            if (engine == null
                    || !engine.open(
                            player,
                            FILE,
                            Map.of("vessel", vessel.value().toString()),
                            Map.of(CONTRACTS, rows(player, seen)))) {
                messages.send(player, "tradewinds.contracts.no_window");
            }
        });
    }

    /** What the window shows: where the vessel lies, what its port offers, what it holds and what its hold has. */
    @SuppressWarnings("ArrayRecordComponent")
    record View(@Nullable String here, List<Contract> offered, List<Held> held, byte[] hold) {}

    private View view(IslandId vessel) {
        String here = market.where(vessel) instanceof PortMarket.Where.Docked docked ? docked.portId() : null;
        List<Contract> offered = here == null ? List.of() : board.offeredAt(here);
        byte[] hold = holds.cargo(vessel).map(VesselsPort.Cargo::items).orElse(new byte[0]);
        return new View(here, offered, board.held(vessel), hold);
    }

    /** The contracts the crew holds, the soonest due first, then those offered here that it does not. */
    List<MenuRow> rows(Player player, View view) {
        List<MenuRow> rows = new ArrayList<>();
        Instant now = clock.instant();
        for (Held held : view.held()) {
            boolean dueHere = held.to().equals(view.here());
            Map<String, String> words = words(player, held.contractId(), held.item(), held.count(), held.to());
            Duration left = Duration.between(now, held.due());
            words.put("left", DurationText.of(messages, player, left.isNegative() ? Duration.ZERO : left));
            words.put("held", Integer.toString(goods.count(view.hold(), held.item())));
            put(words, player, dueHere ? Standing.DUE_HERE : Standing.HELD, held.contractId(), "goods to left held");
            rows.add(new MenuRow(words, new Pick(held.contractId(), true)));
        }
        for (Contract contract : view.offered()) {
            if (view.held().stream().anyMatch(one -> one.contractId().equals(contract.id()))) {
                continue;
            }
            Map<String, String> words = words(player, contract.id(), contract.item(), contract.count(), contract.to());
            words.put("within", DurationText.of(messages, player, contract.within()));
            put(words, player, Standing.OFFERED, contract.id(), "goods to within");
            rows.add(new MenuRow(words, new Pick(contract.id(), false)));
        }
        return List.copyOf(rows);
    }

    private Map<String, String> words(Player player, String id, String item, int count, String to) {
        Map<String, String> words = new HashMap<>();
        Material material = Material.matchMaterial(item);
        words.put("material", material == null || material.isAir() ? "PAPER" : material.name());
        words.put("contract", nameWord(player, "contracts.names", id));
        words.put("item", ItemNames.word(item));
        words.put("count", Integer.toString(count));
        words.put("to", nameWord(player, "ports", to));
        return words;
    }

    /** The tile's state, colour, reward and facts; a contract held elsewhere has no click. */
    private void put(Map<String, String> words, Player player, Standing standing, String id, String facts) {
        words.put("state", "<key:" + standing.key() + ">");
        words.put("colour", standing.colour);
        String reward = config.contracts()
                .previewOf(id)
                // A tile's words are plain: tags written in the file are left out rather than shown.
                .map(written -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .stripTags(messages.words(player, written)))
                .orElse("");
        words.put("reward", reward);
        StringBuilder written = new StringBuilder(facts).append(" state");
        if (reward.isEmpty()) {
            written.append(" -description");
        }
        switch (standing) {
            case OFFERED -> written.append(" action:@tradewinds.contracts.contract.take");
            case DUE_HERE -> written.append(" action:@tradewinds.contracts.contract.deliver");
            case HELD -> written.append(" -action");
        }
        words.put("facts", written.toString());
    }

    /** A name of the language files as a row word, or the id when no file names it. */
    private String nameWord(Player player, String group, String id) {
        String key = "tradewinds." + group + "." + id;
        return messages.raw(player, key) == null ? id : "<key:" + key + ">";
    }

    void take(Player player, IslandId vessel, String contractId) {
        // Taking a contract binds the whole crew, as setting sail does.
        Result<IslandId, String> still = crew.aboard(player, vessel, IslandPermission.SETTINGS_MODIFY);
        if (!still.isOk()) {
            messages.send(player, still.errorOrThrow());
            return;
        }
        off(player, () -> board.take(vessel, PlayerUuid.of(player.getUniqueId()), contractId), taken -> {
            if (!taken.isOk()) {
                messages.send(player, taken.errorOrThrow(), tags(player, contractId, null));
                return;
            }
            Held held = taken.orElseThrow();
            messages.send(player, "tradewinds.contracts.taken", tags(player, contractId, held));
            fire(player, "vessel-contract-taken", says(held));
            open(player);
        });
    }

    void deliver(Player player, IslandId vessel, String contractId) {
        // Delivering takes the island's goods out of the hold.
        Result<IslandId, String> still = crew.aboard(player, vessel, IslandPermission.VAULT_WITHDRAW);
        if (!still.isOk()) {
            messages.send(player, still.errorOrThrow());
            return;
        }
        off(player, () -> board.deliver(vessel, contractId), delivered -> {
            if (!delivered.isOk()) {
                messages.send(player, delivered.errorOrThrow(), tags(player, contractId, null));
                return;
            }
            Held held = delivered.orElseThrow();
            messages.send(player, "tradewinds.contracts.delivered", tags(player, contractId, held));
            Map<String, String> says = says(held);
            fire(player, "vessel-contract-delivered", says);
            reward(player, vessel, config.contracts().rewardOf(contractId), says);
            open(player);
        });
    }

    /** Runs a delivered contract's reward: once for the deliverer, and for every member of the island online. */
    private void reward(
            Player deliverer, IslandId vessel, TradeWindsContracts.Reward reward, Map<String, String> says) {
        InteractionEffectPlayer fires = effectPlayer;
        if (fires == null) {
            return;
        }
        String interaction = "tradewinds contract " + says.get("contract");
        run(fires, interaction, reward.deliverer(), deliverer, says);
        if (reward.members().actions().isEmpty()) {
            return;
        }
        scheduler.async(() -> islandOf.apply(vessel)
                .ifPresent(island ->
                        eachOnline(island, member -> run(fires, interaction, reward.members(), member, says))));
    }

    /** Forgets what is kept for {@code vessel}, an island that is gone. */
    public void forget(IslandId vessel) {
        board.forget(vessel);
    }

    /**
     * Fails every contract not delivered in time, of every vessel, and tells each one's crew online. Called off the
     * main thread, now and then.
     */
    public void failOverdue() {
        for (Held failed : board.failOverdue()) {
            islandOf.apply(failed.vessel())
                    .ifPresent(island -> eachOnline(island, member -> {
                        messages.send(member, "tradewinds.contracts.failed", tags(member, failed.contractId(), failed));
                        fire(member, "vessel-contract-failed", says(failed));
                    }));
        }
    }

    private void eachOnline(Island island, java.util.function.Consumer<Player> then) {
        for (IslandMember member : island.members().values()) {
            UUID uuid = member.playerUuid().value();
            Player player = online.apply(uuid);
            if (player != null) {
                scheduler.onEntity(PlayerUuid.of(uuid), () -> then.accept(player));
            }
        }
    }

    private void fire(Player player, String interaction, Map<String, String> says) {
        InteractionEffectPlayer fires = effectPlayer;
        if (fires != null) {
            fires.fire(effects, interaction, player, says);
        }
    }

    private static void run(
            InteractionEffectPlayer fires,
            String interaction,
            ActionList list,
            Player player,
            Map<String, String> says) {
        if (!list.actions().isEmpty()) {
            fires.fire(new InteractionEffects(Map.of(interaction, list)), interaction, player, says);
        }
    }

    private static Map<String, String> says(Held held) {
        return Map.of(
                "contract", held.contractId(),
                "item", held.item(),
                "count", Integer.toString(held.count()),
                "port", held.to());
    }

    /**
     * {@code <contract>}, {@code <item>}, {@code <count>}, {@code <port>} and {@code <time>} for a chat line, from the
     * contract as held, or as the file writes it when the vessel holds none.
     */
    private TagResolver[] tags(Player player, String contractId, @Nullable Held held) {
        Optional<Contract> written = board.contract(contractId);
        String item = held != null ? held.item() : written.map(Contract::item).orElse("");
        String count = held != null
                ? Integer.toString(held.count())
                : written.map(one -> Integer.toString(one.count())).orElse("");
        String to = held != null ? held.to() : written.map(Contract::to).orElse("");
        Duration left = held == null ? Duration.ZERO : Duration.between(clock.instant(), held.due());
        return new TagResolver[] {
            Names.of(messages, player, "contract", "contracts.names", contractId),
            ItemNames.placeholder("item", item),
            Placeholder.unparsed("count", count),
            Names.of(messages, player, "port", "ports", to),
            Placeholder.unparsed("time", DurationText.of(messages, player, left.isNegative() ? Duration.ZERO : left)),
            Placeholder.unparsed("most", Integer.toString(config.contracts().atOnce()))
        };
    }

    /** Runs {@code work} off the player's thread and hands its answer back to it. */
    private <T> void off(Player player, java.util.function.Supplier<T> work, java.util.function.Consumer<T> then) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            T answer;
            try {
                answer = work.get();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A TradeWinds contract of " + who + " failed", e);
                scheduler.onEntity(who, () -> messages.send(player, "tradewinds.market.busy"));
                return;
            }
            scheduler.onEntity(who, () -> then.accept(answer));
        });
    }
}
