package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The creation action that makes an island a Boxed island: it records the island, lays the template's land on it
 * when the operator has new islands played on the template, and shrinks its edge to the starting box.
 *
 * <p>Recording is a write, so it is done off the region's thread. The player arrives once the land the starting box
 * wants stands, and the edge is moved then.
 */
public final class BoxedStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:boxed";

    private static final Logger LOGGER = Logger.getLogger(BoxedStart.class.getName());

    private final BoxedService service;
    private final SchedulerPort scheduler;
    private final Consumer<IslandId> boxChanged;
    private final Function<IslandId, CompletableFuture<Void>> land;

    /** @param land lays the template's land on a new island, done once it stands */
    public BoxedStart(
            BoxedService service,
            SchedulerPort scheduler,
            Consumer<IslandId> boxChanged,
            Function<IslandId, CompletableFuture<Void>> land) {
        this.land = Objects.requireNonNull(land, "land must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.boxChanged = Objects.requireNonNull(boxChanged, "boxChanged must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    @Override
    public void apply(IslandStart start) {
        var unused = applyThen(start);
    }

    @Override
    public CompletableFuture<Void> applyThen(IslandStart start) {
        IslandId islandId = start.islandId();
        CompletableFuture<Void> done = new CompletableFuture<>();
        scheduler.async(() -> {
            try {
                service.start(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be made a Boxed island.");
                done.complete(null);
                return;
            }
            var unused = land.apply(islandId).whenComplete((ignored, failed) -> {
                try {
                    boxChanged.accept(islandId);
                } finally {
                    done.complete(null);
                }
            });
        });
        return done;
    }
}
