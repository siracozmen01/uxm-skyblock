package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Location;
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
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * {@code /isadmin chunkblock}: open or close one chunk of an island, the one the operator stands in or one named by
 * its chunk coordinates, or close every chunk but the first. A chunk an operator opens stays open whatever the level
 * does, as one paid for does. Whoever stands in a chunk that closes is moved, as when the level closes it.
 */
public final class ChunkBlockAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "chunkblock";

    private final ChunkBlockService service;

    public ChunkBlockAdmin(ChunkBlockService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("open", CatalogPermissions.ADMIN_CHUNKBLOCK_OPEN)
                        .then(chunk(admin, this::open)))
                .then(ModeAdminCommands.verb("close", CatalogPermissions.ADMIN_CHUNKBLOCK_CLOSE)
                        .then(chunk(admin, this::close)))
                .then(ModeAdminCommands.verb("reset", CatalogPermissions.ADMIN_CHUNKBLOCK_RESET)
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(ctx -> reset(admin, ctx))));
    }

    /** What a chunk verb does once it knows the island and the chunk. */
    private interface ChunkVerb {
        void run(ModeAdminCommands admin, CommandSourceStack source, String target, IslandId island, ChunkPos chunk);
    }

    /** {@code <target>}, the chunk the sender stands in, or {@code <target> <chunk_x> <chunk_z>}. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> chunk(
            ModeAdminCommands admin, ChunkVerb verb) {
        return Cmd.argument("target", StringArgumentType.word())
                .executes(ctx -> here(admin, ctx, verb))
                .then(Cmd.argument("chunk_x", IntegerArgumentType.integer())
                        .then(Cmd.argument("chunk_z", IntegerArgumentType.integer())
                                .executes(ctx -> on(
                                        admin,
                                        ctx,
                                        new ChunkPos(
                                                IntegerArgumentType.getInteger(ctx, "chunk_x"),
                                                IntegerArgumentType.getInteger(ctx, "chunk_z")),
                                        verb))));
    }

    private static int here(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx, ChunkVerb verb) {
        Location at = ctx.getSource().getSender() instanceof Player player ? player.getLocation() : null;
        if (at == null) {
            admin.send(ctx.getSource().getSender(), "isadmin.chunkblock.name_the_chunk");
            return Cmd.OK;
        }
        return on(admin, ctx, ChunkPos.ofBlock(at.getBlockX(), at.getBlockZ()), verb);
    }

    private static int on(
            ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx, ChunkPos chunk, ChunkVerb verb) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> verb.run(admin, source, target, island, chunk));
        return Cmd.OK;
    }

    private void open(
            ModeAdminCommands admin, CommandSourceStack source, String target, IslandId island, ChunkPos chunk) {
        Optional<com.uxplima.uxmskyblock.core.domain.island.IslandBounds> bounds =
                admin.locations().findLocation(island).map(found -> found.bounds());
        if (bounds.isEmpty()) {
            notChunkBlock(admin, source, target);
            return;
        }
        ChunkBlockService.Outcome outcome =
                service.open(island, chunk, bounds.get(), false).outcome();
        if (outcome == ChunkBlockService.Outcome.NOT_CHUNKBLOCK) {
            notChunkBlock(admin, source, target);
            return;
        }
        String key = outcome == ChunkBlockService.Outcome.UNLOCKED
                ? "isadmin.chunkblock.opened"
                : "isadmin.chunkblock.refused." + outcome.name().toLowerCase(Locale.ROOT);
        say(admin, source, key, target, chunk);
    }

    private void close(
            ModeAdminCommands admin, CommandSourceStack source, String target, IslandId island, ChunkPos chunk) {
        Optional<ChunkTerritory.Close> outcome = service.close(island, chunk);
        if (outcome.isEmpty()) {
            notChunkBlock(admin, source, target);
            return;
        }
        say(admin, source, "isadmin.chunkblock." + outcome.get().name().toLowerCase(Locale.ROOT), target, chunk);
    }

    private int reset(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> {
            Optional<java.util.List<ChunkPos>> closed = service.reset(island);
            if (closed.isEmpty()) {
                notChunkBlock(admin, source, target);
                return;
            }
            admin.send(
                    source.getSender(),
                    "isadmin.chunkblock.reset",
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed("count", Integer.toString(closed.get().size())));
        });
        return Cmd.OK;
    }

    private static void say(
            ModeAdminCommands admin, CommandSourceStack source, String key, String target, ChunkPos chunk) {
        admin.send(
                source.getSender(),
                key,
                Placeholder.unparsed("target", target),
                Placeholder.unparsed("chunk", chunk.x() + " " + chunk.z()));
    }

    private static void notChunkBlock(ModeAdminCommands admin, CommandSourceStack source, String target) {
        admin.send(source.getSender(), "isadmin.chunkblock.not_chunkblock", Placeholder.unparsed("target", target));
    }
}
