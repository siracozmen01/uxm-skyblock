package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Objects;
import java.util.Optional;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.result.Result;

/**
 * {@code /isadmin tradewinds}: give a vessel a rank, set its standing in a port, or put it in a port at once. A rank
 * is given by setting the vessel's trade to what the rank takes, so the next trade carries on from there.
 */
public final class TradeWindsAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "tradewinds";

    private final PortMarket market;
    private final VesselService vessels;
    private final TradeWindsConfiguration config;

    public TradeWindsAdmin(PortMarket market, VesselService vessels, TradeWindsConfiguration config) {
        this.market = Objects.requireNonNull(market, "market must not be null");
        this.vessels = Objects.requireNonNull(vessels, "vessels must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("setrank", CatalogPermissions.ADMIN_TRADEWINDS_SETRANK)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(Cmd.argument("rank", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            config.ranks().ranks().stream()
                                                    .map(Ranks.Rank::id)
                                                    .filter(id -> id.startsWith(builder.getRemainingLowerCase()))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> setRank(admin, ctx)))))
                .then(ModeAdminCommands.verb("setstanding", CatalogPermissions.ADMIN_TRADEWINDS_SETSTANDING)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(port().then(Cmd.argument("standing", LongArgumentType.longArg(0))
                                        .executes(ctx -> setStanding(admin, ctx))))))
                .then(ModeAdminCommands.verb("moveport", CatalogPermissions.ADMIN_TRADEWINDS_MOVEPORT)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(port().executes(ctx -> movePort(admin, ctx)))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> port() {
        return Cmd.argument("port", StringArgumentType.word()).suggests((ctx, builder) -> {
            config.ports().stream()
                    .map(Port::id)
                    .filter(id -> id.startsWith(builder.getRemainingLowerCase()))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        });
    }

    private int setRank(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        String rank = StringArgumentType.getString(ctx, "rank");
        return onVessel(admin, ctx, (sender, target, vessel) -> {
            Result<Ranks.Rank, String> given = market.setRank(vessel, rank);
            if (!given.isOk()) {
                admin.send(
                        sender,
                        given.errorOrThrow(),
                        Placeholder.unparsed("target", target),
                        Placeholder.unparsed("asked", rank),
                        Placeholder.unparsed(
                                "ranks",
                                String.join(
                                        ", ",
                                        config.ranks().ranks().stream()
                                                .map(Ranks.Rank::id)
                                                .toList())));
                return;
            }
            admin.send(
                    sender,
                    "isadmin.tradewinds.ranked",
                    Placeholder.unparsed("target", target),
                    Names.of(
                            admin.messages(),
                            sender,
                            "vessel_rank",
                            "ranks",
                            given.orElseThrow().id()),
                    Placeholder.unparsed(
                            "rows", Integer.toString(given.orElseThrow().holdRows())));
        });
    }

    private int setStanding(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        long standing = LongArgumentType.getLong(ctx, "standing");
        return onPort(admin, ctx, (sender, target, vessel, port) -> {
            market.setStanding(vessel, port, standing);
            admin.send(
                    sender,
                    "isadmin.tradewinds.standing",
                    Placeholder.unparsed("target", target),
                    Names.of(admin.messages(), sender, "port", "ports", port.id()),
                    Placeholder.unparsed("standing", Long.toString(standing)));
        });
    }

    private int movePort(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        return onPort(admin, ctx, (sender, target, vessel, port) -> {
            Result<java.time.Instant, String> moved = market.moveTo(vessel, port);
            admin.send(
                    sender,
                    moved.isOk() ? "isadmin.tradewinds.moved" : "isadmin.tradewinds.held_elsewhere",
                    Placeholder.unparsed("target", target),
                    Names.of(admin.messages(), sender, "port", "ports", port.id()));
        });
    }

    /** What a verb does once it knows the vessel. */
    private interface OnVessel {
        void run(Audience sender, String target, IslandId vessel);
    }

    /** What a verb does once it knows the vessel and the port. */
    private interface OnPort {
        void run(Audience sender, String target, IslandId vessel, Port port);
    }

    private int onPort(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx, OnPort then) {
        String portId = StringArgumentType.getString(ctx, "port");
        Optional<Port> port = config.port(portId);
        if (port.isEmpty()) {
            admin.send(
                    ctx.getSource().getSender(),
                    "isadmin.tradewinds.unknown_port",
                    Placeholder.unparsed("port", portId),
                    Placeholder.unparsed(
                            "ports",
                            String.join(
                                    ", ", config.ports().stream().map(Port::id).toList())));
            return Cmd.OK;
        }
        return onVessel(admin, ctx, (sender, target, vessel) -> then.run(sender, target, vessel, port.get()));
    }

    private int onVessel(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx, OnVessel then) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> {
            if (!vessels.isVessel(island)) {
                admin.send(source.getSender(), "isadmin.tradewinds.not_vessel", Placeholder.unparsed("target", target));
                return;
            }
            then.run(source.getSender(), target, island);
        });
        return Cmd.OK;
    }
}
