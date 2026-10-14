package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Optional;

import org.bukkit.Location;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;

/**
 * {@code /is warp <name>} and {@code /is warp visit <owner> <name>}: going to a warp of one's own island or of another. Split out of {@link IslandWarpCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class WarpVisitVerbs {

    private final IslandWarpCommands host;

    WarpVisitVerbs(IslandWarpCommands host) {
        this.host = host;
    }

    int executeGo(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        return host.onOwnIsland(ctx, (player, service, island) -> {
            Optional<IslandWarp> optWarp = service.getWarp(island.id(), WarpName.of(rawName));
            if (optWarp.isEmpty()) {
                host.onEntity(player, () -> host.send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
                return;
            }
            WarpLocation where = optWarp.get().location();
            host.onEntity(player, () -> {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(where.worldName());
                if (world == null) {
                    host.send(player, "warp.world_unloaded", Placeholder.unparsed("world", where.worldName()));
                    return;
                }
                Location target = new Location(world, where.x(), where.y(), where.z(), where.yaw(), where.pitch());
                var unused = player.teleportAsync(target);
                host.send(player, "warp.travelled", Placeholder.unparsed("name", rawName));
            });
        });
    }
    /**
     * {@code /is warp visit <owner> <name>}: travelling to somebody else's public warp.
     *
     * <p>{@code /is warp browse} has listed public warps since the warp work and a player could not
     * go to a single one of them. The ban list, the island lock, the per warp lock and the anti trap
     * safe spot search all sat behind {@code prepareVisit}, which nothing in the plugin ever called,
     * so the whole visitor security surface was dead code around a listing nobody could act on.
     *
     * <p>The gate and the warp row are storage, so they are read on the scheduler thread. The safe
     * spot search reads blocks, and under Folia a block belongs to the region thread that owns it,
     * so that is a second hop rather than part of the first.
     */
    int executeVisitWarp(CommandContext<CommandSourceStack> ctx) {
        String owner = StringArgumentType.getString(ctx, "owner");
        String rawName = StringArgumentType.getString(ctx, "name");

        return host.withService(
                ctx,
                (player, service, profileId) -> host.schedulerPort.async(() -> {
                    Optional<IslandId> optTarget = IslandAdminCommands.resolveIslandId(
                            host.sessionCoordinator, host.islandLocationService, owner);
                    Optional<Island> optIsland = optTarget.flatMap(host.islandLocationService::findIsland);
                    if (optIsland.isEmpty()) {
                        host.onEntity(
                                player,
                                () -> host.send(player, "warp.island_unknown", Placeholder.unparsed("owner", owner)));
                        return;
                    }

                    host.visitResolvedIsland(player, service, profileId, optIsland.get(), owner, rawName);
                }));
    }
}
