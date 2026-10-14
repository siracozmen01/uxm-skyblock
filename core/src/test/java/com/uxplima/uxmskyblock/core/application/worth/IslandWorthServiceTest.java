package com.uxplima.uxmskyblock.core.application.worth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandWorthServiceTest {

    private MaterialValuationIndex valuationIndex;
    private IslandLeaderboardPort mockLeaderboardPort;
    private IslandWorthService service;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        valuationIndex = new MaterialValuationIndex(
                Map.of(
                        "minecraft:diamond_block", 900L,
                        "minecraft:emerald_block", 1200L,
                        "minecraft:iron_block", 90L,
                        "minecraft:gold_block", 450L,
                        "minecraft:netherite_block", 15000L,
                        "minecraft:beacon", 5000L),
                Map.of(
                        "minecraft:diamond_block", 90000L, // $900.00
                        "minecraft:emerald_block", 120000L, // $1200.00
                        "minecraft:iron_block", 9000L, // $90.00
                        "minecraft:gold_block", 45000L // $450.00
                        ));

        Map<String, Long> spawnerWeights = Map.of(
                "minecraft:iron_golem", 5000L,
                "minecraft:blaze", 1000L,
                "minecraft:zombie", 200L);

        mockLeaderboardPort = mock(IslandLeaderboardPort.class);

        service = new IslandWorthService(
                valuationIndex,
                spawnerWeights,
                25L, // default spawner weight
                50L, // quest weight
                100L, // points per level
                10000L, // bank minor units per point
                0.85, // damping factor
                mockLeaderboardPort,
                null);

        islandId = new IslandId(UUID.randomUUID());
    }

    @Test
    @DisplayName("block placement increments cached score and economic worth")
    void blockPlacementIncrementsScoreAndWorth() {
        service.recordBlockPlace(islandId, "minecraft:diamond_block", 2);
        service.recordBlockPlace(islandId, "minecraft:emerald_block", 1);

        // diamond: 2 * 900 = 1800, emerald: 1 * 1200 = 1200 -> total 3000
        IslandScoreBreakdown score = service.calculateScore(islandId, 0, 0L);

        assertThat(score.blockScore()).isEqualTo(3000L);
        assertThat(score.totalScore()).isEqualTo(3000L);
        assertThat(score.calculatedLevel()).isEqualTo(30L); // 3000 / 100 = 30
        assertThat(score.rawEconomicWorthMinorUnits()).isEqualTo(2 * 90000L + 120000L); // 300000 minor units ($3000.00)
        assertThat(score.dampedEconomicWorthMinorUnits()).isEqualTo((long) (300000L * 0.85));

        verify(mockLeaderboardPort).updateIslandScore(eq(islandId), eq(3000L), eq((long) (300000L * 0.85)));
    }

    @Test
    @DisplayName("unconfigured block evaluates to 0 points")
    void unconfiguredBlockEvaluatesToZeroPoints() {
        service.recordBlockPlace(islandId, "minecraft:dirt", 500);
        service.recordBlockPlace(islandId, "minecraft:cobblestone", 1000);

        IslandScoreBreakdown score = service.calculateScore(islandId, 0, 0L);

        assertThat(score.blockScore()).isEqualTo(0L);
        assertThat(score.totalScore()).isEqualTo(0L);
        assertThat(score.calculatedLevel()).isEqualTo(0L);
    }

    @Test
    @DisplayName("block break decrements score and bounds at zero")
    void blockBreakDecrementsScore() {
        service.recordBlockPlace(islandId, "minecraft:diamond_block", 5);
        service.recordBlockBreak(islandId, "minecraft:diamond_block", 2);

        IslandScoreBreakdown score = service.calculateScore(islandId, 0, 0L);
        assertThat(score.blockScore()).isEqualTo(3 * 900L);

        // Breaking more than placed bounds at 0
        service.recordBlockBreak(islandId, "minecraft:diamond_block", 10);
        IslandScoreBreakdown zeroScore = service.calculateScore(islandId, 0, 0L);
        assertThat(zeroScore.blockScore()).isEqualTo(0L);
    }

    @Test
    @DisplayName("spawner place and break tracks mob types and custom weights")
    void spawnerTrackingWithCustomWeights() {
        service.recordSpawnerPlace(islandId, "minecraft:iron_golem");
        service.recordSpawnerPlace(islandId, "minecraft:iron_golem");
        service.recordSpawnerPlace(islandId, "minecraft:blaze");
        service.recordSpawnerPlace(islandId, "minecraft:pig"); // unlisted -> fallback weight 25

        assertThat(service.spawnerCount(islandId, "minecraft:iron_golem")).isEqualTo(2);
        assertThat(service.spawnerCount(islandId, "minecraft:blaze")).isEqualTo(1);
        assertThat(service.spawnerCount(islandId, "minecraft:pig")).isEqualTo(1);
        assertThat(service.totalSpawnerCount(islandId)).isEqualTo(4);

        // 2 * 5000 + 1 * 1000 + 1 * 25 = 11025
        assertThat(service.calculateSpawnerScore(islandId)).isEqualTo(11025L);

        service.recordSpawnerBreak(islandId, "minecraft:iron_golem");
        assertThat(service.spawnerCount(islandId, "minecraft:iron_golem")).isEqualTo(1);
        // 1 * 5000 + 1 * 1000 + 1 * 25 = 6025
        assertThat(service.calculateSpawnerScore(islandId)).isEqualTo(6025L);
    }

    @Test
    @DisplayName("multi-factor score aggregates blocks, spawners, quests, and bank")
    void multiFactorScoreAggregation() {
        service.recordBlockPlace(islandId, "minecraft:diamond_block", 1); // 900 pts
        service.recordSpawnerPlace(islandId, "minecraft:zombie"); // 200 pts

        // 1 quest * 50 = 50 pts
        // 50,000 bank minor units / 10000 = 5 pts
        // Total: 900 + 200 + 50 + 5 = 1155 pts -> Level 11
        IslandScoreBreakdown score = service.calculateScore(islandId, 1, 50000L);

        assertThat(score.blockScore()).isEqualTo(900L);
        assertThat(score.spawnerScore()).isEqualTo(200L);
        assertThat(score.questScore()).isEqualTo(50L);
        assertThat(score.bankScore()).isEqualTo(5L);
        assertThat(score.totalScore()).isEqualTo(1155L);
        assertThat(score.calculatedLevel()).isEqualTo(11L);
    }

    @Test
    @DisplayName("dynamic price shift updates cached worth with damping factor")
    void dynamicPriceShiftUpdatesCachedWorth() {
        service.recordBlockPlace(islandId, "minecraft:diamond_block", 10);
        // initial price: 90000 -> 10 * 90000 = 900000

        IslandScoreBreakdown score1 = service.calculateScore(islandId, 0, 0L);
        assertThat(score1.rawEconomicWorthMinorUnits()).isEqualTo(900000L);
        assertThat(score1.dampedEconomicWorthMinorUnits()).isEqualTo((long) (900000L * 0.85));

        // Update diamond block unit price to 100000
        service.updateMaterialPrice("minecraft:diamond_block", 100000L);

        IslandScoreBreakdown score2 = service.calculateScore(islandId, 0, 0L);
        assertThat(score2.rawEconomicWorthMinorUnits()).isEqualTo(1000000L);
        assertThat(score2.dampedEconomicWorthMinorUnits()).isEqualTo((long) (1000000L * 0.85));
    }

    @Test
    @DisplayName("recalculateIsland resets and updates counts from full scan")
    void recalculateIslandResetsAndUpdates() {
        service.recordBlockPlace(islandId, "minecraft:diamond_block", 100);

        Map<String, Integer> scannedBlocks = Map.of(
                "minecraft:emerald_block", 2,
                "minecraft:iron_block", 10);
        Map<String, Integer> scannedSpawners = Map.of("minecraft:blaze", 3);

        // emerald: 2 * 1200 = 2400, iron: 10 * 90 = 900, blaze: 3 * 1000 = 3000 -> Total = 6300
        IslandScoreBreakdown score = service.recalculateIsland(islandId, scannedBlocks, scannedSpawners, 2, 20000L);

        assertThat(score.blockScore()).isEqualTo(3300L);
        assertThat(score.spawnerScore()).isEqualTo(3000L);
        assertThat(score.questScore()).isEqualTo(100L);
        assertThat(score.bankScore()).isEqualTo(2L);
        assertThat(score.totalScore()).isEqualTo(6402L);
        assertThat(score.calculatedLevel()).isEqualTo(64L);
    }

    @Test
    @DisplayName("triggerAsyncRecalculation delegates to chunkScannerPort")
    void triggerAsyncRecalculationDelegatesToScanner() {
        IslandChunkScannerPort scanner = (id, world, bounds, cb) -> {
            cb.accept(Map.of("minecraft:diamond_block", 5), Map.of("minecraft:iron_golem", 1));
        };

        IslandWorthService scannerService = new IslandWorthService(
                valuationIndex,
                Map.of("minecraft:iron_golem", 5000L),
                25L,
                50L,
                100L,
                10000L,
                0.85,
                mockLeaderboardPort,
                scanner);

        AtomicReference<IslandScoreBreakdown> resultRef = new AtomicReference<>();
        scannerService.triggerAsyncRecalculation(
                islandId, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 50), 0, 0L, resultRef::set);

        IslandScoreBreakdown result = Objects.requireNonNull(resultRef.get());
        // 5 * 900 + 1 * 5000 = 4500 + 5000 = 9500
        assertThat(result.totalScore()).isEqualTo(9500L);
        assertThat(result.calculatedLevel()).isEqualTo(95L);
    }

    @Test
    @DisplayName("validates constructor invariants")
    void constructorValidation() {
        assertThatThrownBy(() -> new IslandWorthService(
                        valuationIndex, Map.of(), 25L, 50L, 0L, 10000L, 0.85, mockLeaderboardPort, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IslandWorthService(
                        valuationIndex, Map.of(), 25L, 50L, 100L, 10000L, 0.0, mockLeaderboardPort, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IslandWorthService(
                        valuationIndex, Map.of(), 25L, 50L, 100L, 10000L, 1.5, mockLeaderboardPort, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Every score worked out is told to the rules that follow the level")
    void aScoreIsTold() {
        com.uxplima.uxmskyblock.core.domain.identity.IslandId island =
                com.uxplima.uxmskyblock.core.domain.identity.IslandId.of(java.util.UUID.randomUUID());
        java.util.List<Long> levels = new java.util.ArrayList<>();
        service.whenScored((scored, score) -> {
            assertThat(scored).isEqualTo(island);
            levels.add(score.calculatedLevel());
        });

        service.calculateScore(island, 4, 0L);

        assertThat(levels).containsExactly(2L);
    }

    @Test
    @DisplayName("An island worth booster makes the worth more while it runs, and leaves the level as it was")
    void aWorthBoosterMakesTheWorthMore() {
        service.recordBlockPlace(islandId, "minecraft:diamond_block", 2);
        service.recordBlockPlace(islandId, "minecraft:emerald_block", 1);
        service.useWorthBoost(island -> island.equals(islandId) ? 1.5 : 1.0);

        IslandScoreBreakdown score = service.calculateScore(islandId, 0, 1_000L);

        assertThat(score.rawEconomicWorthMinorUnits()).isEqualTo((long) (301_000L * 1.5));
        long boosted = (long) (301_000L * 1.5);
        assertThat(score.dampedEconomicWorthMinorUnits()).isEqualTo((long) (boosted * 0.85));
        assertThat(score.calculatedLevel())
                .describedAs("the level is the island's own")
                .isEqualTo(30L);

        service.useWorthBoost(island -> 0.5);
        assertThat(service.calculateScore(islandId, 0, 1_000L).rawEconomicWorthMinorUnits())
                .describedAs("nothing makes an island worth less")
                .isEqualTo(301_000L);
    }
}
