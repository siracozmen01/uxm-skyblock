package com.uxplima.uxmskyblock.bukkit.schematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmlib.schematic.paper.PasteReport;
import com.uxplima.uxmlib.schematic.paper.Rotation;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActions;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.jspecify.annotations.Nullable;

/**
 * Builds a new island by the creation actions its preset names.
 *
 * <p>The starter platform comes from the preset's schematic file when one stands where the preset says,
 * and is laid block by block otherwise. The plugin writes the platform it ships as that file on its first
 * start, so an operator who wants another island edits a file rather than code, with any tool that writes
 * a Sponge schematic, or with {@code /is schematic save}. A file that cannot be read is named in the log
 * and the shipped platform is laid instead, so nobody arrives over the void.
 */
public final class StarterSchematicEngine {

    private static final Logger LOGGER = Logger.getLogger(StarterSchematicEngine.class.getName());

    /** Where the feature stands, from the centre. See {@link PlatformLayout#FEATURE_OFFSET}. */
    static final int FEATURE_OFFSET = PlatformLayout.FEATURE_OFFSET;

    private final com.uxplima.uxmskyblock.core.application.performance.@Nullable AdaptiveBackpressureController
            backpressureController;

    private final @Nullable IslandSchematics schematics;
    private final @Nullable SchedulerPort scheduler;
    private final Function<IslandDimensionType, Optional<String>> dimensionSchematics;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private final CreationActions<IslandStart> actions = new CreationActions<>();

    public StarterSchematicEngine(
            com.uxplima.uxmskyblock.core.application.performance.@Nullable AdaptiveBackpressureController
                    backpressureController) {
        this(backpressureController, null, null, dimension -> Optional.empty());
    }

    /**
     * @param schematics the server's structure files, or nothing to lay every platform block by block
     * @param scheduler what hands an action back to the region its island stands in after a paste
     * @param dimensionSchematics the name of the schematic a dimension's platform is pasted from
     */
    public StarterSchematicEngine(
            com.uxplima.uxmskyblock.core.application.performance.@Nullable AdaptiveBackpressureController
                    backpressureController,
            @Nullable IslandSchematics schematics,
            @Nullable SchedulerPort scheduler,
            Function<IslandDimensionType, Optional<String>> dimensionSchematics) {
        this.backpressureController = backpressureController;
        this.schematics = schematics;
        this.scheduler = scheduler;
        this.dimensionSchematics = Objects.requireNonNull(dimensionSchematics, "dimensionSchematics must not be null");
        actions.register(new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return StarterPreset.PLATFORM;
            }

            @Override
            public void apply(IslandStart start) {
                pastePreset(start.world(), start.centerX(), start.y(), start.centerZ(), start.preset());
            }

            @Override
            public CompletableFuture<Void> applyThen(IslandStart start) {
                return fromFile(start, Optional.of(start.preset().schematicPath()), () -> apply(start));
            }

            @Override
            public List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
                return pasted(start);
            }
        });
        actions.register(new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle.DIMENSION_PLATFORM;
            }

            @Override
            public void apply(IslandStart start) {
                pasteDimensionPlatform(
                        start.world(), start.centerX(), start.y(), start.centerZ(), dimensionOf(start.world()));
            }

            @Override
            public CompletableFuture<Void> applyThen(IslandStart start) {
                Optional<String> path = StarterSchematicEngine.this
                        .dimensionSchematics
                        .apply(dimensionOf(start.world()))
                        .flatMap(IslandSchematics::nameOf)
                        .map(IslandSchematics::pathOf);
                return fromFile(start, path, () -> apply(start));
            }

            @Override
            public List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
                return pasted(start);
            }
        });
    }

    public StarterSchematicEngine() {
        this(null);
    }

    /**
     * Runs the named creation actions at the place the start describes, as a dimension's template does.
     * Answers once the last of them stands.
     */
    public CompletableFuture<Void> build(IslandStart start, List<String> actions) {
        return noted(start, actions, this.actions.runThen(actions, start, resume(start)));
    }

    /** The creation actions this server provides; a game mode adds its own while the server starts. */
    public CreationActions<IslandStart> actions() {
        return actions;
    }

    /**
     * Builds a new island by running the creation actions its preset names, in the region that owns it.
     * Answers once the last of them stands, which for a pasted schematic is some ticks later.
     */
    public CompletableFuture<Void> start(IslandStart start) {
        List<String> names = start.preset().start();
        return noted(start, names, actions.runThen(names, start, resume(start)));
    }

    /** Where each island wrote blocks, kept so the island is cleared over all of it when it goes. */
    private volatile com.uxplima.uxmskyblock.core.application.recycle.@Nullable IslandFootprintPort footprints;

    /** How far each pasted file reached from its island's centre, by island and world, until it is noted. */
    private final Map<String, Integer> pastedReach = new java.util.concurrent.ConcurrentHashMap<>();

    /** Hands the engine where to keep what each island's start wrote. */
    public void useFootprints(com.uxplima.uxmskyblock.core.application.recycle.@Nullable IslandFootprintPort port) {
        this.footprints = port;
    }

    /**
     * Notes where the start writes before it runs, and again once it is done, when a pasted file has said how far
     * it reached. A start that fails halfway has still written, so it is noted either way. The world it is laid in
     * is noted too, for an island's Nether or End, which is cleared as far as its border.
     */
    private CompletableFuture<Void> noted(IslandStart start, List<String> names, CompletableFuture<Void> built) {
        note(start, names);
        var unused = built.whenComplete((done, failure) -> note(start, names));
        return built;
    }

    private void note(IslandStart start, List<String> names) {
        com.uxplima.uxmskyblock.core.application.recycle.IslandFootprintPort port = this.footprints;
        if (port == null) {
            return;
        }
        List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach = new ArrayList<>();
        reach.add(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), 0));
        reach.addAll(actions.reachOf(names, start));
        Runnable write = () -> {
            for (var square : reach) {
                port.record(
                        start.islandId(),
                        new com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint(
                                square.world(), start.centerX(), start.centerZ(), square.radius()));
            }
        };
        SchedulerPort async = this.scheduler;
        if (async == null) {
            write.run();
        } else {
            async.async(write);
        }
    }

    /** How far a pasted file reached from the island's centre in the start's world, or nothing. */
    private List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> pasted(IslandStart start) {
        Integer radius =
                pastedReach.get(start.islandId().value() + "@" + start.world().getName());
        return radius == null
                ? List.of()
                : List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                        start.world().getName(), radius));
    }

    /** How far a file pasted at its origin reaches sideways: the furthest of its four sides. */
    static int reachOf(com.uxplima.uxmlib.schematic.Schematic schematic) {
        int fromX = schematic.offset().x();
        int fromZ = schematic.offset().z();
        int toX = fromX + schematic.width() - 1;
        int toZ = fromZ + schematic.length() - 1;
        return Math.max(Math.max(Math.abs(fromX), Math.abs(toX)), Math.max(Math.abs(fromZ), Math.abs(toZ)));
    }

    public com.uxplima.uxmskyblock.core.application.performance.@Nullable AdaptiveBackpressureController
            backpressureController() {
        return backpressureController;
    }

    /**
     * Writes the platform each preset that lays one would lay, and each dimension's, as the file it names,
     * where no file stands yet. Answers the paths written.
     */
    public CompletableFuture<List<String>> writeShippedPlatforms(
            List<StarterPreset> presets, Map<IslandDimensionType, String> dimensions, int dataVersion) {
        IslandSchematics files = schematics;
        if (files == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        Map<String, PlatformLayout> wanted = new LinkedHashMap<>();
        for (StarterPreset preset : presets) {
            boolean laysPlatform =
                    preset.start().stream().anyMatch(action -> action.trim().equalsIgnoreCase(StarterPreset.PLATFORM));
            if (laysPlatform) {
                wanted.putIfAbsent(preset.schematicPath(), PlatformLayout.ofPreset(preset.id()));
            }
        }
        dimensions.forEach((dimension, name) -> IslandSchematics.nameOf(name)
                .ifPresent(valid ->
                        wanted.putIfAbsent(IslandSchematics.pathOf(valid), PlatformLayout.ofDimension(dimension))));
        List<CompletableFuture<Optional<String>>> writes = new ArrayList<>();
        wanted.forEach((path, layout) -> writes.add(files.writeIfMissing(path, layout.schematic(dataVersion))
                .thenApply(written -> written ? Optional.of(path) : Optional.<String>empty())));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenApply(done -> writes.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)
                        .toList());
    }

    /**
     * Runs {@code then} once {@code built} is done: at once when it already is, so a build done at once
     * changes nothing about the order things happen in, and when it finishes otherwise. {@code failed}
     * hears why a build failed before {@code then} runs anyway, since a player still has to arrive. What
     * {@code then} throws after a later finish is written to the log rather than lost in the future.
     */
    public static void afterBuilt(CompletableFuture<Void> built, Consumer<Throwable> failed, Runnable then) {
        if (built.isDone()) {
            try {
                built.join();
            } catch (CompletionException | java.util.concurrent.CancellationException failure) {
                failed.accept(failure);
            }
            then.run();
            return;
        }
        var unused = built.whenComplete((done, failure) -> {
            if (failure != null) {
                failed.accept(failure);
            }
            try {
                then.run();
            } catch (RuntimeException thrown) {
                LOGGER.log(Level.WARNING, "What follows a built island threw.", thrown);
            }
        });
    }

    /**
     * The height of the platform's top for an island whose players arrive at {@code spawnY}: the block
     * they stand on. The platform was laid at the spawn height itself, so a player arrived with their
     * feet in the grass and was pushed up into whatever stood above it.
     */
    public static int platformBelow(double spawnY) {
        return (int) Math.floor(spawnY) - 1;
    }

    public void pastePreset(World world, int centerX, int y, int centerZ, StarterPreset preset) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(preset, "preset");
        PlatformLayout.ofPreset(preset.id()).lay(world, centerX, y, centerZ);
    }

    public void pasteDimensionPlatform(
            World world, int centerX, int y, int centerZ, IslandDimensionType dimensionType) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimensionType, "dimensionType");
        PlatformLayout.ofDimension(dimensionType).lay(world, centerX, y, centerZ);
    }

    /**
     * Pastes the file at {@code path} at the start's centre, or runs {@code laid} on the island's region
     * when there is no file, or none that can be read or pasted.
     */
    private CompletableFuture<Void> fromFile(IslandStart start, Optional<String> path, Runnable laid) {
        IslandSchematics files = schematics;
        if (files == null || path.isEmpty()) {
            laid.run();
            return CompletableFuture.completedFuture(null);
        }
        String file = path.get();
        Executor region = resume(start);
        Location at = new Location(start.world(), start.centerX(), start.y(), start.centerZ());
        return files.read(file)
                .thenCompose(found -> {
                    if (found.isEmpty()) {
                        return CompletableFuture.runAsync(laid, region);
                    }
                    pastedReach.merge(
                            start.islandId().value() + "@" + start.world().getName(), reachOf(found.get()), Math::max);
                    return files.paste(found.get(), at, Rotation.NONE).thenAccept(report -> noteChanges(file, report));
                })
                .exceptionallyCompose(failure -> {
                    if (warned.add("failed:" + file)) {
                        LOGGER.log(
                                Level.WARNING,
                                "The schematic " + file + " could not be pasted, so the shipped platform is laid: "
                                        + rootMessage(failure),
                                failure);
                    }
                    return CompletableFuture.runAsync(laid, region);
                });
    }

    private void noteChanges(String file, PasteReport report) {
        if (report.faithful() || !warned.add("changed:" + file)) {
            return;
        }
        LOGGER.warning(() -> "The schematic " + file + " was pasted with changes. Read as other blocks: "
                + report.statesChanged() + ". Unknown blocks, left out: " + report.statesUnknown()
                + ". Block entities with their defaults: " + report.blockEntitiesNotCarried()
                + ". Entities not made: " + report.entitiesRefused()
                + ". Blocks past the world's height: " + report.blocksOutsideWorld() + ".");
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /** Hands a task to the region the start's centre stands in, or runs it at once without a scheduler. */
    private Executor resume(IslandStart start) {
        SchedulerPort port = scheduler;
        if (port == null) {
            return Runnable::run;
        }
        String world = start.world().getName();
        int chunkX = start.centerX() >> 4;
        int chunkZ = start.centerZ() >> 4;
        return task -> port.onRegion(world, chunkX, chunkZ, task);
    }

    /** Which dimension a world is, by the environment the server gave it. */
    private static IslandDimensionType dimensionOf(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> IslandDimensionType.NETHER;
            case THE_END -> IslandDimensionType.THE_END;
            default -> IslandDimensionType.OVERWORLD;
        };
    }
}
