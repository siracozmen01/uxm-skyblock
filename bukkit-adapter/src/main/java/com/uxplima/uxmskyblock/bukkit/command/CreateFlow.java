package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * {@code /is create}: the kinds offered or the one named, the island made under every rule creation is held to, and
 * the owner set down standing on it. Split out of {@link IslandLifecycleCommands}, whose services and words it uses,
 * so neither file is over the size the standards draw a line at.
 */
final class CreateFlow {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(CreateFlow.class.getName());

    private final IslandLifecycleCommands host;

    CreateFlow(IslandLifecycleCommands host) {
        this.host = host;
    }

    int executeCreate(CommandContext<CommandSourceStack> ctx, String presetId) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            host.send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        // A kind the operator keeps behind a permission node is refused before anything is made.
        java.util.Optional<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> asked =
                host.presetCatalog.findById(presetId);
        // A kind this server does not offer, of a mode the operator left out, is answered as no kind at all.
        if (asked.isPresent() && !host.playableKinds.test(asked.get())) {
            host.send(
                    player,
                    "create.unknown_preset",
                    Placeholder.unparsed("preset", presetId),
                    Placeholder.unparsed("presets", availablePresetIds()));
            return Cmd.OK;
        }
        if (asked.isPresent()
                && !host.presetChoices.get().allows(player, asked.get().id())) {
            host.send(
                    player,
                    "create.preset_locked",
                    Placeholder.unparsed(
                            "preset", host.messages.words(player, asked.get().displayName())));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        // A preset may name a world of its own. One the server has not loaded would get an island
        // written to it and nowhere to send the player, so nothing is made.
        java.util.Optional<String> missingWorld = host.presetCatalog
                .findById(presetId)
                .map(preset -> preset.worldOr(host.worldName))
                .filter(world -> Bukkit.getWorld(world) == null);
        if (missingWorld.isPresent()) {
            host.send(player, "create.world_missing", Placeholder.unparsed("world", missingWorld.get()));
            return Cmd.OK;
        }

        host.schedulerPort.async(() -> {
            CreateIslandUseCase.CreateIslandResult result;
            try {
                result = host.createIslandUseCase.execute(
                        playerUuid, profileId, presetId, host.serverNodeId, host.worldName);
            } catch (RuntimeException thrown) {
                // A database that refuses a statement throws out of the use case. The player was told
                // nothing and the command just stopped; the trace goes to the log, they get the failure.
                LOGGER.log(
                        java.util.logging.Level.WARNING,
                        thrown,
                        () -> "Creating an island for " + player.getName() + " threw.");
                result = new CreateIslandUseCase.CreateIslandResult.Failure(String.valueOf(thrown.getMessage()));
            }
            CreateIslandUseCase.CreateIslandResult outcome = result;

            host.schedulerPort.onEntity(playerUuid, () -> {
                if (outcome instanceof CreateIslandUseCase.CreateIslandResult.Success success) {
                    host.activityLog.recordForMembers(
                            success.island().id(),
                            profileId,
                            ActivityEventType.TEMPLATE_APPLIED,
                            "activity.template_applied",
                            java.util.Map.of(
                                    "player",
                                    player.getName(),
                                    "preset",
                                    // The preset's own name, which is a key a reader resolves.
                                    success.preset().displayName()));
                    host.protectionListener.cacheIsland(success.island());
                    host.islandCreated(success.island().id());
                    IslandAntiAbuseService antiAbuse = host.antiAbuseServiceProvider.get();
                    if (antiAbuse != null) {
                        antiAbuse.quarantineNewIsland(success.island().id(), Instant.now());
                    }

                    // The world the island was made in, which its preset may name.
                    String madeIn = success.location().worldName();
                    World resolvedWorld = Bukkit.getWorld(madeIn);
                    if (resolvedWorld != null) {
                        int centerX = success.location().bounds().centerX();
                        int centerZ = success.location().bounds().centerZ();
                        int platformY = StarterSchematicEngine.platformBelow(
                                success.location().spawnY());
                        int chunkX = centerX >> 4;
                        int chunkZ = centerZ >> 4;
                        String targetWorld = resolvedWorld.getName();

                        host.schedulerPort.onRegion(targetWorld, chunkX, chunkZ, () -> {
                            World w = Bukkit.getWorld(targetWorld);
                            if (w == null) {
                                arrive(
                                        player,
                                        playerUuid,
                                        resolvedWorld,
                                        success,
                                        success.location().spawnY());
                                return;
                            }
                            // The preset's own creation actions: a platform, a pasted schematic, a OneBlock
                            // island's block. A schematic stands some ticks later, and the player arrives
                            // once it does, on whatever it built.
                            com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine.afterBuilt(
                                    host.schematicEngine.start(new com.uxplima.uxmskyblock.bukkit.schematic.IslandStart(
                                            w, success.island().id(), centerX, platformY, centerZ, success.preset())),
                                    failure -> LOGGER.log(
                                            java.util.logging.Level.WARNING,
                                            failure,
                                            () -> "Building the island of " + player.getName() + " failed."),
                                    () -> host.schedulerPort.onRegion(targetWorld, chunkX, chunkZ, () -> {
                                        Arrival at = arrivalOn(
                                                w,
                                                success.island().id(),
                                                success.location(),
                                                success.preset().mode().playedUnderwater());
                                        arrive(player, playerUuid, w, success, at);
                                    }));
                        });
                    } else {
                        host.send(player, "create.world_unloaded", Placeholder.unparsed("world", madeIn));
                    }
                } else if (outcome instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    host.send(player, "create.already_has_island");
                } else if (outcome instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unknown) {
                    host.send(
                            player,
                            "create.unknown_preset",
                            Placeholder.unparsed("preset", unknown.presetId()),
                            Placeholder.unparsed("presets", availablePresetIds()));
                } else if (outcome instanceof CreateIslandUseCase.CreateIslandResult.Failure failure) {
                    // The reason is an exception's message, which can be a database's own words. It
                    // goes to the log; the player reads a line in their own language.
                    LOGGER.warning(() -> "Creating an island for " + player.getName() + " failed: " + failure.reason());
                    host.send(player, "create.failed");
                }
            });
        });

        return Cmd.OK;
    }
    /** {@code /is create} with no kind written: the window of kinds, or the default island, as the operator chose. */
    int executeCreateWithoutKind(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player && host.offerKinds(player)) {
            return Cmd.OK;
        }
        return executeCreate(ctx, host.presetCatalog.defaultPreset().id());
    }
    /** Where a player arrives on a new island: its spawn, or the spot creation moved it to. */
    private record Arrival(double x, double y, double z) {}

    /** Sends the player who made the island onto it, standing at {@code standingY} over its spawn, and tells them. */
    private void arrive(
            Player player,
            PlayerUuid playerUuid,
            World world,
            CreateIslandUseCase.CreateIslandResult.Success success,
            double standingY) {
        arrive(
                player,
                playerUuid,
                world,
                success,
                new Arrival(
                        success.location().spawnX(),
                        standingY,
                        success.location().spawnZ()));
    }

    /** Sends the player who made the island onto it, standing at {@code at}, and tells them. */
    private void arrive(
            Player player,
            PlayerUuid playerUuid,
            World world,
            CreateIslandUseCase.CreateIslandResult.Success success,
            Arrival at) {
        host.schedulerPort.onEntity(playerUuid, () -> {
            if (!player.isOnline()) {
                return;
            }
            Location destination = new Location(world, at.x(), at.y(), at.z(), 0.0f, 0.0f);
            var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                if (Boolean.TRUE.equals(teleported)) {
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                }
            });
            host.send(
                    player,
                    "create.success",
                    Placeholder.unparsed("preset", host.presetName(player, success.preset())));
            host.fireMilestone("island-created", player);
        });
    }
    /**
     * Where a player arrives standing on a new island, found in the chunk it was built in, on the thread that owns
     * it: the spawn's column at the nearest safe height, or the nearest column of that chunk with ground, or the top
     * of the water when the chunk is all sea. A spawn that had to move is written back, so a home is not inside a hill
     * or out over the sea either.
     */
    private Arrival arrivalOn(World world, IslandId islandId, IslandLocation location, boolean underwater) {
        int x = (int) Math.floor(location.spawnX());
        int planned = (int) Math.floor(location.spawnY());
        int z = (int) Math.floor(location.spawnZ());
        java.util.Optional<com.uxplima.uxmskyblock.bukkit.world.SafeArrival.Spot> safe =
                com.uxplima.uxmskyblock.bukkit.world.SafeArrival.standingInChunk(world, x, planned, z, underwater);
        if (safe.isEmpty()) {
            java.util.OptionalInt floating = underwater
                    ? java.util.OptionalInt.empty()
                    : com.uxplima.uxmskyblock.bukkit.world.SafeArrival.onTheWater(world, x, z);
            if (floating.isPresent()) {
                return heightMoved(islandId, location, floating.getAsInt());
            }
            // Rock as far as the search reaches: the room above the platform is cleared instead.
            com.uxplima.uxmskyblock.bukkit.world.SafeArrival.makeRoom(world, x, planned, z, underwater);
            return new Arrival(location.spawnX(), location.spawnY(), location.spawnZ());
        }
        com.uxplima.uxmskyblock.bukkit.world.SafeArrival.Spot spot = safe.get();
        if (spot.x() != x || spot.z() != z) {
            Arrival moved = new Arrival(spot.x() + 0.5, spot.y(), spot.z() + 0.5);
            host.schedulerPort.async(
                    () -> host.islandLocationService.moveSpawn(islandId, moved.x(), moved.y(), moved.z()));
            return moved;
        }
        if (spot.y() == planned) {
            return new Arrival(location.spawnX(), location.spawnY(), location.spawnZ());
        }
        return heightMoved(islandId, location, spot.y());
    }

    /** The spawn's own column at {@code y}, written back. */
    private Arrival heightMoved(IslandId islandId, IslandLocation location, double y) {
        host.schedulerPort.async(() -> host.islandLocationService.moveSpawnHeight(islandId, y));
        return new Arrival(location.spawnX(), y, location.spawnZ());
    }
    /** The preset ids an operator can actually pass, read off the catalogue rather than typed. */
    private String availablePresetIds() {
        return host.presetCatalog.allPresets().stream()
                .filter(host.playableKinds)
                .map(preset -> preset.id())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
