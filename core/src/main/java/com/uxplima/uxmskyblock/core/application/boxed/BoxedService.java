package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts;
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

    public BoxedService(BoxedIslandsPort port, BoxRules rules) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    public BoxRules rules() {
        return rules;
    }

    /** Reads every Boxed island into memory. Off the main thread, when the server starts. */
    public int prime() {
        Map<IslandId, Long> all = port.findAll();
        all.forEach(earned::putIfAbsent);
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
        return blocks == null ? OptionalInt.empty() : OptionalInt.of(rules.radius(blocks));
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
        int from = rules.radius(before);
        int to = rules.radius(before + blocks);
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
        return java.util.Optional.of(new Grown(islandId, blocks, rules.radius(before), rules.radius(before + blocks)));
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
        }
    }
}
