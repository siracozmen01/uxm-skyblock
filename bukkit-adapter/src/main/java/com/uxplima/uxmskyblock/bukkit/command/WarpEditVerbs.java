package com.uxplima.uxmskyblock.bukkit.command;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.warp.DuplicateWarpNameException;
import com.uxplima.uxmskyblock.core.domain.warp.InvalidWarpNameException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import com.uxplima.uxmskyblock.core.domain.warp.WarpNotFoundException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpOutsideIslandException;

/**
 * {@code /is setwarp}, {@code /is delwarp} and the warp's lock, category and place: a warp made, changed or taken away, and why one was refused. Split out of {@link IslandWarpCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class WarpEditVerbs {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(WarpEditVerbs.class.getName());

    private final IslandWarpCommands host;

    WarpEditVerbs(IslandWarpCommands host) {
        this.host = host;
    }

    int executeCreate(CommandContext<CommandSourceStack> ctx, WarpCategory category) {
        String rawName = StringArgumentType.getString(ctx, "name");
        if (!(ctx.getSource().getSender() instanceof Player standing)) {
            host.send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Location at = standing.getLocation();
        if (at == null || at.getWorld() == null) {
            host.send(standing, "warp.location_unreadable");
            return Cmd.OK;
        }
        // Read where the player is now, on the thread that owns them, before anything goes async.
        WarpLocation where =
                new WarpLocation(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());

        return host.onOwnIsland(ctx, (player, service, island) -> {
            ProfileId profileId = host.activeProfile(player).orElse(null);
            if (profileId == null) {
                host.onEntity(player, () -> host.send(player, "error.session_not_active"));
                return;
            }
            try {
                IslandWarp warp = service.createWarp(
                        island, profileId, WarpName.of(rawName), where, category, category.defaultIconMaterial());
                host.activityLog.recordForMembers(
                        island.id(),
                        profileId,
                        ActivityEventType.WARP_CREATED,
                        "activity.warp_created",
                        java.util.Map.of(
                                "player",
                                player.getName(),
                                "name",
                                warp.name().value(),
                                "category",
                                // Stored as its key, so each reader of the feed sees their own word.
                                host.messages.stored(
                                        "warp.categories",
                                        warp.category().name(),
                                        warp.category().name())));
                host.onEntity(
                        player,
                        () -> host.send(
                                player,
                                "warp.created",
                                Placeholder.unparsed("name", warp.name().value()),
                                Placeholder.unparsed(
                                        "category",
                                        host.messages.named(
                                                player,
                                                "warp.categories",
                                                warp.category().name(),
                                                warp.category().name()))));
            } catch (SecurityException denied) {
                host.onEntity(player, () -> host.send(player, "warp.no_permission"));
            } catch (RuntimeException refused) {
                tellRefusal(player, rawName, refused);
            }
        });
    }

    int executeCreateWithCategory(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "category");
        for (WarpCategory category : WarpCategory.values()) {
            if (category.name().equalsIgnoreCase(raw)) {
                return executeCreate(ctx, category);
            }
        }
        host.send(
                ctx.getSource().getSender(),
                "warp.unknown_category",
                Placeholder.unparsed("category", raw),
                Placeholder.unparsed("categories", IslandWarpCommands.categoryNames()));
        return Cmd.OK;
    }
    /**
     * {@code /is warp move <name>}: puts an existing warp where the player is standing.
     *
     * <p>relocateWarp was written with the warp work and had no caller anywhere, so a warp put down
     * in the wrong place could only be deleted and made again, which loses whatever a visitor had
     * bookmarked about it.
     */
    int executeMove(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        if (!(ctx.getSource().getSender() instanceof Player standing)) {
            host.send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Location at = standing.getLocation();
        if (at == null || at.getWorld() == null) {
            host.send(standing, "warp.location_unreadable");
            return Cmd.OK;
        }
        // Read where the player is now, on the thread that owns them, before anything goes async.
        WarpLocation where =
                new WarpLocation(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());

        return host.onOwnIsland(ctx, (player, service, island) -> {
            ProfileId profileId = host.activeProfile(player).orElse(null);
            if (profileId == null) {
                host.onEntity(player, () -> host.send(player, "error.session_not_active"));
                return;
            }
            try {
                IslandWarp moved = service.relocateWarp(island, profileId, WarpName.of(rawName), where);
                host.onEntity(
                        player,
                        () -> host.send(
                                player,
                                "warp.moved",
                                Placeholder.unparsed("name", moved.name().value())));
            } catch (SecurityException denied) {
                host.onEntity(player, () -> host.send(player, "warp.no_permission"));
            } catch (WarpNotFoundException missing) {
                host.onEntity(player, () -> host.send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            } catch (RuntimeException refused) {
                tellRefusal(player, rawName, refused);
            }
        });
    }
    /**
     * Moves a warp between the directory categories a visitor browses by.
     *
     * <p>{@code setWarpCategory} was the other half of the same dead pair: a warp could be created
     * in a category and never moved out of it.
     */
    int executeSetCategory(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        String rawCategory = StringArgumentType.getString(ctx, "category");
        WarpCategory category = null;
        for (WarpCategory candidate : WarpCategory.values()) {
            if (candidate.name().equalsIgnoreCase(rawCategory)) {
                category = candidate;
            }
        }
        if (category == null) {
            host.send(
                    ctx.getSource().getSender(),
                    "warp.unknown_category",
                    Placeholder.unparsed("category", rawCategory),
                    Placeholder.unparsed("categories", IslandWarpCommands.categoryNames()));
            return Cmd.OK;
        }

        WarpCategory chosen = category;
        return host.onOwnIsland(ctx, (player, service, island) -> {
            try {
                service.setWarpCategory(island, host.ownerProfileOf(island, player), WarpName.of(rawName), chosen);
                host.onEntity(
                        player,
                        () -> host.send(
                                player,
                                "warp.category_changed",
                                Placeholder.unparsed("name", rawName),
                                Placeholder.unparsed(
                                        "category",
                                        host.messages.named(player, "warp.categories", chosen.name(), chosen.name()))));
            } catch (SecurityException denied) {
                host.onEntity(player, () -> host.send(player, "warp.no_permission"));
            } catch (RuntimeException missing) {
                host.onEntity(player, () -> host.send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            }
        });
    }

    int executeDelete(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        return host.onOwnIsland(ctx, (player, service, island) -> {
            ProfileId profileId = host.activeProfile(player).orElse(null);
            if (profileId == null) {
                host.onEntity(player, () -> host.send(player, "error.session_not_active"));
                return;
            }
            try {
                service.deleteWarp(island, profileId, WarpName.of(rawName));
                host.onEntity(player, () -> host.send(player, "warp.deleted", Placeholder.unparsed("name", rawName)));
            } catch (SecurityException denied) {
                host.onEntity(player, () -> host.send(player, "warp.no_permission"));
            } catch (RuntimeException refused) {
                host.onEntity(player, () -> host.send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            }
        });
    }
    /**
     * Shuts one warp to visitors, or opens it again.
     *
     * <p>{@code IslandWarpService.setWarpLock} had no caller anywhere in the plugin, so
     * {@code warp.isLocked()} was a column the visit gate read and nothing ever wrote: every warp on
     * every island was open, whatever the owner wanted.
     */
    int executeSetLock(CommandContext<CommandSourceStack> ctx, boolean locked) {
        String rawName = StringArgumentType.getString(ctx, "name");
        return host.onOwnIsland(ctx, (player, service, island) -> {
            try {
                service.setWarpLock(island, host.ownerProfileOf(island, player), WarpName.of(rawName), locked);
                host.onEntity(
                        player,
                        () -> host.send(
                                player,
                                locked ? "warp.locked" : "warp.unlocked",
                                Placeholder.unparsed("name", rawName)));
            } catch (SecurityException denied) {
                host.onEntity(player, () -> host.send(player, "warp.no_permission"));
            } catch (RuntimeException missing) {
                host.onEntity(player, () -> host.send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            }
        });
    }
    /**
     * Tells a player why a warp was not made or moved, from the catalogue.
     *
     * <p>The refusal used to reach the player as the exception's own sentence, in English, with the
     * island's id and bounds in it. The kind of refusal picks the line now; anything unexpected is a
     * line saying it could not be saved, and the sentence goes to the log.
     */
    void tellRefusal(Player player, String rawName, RuntimeException refused) {
        String name = rawName;
        switch (refused) {
            case InvalidWarpNameException _ -> host.onEntity(player, () -> host.send(player, "warp.bad_name"));
            case WarpOutsideIslandException _ -> host.onEntity(player, () -> host.send(player, "warp.outside_island"));
            case DuplicateWarpNameException _ ->
                host.onEntity(player, () -> host.send(player, "warp.name_taken", Placeholder.unparsed("name", name)));
            case WarpLimitExceededException full ->
                host.onEntity(
                        player,
                        () -> host.send(
                                player,
                                "warp.limit_reached",
                                Placeholder.unparsed("max", Integer.toString(full.maxAllowed()))));
            default -> {
                LOGGER.log(java.util.logging.Level.WARNING, "A warp could not be saved", refused);
                host.onEntity(player, () -> host.send(player, "warp.failed"));
            }
        }
    }
}
