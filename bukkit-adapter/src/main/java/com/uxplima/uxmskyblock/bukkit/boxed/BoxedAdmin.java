package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * {@code /isadmin boxed}: set how far across an island's box is, or push it out. The island's edge moves with it, as
 * it does when an advancement grows the box.
 */
public final class BoxedAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "boxed";

    private final BoxedService service;
    private final Consumer<IslandId> moveTheEdge;

    public BoxedAdmin(BoxedService service, Consumer<IslandId> moveTheEdge) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.moveTheEdge = Objects.requireNonNull(moveTheEdge, "moveTheEdge must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("setsize", CatalogPermissions.ADMIN_BOXED_SETSIZE)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(Cmd.argument("size", IntegerArgumentType.integer(1))
                                        .executes(ctx -> setSize(admin, ctx)))))
                .then(ModeAdminCommands.verb("grow", CatalogPermissions.ADMIN_BOXED_GROW)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(Cmd.argument("blocks", IntegerArgumentType.integer(1))
                                        .executes(ctx -> change(
                                                admin,
                                                ctx,
                                                island -> service.grant(
                                                        island, IntegerArgumentType.getInteger(ctx, "blocks")))))));
    }

    private int setSize(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        int size = IntegerArgumentType.getInteger(ctx, "size");
        BoxRules rules = service.rules();
        // A box reaches as far each way from its middle block, so it is across twice that and one.
        int radius = (size - 1) / 2;
        if (radius < rules.startRadius() || radius > rules.maxRadius()) {
            admin.send(
                    ctx.getSource().getSender(),
                    "isadmin.boxed.size_range",
                    Placeholder.unparsed("size", Integer.toString(size)),
                    Placeholder.unparsed("min", Integer.toString(across(rules.startRadius()))),
                    Placeholder.unparsed("max", Integer.toString(across(rules.maxRadius()))));
            return Cmd.OK;
        }
        return change(admin, ctx, island -> service.resize(island, radius));
    }

    private int change(
            ModeAdminCommands admin,
            CommandContext<CommandSourceStack> ctx,
            Function<IslandId, Optional<BoxedService.Grown>> how) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> {
            Optional<BoxedService.Grown> changed = how.apply(island);
            if (changed.isEmpty()) {
                admin.send(source.getSender(), "isadmin.boxed.not_boxed", Placeholder.unparsed("target", target));
                return;
            }
            moveTheEdge.accept(island);
            admin.send(
                    source.getSender(),
                    "isadmin.boxed.changed",
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed(
                            "was", Integer.toString(across(changed.get().fromRadius()))),
                    Placeholder.unparsed(
                            "size", Integer.toString(across(changed.get().toRadius()))));
        });
        return Cmd.OK;
    }

    private static int across(int radius) {
        return 2 * radius + 1;
    }
}
