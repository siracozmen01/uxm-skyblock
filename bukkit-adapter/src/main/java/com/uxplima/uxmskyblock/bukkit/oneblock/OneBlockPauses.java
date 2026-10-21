package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockWaitPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * A OneBlock phase that sets a wait: once it begins, its block is bedrock with a countdown over it, and when the
 * time is up the phase's first block is laid. The wait is stored, so a restart goes on with it where it stood.
 * Everything that touches the block runs on the block's region.
 */
public final class OneBlockPauses {

    private static final Logger LOGGER = Logger.getLogger(OneBlockPauses.class.getName());

    /** How often the countdown is told again. */
    static final Duration TICK = Duration.ofSeconds(1);

    /** How near the block a Bedrock player reads the countdown on the action bar, who is shown no line. */
    static final double HEARD_WITHIN = 16;

    /** A line over a block: a countdown, or a OneBlock island's progress. */
    public interface Shown {
        /** Says {@code line} from now on, as each viewer reads it. */
        void say(Function<Player, Component> line);

        void remove();

        /** Whether the line is still in the world, which a chunk let go of or a restart takes it out of. */
        default boolean showing() {
            return true;
        }
    }

    /** How a line is put over a block, in the words each viewer reads. */
    @FunctionalInterface
    public interface Lines {
        Shown show(World world, Location at, Function<Player, Component> line);
    }

    private final java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration> config;
    private final OneBlockService service;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final OneBlockDraws draws;
    private final Predicate<Player> bedrock;
    private final Clock clock;
    private final Lines lines;

    /** Waits read back before their world was loaded, by the world's name, gone on with once it is. */
    private final java.util.Map<String, java.util.Queue<Runnable>> pending =
            new java.util.concurrent.ConcurrentHashMap<>();

    public OneBlockPauses(
            java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration> config,
            OneBlockService service,
            SchedulerPort scheduler,
            Messages messages,
            OneBlockDraws draws,
            Predicate<Player> bedrock,
            Clock clock,
            Lines lines) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.draws = Objects.requireNonNull(draws, "draws must not be null");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.lines = Objects.requireNonNull(lines, "lines must not be null");
    }

    /** As on a server: the system clock. */
    public OneBlockPauses(
            java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration> config,
            OneBlockService service,
            SchedulerPort scheduler,
            Messages messages,
            OneBlockDraws draws,
            Predicate<Player> bedrock,
            Lines lines) {
        this(config, service, scheduler, messages, draws, bedrock, Clock.systemUTC(), lines);
    }

    /** How long the phase's block stays bedrock once it begins, nothing when its file sets no wait. */
    public Duration pauseOf(String phase) {
        return config.get().gateOf(phase).pause();
    }

    /** Whether the island waits for a time to pass, so its bedrock is not to be broken. */
    public boolean holds(IslandId islandId) {
        return service.waiting(islandId).filter(OneBlockWaitPort.Waiting::timed).isPresent();
    }

    /**
     * Begins the wait of {@code phase} at the island's block, on its region: bedrock, the countdown over it, and the
     * wait written off the region.
     */
    public void begin(IslandId islandId, String phase, Duration pause, World world, int x, int y, int z) {
        Instant until = clock.instant().plus(pause);
        if (!service.waitBefore(islandId, phase, until)) {
            return;
        }
        OneBlockWaitPort.Waiting waiting = service.waiting(islandId).orElseThrow();
        scheduler.async(() -> service.storeWait(islandId));
        world.getBlockAt(x, y, z).setType(Material.BEDROCK, false);
        run(waiting, world, x, y, z);
    }

    /**
     * Goes on with a wait read back after a restart, at the island's block in {@code worldOf} its world. The plugin
     * starts before the worlds are loaded, so a wait whose world is not loaded yet goes on once it is.
     */
    public void resume(OneBlockWaitPort.Waiting waiting, Function<IslandId, Optional<String>> worldOf) {
        IslandId islandId = waiting.islandId();
        Optional<OneBlockProgressPort.OneBlockIsland> island = service.island(islandId);
        Optional<String> named = worldOf.apply(islandId);
        if (island.isEmpty() || named.isEmpty()) {
            LOGGER.fine(() -> "The OneBlock wait of island " + islandId + " has no world to go on in.");
            return;
        }
        Optional<World> world = named.map(Bukkit::getWorld);
        if (world.isEmpty()) {
            pending.computeIfAbsent(named.get(), name -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                    .add(() -> resume(waiting, worldOf));
            // Loaded while the line above was written: nothing else would go on with it.
            World loaded = Bukkit.getWorld(named.get());
            if (loaded != null) {
                worldLoaded(loaded);
            }
            return;
        }
        int x = island.get().x();
        int y = island.get().y();
        int z = island.get().z();
        World at = world.get();
        scheduler.onRegion(at.getName(), x >> 4, z >> 4, () -> {
            at.getBlockAt(x, y, z).setType(Material.BEDROCK, false);
            run(waiting, at, x, y, z);
        });
    }

    /** Goes on with every wait that was read back before {@code world} was loaded. */
    public void worldLoaded(World world) {
        java.util.Queue<Runnable> waiting = pending.remove(world.getName());
        if (waiting == null) {
            return;
        }
        for (Runnable resume = waiting.poll(); resume != null; resume = waiting.poll()) {
            resume.run();
        }
    }

    private void run(OneBlockWaitPort.Waiting waiting, World world, int x, int y, int z) {
        Location over = new Location(world, x + 0.5, y + 1 + OneBlockMoments.TEXT_RISE, z + 0.5);
        Shown shown = lines.show(world, over, viewer -> line(viewer, waiting));
        tick(waiting, shown, world, x, y, z);
    }

    /** Tells the countdown again, or ends the wait once its time is up or someone else ended it. On the region. */
    private void tick(OneBlockWaitPort.Waiting waiting, Shown shown, World world, int x, int y, int z) {
        IslandId islandId = waiting.islandId();
        if (!waiting.equals(service.waiting(islandId).orElse(null))) {
            shown.remove();
            return;
        }
        if (!clock.instant().isBefore(Objects.requireNonNull(waiting.until()))) {
            shown.remove();
            if (service.endWait(islandId, waiting)) {
                scheduler.async(() -> service.storeWait(islandId));
                service.blockNow(islandId).ifPresent(block -> {
                    OneBlockListener.lay(draws, world, x, y, z, block);
                    var unused = draws.fill(world, x, y, z, block, waiting.phase());
                });
            }
            return;
        }
        shown.say(viewer -> line(viewer, waiting));
        Location block = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        for (Player near : world.getNearbyPlayers(block, HEARD_WITHIN)) {
            if (bedrock.test(near)) {
                near.sendActionBar(line(near, waiting));
            }
        }
        scheduler.asyncAfter(
                TICK,
                () -> scheduler.onRegion(world.getName(), x >> 4, z >> 4, () -> tick(waiting, shown, world, x, y, z)));
    }

    /** The countdown, as {@code viewer} reads it: the phase and the time left. */
    private Component line(Audience viewer, OneBlockWaitPort.Waiting waiting) {
        Duration left = Duration.between(clock.instant(), Objects.requireNonNull(waiting.until()));
        String phase = messages.named(viewer, "oneblock.phases", waiting.phase(), waiting.phase());
        return messages.renderPlain(
                viewer,
                "oneblock.pause_line",
                Placeholder.unparsed("phase", phase),
                Placeholder.unparsed("time", clockFace(left)));
    }

    /** {@code 4:05}, or {@code 1:02:03} past an hour; nothing left reads {@code 0:00}. */
    static String clockFace(Duration left) {
        long seconds = Math.max(0, (left.toMillis() + 999) / 1000);
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
