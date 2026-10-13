package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The Boxed islands, held in memory so every block a player touches is answered without the database,
 * and the box of each grown as its players make advancements.
 */
public final class BoxedService {

    /** A box that grew, from one radius to another. */
    public record Grown(IslandId islandId, int blocks, int fromRadius, int toRadius) {}

    private final BoxedIslandsPort port;
    private final BoxRules rules;
    private final Map<IslandId, Long> earned = new ConcurrentHashMap<>();
    private final BoxOffsetsPort offsetsPort;
    private final Map<IslandId, BoxOffset> offsets = new ConcurrentHashMap<>();

    /** A service whose moved boxes are kept in memory only, as a test's are. */
    public BoxedService(BoxedIslandsPort port, BoxRules rules) {
        this(port, rules, new KeptInMemory());
    }

    public BoxedService(BoxedIslandsPort port, BoxRules rules, BoxOffsetsPort offsetsPort) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
        this.offsetsPort = Objects.requireNonNull(offsetsPort, "offsetsPort must not be null");
    }

    /** Moved boxes kept nowhere but here. */
    private static final class KeptInMemory implements BoxOffsetsPort {
        private final Map<IslandId, BoxOffset> kept = new ConcurrentHashMap<>();

        @Override
        public Map<IslandId, BoxOffset> findAll() {
            return Map.copyOf(kept);
        }

        @Override
        public void move(IslandId islandId, BoxOffset offset) {
            kept.put(islandId, offset);
        }
    }

    public BoxRules rules() {
        return rules;
    }

    /** Reads every Boxed island into memory. Off the main thread, when the server starts. */
    public int prime() {
        Map<IslandId, Long> all = port.findAll();
        all.forEach(earned::putIfAbsent);
        offsetsPort.findAll().forEach(offsets::putIfAbsent);
        return all.size();
    }

    /** Makes an island a Boxed island, its box at the starting size. Off the main thread: it writes a row. */
    public void start(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        port.add(islandId);
        earned.putIfAbsent(islandId, 0L);
    }

    /** Whether the island is a Boxed island. Memory only. */
    public boolean isBoxed(IslandId islandId) {
        return earned.containsKey(islandId);
    }

    /** How far the island's box reaches, or empty for an island that is not a Boxed island. Memory only. */
    public OptionalInt radius(IslandId islandId) {
        Long blocks = earned.get(islandId);
        if (blocks == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(boxed(islandId, blocks));
    }

    /** The radius of the island's box once it earned {@code blocks}. */
    private int boxed(IslandId islandId, long blocks) {
        // A moved box grows no further than a box never moved could reach, measured from the island's centre.
        return Math.min(
                rules.radius(blocks), rules.maxRadius() - offset(islandId).reach());
    }

    /** How far the island's box lies from the island's centre. Memory only. */
    public BoxOffset offset(IslandId islandId) {
        return offsets.getOrDefault(islandId, BoxOffset.NONE);
    }

    /**
     * How far from the island's centre its box reaches, along either way: the box's radius and how far it was
     * moved. The island's protected square is this wide, so the box is inside it wherever it was moved. Memory only.
     */
    public OptionalInt reach(IslandId islandId) {
        OptionalInt radius = radius(islandId);
        return radius.isEmpty()
                ? radius
                : OptionalInt.of(radius.getAsInt() + offset(islandId).reach());
    }

    /** Whether the island's box, as wide as it is now, may be moved {@code to} from the island's centre. Memory only. */
    public boolean canMove(IslandId islandId, BoxOffset to) {
        Long blocks = earned.get(islandId);
        return blocks != null && rules.radius(blocks) + to.reach() <= rules.maxRadius();
    }

    /**
     * Moves the island's box {@code to} from the island's centre. Off the main thread: it writes a row.
     *
     * @return the box's offset now, or why it was not moved: no Boxed island, or further than a box reaches
     */
    public com.uxplima.uxmskyblock.core.domain.result.Result<BoxOffset, String> move(IslandId islandId, BoxOffset to) {
        Objects.requireNonNull(to, "to must not be null");
        if (!isBoxed(islandId)) {
            return com.uxplima.uxmskyblock.core.domain.result.Result.err("boxed.move.no_box");
        }
        if (!canMove(islandId, to)) {
            return com.uxplima.uxmskyblock.core.domain.result.Result.err("boxed.move.too_far");
        }
        offsetsPort.move(islandId, to);
        offsets.put(islandId, to);
        return com.uxplima.uxmskyblock.core.domain.result.Result.ok(to);
    }

    /**
     * An advancement one of the island's players made. The box grows the first time the island earns
     * it, by what it is worth, and never again for the same one. Off the main thread: it writes a row.
     *
     * @return how the box grew, or empty when the island is no Boxed island, the advancement is worth
     *     nothing or the island had earned it already
     */
    public java.util.Optional<Grown> earn(IslandId islandId, AdvancementFacts advancement) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(advancement, "advancement must not be null");
        int blocks = rules.blocksFor(advancement);
        if (blocks <= 0 || !isBoxed(islandId) || !port.earn(islandId, advancement.key(), blocks)) {
            return java.util.Optional.empty();
        }
        long before = earned.merge(islandId, (long) blocks, Long::sum) - blocks;
        int from = boxed(islandId, before);
        int to = boxed(islandId, before + blocks);
        return java.util.Optional.of(new Grown(islandId, blocks, from, to));
    }

    /**
     * An operator pushes the island's box out by {@code blocks}. Off the main thread: it writes a row.
     *
     * @return how the box changed, or empty when the island is no Boxed island
     */
    public java.util.Optional<Grown> grant(IslandId islandId, int blocks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (!isBoxed(islandId)) {
            return java.util.Optional.empty();
        }
        port.grant(islandId, blocks);
        long before = earned.merge(islandId, (long) blocks, Long::sum) - blocks;
        return java.util.Optional.of(
                new Grown(islandId, blocks, boxed(islandId, before), boxed(islandId, before + blocks)));
    }

    /**
     * An operator sets how far the island's box reaches, between where every box starts and the largest a box can
     * be. Off the main thread: it writes a row.
     *
     * @return how the box changed, or empty when the island is no Boxed island
     */
    public java.util.Optional<Grown> resize(IslandId islandId, int radius) {
        if (radius < rules.startRadius() || radius > rules.maxRadius()) {
            throw new IllegalArgumentException(
                    "a box reaches from " + rules.startRadius() + " to " + rules.maxRadius() + ", not " + radius);
        }
        Long now = earned.get(islandId);
        if (now == null) {
            return java.util.Optional.empty();
        }
        // The island is left having earned exactly what that box takes, whatever it had earned past it.
        return grant(islandId, Math.toIntExact(radius - rules.startRadius() - now));
    }

    /**
     * Reads the island again after it changed. An island that still has its row keeps what it earned,
     * and one whose row is gone, because it was erased, is dropped. Off the main thread: it reads a row.
     */
    public void forget(IslandId islandId) {
        OptionalLong stored = port.find(islandId);
        if (stored.isPresent()) {
            earned.put(islandId, stored.getAsLong());
        } else {
            earned.remove(islandId);
            offsets.remove(islandId);
        }
    }
}
