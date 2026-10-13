package com.uxplima.uxmskyblock.core.application.parkour;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/** The Parkour courses as a directory lists them, with what a runner chooses one by. */
public interface ParkourDirectoryPort {

    /**
     * One course in the directory.
     *
     * @param name the name its island was given, or none
     * @param owner the player who owns its island
     * @param stars the stars its runners gave it on average, 0 when none rated it
     * @param ratings how many runners rated it
     * @param record the fastest time anybody ran it in, in milliseconds, or none before a finish
     * @param runs how many runs have been finished on it, by everybody
     */
    record Listing(
            IslandId course,
            @Nullable String name,
            UUID owner,
            ParkourTagsPort.Tags tags,
            double stars,
            int ratings,
            @Nullable Long record,
            long runs) {

        public Listing {
            Objects.requireNonNull(course, "course must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(tags, "tags must not be null");
        }
    }

    /** The courses, the most run first, at most {@code limit}. */
    List<Listing> directory(int limit);
}
