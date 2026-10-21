package com.uxplima.uxmskyblock.bukkit.top;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import net.kyori.adventure.audience.Audience;

import org.jspecify.annotations.Nullable;

/** Draws what a display shows on the block or in the air where it stands. */
public interface TopPainter {

    /**
     * The island a display shows: its words as each reader reads them, and its owner for a head.
     *
     * @param owner the island owner's id, for a head, or nothing when unknown
     */
    record Shown(
            Function<Audience, Words> words,
            String ownerName,
            @Nullable UUID owner) {

        /** The words of this place, in {@code reader}'s language. */
        public Words in(Audience reader) {
            return words.apply(reader);
        }
    }

    /** The words of a place, in one reader's language. */
    record Words(String board, String island, String score) {}

    /** Draws {@code shown} on {@code display}, or that nobody holds its place yet. */
    void paint(TopDisplay display, Optional<Shown> shown);

    /** Takes what {@code display} drew away, for a display taken away. */
    void clear(TopDisplay display);
}
