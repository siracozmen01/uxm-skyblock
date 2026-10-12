package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;

/**
 * {@code /isadmin loot mark <table>}: gives the container the operator looks at a loot table of {@code loot/}, filled
 * the first time a player opens it. Every mode's loot is laid this way by hand.
 */
public final class LootAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "loot";

    /** How far off a container may be and still be the one the operator looks at, in blocks. */
    static final int REACH = 6;

    private final Supplier<LootTables> tables;

    public LootAdmin(Supplier<LootTables> tables) {
        this.tables = Objects.requireNonNull(tables, "tables must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("mark", CatalogPermissions.ADMIN_LOOT_MARK)
                        .then(Cmd.argument("table", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    tables.get().all().keySet().stream()
                                            .filter(id -> id.startsWith(builder.getRemainingLowerCase()))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> mark(admin, ctx))));
    }

    private int mark(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        String table = StringArgumentType.getString(ctx, "table").toLowerCase(Locale.ROOT);
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            admin.send(ctx.getSource().getSender(), "isadmin.loot.in_game");
            return Cmd.OK;
        }
        if (tables.get().named(table).isEmpty()) {
            admin.send(
                    player,
                    "isadmin.loot.unknown_table",
                    Placeholder.unparsed("table", table),
                    Placeholder.unparsed(
                            "tables", String.join(", ", tables.get().all().keySet())));
            return Cmd.OK;
        }
        // A command from a player runs on the player's own thread, which owns the blocks within reach.
        Block looked = player.getTargetBlockExact(REACH);
        if (looked == null
                || !LootContainers.give(
                        looked, table, ThreadLocalRandom.current().nextLong())) {
            admin.send(player, "isadmin.loot.no_container");
            return Cmd.OK;
        }
        admin.send(
                player,
                "isadmin.loot.marked",
                Placeholder.unparsed("table", table),
                Placeholder.unparsed("at", looked.getX() + " " + looked.getY() + " " + looked.getZ()));
        return Cmd.OK;
    }
}
