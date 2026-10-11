package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.wall.WallOutline;
import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;

/**
 * Where a ChunkBlock island's open ground ends, for a Bedrock player. A Bedrock client draws no display entity, so
 * the wall every Java player sees is not there for them. Once a second, a line of dust is drawn along every wall near
 * each Bedrock player on such an island, three rows high around them, and shown to them alone.
 */
public final class BedrockEdges {

    /** Draws one speck for one player, on the player's own thread. */
    @FunctionalInterface
    public interface Speck {
        void draw(Player player, double x, double y, double z);
    }

    private final ChunkBlockService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Predicate<UUID> bedrock;
    private final WallConfiguration.Bedrock settings;
    private final Speck speck;

    public BedrockEdges(
            ChunkBlockService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Predicate<UUID> bedrock,
            WallConfiguration.Bedrock settings,
            Speck speck) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.speck = Objects.requireNonNull(speck, "speck must not be null");
    }

    /** A speck of dust in {@code settings}' colour, shown to the player alone. */
    public static Speck dust(WallConfiguration.Bedrock settings) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(settings.rgb()), 1.0f);
        return (player, x, y, z) -> player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private void round() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (bedrock.test(player.getUniqueId())) {
                scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> show(player));
            }
        }
    }

    /** Draws the edges near {@code player}, on their own thread, and answers how many specks. */
    public int show(Player player) {
        Location at = player.getLocation();
        if (at == null) {
            return 0;
        }
        Optional<Island> island = islands.findIslandAt(at);
        Optional<ChunkTerritory> territory = island.flatMap(found -> service.inMemory(found.id()));
        if (territory.isEmpty()) {
            return 0;
        }
        List<double[]> points =
                points(territory.get().open(), at.getX(), at.getZ(), settings.reach(), settings.spacing());
        for (double[] point : points) {
            for (int row = 0; row < 3; row++) {
                speck.draw(player, point[0], at.getY() + 0.5 + row, point[1]);
            }
        }
        return points.size() * 3;
    }

    /**
     * Every point along the walls of {@code open} that lies within {@code reach} blocks of {@code (x, z)}, one each
     * {@code spacing} blocks, as {@code {x, z}}.
     */
    static List<double[]> points(Set<ChunkPos> open, double x, double z, int reach, double spacing) {
        ChunkPos here = ChunkPos.ofBlock((int) Math.floor(x), (int) Math.floor(z));
        int around = reach / 16 + 1;
        List<double[]> points = new ArrayList<>();
        for (int dx = -around; dx <= around; dx++) {
            for (int dz = -around; dz <= around; dz++) {
                ChunkPos chunk = new ChunkPos(here.x() + dx, here.z() + dz);
                for (WallSegment wall : WallOutline.of(chunk, open)) {
                    for (double along = wall.from() + spacing / 2; along < wall.to(); along += spacing) {
                        double px = wall.side().alongX() ? along : wall.line();
                        double pz = wall.side().alongX() ? wall.line() : along;
                        if (Math.hypot(px - x, pz - z) <= reach) {
                            points.add(new double[] {px, pz});
                        }
                    }
                }
            }
        }
        return points;
    }
}
