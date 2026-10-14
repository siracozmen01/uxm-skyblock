package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.ItemNames;
import com.uxplima.uxmskyblock.bukkit.i18n.MoneyText;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockTiles;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;

/**
 * A port's market window: the stall an island's vessel trades at, its goods and their prices, and one trade bought or sold. Split out of {@link Harbour}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class HarbourMarket {

    private final Harbour host;

    HarbourMarket(Harbour host) {
        this.host = host;
    }

    void openMarket(Player player, IslandId vessel, Harbour.Stall stall) {
        BedrockFormService bedrock = host.forms;
        if (bedrock != null && bedrock.isBedrock(player)) {
            List<BedrockFormService.Choice> choices = new ArrayList<>();
            for (Harbour.Offer offer : stall.offers()) {
                if (offer.good().sold()) {
                    choices.add(new BedrockFormService.Choice(
                            Harbour.legacy(host.messages.renderPlain(
                                    player, "tradewinds.market.form_buy", Harbour.offerTags(offer))),
                            () -> host.onThread(
                                    player, () -> trade(player, vessel, stall.port(), offer.good(), true))));
                }
                if (offer.good().bought()) {
                    choices.add(new BedrockFormService.Choice(
                            Harbour.legacy(host.messages.renderPlain(
                                    player, "tradewinds.market.form_sell", Harbour.offerTags(offer))),
                            () -> host.onThread(
                                    player, () -> trade(player, vessel, stall.port(), offer.good(), false))));
                }
            }
            bedrock.openChoiceForm(player, "tradewinds.market.form_title", "tradewinds.market.form_body", choices);
            return;
        }
        SkyblockMenuEngine engine = host.menuEngine;
        if (engine != null
                && engine.open(
                        player,
                        Harbour.MARKET_FILE,
                        Map.of("vessel", vessel.value().toString(), "port", host.portWord(player, stall.port())),
                        Map.of(Harbour.GOODS, Harbour.goodRows(stall)))) {
            return;
        }
        int rows = Math.min(6, Math.max(1, (stall.offers().size() + 8) / 9));
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(host.messages.renderPlain(
                        player,
                        "tradewinds.market.title",
                        Names.of(
                                host.messages,
                                player,
                                "argument_port",
                                "ports",
                                stall.port().id()))))
                .rows(rows)
                .build();
        SkyblockTiles tiles = new SkyblockTiles(host.messages);
        int slot = 0;
        for (Harbour.Offer offer : stall.offers()) {
            if (slot >= rows * 9) {
                break;
            }
            ItemStack icon = tiles.item(
                    Harbour.material(offer.good().item()),
                    player,
                    "tile:money @tradewinds.market.good " + Harbour.factsOf(offer),
                    Harbour.offerTags(offer));
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

    void trade(Player player, IslandId vessel, Port port, Port.Good good, boolean buying) {
        // Buying spends the island bank; selling takes the island's goods out of the hold.
        Result<IslandId, String> still = host.crew.aboard(
                player,
                vessel,
                IslandPermission.SHOP_ACCESS,
                buying ? IslandPermission.BANK_WITHDRAW : IslandPermission.VAULT_WITHDRAW);
        if (!still.isOk()) {
            host.messages.send(player, still.errorOrThrow());
            player.closeInventory();
            return;
        }
        PlayerUuid actor = PlayerUuid.of(player.getUniqueId());
        host.off(
                player,
                () -> {
                    Ranks.Rank before = host.market.rank(vessel);
                    Result<PortMarket.Deal, String> made = buying
                            ? host.market.buy(vessel, actor, port, good)
                            : host.market.sell(vessel, actor, port, good);
                    return new Harbour.Trade(made, before, host.market.rank(vessel));
                },
                trade -> {
                    Result<PortMarket.Deal, String> done = trade.made();
                    if (!done.isOk()) {
                        host.messages.send(player, done.errorOrThrow(), ItemNames.placeholder("item", good.item()));
                        return;
                    }
                    PortMarket.Deal deal = done.orElseThrow();
                    host.messages.send(
                            player,
                            buying ? "tradewinds.market.bought" : "tradewinds.market.sold",
                            ItemNames.placeholder("item", deal.item()),
                            Placeholder.unparsed("count", Integer.toString(deal.count())),
                            Placeholder.unparsed("price", MoneyText.of(deal.amount())));
                    if (!trade.after().equals(trade.before())) {
                        host.messages.send(
                                player,
                                "tradewinds.market.ranked_up",
                                Names.of(
                                        host.messages,
                                        player,
                                        "vessel_rank",
                                        "ranks",
                                        trade.after().id()),
                                Placeholder.unparsed(
                                        "rows", Integer.toString(trade.after().holdRows())));
                    }
                    // Prices and the hold moved: the window shows them as they are now.
                    if (host.forms == null
                            || !Objects.requireNonNull(host.forms).isBedrock(player)) {
                        host.market(player);
                    }
                });
    }

    Result<Harbour.Stall, String> stall(IslandId vessel) {
        PortMarket.Where where = host.market.where(vessel);
        if (!(where instanceof PortMarket.Where.Docked docked)) {
            return Result.err(
                    where instanceof PortMarket.Where.Sailing
                            ? "tradewinds.market.at_sea"
                            : "tradewinds.market.not_docked");
        }
        Optional<Port> port = host.config.port(docked.portId());
        if (port.isEmpty()) {
            // A port the operator took out of the file: the vessel has to sail on.
            return Result.err("tradewinds.market.not_docked");
        }
        byte[] hold = host.holds.cargo(vessel).map(VesselsPort.Cargo::items).orElse(new byte[0]);
        List<Harbour.Offer> offers = new ArrayList<>();
        for (Port.Good good : port.get().goods()) {
            offers.add(new Harbour.Offer(
                    good,
                    host.market.pays(vessel, port.get(), good),
                    host.market.asks(vessel, port.get(), good),
                    host.goods.count(hold, good.item()),
                    good.bought() && host.market.wanted(port.get(), good.item())
                            ? Math.round(host.config.demand().bonus() * 100)
                            : 0));
        }
        return Result.ok(new Harbour.Stall(port.get(), offers));
    }
}
