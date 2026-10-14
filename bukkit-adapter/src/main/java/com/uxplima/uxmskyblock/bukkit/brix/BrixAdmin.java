package com.uxplima.uxmskyblock.bukkit.brix;

import java.util.Objects;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;

/**
 * {@code /isadmin brix startcontest [theme]} opens a build contest, on the theme written or on one of the configured
 * themes; {@code /isadmin brix stopcontest} takes the one under way off, with its entries and votes.
 */
public final class BrixAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "brix";

    private final BrixContestPlay contests;

    public BrixAdmin(BrixContestPlay contests) {
        this.contests = Objects.requireNonNull(contests, "contests must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("startcontest", CatalogPermissions.ADMIN_BRIX_STARTCONTEST)
                        .executes(ctx -> {
                            contests.open(ctx.getSource().getSender(), null);
                            return Cmd.OK;
                        })
                        .then(Cmd.argument("theme", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    contests.open(
                                            ctx.getSource().getSender(), StringArgumentType.getString(ctx, "theme"));
                                    return Cmd.OK;
                                })))
                .then(ModeAdminCommands.verb("stopcontest", CatalogPermissions.ADMIN_BRIX_STOPCONTEST)
                        .executes(ctx -> {
                            contests.close(ctx.getSource().getSender());
                            return Cmd.OK;
                        }));
    }
}
