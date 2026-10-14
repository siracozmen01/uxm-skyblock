package com.uxplima.uxmskyblock.core.application.worth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.level.IslandMaterialIndex;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
import org.jspecify.annotations.Nullable;

/**
 * Pure domain orchestration service for the Island Block Worth Valuation Engine.
 *
 * <p>Implements declarative HOCON valuation lookups, live O(1) block event caching,
 * custom mob spawner weighting, dynamic price damping, and multi-factor level aggregation.
 */
public final class IslandWorthService {

    private final java.util.List<java.util.function.BiConsumer<IslandId, IslandScoreBreakdown>> scoredListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private final MaterialValuationIndex valuationIndex;
    private final Map<String, Long> spawnerWeights;
    private final long defaultSpawnerWeight;
    private final long questWeight;
    private final long pointsPerLevel;
    private final long bankMinorUnitsPerPoint;
    private final double dampingFactor;
    private final IslandLeaderboardPort leaderboardPort;
    private final @Nullable IslandChunkScannerPort chunkScannerPort;

    private final ConcurrentHashMap<IslandId, IslandMaterialIndex> activeIndices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IslandId, ConcurrentHashMap<String, AtomicInteger>> activeSpawners =
            new ConcurrentHashMap<>();

    public IslandWorthService(
            MaterialValuationIndex valuationIndex,
            Map<String, Long> spawnerWeights,
            long defaultSpawnerWeight,
            long questWeight,
            long pointsPerLevel,
            long bankMinorUnitsPerPoint,
            double dampingFactor,
            IslandLeaderboardPort leaderboardPort,
            @Nullable IslandChunkScannerPort chunkScannerPort) {
        this.valuationIndex = Objects.requireNonNull(valuationIndex, "valuationIndex must not be null");
        this.spawnerWeights = Collections.unmodifiableMap(new HashMap<>(spawnerWeights));
        this.defaultSpawnerWeight = Math.max(0L, defaultSpawnerWeight);
        this.questWeight = Math.max(0L, questWeight);
        if (pointsPerLevel <= 0) {
            throw new IllegalArgumentException("pointsPerLevel must be positive: " + pointsPerLevel);
        }
        this.pointsPerLevel = pointsPerLevel;
        this.bankMinorUnitsPerPoint = Math.max(1L, bankMinorUnitsPerPoint);
        if (dampingFactor <= 0.0 || dampingFactor > 1.0) {
            throw new IllegalArgumentException("dampingFactor must be in range (0.0, 1.0]: " + dampingFactor);
        }
        this.dampingFactor = dampingFactor;
        this.leaderboardPort = Objects.requireNonNull(leaderboardPort, "leaderboardPort must not be null");
        this.chunkScannerPort = chunkScannerPort;
    }

    public IslandMaterialIndex getOrCreateIndex(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return activeIndices.computeIfAbsent(islandId, id -> new IslandMaterialIndex(id, valuationIndex));
    }

    public int recordBlockPlace(IslandId islandId, String materialKey, int amount) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(materialKey, "materialKey must not be null");
        IslandMaterialIndex index = getOrCreateIndex(islandId);
        return index.increment(materialKey, amount);
    }

    public int recordBlockBreak(IslandId islandId, String materialKey, int amount) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(materialKey, "materialKey must not be null");
        IslandMaterialIndex index = getOrCreateIndex(islandId);
        return index.decrement(materialKey, amount);
    }

    public int recordSpawnerPlace(IslandId islandId, String entityType) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        ConcurrentHashMap<String, AtomicInteger> spawners =
                activeSpawners.computeIfAbsent(islandId, k -> new ConcurrentHashMap<>());
        AtomicInteger counter = spawners.computeIfAbsent(entityType, k -> new AtomicInteger(0));
        return counter.incrementAndGet();
    }

    public int recordSpawnerBreak(IslandId islandId, String entityType) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        ConcurrentHashMap<String, AtomicInteger> spawners = activeSpawners.get(islandId);
        if (spawners == null) {
            return 0;
        }
        AtomicInteger counter = spawners.get(entityType);
        if (counter == null) {
            return 0;
        }
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                return 0;
            }
            if (counter.compareAndSet(current, current - 1)) {
                return current - 1;
            }
        }
    }

    public int spawnerCount(IslandId islandId, String entityType) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        ConcurrentHashMap<String, AtomicInteger> spawners = activeSpawners.get(islandId);
        if (spawners == null) {
            return 0;
        }
        AtomicInteger counter = spawners.get(entityType);
        return counter != null ? Math.max(0, counter.get()) : 0;
    }

    public int totalSpawnerCount(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        ConcurrentHashMap<String, AtomicInteger> spawners = activeSpawners.get(islandId);
        if (spawners == null) {
            return 0;
        }
        int total = 0;
        for (AtomicInteger count : spawners.values()) {
            total += Math.max(0, count.get());
        }
        return total;
    }

    public long calculateSpawnerScore(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        ConcurrentHashMap<String, AtomicInteger> spawners = activeSpawners.get(islandId);
        if (spawners == null) {
            return 0L;
        }
        long score = 0L;
        for (Map.Entry<String, AtomicInteger> entry : spawners.entrySet()) {
            int count = Math.max(0, entry.getValue().get());
            if (count > 0) {
                long weight = spawnerWeights.getOrDefault(entry.getKey(), defaultSpawnerWeight);
                score += (long) count * weight;
            }
        }
        return score;
    }

    public Map<String, Integer> spawnerCountsSnapshot(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        ConcurrentHashMap<String, AtomicInteger> spawners = activeSpawners.get(islandId);
        if (spawners == null) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        spawners.forEach((k, v) -> {
            int c = v.get();
            if (c > 0) {
                result.put(k, c);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public void updateMaterialPrice(String materialKey, long newPriceMinorUnits) {
        Objects.requireNonNull(materialKey, "materialKey must not be null");
        long oldPrice = valuationIndex.priceOf(materialKey);
        long priceDelta = newPriceMinorUnits - oldPrice;
        valuationIndex.setPrice(materialKey, newPriceMinorUnits);
        for (IslandMaterialIndex index : activeIndices.values()) {
            index.applyPriceDelta(materialKey, priceDelta);
        }
    }

    public IslandScoreBreakdown calculateScore(IslandId islandId, int questCount, long bankMinorUnits) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        IslandMaterialIndex index = getOrCreateIndex(islandId);

        long blockScore = index.getCachedLevelScore();
        long spawnerScore = calculateSpawnerScore(islandId);
        long qScore = (long) Math.max(0, questCount) * questWeight;
        long bScore = Math.max(0L, bankMinorUnits) / bankMinorUnitsPerPoint;

        long totalScore = blockScore + spawnerScore + qScore + bScore;
        long level = totalScore / pointsPerLevel;

        // An island worth booster makes what the island is worth more for as long as it runs; its level is its own.
        double boost = Math.max(1.0, worthBoost.applyAsDouble(islandId));
        long rawWorth = (long) ((index.getCachedEconomicWorth() + Math.max(0L, bankMinorUnits)) * boost);
        long dampedWorth = (long) (rawWorth * dampingFactor);

        leaderboardPort.updateIslandScore(islandId, totalScore, dampedWorth);

        IslandScoreBreakdown score = new IslandScoreBreakdown(
                blockScore, spawnerScore, qScore, bScore, totalScore, level, rawWorth, dampedWorth);
        for (java.util.function.BiConsumer<IslandId, IslandScoreBreakdown> listener : scoredListeners) {
            listener.accept(islandId, score);
        }
        return score;
    }

    /** Reads the island worth booster an island has running, whenever its score is worked out. */
    public void useWorthBoost(java.util.function.ToDoubleFunction<IslandId> boost) {
        this.worthBoost = Objects.requireNonNull(boost, "boost must not be null");
    }

    private volatile java.util.function.ToDoubleFunction<IslandId> worthBoost = island -> 1.0;

    /**
     * Told each island's score as it is worked out, so a rule that rides on the level follows it: a
     * ChunkBlock island closes the chunks a fallen level no longer holds.
     */
    public void whenScored(java.util.function.BiConsumer<IslandId, IslandScoreBreakdown> listener) {
        scoredListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    public IslandScoreBreakdown recalculateIsland(
            IslandId islandId,
            Map<String, Integer> blockCounts,
            Map<String, Integer> spawnerCounts,
            int questCount,
            long bankMinorUnits) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(blockCounts, "blockCounts must not be null");
        Objects.requireNonNull(spawnerCounts, "spawnerCounts must not be null");

        IslandMaterialIndex index = getOrCreateIndex(islandId);
        index.reset();

        blockCounts.forEach((mat, count) -> {
            if (count != null && count > 0) {
                index.increment(mat, count);
            }
        });

        ConcurrentHashMap<String, AtomicInteger> spawners =
                activeSpawners.computeIfAbsent(islandId, k -> new ConcurrentHashMap<>());
        spawners.clear();

        spawnerCounts.forEach((entity, count) -> {
            if (count != null && count > 0) {
                spawners.put(entity, new AtomicInteger(count));
            }
        });

        return calculateScore(islandId, questCount, bankMinorUnits);
    }

    public void triggerAsyncRecalculation(
            IslandId islandId,
            String worldName,
            IslandBounds bounds,
            int questCount,
            long bankMinorUnits,
            Consumer<IslandScoreBreakdown> onComplete) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(onComplete, "onComplete must not be null");

        if (chunkScannerPort == null) {
            IslandScoreBreakdown fallback = calculateScore(islandId, questCount, bankMinorUnits);
            onComplete.accept(fallback);
            return;
        }

        chunkScannerPort.scanIsland(islandId, worldName, bounds, (blocks, spawners) -> {
            IslandScoreBreakdown score = recalculateIsland(islandId, blocks, spawners, questCount, bankMinorUnits);
            onComplete.accept(score);
        });
    }

    /**
     * Drops everything this service holds for one island.
     *
     * <p>The material index is a count per material and the spawner map is a count per entity type.
     * Nothing removed either, so an island that was deleted a year ago was still being carried.
     */
    public void forgetIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        activeIndices.remove(islandId);
        activeSpawners.remove(islandId);
    }

    public MaterialValuationIndex valuationIndex() {
        return valuationIndex;
    }

    public Map<String, Long> spawnerWeights() {
        return spawnerWeights;
    }

    public long defaultSpawnerWeight() {
        return defaultSpawnerWeight;
    }

    public long questWeight() {
        return questWeight;
    }

    public long pointsPerLevel() {
        return pointsPerLevel;
    }

    public long bankMinorUnitsPerPoint() {
        return bankMinorUnitsPerPoint;
    }

    public double dampingFactor() {
        return dampingFactor;
    }
}
