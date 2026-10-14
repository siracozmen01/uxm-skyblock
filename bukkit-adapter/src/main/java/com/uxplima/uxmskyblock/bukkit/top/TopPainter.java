package com.uxplima.uxmskyblock.bukkit.top;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Draws what a display shows on the block or in the air where it stands. */
public interface TopPainter {

    /**
     * The island a display shows, in words already chosen.
     *
     * @param owner the island owner's id, for a head, or nothing when unknown
     */
    record Shown(
            String board,
            String island,
            String ownerName,
            @Nullable UUID owner,
            String score) {}

    /** Draws {@code shown} on {@code display}, or that nobody holds its place yet. */
    void paint(TopDisplay display, Optional<Shown> shown);

    /** Takes what {@code display} drew away, for a display taken away. */
    void clear(TopDisplay display);
}
