package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.BoxedLand;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedLandPort;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Lays the template's land on Boxed islands, so every island plays on the same land.
 *
 * <p>The land is copied in rings of chunks around the chunk an island's centre stands in, as far as the box reaches
 * and {@link BoxedLand#ahead()} blocks past it, so the land a player sees past the box is the land it grows into.
 * Each ring is written down once it stands, so a ring cut short by a stop is copied again, whole, the next time the
 * island is looked at. One island copies one ring at a time, a chunk at a time, each on the thread that owns it.
 */
public final class BoxedLandCopier {

    /** Copies one chunk of a world onto another chunk of the same world, on the threads that own them. */
    @FunctionalInterface
    public interface ChunkCopy {
        CompletableFuture<Void> copy(String world, int fromX, int fromZ, int toX, int toZ);
    }

    private static final Logger LOGGER = Logger.getLogger(BoxedLandCopier.class.getName());

    private final BoxedLandPort port;
    private final BoxedService service;
    private final Function<IslandId, Optional<IslandLocation>> places;
    private final ChunkCopy chunks;
    private final SchedulerPort scheduler;
    private final BoxedLand land;
    private final Map<IslandId, CompletableFuture<Void>> running = new ConcurrentHashMap<>();
    private final Map<IslandId, CompletableFuture<Void>> after = new ConcurrentHashMap<>();

    /** @param places where an island stands, read from storage */
    public BoxedLandCopier(
            BoxedLandPort port,
            BoxedService service,
            Function<IslandId, Optional<IslandLocation>> places,
            ChunkCopy chunks,
            SchedulerPort scheduler,
            BoxedLand land) {
        this.port = Objects.requireNonNull(port, "port");
        this.service = Objects.requireNonNull(service, "service");
        this.places = Objects.requireNonNull(places, "places");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.land = Objects.requireNonNull(land, "land");
    }

    /**
     * Puts a new Boxed island on the template's land, while the operator has new islands played on it, and copies as
     * much as its box wants. Done once that much stands, or once it could not be done, which is logged.
     */
    public CompletableFuture<Void> begin(IslandId islandId) {
        if (!land.template()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> marked = new CompletableFuture<>();
        scheduler.async(() -> {
            try {
                port.begin(islandId);
                marked.complete(null);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be put on the template's land.");
                marked.complete(null);
            }
        });
        return marked.thenCompose(ignored -> catchUp(islandId));
    }

    /**
     * Copies the template's land out to what the island's box wants now. Nothing for an island played on the land its
     * world made. Done once it stands; asked again while it is copying, done once a second look after it stands.
     */
    public CompletableFuture<Void> catchUp(IslandId islandId) {
        synchronized (running) {
            if (running.containsKey(islandId)) {
                return after.computeIfAbsent(islandId, ignored -> new CompletableFuture<>());
            }
            CompletableFuture<Void> job = new CompletableFuture<>();
            running.put(islandId, job);
            run(islandId, job);
            return job;
        }
    }

    /** {@link #catchUp}, for whoever does not wait for it. */
    public void bringUp(IslandId islandId) {
        var unused = catchUp(islandId);
    }

    /** Lets go of what is copying for an island that is gone. */
    public void forget(IslandId islandId) {
        synchronized (running) {
            running.remove(islandId);
            CompletableFuture<Void> waiting = after.remove(islandId);
            if (waiting != null) {
                waiting.complete(null);
            }
        }
    }

    private void run(IslandId islandId, CompletableFuture<Void> job) {
        scheduler.async(() -> {
            try {
                OptionalInt kept = port.rings(islandId);
                OptionalInt radius = service.radius(islandId);
                Optional<IslandLocation> place = places.apply(islandId);
                if (kept.isEmpty() || radius.isEmpty() || place.isEmpty()) {
                    finish(islandId, job);
                    return;
                }
                ring(islandId, place.get(), kept.getAsInt(), land.rings(radius.getAsInt()), job);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The land of island " + islandId + " could not be read.");
                finish(islandId, job);
            }
        });
    }

    private void ring(IslandId islandId, IslandLocation place, int done, int wanted, CompletableFuture<Void> job) {
        if (done >= wanted) {
            finish(islandId, job);
            return;
        }
        int next = done + 1;
        var unused = copyRing(place, next)
                .whenComplete((ignored, failed) -> scheduler.async(() -> {
                    if (failed != null) {
                        LOGGER.log(
                                Level.WARNING,
                                failed,
                                () -> "Ring " + next + " of the land of island " + islandId
                                        + " could not be copied. It is copied again the next time the island is looked at.");
                        finish(islandId, job);
                        return;
                    }
                    try {
                        if (port.copied(islandId, done, next)) {
                            ring(islandId, place, next, wanted, job);
                        } else {
                            // Another node moved the land on: look again.
                            finish(islandId, job);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.log(
                                Level.WARNING,
                                e,
                                () -> "The land of island " + islandId + " could not be written down.");
                        finish(islandId, job);
                    }
                }));
    }

    /** The chunks of one ring, one after the other. */
    private CompletableFuture<Void> copyRing(IslandLocation place, int ring) {
        int centerX = Math.floorDiv(place.bounds().centerX(), 16);
        int centerZ = Math.floorDiv(place.bounds().centerZ(), 16);
        int fromX = Math.floorDiv(land.x(), 16);
        int fromZ = Math.floorDiv(land.z(), 16);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                    continue;
                }
                int x = dx;
                int z = dz;
                chain = chain.thenCompose(
                        ignored -> chunks.copy(place.worldName(), fromX + x, fromZ + z, centerX + x, centerZ + z));
            }
        }
        return chain;
    }

    private void finish(IslandId islandId, CompletableFuture<Void> job) {
        CompletableFuture<Void> waiting;
        synchronized (running) {
            running.remove(islandId, job);
            waiting = after.remove(islandId);
            if (waiting != null) {
                running.put(islandId, waiting);
            }
        }
        job.complete(null);
        if (waiting != null) {
            run(islandId, waiting);
        }
    }
}
