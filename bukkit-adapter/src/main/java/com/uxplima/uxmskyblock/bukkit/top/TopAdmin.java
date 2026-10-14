package com.uxplima.uxmskyblock.bukkit.top;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;

/**
 * {@code /isadmin top}: places a display showing one place of a leaderboard, takes one away, and lists them. A sign
 * or a head is the one the operator looks at; a line of text stands where the operator stands.
 */
public final class TopAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "top";

    /** How far off a sign or a head may be and still be the one looked at, in blocks. */
    static final int REACH = 6;

    private final TopDisplayBeat beat;

    public TopAdmin(TopDisplayBeat beat) {
        this.beat = Objects.requireNonNull(beat, "beat must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("add", CatalogPermissions.ADMIN_TOP_ADD)
                        .then(Cmd.argument("board", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    Arrays.stream(LeaderboardCategory.values())
                                            .map(board -> board.name().toLowerCase(Locale.ROOT))
                                            .filter(board -> board.startsWith(builder.getRemainingLowerCase()))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .then(Cmd.argument("rank", IntegerArgumentType.integer())
                                        .then(Cmd.argument("kind", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (TopDisplay.Kind kind : TopDisplay.Kind.values()) {
                                                        builder.suggest(kind.word());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> add(admin, ctx))))))
                .then(ModeAdminCommands.verb("remove", CatalogPermissions.ADMIN_TOP_REMOVE)
                        .executes(ctx -> remove(admin, ctx)))
                .then(ModeAdminCommands.verb("list", CatalogPermissions.ADMIN_TOP_LIST)
                        .executes(ctx -> list(admin, ctx)));
    }

    private int add(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            admin.send(ctx.getSource().getSender(), "isadmin.top.in_game");
            return Cmd.OK;
        }
        String asked = StringArgumentType.getString(ctx, "board");
        Optional<LeaderboardCategory> board = Arrays.stream(LeaderboardCategory.values())
                .filter(each -> each.name().equalsIgnoreCase(asked))
                .findFirst();
        if (board.isEmpty()) {
            admin.send(
                    player,
                    "isadmin.top.unknown_board",
                    Placeholder.unparsed("asked", asked),
                    Placeholder.unparsed(
                            "boards",
                            Arrays.stream(LeaderboardCategory.values())
                                    .map(each -> each.name().toLowerCase(Locale.ROOT))
                                    .collect(Collectors.joining(", "))));
            return Cmd.OK;
        }
        int rank = IntegerArgumentType.getInteger(ctx, "rank");
        if (rank < 1 || rank > TopDisplay.MOST_RANK) {
            admin.send(
                    player,
                    "isadmin.top.bad_rank",
                    Placeholder.unparsed("most", Integer.toString(TopDisplay.MOST_RANK)));
            return Cmd.OK;
        }
        Optional<TopDisplay.Kind> kind = TopDisplay.Kind.named(StringArgumentType.getString(ctx, "kind"));
        if (kind.isEmpty()) {
            admin.send(player, "isadmin.top.bad_kind");
            return Cmd.OK;
        }
        Optional<Block> on = blockFor(player, kind.get());
        if (on.isEmpty()) {
            admin.send(player, "isadmin.top.no_block");
            return Cmd.OK;
        }
        Block block = on.get();
        TopDisplay display = new TopDisplay(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), kind.get(), board.get(), rank);
        beat.displays().add(display);
        admin.scheduler().async(() -> beat.refresh(display));
        admin.send(
                player,
                "isadmin.top.added",
                Placeholder.unparsed("kind", kind.get().word()),
                Placeholder.unparsed("at", display.place()),
                Placeholder.unparsed("position", Integer.toString(rank)),
                Placeholder.unparsed("board", board.get().name().toLowerCase(Locale.ROOT)));
        return Cmd.OK;
    }

    /** The block a display of {@code kind} goes on: the sign or head looked at, or where the player stands. */
    static Optional<Block> blockFor(Player player, TopDisplay.Kind kind) {
        if (kind == TopDisplay.Kind.TEXT) {
            Location standing = player.getLocation();
            return standing == null ? Optional.empty() : Optional.of(standing.getBlock());
        }
        // A command from a player runs on the player's own thread, which owns the blocks within reach.
        Block looked = player.getTargetBlockExact(REACH);
        if (looked == null) {
            return Optional.empty();
        }
        boolean fits =
                kind == TopDisplay.Kind.SIGN ? looked.getState() instanceof Sign : looked.getState() instanceof Skull;
        return fits ? Optional.of(looked) : Optional.empty();
    }

    private int remove(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            admin.send(ctx.getSource().getSender(), "isadmin.top.in_game");
            return Cmd.OK;
        }
        Block looked = player.getTargetBlockExact(REACH);
        Location standing = player.getLocation();
        Optional<TopDisplay> gone = Optional.empty();
        if (looked != null) {
            gone = beat.displays().remove(looked.getWorld().getName(), looked.getX(), looked.getY(), looked.getZ());
        }
        if (gone.isEmpty() && standing != null) {
            gone = beat.displays()
                    .remove(
                            standing.getWorld().getName(),
                            standing.getBlockX(),
                            standing.getBlockY(),
                            standing.getBlockZ());
        }
        if (gone.isEmpty()) {
            admin.send(player, "isadmin.top.none_here");
            return Cmd.OK;
        }
        beat.painter().clear(gone.get());
        admin.send(
                player,
                "isadmin.top.removed",
                Placeholder.unparsed("at", gone.get().place()));
        return Cmd.OK;
    }

    private int list(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        var all = beat.displays().all();
        if (all.isEmpty()) {
            admin.send(sender, "isadmin.top.listed_none");
            return Cmd.OK;
        }
        for (TopDisplay display : all) {
            admin.send(
                    sender,
                    "isadmin.top.listed",
                    Placeholder.unparsed("kind", display.kind().word()),
                    Placeholder.unparsed("at", display.place()),
                    Placeholder.unparsed("position", Integer.toString(display.rank())),
                    Placeholder.unparsed("board", display.board().name().toLowerCase(Locale.ROOT)));
        }
        return Cmd.OK;
    }
}
