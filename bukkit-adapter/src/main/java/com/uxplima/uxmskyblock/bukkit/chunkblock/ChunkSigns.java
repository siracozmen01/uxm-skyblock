package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * A sign over each closed chunk a ChunkBlock island can open next: its grade, and a box a member clicks to see its
 * price. It stands just inside the closed chunk, at the middle of a side it shares with open ground, where a player
 * at the edge can reach it.
 *
 * <p>Signs are drawn when a chunk loads, and drawn again in a chunk and its neighbours when one opens or closes. The
 * text is the server's own language: one entity reads the same to everybody who sees it. Members and visitors see
 * it; only a member's click opens the window.
 */
public final class ChunkSigns implements Listener {

    /** Where a sign stands: the middle of its box's floor. */
    public record Spot(double x, double y, double z) {}

    /** What a sign needs of its island that a chunk does not hold: its world, edge and height. */
    record Place(String world, IslandBounds bounds, double y) {}

    private final ChunkBlockService service;
    private final ChunkTiers tiers;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final SignPainter painter;
    private final Messages messages;
    private final Function<IslandId, Optional<IslandLocation>> locationOf;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final BiConsumer<Player, ChunkPos> open;
    private final Map<IslandId, Place> places = new ConcurrentHashMap<>();

    /**
     * @param locationOf an island's location, read off the main thread: where its signs stand and how high
     * @param open shows a member the window of a chunk's price
     */
    public ChunkSigns(
            ChunkBlockService service,
            ChunkTiers tiers,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            SignPainter painter,
            Messages messages,
            Function<IslandId, Optional<IslandLocation>> locationOf,
            Function<UUID, Optional<ProfileId>> activeProfile,
            BiConsumer<Player, ChunkPos> open) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.tiers = Objects.requireNonNull(tiers, "tiers must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.painter = Objects.requireNonNull(painter, "painter must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.locationOf = Objects.requireNonNull(locationOf, "locationOf must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.open = Objects.requireNonNull(open, "open must not be null");
    }

    /** A chunk of a ChunkBlock island that loads stands its sign, on its own thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Optional<Island> island = islands.findIslandAt(new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8));
        if (island.isEmpty() || service.inMemory(island.get().id()).isEmpty()) {
            return;
        }
        IslandId islandId = island.get().id();
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        Place place = places.get(islandId);
        if (place != null) {
            scheduler.onRegion(world.getName(), chunkX, chunkZ, () -> redraw(islandId, place, chunk));
            return;
        }
        // The island's height is a row away; read it once, off this thread, and draw then.
        scheduler.async(() -> placeOf(islandId)
                .ifPresent(
                        read -> scheduler.onRegion(read.world(), chunkX, chunkZ, () -> redraw(islandId, read, chunk))));
    }

    /** A chunk opened: its sign comes down, and the closed chunks beside it stand theirs. */
    public void opened(IslandId islandId, ChunkPos chunk) {
        redrawAround(islandId, List.of(chunk));
    }

    /** Chunks closed: each stands a sign again where it can open, and its neighbours look again. */
    public void closed(IslandId islandId, List<ChunkPos> chunks) {
        redrawAround(islandId, chunks);
    }

    /** A click on a sign: a member of its island sees the window of the chunk's price. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction box)) {
            return;
        }
        Optional<ChunkSignEntities.Marked> marked = ChunkSignEntities.marked(box);
        if (marked.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Optional<Island> island = islands.findIslandAt(box.getLocation());
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        boolean member = island.isPresent()
                && island.get().id().value().toString().equals(marked.get().owner())
                && profile.isPresent()
                && island.get().isMember(profile.get());
        if (!member) {
            messages.send(player, "chunkblock.sign_not_yours");
            return;
        }
        open.accept(player, marked.get().chunk());
    }

    /**
     * Where the sign of {@code closed} stands, at {@code y}: one block inside it from the middle of the side it shares
     * with open ground. Of several such sides, the one nearest the island's first chunk, then west, east, north and
     * south.
     */
    static Optional<Spot> spot(ChunkPos closed, ChunkTerritory territory, double y) {
        Set<ChunkPos> open = territory.open();
        double left = closed.x() * 16.0;
        double top = closed.z() * 16.0;
        List<Side> sides = List.of(
                new Side(new ChunkPos(closed.x() - 1, closed.z()), new Spot(left + 1, y, top + 8)),
                new Side(new ChunkPos(closed.x() + 1, closed.z()), new Spot(left + 15, y, top + 8)),
                new Side(new ChunkPos(closed.x(), closed.z() - 1), new Spot(left + 8, y, top + 1)),
                new Side(new ChunkPos(closed.x(), closed.z() + 1), new Spot(left + 8, y, top + 15)));
        ChunkPos origin = territory.origin();
        return sides.stream()
                .filter(side -> open.contains(side.beside()))
                .min(Comparator.comparingInt((Side side) -> ChunkTiers.ring(origin, side.beside())))
                .map(Side::spot);
    }

    private record Side(ChunkPos beside, Spot spot) {}

    /** The words over {@code chunk}, in the server's own language. */
    Component text(IslandId islandId, ChunkTerritory territory, ChunkPos chunk) {
        var console = Bukkit.getConsoleSender();
        String tier = messages.words(
                console, tiers.of(islandId, territory.origin(), chunk).name());
        return messages.render(console, "chunkblock.sign", Placeholder.unparsed("tier", tier));
    }

    /**
     * Takes down the island's sign in {@code chunk} and stands the one it holds now, on the chunk's own thread. A
     * chunk that is not loaded is left: it stands its sign when it loads.
     */
    void redraw(IslandId islandId, Place place, ChunkPos chunk) {
        World world = Bukkit.getWorld(place.world());
        if (world == null || !world.isChunkLoaded(chunk.x(), chunk.z())) {
            return;
        }
        String owner = islandId.value().toString();
        painter.clear(world.getChunkAt(chunk.x(), chunk.z()), owner);
        Optional<ChunkTerritory> territory = service.inMemory(islandId);
        if (territory.isEmpty() || territory.get().placeFor(chunk, place.bounds()) != ChunkTerritory.Unlock.UNLOCKED) {
            return;
        }
        spot(chunk, territory.get(), place.y())
                .ifPresent(spot -> painter.draw(world, owner, chunk, spot, text(islandId, territory.get(), chunk)));
    }

    /** Draws again the signs of {@code chunks} and their neighbours, once the island's place is known. */
    private void redrawAround(IslandId islandId, List<ChunkPos> chunks) {
        placeOf(islandId).ifPresent(place -> {
            Set<ChunkPos> all = new LinkedHashSet<>();
            for (ChunkPos chunk : chunks) {
                all.add(chunk);
                all.addAll(chunk.neighbours());
            }
            for (ChunkPos each : all) {
                scheduler.onRegion(place.world(), each.x(), each.z(), () -> redraw(islandId, place, each));
            }
        });
    }

    /** The island's place, read once and kept. Its world, edge and height do not change while it stands. */
    private Optional<Place> placeOf(IslandId islandId) {
        Place known = places.get(islandId);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<Place> read = locationOf
                .apply(islandId)
                .map(location -> new Place(location.worldName(), location.bounds(), location.spawnY()));
        read.ifPresent(place -> places.put(islandId, place));
        return read;
    }

    /** Forgets what was kept of an island that is gone or reset. */
    public void forget(IslandId islandId) {
        places.remove(islandId);
    }
}
