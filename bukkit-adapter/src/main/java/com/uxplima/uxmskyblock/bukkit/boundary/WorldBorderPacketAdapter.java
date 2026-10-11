package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.application.boundary.WorldBorderPacketPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Bukkit/Paper adapter for per-player clientbound virtual WorldBorder packets.
 */
public final class WorldBorderPacketAdapter implements WorldBorderPacketPort {

    private final SchedulerPort schedulerPort;
    private final java.util.function.Supplier<WorldBorder> borders;

    public WorldBorderPacketAdapter(SchedulerPort schedulerPort) {
        this(schedulerPort, Bukkit::createWorldBorder);
    }

    /** An adapter that makes each player's border with {@code borders}, as a test hands it one. */
    WorldBorderPacketAdapter(SchedulerPort schedulerPort, java.util.function.Supplier<WorldBorder> borders) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.borders = Objects.requireNonNull(borders, "borders must not be null");
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void sendWorldBorder(
            PlayerUuid playerUuid,
            int centerX,
            int centerZ,
            double radius,
            double oldRadius,
            long transitionDurationMs) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player == null || !player.isOnline()) {
                return;
            }
            WorldBorder border = borders.get();
            border.setCenter(centerX + 0.5, centerZ + 0.5);
            if (oldRadius > 0.0 && transitionDurationMs > 0L) {
                border.setSize(widthOf(oldRadius));
                border.setSize(widthOf(radius), Math.max(1L, transitionDurationMs / 1000L));
            } else {
                BorderChoice.colour(player).paint(border, widthOf(radius));
            }
            player.setWorldBorder(border);
        });
    }

    /**
     * How wide the border of an island of this radius is. The island holds its centre block and
     * {@code radius} blocks either side of it, so a radius of 50 is 101 blocks across. The border was
     * drawn 100 wide around the middle of the centre block, half a block inside the island on each
     * side, so the edge blocks a player owned stood half outside it.
     */
    static double widthOf(double radius) {
        return radius * 2.0 + 1.0;
    }

    @Override
    public void resetWorldBorder(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player != null && player.isOnline()) {
                player.setWorldBorder(null);
            }
        });
    }
}
