package com.uxplima.uxmskyblock.core.application.parkour;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/** Where each course's difficulty and category are kept, as its team gives them, and the stars its runners give it. */
public interface ParkourTagsPort {

    /** A course's difficulty and category, each by the operator's name for it, or none given. */
    record Tags(@Nullable String difficulty, @Nullable String category) {

        public static final Tags NONE = new Tags(null, null);
    }

    /** How a course is rated: the stars its runners gave it on average, and how many gave them. */
    record Rating(double average, int count) {

        public static final Rating NONE = new Rating(0, 0);
    }

    /** The course's tags, none when its team gave none. */
    Tags tags(IslandId course);

    /** Gives the course these tags, in place of those it had. */
    void tag(IslandId course, Tags tags);

    /** How the course is rated. */
    Rating rating(IslandId course);

    /** The runner rates the course, in place of any rating they gave it before. */
    void rate(IslandId course, PlayerUuid runner, int stars);
}
