package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.World;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

/**
 * {@code /isadmin realms remirror}: lays the Upside Down of an island again from its land, the whole island or one
 * chunk named by its coordinates. It copies what a late mirror copies, the plain ground the operator lists in
 * {@code upside-down.late-copy}, and air for the rest, so nothing anybody built is had twice; what stood in the
 * Upside Down there is laid over.
 */
public final class StrangerRealmsAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "realms";

    private final StrangerRealmsService service;
    private final UpsideDownStart mirror;
    private final Supplier<String> upsideDownWorld;
    private final Function<String, @Nullable World> worlds;
    private final Predicate<Material> copied;

    public StrangerRealmsAdmin(
            StrangerRealmsService service,
            UpsideDownStart mirror,
            Supplier<String> upsideDownWorld,
            Function<String, @Nullable World> worlds,
            Predicate<Material> copied) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.mirror = Objects.requireNonNull(mirror, "mirror must not be null");
        this.upsideDownWorld = Objects.requireNonNull(upsideDownWorld, "upsideDownWorld must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.copied = Objects.requireNonNull(copied, "copied must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("remirror", CatalogPermissions.ADMIN_REALMS_REMIRROR)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .executes(ctx -> remirror(admin, ctx, false))
                                .then(Cmd.argument("chunk_x", IntegerArgumentType.integer())
                                        .then(Cmd.argument("chunk_z", IntegerArgumentType.integer())
                                                .executes(ctx -> remirror(admin, ctx, true))))));
    }

    private int remirror(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx, boolean oneChunk) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        int[] named = oneChunk
                ? new int[] {
                    IntegerArgumentType.getInteger(ctx, "chunk_x"), IntegerArgumentType.getInteger(ctx, "chunk_z")
                }
                : new int[0];
        admin.island(source, target, island -> {
            Optional<IslandLocation> location = admin.locations().findLocation(island);
            if (!service.isStranger(island) || location.isEmpty()) {
                admin.send(source.getSender(), "isadmin.realms.not_realms", Placeholder.unparsed("target", target));
                return;
            }
            World land = worlds.apply(location.get().worldName());
            World upsideDown = worlds.apply(upsideDownWorld.get());
            if (land == null || upsideDown == null) {
                admin.send(
                        source.getSender(),
                        "isadmin.realms.not_loaded",
                        Placeholder.unparsed("target", target),
                        Placeholder.unparsed(
                                "world", land == null ? location.get().worldName() : upsideDownWorld.get()));
                return;
            }
            IslandBounds bounds = location.get().bounds();
            int count = 0;
            int fromX = bounds.minX() >> 4;
            int toX = bounds.maxX() >> 4;
            int fromZ = bounds.minZ() >> 4;
            int toZ = bounds.maxZ() >> 4;
            if (oneChunk) {
                fromX = toX = named[0];
                fromZ = toZ = named[1];
                if (named[0] < bounds.minX() >> 4
                        || named[0] > bounds.maxX() >> 4
                        || named[1] < bounds.minZ() >> 4
                        || named[1] > bounds.maxZ() >> 4) {
                    admin.send(
                            source.getSender(),
                            "isadmin.realms.outside",
                            Placeholder.unparsed("target", target),
                            Placeholder.unparsed("chunk", named[0] + " " + named[1]));
                    return;
                }
            }
            for (int chunkX = fromX; chunkX <= toX; chunkX++) {
                for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                    int cx = chunkX;
                    int cz = chunkZ;
                    admin.scheduler()
                            .onRegion(land.getName(), cx, cz, () -> mirror.mirror(land, upsideDown, cx, cz, copied));
                    count++;
                }
            }
            admin.send(
                    source.getSender(),
                    "isadmin.realms.remirrored",
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed("count", Integer.toString(count)));
        });
        return Cmd.OK;
    }
}
