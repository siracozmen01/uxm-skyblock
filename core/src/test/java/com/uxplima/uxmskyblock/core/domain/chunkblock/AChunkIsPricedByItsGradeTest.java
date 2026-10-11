package com.uxplima.uxmskyblock.core.domain.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A chunk's grade is drawn from the island and the chunk alone, among the grades of its ring by their weights, and
 * prices it: lines paid a part at a time, and the level, held.
 */
class AChunkIsPricedByItsGradeTest {

    private static final ChunkPos ORIGIN = new ChunkPos(10, -4);
    private static final IslandId ISLAND = IslandId.of(UUID.fromString("00000000-0000-0000-0000-000000000042"));

    private static ChunkTier tier(String id, int nearest, int farthest, int weight) {
        return new ChunkTier(
                id, "@" + id, nearest, farthest, weight, List.of(new ChunkRequirement.Points(1)), List.of(), List.of());
    }

    @Test
    @DisplayName("Each line is kept under a key of its own, an item named without a namespace is a minecraft one")
    void everyLineHasItsKey() {
        assertThat(new ChunkRequirement.Money(50, ChunkRequirement.Source.PLAYER).key())
                .isEqualTo("money:player");
        assertThat(new ChunkRequirement.Money(50, ChunkRequirement.Source.ISLAND).key())
                .isEqualTo("money:island");
        assertThat(new ChunkRequirement.Currency("points", 5, ChunkRequirement.Source.ISLAND).key())
                .isEqualTo("currency:points:island");
        assertThat(new ChunkRequirement.Items("OAK_LOG", 96).key()).isEqualTo("item:minecraft:oak_log");
        assertThat(new ChunkRequirement.Items("myserver:ruby", 1).material()).isEqualTo("myserver:ruby");
        assertThat(new ChunkRequirement.Points(3).key()).isEqualTo("points");
        assertThat(new ChunkRequirement.Level(10).paid()).isFalse();
        assertThat(new ChunkRequirement.Level(ChunkRequirement.Level.LADDER).of(7))
                .isEqualTo(7);
        assertThat(new ChunkRequirement.Level(10).of(7)).isEqualTo(10);
        assertThatThrownBy(() -> new ChunkRequirement.Items("stone", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkRequirement.Level(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkRequirement.Currency(" ", 1, ChunkRequirement.Source.PLAYER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A grade costs something, is skipped only by paying, and is held when its cost is the level alone")
    void aGradeIsWellFormed() {
        ChunkTier levelled =
                new ChunkTier("l", "@l", 1, 3, 1, List.of(new ChunkRequirement.Level(5)), List.of(), List.of());
        ChunkTier paid = new ChunkTier(
                "p",
                "@p",
                1,
                3,
                1,
                List.of(new ChunkRequirement.Level(5), new ChunkRequirement.Points(1)),
                List.of(new ChunkRequirement.Currency("gems", 500, ChunkRequirement.Source.PLAYER)),
                List.of("[message] opened"));

        assertThat(levelled.held()).isTrue();
        assertThat(paid.held()).isFalse();
        assertThat(paid.fallsOn(3)).isTrue();
        assertThat(paid.fallsOn(4)).isFalse();
        assertThatThrownBy(() -> new ChunkTier("x", "@x", 1, 2, 1, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkTier(
                        "x",
                        "@x",
                        1,
                        2,
                        1,
                        List.of(new ChunkRequirement.Points(1)),
                        List.of(new ChunkRequirement.Level(1)),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tier("x", 3, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tier("x", 0, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tier("x", 1, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The ring is how far a chunk lies from the first, the eight around it the first ring")
    void theRing() {
        assertThat(ChunkTiers.ring(ORIGIN, ORIGIN)).isZero();
        assertThat(ChunkTiers.ring(ORIGIN, new ChunkPos(11, -3))).isEqualTo(1);
        assertThat(ChunkTiers.ring(ORIGIN, new ChunkPos(7, -2))).isEqualTo(3);
    }

    @Test
    @DisplayName("A chunk's grade is the same every time it is asked, and a server with no grades uses the ladder")
    void theGradeIsDrawnOnce() {
        ChunkTiers tiers = new ChunkTiers(List.of(tier("common", 1, 9, 1), tier("rare", 1, 9, 1)));
        ChunkPos chunk = new ChunkPos(12, -5);

        ChunkTier first = tiers.of(ISLAND, ORIGIN, chunk);
        for (int i = 0; i < 20; i++) {
            assertThat(new ChunkTiers(tiers.all()).of(ISLAND, ORIGIN, chunk)).isEqualTo(first);
        }
        assertThat(ChunkTiers.ladder().of(ISLAND, ORIGIN, chunk)).isEqualTo(ChunkTiers.LADDER);
        assertThat(ChunkTiers.LADDER.held()).isTrue();
        assertThat(tiers.named("rare")).contains(tier("rare", 1, 9, 1));
        assertThat(tiers.named("ladder")).contains(ChunkTiers.LADDER);
        assertThat(tiers.named("none")).isEmpty();
    }

    @Test
    @DisplayName("Grades of one ring fall by their weights, and only the grades of that ring")
    void gradesFallByWeight() {
        ChunkTiers tiers =
                new ChunkTiers(List.of(tier("common", 1, 2, 3), tier("epic", 1, 2, 1), tier("far", 3, 9, 1)));
        Map<String, Integer> fell = new HashMap<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int island = 0; island < 40; island++) {
                    ChunkPos chunk = new ChunkPos(ORIGIN.x() + x, ORIGIN.z() + z);
                    if (chunk.equals(ORIGIN)) {
                        continue;
                    }
                    IslandId id = IslandId.of(new UUID(island * 7919L, island));
                    fell.merge(tiers.of(id, ORIGIN, chunk).id(), 1, Integer::sum);
                }
            }
        }

        assertThat(fell).doesNotContainKey("far");
        int common = fell.getOrDefault("common", 0);
        int epic = fell.getOrDefault("epic", 0);
        assertThat((double) common / (common + epic)).isBetween(0.65, 0.85);
    }

    @Test
    @DisplayName("A ring past every grade takes the farthest, and one before every grade the nearest")
    void everyRingIsPriced() {
        ChunkTiers tiers = new ChunkTiers(List.of(tier("near", 2, 3, 1), tier("far", 4, 6, 1)));

        assertThat(tiers.of(ISLAND, ORIGIN, new ChunkPos(ORIGIN.x() + 20, ORIGIN.z()))
                        .id())
                .isEqualTo("far");
        assertThat(tiers.of(ISLAND, ORIGIN, new ChunkPos(ORIGIN.x() + 1, ORIGIN.z()))
                        .id())
                .isEqualTo("near");
        ChunkTiers gapped = new ChunkTiers(List.of(tier("near", 2, 3, 1), tier("far", 7, 9, 1)));
        assertThat(gapped.of(ISLAND, ORIGIN, new ChunkPos(ORIGIN.x() + 5, ORIGIN.z()))
                        .id())
                .describedAs("a ring between grades is not past them all")
                .isEqualTo("near");
    }

    @Test
    @DisplayName("Payments add up line by line, and a cost is met when every paid line is full and the level holds")
    void paymentsMeetTheCost() {
        ChunkRequirement logs = new ChunkRequirement.Items("oak_log", 96);
        ChunkRequirement money = new ChunkRequirement.Money(50_000, ChunkRequirement.Source.PLAYER);
        ChunkRequirement level = new ChunkRequirement.Level(ChunkRequirement.Level.LADDER);
        List<ChunkRequirement> cost = List.of(logs, money, level);

        ChunkPayments paid = ChunkPayments.none().with(logs, 40).with(logs, 56).with(money, 49_999);

        assertThat(paid.of(logs)).isEqualTo(96);
        assertThat(paid.remaining(logs)).isZero();
        assertThat(paid.remaining(money)).isEqualTo(1);
        assertThat(paid.remaining(level)).isZero();
        assertThat(paid.remaining(new ChunkRequirement.Level(10)))
                .describedAs("a level is held, never owed")
                .isZero();
        assertThat(paid.meets(cost, 99, 3)).isFalse();
        ChunkPayments full = paid.with(money, 5);
        assertThat(full.remaining(money)).describedAs("never below nothing").isZero();
        assertThat(full.meets(cost, 3, 3)).isTrue();
        assertThat(full.meets(cost, 2, 3)).describedAs("the ladder asks three").isFalse();
        assertThatThrownBy(() -> paid.with(logs, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
