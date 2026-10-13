package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/parkour.conf}: whether islands can be Parkour courses, which blocks mark a course's
 * start, checkpoints and finish, and how a run is kept.
 */
public record ParkourConfiguration(
        boolean enabled,
        Markers markers,
        Runs runs,
        Modes modes,
        Ghost ghost,
        Rewards rewards,
        Tagging tagging,
        Ratings ratings) {

    private static final Logger LOGGER = Logger.getLogger(ParkourConfiguration.class.getName());

    /**
     * What marks a course.
     *
     * @param plate the plate a run starts and ends on
     * @param startUnder the block under a plate that makes it the start
     * @param finishUnder the block under a plate that makes it the finish
     * @param checkpoint the plate that is a checkpoint wherever it stands
     */
    public record Markers(Material plate, Material startUnder, Material finishUnder, Material checkpoint) {

        public static final Markers SHIPPED = new Markers(
                Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
                Material.EMERALD_BLOCK,
                Material.GOLD_BLOCK,
                Material.HEAVY_WEIGHTED_PRESSURE_PLATE);

        public Markers {
            Objects.requireNonNull(plate, "plate must not be null");
            Objects.requireNonNull(startUnder, "startUnder must not be null");
            Objects.requireNonNull(finishUnder, "finishUnder must not be null");
            Objects.requireNonNull(checkpoint, "checkpoint must not be null");
            if (startUnder == finishUnder || plate == checkpoint) {
                throw new IllegalArgumentException(
                        "the start and the finish, and the plate and the checkpoint, must differ");
            }
        }
    }

    /**
     * How a run is kept.
     *
     * @param fallDepth how far below the last checkpoint a runner may fall before being put back on it
     * @param timeLimit how long a run may take before it is dropped
     * @param boardSize how many runners the course command shows
     * @param splits whether each checkpoint says how long the run took to it, against the runner's best
     */
    public record Runs(int fallDepth, Duration timeLimit, int boardSize, boolean splits) {

        public static final Runs SHIPPED = new Runs(8, Duration.ofMinutes(30), 10, true);

        public Runs {
            Objects.requireNonNull(timeLimit, "timeLimit must not be null");
            if (fallDepth < 1 || timeLimit.toSeconds() < 1 || boardSize < 1 || boardSize > 100) {
                throw new IllegalArgumentException(
                        "the fall depth and the time limit must be above 0, and the board 1 to 100 long");
            }
        }
    }

    /**
     * The game modes on a course, which is how a run is played.
     *
     * @param build the mode of the course's own team while they are on it and not running
     * @param play the mode of anybody running, and of everybody else on the course
     * @param keepPermission the permission that leaves a player's mode alone; empty for none
     */
    public record Modes(org.bukkit.GameMode build, org.bukkit.GameMode play, String keepPermission) {

        public static final Modes SHIPPED =
                new Modes(org.bukkit.GameMode.CREATIVE, org.bukkit.GameMode.SURVIVAL, "uxmskyblock.parkour.keepmode");

        public Modes {
            Objects.requireNonNull(build, "build must not be null");
            Objects.requireNonNull(play, "play must not be null");
            Objects.requireNonNull(keepPermission, "keepPermission must not be null");
            if (play == org.bukkit.GameMode.CREATIVE || play == org.bukkit.GameMode.SPECTATOR) {
                throw new IllegalArgumentException("a run is played in survival or adventure");
            }
        }
    }

    /**
     * The ghost a runner races: a runner's best run is recorded as it goes, and replayed beside the next runner as an
     * item only they see.
     *
     * @param against whose ghost is raced: the course's fastest, or the runner's own best and the fastest before they
     *     have one
     * @param sampleEvery how often a run is sampled; the ghost moves on a straight line between two samples
     * @param item the item the ghost is drawn as; a player head wears the face of whoever ran it
     * @param height how far above the ground the item floats
     * @param scale how large the item is drawn
     * @param glow whether the item glows, so it shows through the course
     */
    public record Ghost(
            boolean enabled,
            Against against,
            Duration sampleEvery,
            Material item,
            double height,
            float scale,
            boolean glow) {

        /** Whose ghost a runner races. */
        public enum Against {
            /** The course's fastest run, whoever ran it. */
            RECORD,
            /** The runner's own best, and the course's fastest before they have one. */
            OWN
        }

        public static final Ghost SHIPPED =
                new Ghost(true, Against.RECORD, Duration.ofMillis(100), Material.PLAYER_HEAD, 1.4, 0.6f, true);

        public Ghost {
            Objects.requireNonNull(against, "against must not be null");
            Objects.requireNonNull(sampleEvery, "sampleEvery must not be null");
            Objects.requireNonNull(item, "item must not be null");
            // Whether the item is one is asked of the server's registry, which the configuration is read without, so
            // the stage asks it when the ghost is drawn.
            if (sampleEvery.toMillis() < 50 || scale <= 0 || scale > 4) {
                throw new IllegalArgumentException(
                        "a run is sampled at most every 50ms, and scaled above 0 and up to 4");
            }
        }
    }

    /**
     * What a finish pays, as lists of action lines the operator writes.
     *
     * @param firstFinish run for a runner the first time they finish a course
     * @param personalBest run each time a runner beats their own best on a course
     * @param courseRecord run each time a runner finishes a course faster than anybody has, the first finish on a
     *     course included
     * @param againAfter how long after paying a runner a new best or a record on a course the same list pays them
     *     again there; a first finish is paid once for good
     * @param shortestRun a run quicker than this pays nothing, so a course of a step or two earns no reward
     */
    public record Rewards(
            List<String> firstFinish,
            List<String> personalBest,
            List<String> courseRecord,
            Duration againAfter,
            Duration shortestRun) {

        public static final Rewards SHIPPED =
                new Rewards(List.of(), List.of(), List.of(), Duration.ofDays(1), Duration.ofSeconds(5));

        public Rewards {
            Objects.requireNonNull(againAfter, "againAfter must not be null");
            Objects.requireNonNull(shortestRun, "shortestRun must not be null");
            if (againAfter.isNegative() || shortestRun.isNegative()) {
                throw new IllegalArgumentException("the periods must not be negative");
            }
            firstFinish = List.copyOf(firstFinish);
            personalBest = List.copyOf(personalBest);
            courseRecord = List.copyOf(courseRecord);
        }
    }

    /**
     * The tags a course's team can give it, each by a name the operator chooses and the language files may word.
     *
     * @param difficulties the difficulties, easiest first
     * @param categories the categories
     */
    public record Tagging(List<String> difficulties, List<String> categories) {

        public static final Tagging SHIPPED = new Tagging(
                List.of("easy", "medium", "hard", "expert"), List.of("classic", "speed", "maze", "dropper", "puzzle"));

        public Tagging {
            difficulties = List.copyOf(difficulties);
            categories = List.copyOf(categories);
        }
    }

    /**
     * How runners rate a course.
     *
     * @param stars the most stars a runner gives, from 2 to 10
     */
    public record Ratings(boolean enabled, int stars) {

        public static final Ratings SHIPPED = new Ratings(true, 5);

        public Ratings {
            if (stars < 2 || stars > 10) {
                throw new IllegalArgumentException("a rating is out of 2 to 10 stars");
            }
        }
    }

    public ParkourConfiguration {
        Objects.requireNonNull(tagging, "tagging must not be null");
        Objects.requireNonNull(ratings, "ratings must not be null");
        Objects.requireNonNull(rewards, "rewards must not be null");
        Objects.requireNonNull(ghost, "ghost must not be null");
        Objects.requireNonNull(markers, "markers must not be null");
        Objects.requireNonNull(runs, "runs must not be null");
        Objects.requireNonNull(modes, "modes must not be null");
    }

    public static ParkourConfiguration defaultConfiguration() {
        return new ParkourConfiguration(
                true,
                Markers.SHIPPED,
                Runs.SHIPPED,
                Modes.SHIPPED,
                Ghost.SHIPPED,
                Rewards.SHIPPED,
                Tagging.SHIPPED,
                Ratings.SHIPPED);
    }

    public static ParkourConfiguration load(ConfigurationNode root) {
        ConfigurationNode written = root.node("markers");
        Markers markers;
        try {
            markers = new Markers(
                    block(written.node("plate").getString(""), Markers.SHIPPED.plate()),
                    block(written.node("start-under").getString(""), Markers.SHIPPED.startUnder()),
                    block(written.node("finish-under").getString(""), Markers.SHIPPED.finishUnder()),
                    block(written.node("checkpoint").getString(""), Markers.SHIPPED.checkpoint()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf markers: " + e.getMessage() + ". The shipped markers are used.");
            markers = Markers.SHIPPED;
        }
        ConfigurationNode run = root.node("runs");
        Runs runs;
        try {
            runs = new Runs(
                    run.node("fall-depth").getInt(Runs.SHIPPED.fallDepth()),
                    AcidIslandConfiguration.durationOf(run.node("time-limit").getString(""), Runs.SHIPPED.timeLimit()),
                    run.node("board-size").getInt(Runs.SHIPPED.boardSize()),
                    run.node("splits").getBoolean(Runs.SHIPPED.splits()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf runs: " + e.getMessage() + ". The shipped runs are used.");
            runs = Runs.SHIPPED;
        }
        ConfigurationNode mode = root.node("modes");
        Modes modes;
        try {
            modes = new Modes(
                    gameMode(mode.node("build").getString(""), Modes.SHIPPED.build()),
                    gameMode(mode.node("play").getString(""), Modes.SHIPPED.play()),
                    mode.node("keep-permission")
                            .getString(Modes.SHIPPED.keepPermission())
                            .trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf modes: " + e.getMessage() + ". The shipped modes are used.");
            modes = Modes.SHIPPED;
        }
        ConfigurationNode paid = root.node("rewards");
        Rewards rewards;
        try {
            rewards = new Rewards(
                    AcidIslandConfiguration.strings(paid.node("first-finish"), Rewards.SHIPPED.firstFinish()),
                    AcidIslandConfiguration.strings(paid.node("personal-best"), Rewards.SHIPPED.personalBest()),
                    AcidIslandConfiguration.strings(paid.node("course-record"), Rewards.SHIPPED.courseRecord()),
                    AcidIslandConfiguration.durationOf(
                            paid.node("again-after").getString(""), Rewards.SHIPPED.againAfter()),
                    AcidIslandConfiguration.durationOf(
                            paid.node("shortest-run").getString(""), Rewards.SHIPPED.shortestRun()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf rewards: " + e.getMessage() + ". The shipped rewards are used.");
            rewards = Rewards.SHIPPED;
        }
        Tagging tagging = new Tagging(
                lowered(AcidIslandConfiguration.strings(
                        root.node("tags", "difficulties"), Tagging.SHIPPED.difficulties())),
                lowered(AcidIslandConfiguration.strings(
                        root.node("tags", "categories"), Tagging.SHIPPED.categories())));
        Ratings ratings;
        try {
            ratings = new Ratings(
                    root.node("ratings", "enabled").getBoolean(Ratings.SHIPPED.enabled()),
                    root.node("ratings", "stars").getInt(Ratings.SHIPPED.stars()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf ratings: " + e.getMessage() + ". The shipped ratings are used.");
            ratings = Ratings.SHIPPED;
        }
        return new ParkourConfiguration(
                root.node("enabled").getBoolean(true), markers, runs, modes, ghost(root), rewards, tagging, ratings);
    }

    /** Names as a command takes them, in lower case, each once. The shared reader trims them and drops blanks. */
    private static List<String> lowered(List<String> written) {
        return written.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static Ghost ghost(ConfigurationNode root) {
        ConfigurationNode written = root.node("ghost");
        Ghost shipped = Ghost.SHIPPED;
        try {
            String against = written.node("against").getString(shipped.against().name());
            String item = written.node("item").getString(shipped.item().name());
            Material drawn = Material.matchMaterial(item.trim().toUpperCase(Locale.ROOT));
            if (drawn == null || drawn == Material.AIR || drawn == Material.CAVE_AIR || drawn == Material.VOID_AIR) {
                throw new IllegalArgumentException(item + " is no item");
            }
            return new Ghost(
                    written.node("enabled").getBoolean(shipped.enabled()),
                    Ghost.Against.valueOf(against.trim().toUpperCase(Locale.ROOT)),
                    AcidIslandConfiguration.durationOf(
                            written.node("sample-every").getString(""), shipped.sampleEvery()),
                    drawn,
                    written.node("height").getDouble(shipped.height()),
                    (float) written.node("scale").getDouble(shipped.scale()),
                    written.node("glow").getBoolean(shipped.glow()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf ghost: " + e.getMessage() + ". The shipped ghost is used.");
            return shipped;
        }
    }

    private static org.bukkit.GameMode gameMode(String written, org.bukkit.GameMode shipped) {
        if (written.isBlank()) {
            return shipped;
        }
        return org.bukkit.GameMode.valueOf(written.trim().toUpperCase(Locale.ROOT));
    }

    private static Material block(String written, Material shipped) {
        if (written.isBlank()) {
            return shipped;
        }
        Material material = Material.matchMaterial(written.trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException(written + " is no block");
        }
        return material;
    }
}
