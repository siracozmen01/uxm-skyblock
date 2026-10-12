package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.ItemNames;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.MoneyText;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockTiles;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * What the crew of a TradeWinds vessel does at sea and in port: {@code /is sail} chooses the next port and
 * {@code /is market} trades at the port the vessel lies in.
 *
 * <p>A Java player gets a chest window and a Bedrock player a native form with the same choices in the
 * same order. Whether the player still crews the vessel they stand on is asked again on every choice.
 * Everything the market reads or writes is read off the player's thread.
 */
public final class Harbour {

    private static final Logger LOGGER = Logger.getLogger(Harbour.class.getName());

    private final PortMarket market;
    private final TradeWindsConfiguration config;
    private final VesselsPort holds;
    private final PortMarket.Goods goods;
    private final Crew crew;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Clock clock;
    private @Nullable BedrockFormService forms;
    private volatile @Nullable SkyblockMenuEngine menuEngine;
    private volatile @Nullable InteractionEffectPlayer effectPlayer;
    private volatile InteractionEffects effects = InteractionEffects.none();

    /** The menu files that draw the ports and the market, and the lists they draw them from. */
    static final String SAIL_FILE = "tradewinds-sail";

    static final String PORTS = "tradewinds:ports";

    static final String MARKET_FILE = "tradewinds-market";

    static final String GOODS = "tradewinds:goods";

    /** One good of one port's market, which is what a buy or a sell in the file acts on. */
    record Choice(Port port, Port.Good good) {}

    @SuppressWarnings("TooManyParameters")
    public Harbour(
            PortMarket market,
            TradeWindsConfiguration config,
            VesselsPort holds,
            PortMarket.Goods goods,
            Crew crew,
            SchedulerPort scheduler,
            Messages messages,
            Clock clock) {
        this.market = Objects.requireNonNull(market, "market");
        this.config = Objects.requireNonNull(config, "config");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.goods = Objects.requireNonNull(goods, "goods");
        this.crew = Objects.requireNonNull(crew, "crew");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Hands the harbour what {@code modules/effects.conf} fires for a vessel's crew. Without it a voyage fires
     * nothing.
     */
    public void useEffects(InteractionEffectPlayer player, InteractionEffects written) {
        this.effects = Objects.requireNonNull(written, "written");
        this.effectPlayer = Objects.requireNonNull(player, "player");
    }

    /**
     * Hands the harbour the engine that reads {@code menus/tradewinds-sail.conf} and
     * {@code menus/tradewinds-market.conf}, and teaches it what choosing a port and trading a good do. The
     * windows built here stay as the answer to a file that is missing or will not parse.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(PORTS);
        engine.handedList(GOODS);
        engine.action(
                "tradewinds:set-sail",
                ctx -> MenuRow.handle(ctx.context(), Port.class)
                        .ifPresent(port -> vesselOf(ctx.context()).ifPresent(vessel -> {
                            ctx.player().closeInventory();
                            setSail(ctx.player(), vessel, port);
                        })));
        engine.action("tradewinds:buy", ctx -> tradeFromFile(ctx, true));
        engine.action("tradewinds:sell", ctx -> tradeFromFile(ctx, false));
    }

    private void tradeFromFile(com.uxplima.uxmlib.menu.runtime.MenuActionContext ctx, boolean buying) {
        MenuRow.handle(ctx.context(), Choice.class)
                .filter(choice -> buying ? choice.good().sold() : choice.good().bought())
                .ifPresent(choice -> vesselOf(ctx.context())
                        .ifPresent(vessel -> trade(ctx.player(), vessel, choice.port(), choice.good(), buying)));
    }

    private static Optional<IslandId> vesselOf(com.uxplima.uxmlib.menu.runtime.MenuContext ctx) {
        String vessel = ctx.arguments().getOrDefault("vessel", "");
        return vessel.isEmpty() ? Optional.empty() : Optional.of(IslandId.of(java.util.UUID.fromString(vessel)));
    }

    /** The port's name as a row word: the language file's line for it, or its id when no file names it. */
    private String portWord(Player player, Port port) {
        String key = "tradewinds.ports." + port.id();
        return messages.raw(player, key) == null ? port.id() : "<key:" + key + ">";
    }

    /** One row per port, in the order the file writes them, the one the vessel lies in marked. */
    List<MenuRow> portRows(Player player, PortMarket.Where where) {
        List<MenuRow> rows = new ArrayList<>();
        for (Port port : config.ports()) {
            String state = state(port, where);
            rows.add(new MenuRow(
                    Map.of(
                            "material", material(config.icon(port)).name(),
                            "port", portWord(player, port),
                            "time", DurationText.of(messages, player, Duration.ofSeconds(port.voyageSeconds())),
                            "left", time(player, arrivesAt(where)),
                            "where", "<key:" + state + ">",
                            // The port the vessel lies in is no voyage, so its tile says nothing about setting sail.
                            "facts", "voyage state:where" + ("tradewinds.sail.here".equals(state) ? " -action" : "")),
                    port));
        }
        return List.copyOf(rows);
    }

    /** One row per good the port trades: its prices for a lot, what the hold has, and the clicks it takes. */
    private static List<MenuRow> goodRows(Stall stall) {
        List<MenuRow> rows = new ArrayList<>();
        for (Offer offer : stall.offers()) {
            rows.add(new MenuRow(
                    Map.of(
                            "material", material(offer.good().item()).name(),
                            "item", ItemNames.word(offer.good().item()),
                            "lot", Integer.toString(offer.good().lot()),
                            "buy", MoneyText.of(offer.asks() * offer.good().lot()),
                            "sell", MoneyText.of(offer.pays() * offer.good().lot()),
                            "held", Integer.toString(offer.held()),
                            "facts", factsOf(offer)),
                    new Choice(stall.port(), offer.good())));
        }
        return List.copyOf(rows);
    }

    /** A port that only sells, or only buys, draws the one price and the one click it has. */
    private static String factsOf(Offer offer) {
        boolean sold = offer.good().sold();
        boolean bought = offer.good().bought();
        StringBuilder facts = new StringBuilder();
        if (sold) {
            facts.append("buy ");
        }
        if (bought) {
            facts.append("sell ");
        }
        facts.append("held");
        if (sold != bought) {
            facts.append(
                    sold ? " action:@tradewinds.market.good.buy_only" : " action:@tradewinds.market.good.sell_only");
        } else if (!sold) {
            facts.append(" -action");
        }
        return facts.toString();
    }

    /** Draws a native form for a Bedrock player, while the server has Floodgate. */
    public void useForms(@Nullable BedrockFormService forms) {
        this.forms = forms;
    }

    /** Opens the ports {@code player}'s vessel can sail for. */
    public void sail(Player player) {
        Result<IslandId, String> aboard = crew.aboard(player);
        if (!aboard.isOk()) {
            messages.send(player, aboard.errorOrThrow());
            return;
        }
        IslandId vessel = aboard.orElseThrow();
        if (config.ports().isEmpty()) {
            messages.send(player, "tradewinds.sail.no_ports");
            return;
        }
        off(player, () -> market.where(vessel), where -> openSail(player, vessel, where));
    }

    /** Opens the market of the port {@code player}'s vessel lies in. */
    public void market(Player player) {
        Result<IslandId, String> aboard = crew.aboard(player, IslandPermission.SHOP_ACCESS);
        if (!aboard.isOk()) {
            messages.send(player, aboard.errorOrThrow());
            return;
        }
        IslandId vessel = aboard.orElseThrow();
        off(player, () -> stall(vessel), found -> {
            if (!found.isOk()) {
                messages.send(player, found.errorOrThrow());
                return;
            }
            openMarket(player, vessel, found.orElseThrow());
        });
    }

    /** What a trade made, and the vessel's rank before and after it. */
    private record Trade(Result<PortMarket.Deal, String> made, Ranks.Rank before, Ranks.Rank after) {}

    /** One good of the market as a vessel sees it: its prices to that vessel and how many its hold has. */
    private record Offer(Port.Good good, long pays, long asks, int held) {}

    /** The market of the port a vessel lies in, as the vessel sees it. */
    private record Stall(Port port, List<Offer> offers) {}

    private Result<Stall, String> stall(IslandId vessel) {
        PortMarket.Where where = market.where(vessel);
        if (!(where instanceof PortMarket.Where.Docked docked)) {
            return Result.err(
                    where instanceof PortMarket.Where.Sailing
                            ? "tradewinds.market.at_sea"
                            : "tradewinds.market.not_docked");
        }
        Optional<Port> port = config.port(docked.portId());
        if (port.isEmpty()) {
            // A port the operator took out of the file: the vessel has to sail on.
            return Result.err("tradewinds.market.not_docked");
        }
        byte[] hold = holds.cargo(vessel).map(VesselsPort.Cargo::items).orElse(new byte[0]);
        List<Offer> offers = new ArrayList<>();
        for (Port.Good good : port.get().goods()) {
            offers.add(new Offer(
                    good,
                    market.pays(vessel, port.get(), good),
                    market.asks(vessel, port.get(), good),
                    goods.count(hold, good.item())));
        }
        return Result.ok(new Stall(port.get(), offers));
    }

    private void openSail(Player player, IslandId vessel, PortMarket.Where where) {
        BedrockFormService bedrock = forms;
        if (bedrock != null && bedrock.isBedrock(player)) {
            List<BedrockFormService.Choice> choices = new ArrayList<>();
            for (Port port : config.ports()) {
                choices.add(new BedrockFormService.Choice(
                        legacy(messages.renderPlain(
                                player, "tradewinds.sail.form_entry", portTags(player, port, where))),
                        // A form answers off the player's thread: the choice is carried back to it.
                        () -> onThread(player, () -> setSail(player, vessel, port))));
            }
            bedrock.openChoiceForm(player, "tradewinds.sail.title", "tradewinds.sail.form_body", choices);
            return;
        }
        SkyblockMenuEngine engine = this.menuEngine;
        if (engine != null
                && engine.open(
                        player,
                        SAIL_FILE,
                        Map.of("vessel", vessel.value().toString()),
                        Map.of(PORTS, portRows(player, where)))) {
            return;
        }
        int rows = Math.min(6, Math.max(1, (config.ports().size() + 8) / 9));
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "tradewinds.sail.title")))
                .rows(rows)
                .build();
        SkyblockTiles tiles = new SkyblockTiles(messages);
        int slot = 0;
        for (Port port : config.ports()) {
            if (slot >= rows * 9) {
                break;
            }
            String state = state(port, where);
            TagResolver[] tags = withTag(
                    portTags(player, port, where),
                    Placeholder.component(
                            "entry_where", messages.renderPlain(player, state, portTags(player, port, where))));
            // The port the vessel lies in is no voyage, so its tile says nothing about setting sail.
            String line = "tile:3 @tradewinds.sail.port voyage state:where"
                    + ("tradewinds.sail.here".equals(state) ? " -action" : "");
            gui.set(slot++, GuiItem.button(tiles.item(material(config.icon(port)), player, line, tags), event -> {
                event.setCancelled(true);
                player.closeInventory();
                setSail(player, vessel, port);
            }));
        }
        gui.open(player);
    }

    void setSail(Player player, IslandId vessel, Port port) {
        // Steering the vessel decides for the whole crew, as an island setting does.
        Result<IslandId, String> still = crew.aboard(player, vessel, IslandPermission.SETTINGS_MODIFY);
        if (!still.isOk()) {
            messages.send(player, still.errorOrThrow());
            return;
        }
        off(player, () -> market.sail(vessel, port), sailed -> {
            if (!sailed.isOk()) {
                messages.send(player, sailed.errorOrThrow(), name(player, port));
                return;
            }
            Instant arrives = sailed.orElseThrow();
            messages.send(
                    player,
                    "tradewinds.sail.sailed",
                    name(player, port),
                    Placeholder.unparsed("time", time(player, arrives)));
            InteractionEffectPlayer fires = effectPlayer;
            if (fires != null) {
                fires.fire(
                        effects,
                        "vessel-sailed",
                        player,
                        Map.of(
                                "port",
                                port.id(),
                                "seconds",
                                Long.toString(Math.max(
                                        0,
                                        Duration.between(clock.instant(), arrives)
                                                .toSeconds()))));
            }
        });
    }

    private void openMarket(Player player, IslandId vessel, Stall stall) {
        BedrockFormService bedrock = forms;
        if (bedrock != null && bedrock.isBedrock(player)) {
            List<BedrockFormService.Choice> choices = new ArrayList<>();
            for (Offer offer : stall.offers()) {
                if (offer.good().sold()) {
                    choices.add(new BedrockFormService.Choice(
                            legacy(messages.renderPlain(player, "tradewinds.market.form_buy", offerTags(offer))),
                            () -> onThread(player, () -> trade(player, vessel, stall.port(), offer.good(), true))));
                }
                if (offer.good().bought()) {
                    choices.add(new BedrockFormService.Choice(
                            legacy(messages.renderPlain(player, "tradewinds.market.form_sell", offerTags(offer))),
                            () -> onThread(player, () -> trade(player, vessel, stall.port(), offer.good(), false))));
                }
            }
            bedrock.openChoiceForm(player, "tradewinds.market.form_title", "tradewinds.market.form_body", choices);
            return;
        }
        SkyblockMenuEngine engine = this.menuEngine;
        if (engine != null
                && engine.open(
                        player,
                        MARKET_FILE,
                        Map.of("vessel", vessel.value().toString(), "port", portWord(player, stall.port())),
                        Map.of(GOODS, goodRows(stall)))) {
            return;
        }
        int rows = Math.min(6, Math.max(1, (stall.offers().size() + 8) / 9));
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(
                        player,
                        "tradewinds.market.title",
                        Names.of(
                                messages,
                                player,
                                "argument_port",
                                "ports",
                                stall.port().id()))))
                .rows(rows)
                .build();
        SkyblockTiles tiles = new SkyblockTiles(messages);
        int slot = 0;
        for (Offer offer : stall.offers()) {
            if (slot >= rows * 9) {
                break;
            }
            ItemStack icon = tiles.item(
                    material(offer.good().item()),
                    player,
                    "tile:money @tradewinds.market.good " + factsOf(offer),
                    offerTags(offer));
            gui.set(slot++, GuiItem.button(icon, event -> {
                event.setCancelled(true);
                boolean buying = event.isLeftClick();
                if (buying ? offer.good().sold() : offer.good().bought()) {
                    trade(player, vessel, stall.port(), offer.good(), buying);
                }
            }));
        }
        gui.open(player);
    }

    private static TagResolver[] withTag(TagResolver[] tags, TagResolver more) {
        TagResolver[] all = java.util.Arrays.copyOf(tags, tags.length + 1);
        all[tags.length] = more;
        return all;
    }

    void trade(Player player, IslandId vessel, Port port, Port.Good good, boolean buying) {
        // Buying spends the island bank; selling takes the island's goods out of the hold.
        Result<IslandId, String> still = crew.aboard(
                player,
                vessel,
                IslandPermission.SHOP_ACCESS,
                buying ? IslandPermission.BANK_WITHDRAW : IslandPermission.VAULT_WITHDRAW);
        if (!still.isOk()) {
            messages.send(player, still.errorOrThrow());
            player.closeInventory();
            return;
        }
        PlayerUuid actor = PlayerUuid.of(player.getUniqueId());
        off(
                player,
                () -> {
                    Ranks.Rank before = market.rank(vessel);
                    Result<PortMarket.Deal, String> made =
                            buying ? market.buy(vessel, actor, port, good) : market.sell(vessel, actor, port, good);
                    return new Trade(made, before, market.rank(vessel));
                },
                trade -> {
                    Result<PortMarket.Deal, String> done = trade.made();
                    if (!done.isOk()) {
                        messages.send(player, done.errorOrThrow(), ItemNames.placeholder("item", good.item()));
                        return;
                    }
                    PortMarket.Deal deal = done.orElseThrow();
                    messages.send(
                            player,
                            buying ? "tradewinds.market.bought" : "tradewinds.market.sold",
                            ItemNames.placeholder("item", deal.item()),
                            Placeholder.unparsed("count", Integer.toString(deal.count())),
                            Placeholder.unparsed("price", MoneyText.of(deal.amount())));
                    if (!trade.after().equals(trade.before())) {
                        messages.send(
                                player,
                                "tradewinds.market.ranked_up",
                                Names.of(
                                        messages,
                                        player,
                                        "vessel_rank",
                                        "ranks",
                                        trade.after().id()),
                                Placeholder.unparsed(
                                        "rows", Integer.toString(trade.after().holdRows())));
                    }
                    // Prices and the hold moved: the window shows them as they are now.
                    if (forms == null || !Objects.requireNonNull(forms).isBedrock(player)) {
                        market(player);
                    }
                });
    }

    private void onThread(Player player, Runnable work) {
        scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), work);
    }

    /** Runs {@code work} off the player's thread and hands its answer back to it. */
    private <T> void off(Player player, java.util.function.Supplier<T> work, java.util.function.Consumer<T> then) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            T answer;
            try {
                answer = work.get();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A TradeWinds voyage or trade of " + who + " failed", e);
                scheduler.onEntity(who, () -> messages.send(player, "tradewinds.market.busy"));
                return;
            }
            scheduler.onEntity(who, () -> then.accept(answer));
        });
    }

    private TagResolver[] portTags(Player player, Port port, PortMarket.Where where) {
        String voyage = DurationText.of(messages, player, Duration.ofSeconds(port.voyageSeconds()));
        String left = time(player, arrivesAt(where));
        // The chat lines name a port, a voyage and a time left as <port>, <time> and <left>; a tile names
        // the same values as the entry it draws.
        return new TagResolver[] {
            name(player, port),
            Placeholder.unparsed("time", voyage),
            Placeholder.unparsed("left", left),
            Names.of(messages, player, "entry_port", "ports", port.id()),
            Placeholder.unparsed("entry_time", voyage),
            Placeholder.unparsed("entry_left", left)
        };
    }

    private Instant arrivesAt(PortMarket.Where where) {
        return where instanceof PortMarket.Where.Sailing sailing ? sailing.arrivesAt() : clock.instant();
    }

    private static String state(Port port, PortMarket.Where where) {
        return switch (where) {
            case PortMarket.Where.Docked docked when docked.portId().equals(port.id()) -> "tradewinds.sail.here";
            case PortMarket.Where.Sailing sailing when sailing.portId().equals(port.id()) -> "tradewinds.sail.bound";
            default -> "tradewinds.sail.away";
        };
    }

    private static TagResolver[] offerTags(Offer offer) {
        String lot = Integer.toString(offer.good().lot());
        String buy = MoneyText.of(offer.asks() * offer.good().lot());
        String sell = MoneyText.of(offer.pays() * offer.good().lot());
        String held = Integer.toString(offer.held());
        return new TagResolver[] {
            ItemNames.placeholder("item", offer.good().item()),
            Placeholder.unparsed("lot", lot),
            Placeholder.unparsed("buy", buy),
            Placeholder.unparsed("sell", sell),
            Placeholder.unparsed("held", held),
            ItemNames.placeholder("entry_item", offer.good().item()),
            Placeholder.unparsed("entry_lot", lot),
            Placeholder.unparsed("entry_buy", buy),
            Placeholder.unparsed("entry_sell", sell),
            Placeholder.unparsed("entry_held", held)
        };
    }

    /** {@code <port>}, the port's name in the reader's language, or its key when no file names it. */
    private TagResolver name(Player player, Port port) {
        return Names.of(messages, player, "port", "ports", port.id());
    }

    private String time(Player player, Instant until) {
        Duration left = Duration.between(clock.instant(), until);
        return DurationText.of(messages, player, left.isNegative() ? Duration.ZERO : left);
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(name);
        return material == null || material.isAir() ? Material.OAK_BOAT : material;
    }

    private static String legacy(Component line) {
        return com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(line);
    }
}
