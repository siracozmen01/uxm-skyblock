package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The grades a server's chunks fall into, and which grade one chunk of one island is.
 *
 * <p>A chunk's grade is drawn once, from the island and the chunk alone, so it is the same every time it is asked
 * and on every node: the grades that fall on its ring, weighed against each other. A server that names no grade
 * prices every chunk by the level ladder, as ChunkBlock always did.
 */
public final class ChunkTiers {

    /** The grade of a server that names none: the island's level, as the ladder in chunkblock.conf prices it. */
    public static final ChunkTier LADDER = new ChunkTier(
            "ladder",
            "@chunkblock.tiers.ladder",
            1,
            Integer.MAX_VALUE,
            1,
            List.of(new ChunkRequirement.Level(ChunkRequirement.Level.LADDER)),
            List.of(),
            List.of());

    private final List<ChunkTier> tiers;

    public ChunkTiers(List<ChunkTier> tiers) {
        this.tiers = List.copyOf(Objects.requireNonNull(tiers, "tiers must not be null"));
    }

    /** No grades: every chunk is priced by the ladder. */
    public static ChunkTiers ladder() {
        return new ChunkTiers(List.of());
    }

    public List<ChunkTier> all() {
        return tiers;
    }

    /** The grade named {@code id}, the ladder's among them. */
    public Optional<ChunkTier> named(String id) {
        if (LADDER.id().equals(id)) {
            return Optional.of(LADDER);
        }
        return tiers.stream().filter(tier -> tier.id().equals(id)).findFirst();
    }

    /** How far {@code chunk} lies from the island's first chunk: one for the eight around it. */
    public static int ring(ChunkPos origin, ChunkPos chunk) {
        return Math.max(Math.abs(chunk.x() - origin.x()), Math.abs(chunk.z() - origin.z()));
    }

    /**
     * The grade of {@code chunk} on the island {@code islandId}: one of the grades that fall on its ring, drawn by
     * their weights. A ring past every grade takes the farthest grades, and one before every grade the nearest, so
     * every chunk is priced. A server that names no grade prices it by the ladder.
     */
    public ChunkTier of(IslandId islandId, ChunkPos origin, ChunkPos chunk) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (tiers.isEmpty()) {
            return LADDER;
        }
        int ring = ring(origin, chunk);
        List<ChunkTier> candidates =
                tiers.stream().filter(tier -> tier.fallsOn(ring)).toList();
        if (candidates.isEmpty()) {
            int farthest = tiers.stream().mapToInt(ChunkTier::farthest).max().orElseThrow();
            int nearest = tiers.stream().mapToInt(ChunkTier::nearest).min().orElseThrow();
            candidates = ring > farthest
                    ? tiers.stream().filter(tier -> tier.farthest() == farthest).toList()
                    : tiers.stream().filter(tier -> tier.nearest() == nearest).toList();
        }
        int total = candidates.stream().mapToInt(ChunkTier::weight).sum();
        int drawn = new SplittableRandom(seed(islandId, chunk)).nextInt(total);
        for (ChunkTier tier : candidates) {
            drawn -= tier.weight();
            if (drawn < 0) {
                return tier;
            }
        }
        return candidates.getLast();
    }

    /** A number of the island and the chunk alone, the same on every node and every start. */
    static long seed(IslandId islandId, ChunkPos chunk) {
        long seed = islandId.value().getMostSignificantBits() ^ islandId.value().getLeastSignificantBits();
        seed = seed * 31 + chunk.x();
        return seed * 31 + chunk.z();
    }
}
