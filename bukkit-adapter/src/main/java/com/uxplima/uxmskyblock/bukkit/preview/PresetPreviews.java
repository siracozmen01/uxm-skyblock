package com.uxplima.uxmskyblock.bukkit.preview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.PresetPreview;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.jspecify.annotations.Nullable;

/**
 * Lets a player look at a kind of island before choosing it: they watch its start island from above as a
 * spectator for a set time, and are put back where they stood, as they were.
 *
 * <p>Each kind's start island is laid once in a showroom of its own, far from every island, the first time anybody
 * looks. While a player looks they cannot leave it: the spectator's jump to another player is refused, and a
 * spectator who drifts too far is put back over the showroom. A teleport of anybody else's, a command's or a plugin's,
 * ends the look where it lands, in the mode the player had. Where they stood and how they played are written on the
 * player, so a quit or a crash during a look puts them back the next time they join.
 */
public final class PresetPreviews implements Listener {

    private static final Logger LOGGER = Logger.getLogger(PresetPreviews.class.getName());

    /** Where a look puts a player back: written on them while they look. */
    static final NamespacedKey RETURN = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:preview_return"));

    /** How high over a showroom's middle, and how far back from it, a look begins. */
    static final int ABOVE = 14;

    static final int BACK = 22;

    /** Lays a kind's start island with its middle at the place given, and answers once the last block stands. */
    @FunctionalInterface
    public interface Showroom {
        CompletableFuture<Void> lay(StarterPreset preset, World world, int x, int y, int z);
    }

    /** One look: the showroom watched, and the token a later end must match. */
    private record Look(Location over, UUID token) {}

    private final Supplier<PresetPreview> settings;
    private final Supplier<List<StarterPreset>> presets;
    private final Function<String, @Nullable World> worlds;
    private final Supplier<String> islandWorld;
    private final Supplier<@Nullable Location> spawn;
    private final Showroom showroom;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Clock clock;
    private final Map<UUID, Look> looking = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastLook = new ConcurrentHashMap<>();
    private final Set<UUID> moving = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Void>> laid = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> hurt = new ConcurrentHashMap<>();

    public PresetPreviews(
            Supplier<PresetPreview> settings,
            Supplier<List<StarterPreset>> presets,
            Function<String, @Nullable World> worlds,
            Supplier<String> islandWorld,
            Supplier<@Nullable Location> spawn,
            Showroom showroom,
            SchedulerPort scheduler,
            Messages messages,
            Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.presets = Objects.requireNonNull(presets, "presets must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.islandWorld = Objects.requireNonNull(islandWorld, "islandWorld must not be null");
        this.spawn = Objects.requireNonNull(spawn, "spawn must not be null");
        this.showroom = Objects.requireNonNull(showroom, "showroom must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Whether a player may look at all on this server. */
    public boolean enabled() {
        return settings.get().enabled();
    }

    /** Whether {@code player} is looking at a kind now. */
    public boolean looking(Player player) {
        return looking.containsKey(player.getUniqueId());
    }

    /**
     * Shows {@code player} the start island of {@code preset}, on the player's own thread. Answers whether a look
     * began; a player told why not answers false.
     */
    public boolean show(Player player, StarterPreset preset) {
        PresetPreview preview = settings.get();
        UUID uuid = player.getUniqueId();
        if (!preview.enabled()) {
            messages.send(player, "preview.off");
            return false;
        }
        if (looking.containsKey(uuid) || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        Instant now = clock.instant();
        Instant lastHurt = hurt.get(uuid);
        // A look is no way out of a fight, a fall or a fire: the player is out of reach while they look.
        if ((lastHurt != null && now.isBefore(lastHurt.plus(preview.calm())))
                || player.getFallDistance() > 0
                || player.getFireTicks() > 0) {
            messages.send(player, "preview.not_now");
            return false;
        }
        Instant last = lastLook.get(uuid);
        if (last != null) {
            Duration left = Duration.between(now, last.plus(preview.cooldown()));
            if (!left.isNegative() && !left.isZero()) {
                messages.send(
                        player,
                        "preview.cooldown",
                        Placeholder.unparsed("time", DurationText.of(messages, player, left)));
                return false;
            }
        }
        Optional<Location> over = showroomOf(preset, preview);
        if (over.isEmpty()) {
            messages.send(player, "preview.unavailable");
            return false;
        }
        lastLook.put(uuid, now);
        Location middle = over.get();
        World world = Objects.requireNonNull(middle.getWorld());
        var unused = laid.computeIfAbsent(preset.id() + "@" + world.getName(), key -> lay(preset, middle))
                .whenComplete((done, failed) -> scheduler.onEntity(PlayerUuid.of(uuid), () -> {
                    if (failed != null) {
                        laid.remove(preset.id() + "@" + world.getName());
                        LOGGER.log(
                                Level.WARNING, failed, () -> "The showroom of " + preset.id() + " could not be laid");
                        messages.send(player, "preview.unavailable");
                        return;
                    }
                    begin(player, preset, middle, preview);
                }));
        return true;
    }

    /** Lays the showroom on the region that owns its middle. */
    private CompletableFuture<Void> lay(StarterPreset preset, Location middle) {
        CompletableFuture<Void> standing = new CompletableFuture<>();
        World world = Objects.requireNonNull(middle.getWorld());
        scheduler.onRegion(world.getName(), middle.getBlockX() >> 4, middle.getBlockZ() >> 4, () -> {
            try {
                var unused = showroom.lay(preset, world, middle.getBlockX(), middle.getBlockY(), middle.getBlockZ())
                        .whenComplete((done, failed) -> {
                            if (failed != null) {
                                standing.completeExceptionally(failed);
                            } else {
                                standing.complete(null);
                            }
                        });
            } catch (RuntimeException e) {
                standing.completeExceptionally(e);
            }
        });
        return standing;
    }

    /** The middle of the showroom of {@code preset}, or nothing when its world is not loaded or the kind is gone. */
    Optional<Location> showroomOf(StarterPreset preset, PresetPreview preview) {
        List<String> ids =
                presets.get().stream().map(StarterPreset::id).sorted().toList();
        int index = ids.indexOf(preset.id());
        String named = preview.world().isEmpty() ? islandWorld.get() : preview.world();
        World world = worlds.apply(named);
        if (index < 0 || world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, preview.xOf(index) + 0.5, preview.y(), preview.z() + 0.5));
    }

    /** Puts the player over the showroom as a spectator, and ends the look after its time. */
    private void begin(Player player, StarterPreset preset, Location middle, PresetPreview preview) {
        if (!player.isOnline() || looking.containsKey(player.getUniqueId())) {
            return;
        }
        UUID token = UUID.randomUUID();
        Location over = viewpoint(middle);
        Location stood = player.getLocation();
        if (stood == null) {
            return;
        }
        player.getPersistentDataContainer()
                .set(RETURN, PersistentDataType.STRING, written(stood, player.getGameMode()));
        looking.put(player.getUniqueId(), new Look(over, token));
        player.closeInventory();
        player.setGameMode(GameMode.SPECTATOR);
        moveTo(player, over);
        messages.send(
                player,
                "preview.started",
                Placeholder.unparsed("preset", messages.words(player, preset.displayName())),
                Placeholder.unparsed("time", DurationText.of(messages, player, preview.time())));
        UUID uuid = player.getUniqueId();
        scheduler.asyncAfter(
                preview.time(),
                () -> scheduler.onEntity(PlayerUuid.of(uuid), () -> {
                    Look look = looking.get(uuid);
                    if (look != null && look.token().equals(token) && player.isOnline()) {
                        end(player, true);
                        messages.send(player, "preview.ended");
                    }
                }));
    }

    /** Where a look begins: above the showroom and back from it, facing its middle. */
    static Location viewpoint(Location middle) {
        Location over = middle.clone().add(0, ABOVE, -BACK);
        over.setYaw(0f);
        over.setPitch((float) Math.toDegrees(Math.atan2(ABOVE, BACK)));
        return over;
    }

    /**
     * Ends a look: the player plays as they did, and with {@code back} is put where they stood. Without it they stay
     * where a teleport of somebody else's is taking them.
     */
    void end(Player player, boolean back) {
        looking.remove(player.getUniqueId());
        Optional<Returned> to = returnOf(player);
        player.getPersistentDataContainer().remove(RETURN);
        to.ifPresent(returned -> {
            player.setGameMode(returned.mode());
            // A world taken away since sends the player to the server's spawn, never left at the showroom.
            Location at = returned.at() != null ? returned.at() : spawn.get();
            if (back && at != null) {
                moveTo(player, at);
            }
        });
    }

    private void moveTo(Player player, Location to) {
        UUID uuid = player.getUniqueId();
        moving.add(uuid);
        var unused = player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((done, failed) -> moving.remove(uuid));
    }

    /** No jump to another player during a look. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpectate(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && looking.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * A teleport of anybody else's that goes through ends the look where it lands. Read last, so one another plugin
     * refuses leaves the look running rather than the player at the showroom in their own mode.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!looking.containsKey(player.getUniqueId()) || moving.contains(player.getUniqueId())) {
            return;
        }
        end(player, false);
    }

    /** A player hurt is not let look until they have been calm a while. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            hurt.put(player.getUniqueId(), clock.instant());
        }
    }

    /** A spectator who drifts too far is put back over the showroom. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Look look = looking.get(event.getPlayer().getUniqueId());
        if (look == null || moving.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Location to = event.getTo();
        int radius = settings.get().radius();
        Location middle = look.over().clone().add(0, -ABOVE, BACK);
        if (!Objects.equals(to.getWorld(), middle.getWorld())
                || horizontal(to, middle) > (double) radius * radius
                || Math.abs(to.getY() - middle.getY()) > radius) {
            moveTo(event.getPlayer(), look.over());
        }
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** A player who leaves during a look plays as they did; where they stood is put back when they return. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        moving.remove(uuid);
        hurt.remove(uuid);
        if (looking.remove(uuid) != null) {
            returnOf(player).ifPresent(returned -> player.setGameMode(returned.mode()));
        }
    }

    /** A player who left or lost the server during a look is put back where they stood. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<Returned> left = returnOf(player);
        if (left.isEmpty()) {
            return;
        }
        // The mode now; the move on the player's next tick. A teleport inside the join, before the server has
        // placed the player, takes down the region they join in. The note stays until the move is made, so a
        // player who leaves at once is put back the next time.
        player.setGameMode(left.get().mode());
        scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> {
            if (player.isOnline() && !looking.containsKey(player.getUniqueId())) {
                end(player, true);
            }
        });
    }

    /** Where a look puts a player back, when that world is still here, and how they played. */
    record Returned(@Nullable Location at, GameMode mode) {}

    private Optional<Returned> returnOf(Player player) {
        String written = player.getPersistentDataContainer().get(RETURN, PersistentDataType.STRING);
        return written == null ? Optional.empty() : read(written, worlds);
    }

    /** {@code world;x;y;z;yaw;pitch;MODE}. */
    static String written(Location at, GameMode mode) {
        World world = Objects.requireNonNull(at.getWorld(), "a place to return to is in a world");
        return String.join(
                ";",
                world.getName(),
                Double.toString(at.getX()),
                Double.toString(at.getY()),
                Double.toString(at.getZ()),
                Float.toString(at.getYaw()),
                Float.toString(at.getPitch()),
                mode.name());
    }

    static Optional<Returned> read(String written, Function<String, @Nullable World> worlds) {
        String[] parts = written.split(";", -1);
        if (parts.length != 7) {
            // Nothing readable: whoever looked plays as most players do rather than staying a spectator.
            return Optional.of(new Returned(null, GameMode.SURVIVAL));
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(parts[6].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unreadable) {
            mode = GameMode.SURVIVAL;
        }
        World world = worlds.apply(parts[0]);
        try {
            return Optional.of(new Returned(
                    world == null
                            ? null
                            : new Location(
                                    world,
                                    Double.parseDouble(parts[1]),
                                    Double.parseDouble(parts[2]),
                                    Double.parseDouble(parts[3]),
                                    Float.parseFloat(parts[4]),
                                    Float.parseFloat(parts[5])),
                    mode));
        } catch (NumberFormatException unreadable) {
            return Optional.of(new Returned(null, mode));
        }
    }
}
