package com.uxplima.uxmskyblock.core.domain.brix;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The time and weather a Brix plot's team sets for everyone standing on it.
 *
 * @param time the time of day, in ticks from 0 to 23999, or {@link #WORLD_TIME} for the world's own
 * @param weather the weather
 */
public record PlotSky(int time, Weather weather) {

    /** The time a plot leaves to its world. */
    public static final int WORLD_TIME = -1;

    /** A plot's sky as its world has it. */
    public static final PlotSky WORLD = new PlotSky(WORLD_TIME, Weather.WORLD);

    /** The weather on a plot. */
    public enum Weather {
        /** The world's own. */
        WORLD,
        /** Clear, whatever the world's is. */
        CLEAR,
        /** Rain or snow, whatever the world's is. */
        RAIN
    }

    public PlotSky {
        Objects.requireNonNull(weather, "weather must not be null");
        if (time < WORLD_TIME || time > 23_999) {
            throw new IllegalArgumentException("a plot's time is from 0 to 23999 ticks, or the world's");
        }
    }

    /** Whether the plot leaves both to its world. */
    public boolean isWorld() {
        return time == WORLD_TIME && weather == Weather.WORLD;
    }

    public PlotSky withTime(int ticks) {
        return new PlotSky(ticks, weather);
    }

    public PlotSky withWeather(Weather changed) {
        return new PlotSky(time, changed);
    }

    /**
     * A time written as a word or a number of ticks: day 1000, noon 6000, sunset 12000, night 13000, midnight 18000,
     * sunrise 23000, world for the world's own, or 0 to 23999.
     */
    public static Optional<Integer> timeOf(String written) {
        String word = written.trim().toLowerCase(Locale.ROOT);
        Integer named =
                switch (word) {
                    case "day" -> 1000;
                    case "noon" -> 6000;
                    case "sunset" -> 12_000;
                    case "night" -> 13_000;
                    case "midnight" -> 18_000;
                    case "sunrise" -> 23_000;
                    case "world" -> WORLD_TIME;
                    default -> null;
                };
        if (named != null) {
            return Optional.of(named);
        }
        try {
            int ticks = Integer.parseInt(word);
            return ticks >= 0 && ticks <= 23_999 ? Optional.of(ticks) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A weather written as clear, rain or world. */
    public static Optional<Weather> weatherOf(String written) {
        return switch (written.trim().toLowerCase(Locale.ROOT)) {
            case "clear", "sun" -> Optional.of(Weather.CLEAR);
            case "rain", "storm", "snow" -> Optional.of(Weather.RAIN);
            case "world" -> Optional.of(Weather.WORLD);
            default -> Optional.empty();
        };
    }
}
