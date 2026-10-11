package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement.Source;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The grades of ChunkBlock chunks come off {@code modules/chunkblock.conf}: what falls where, what a chunk costs, what
 * skips the cost and what opening one gives. Every number in them is the operator's.
 */
class AChunkGradeIsTheOperatorsTest {

    private static ChunkTiers load(String hocon) throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        return ChunkBlockConfiguration.load(root).tiers();
    }

    @Test
    @DisplayName("Every kind of cost line reads as the price it names")
    void everyLineReads() {
        assertThat(ChunkCostLines.parse("money 2500.5"))
                .describedAs(
                        "money is written in whole units and kept in cents, from the player when no purse is named")
                .contains(new ChunkRequirement.Money(250050, Source.PLAYER));
        assertThat(ChunkCostLines.parse("money 300 island")).contains(new ChunkRequirement.Money(30000, Source.ISLAND));
        assertThat(ChunkCostLines.parse("MONEY 1 Bank")).contains(new ChunkRequirement.Money(100, Source.ISLAND));
        assertThat(ChunkCostLines.parse("currency Points 25"))
                .contains(new ChunkRequirement.Currency("points", 25, Source.PLAYER));
        assertThat(ChunkCostLines.parse("currency gems 4 island"))
                .contains(new ChunkRequirement.Currency("gems", 4, Source.ISLAND));
        assertThat(ChunkCostLines.parse("  item oak_log   32 "))
                .contains(new ChunkRequirement.Items("minecraft:oak_log", 32));
        assertThat(ChunkCostLines.parse("points 5")).contains(new ChunkRequirement.Points(5));
        assertThat(ChunkCostLines.parse("level 12")).contains(new ChunkRequirement.Level(12));
        assertThat(ChunkCostLines.parse("level ladder"))
                .contains(new ChunkRequirement.Level(ChunkRequirement.Level.LADDER));
    }

    @Test
    @DisplayName("A line that says no price reads as none")
    void aWrongLineIsNone() {
        for (String wrong : List.of(
                "",
                "money",
                "money lots",
                "money 5 nowhere",
                "money 5 island extra",
                "currency points",
                "currency points many",
                "currency points 3 island extra",
                "item stone",
                "item stone 4 extra",
                "points",
                "points 2 3",
                "level high",
                "level 3 4",
                "wizard 3",
                "money -5",
                "points 0")) {
            assertThat(ChunkCostLines.parse(wrong)).describedAs(wrong).isEmpty();
        }
    }

    @Test
    @DisplayName("Rings are written as a span, one ring, or a ring and every ring past it")
    void ringsRead() {
        assertThat(ChunkBlockConfiguration.rings("1-2"))
                .hasValueSatisfying(r -> assertThat(r).containsExactly(1, 2));
        assertThat(ChunkBlockConfiguration.rings(" 3 - 5 "))
                .hasValueSatisfying(r -> assertThat(r).containsExactly(3, 5));
        assertThat(ChunkBlockConfiguration.rings("3"))
                .hasValueSatisfying(r -> assertThat(r).containsExactly(3, 3));
        assertThat(ChunkBlockConfiguration.rings("6+"))
                .hasValueSatisfying(r -> assertThat(r).containsExactly(6, Integer.MAX_VALUE));
        assertThat(ChunkBlockConfiguration.rings("")).isEmpty();
        assertThat(ChunkBlockConfiguration.rings("far")).isEmpty();
        assertThat(ChunkBlockConfiguration.rings("1-x")).isEmpty();
    }

    @Test
    @DisplayName("A grade is read whole, and the grades stand in the order of their names")
    void aGradeReadsWhole() throws Exception {
        ChunkTiers tiers = load("""
                tiers {
                  rare {
                    name = "<gold>Rare"
                    rings = "2-4"
                    weight = 2
                    cost = ["item iron_ingot 16", "money 2500 island", "level ladder"]
                    bypass = ["currency points 60"]
                    rewards = ["[sound] entity.player.levelup 1.0 1.0", "", "[give-money] 500"]
                    points = 2
                  }
                  common {
                    rings = 1
                    cost = ["item cobblestone 64"]
                  }
                }
                """);

        assertThat(tiers.all()).extracting(ChunkTier::id).containsExactly("common", "rare");
        ChunkTier rare = tiers.named("rare").orElseThrow();
        assertThat(rare.name()).isEqualTo("<gold>Rare");
        assertThat(rare.nearest()).isEqualTo(2);
        assertThat(rare.farthest()).isEqualTo(4);
        assertThat(rare.weight()).isEqualTo(2);
        assertThat(rare.cost())
                .containsExactly(
                        new ChunkRequirement.Items("minecraft:iron_ingot", 16),
                        new ChunkRequirement.Money(250000, Source.ISLAND),
                        new ChunkRequirement.Level(ChunkRequirement.Level.LADDER));
        assertThat(rare.bypass()).containsExactly(new ChunkRequirement.Currency("points", 60, Source.PLAYER));
        assertThat(rare.rewards())
                .describedAs("an empty line fires nothing, so it is not kept")
                .containsExactly("[sound] entity.player.levelup 1.0 1.0", "[give-money] 500");
        assertThat(rare.points()).isEqualTo(2);

        ChunkTier common = tiers.named("common").orElseThrow();
        assertThat(common.name())
                .describedAs("a grade with no name is named from the language files")
                .isEqualTo("@chunkblock.tiers.common");
        assertThat(common.nearest()).isEqualTo(1);
        assertThat(common.farthest()).isEqualTo(1);
        assertThat(common.weight()).isEqualTo(1);
        assertThat(common.bypass()).isEmpty();
        assertThat(common.rewards()).isEmpty();
        assertThat(common.points()).isZero();
    }

    @Test
    @DisplayName("A grade that cannot be read is left out, and the others stand")
    void aBrokenGradeIsLeftOut() throws Exception {
        ChunkTiers tiers = load("""
                tiers {
                  ringless { cost = ["points 1"] }
                  priceless { rings = "1-2", cost = ["wizard 3", "money lots"] }
                  backwards { rings = "4-2", cost = ["points 1"] }
                  weightless { rings = "1", weight = 0, cost = ["points 1"] }
                  fine { rings = "1-3", cost = ["wizard 3", "points 1"] }
                }
                """);

        assertThat(tiers.all()).extracting(ChunkTier::id).containsExactly("fine");
        assertThat(tiers.named("fine").orElseThrow().cost())
                .describedAs("the line that does not read is left out of a grade that still costs something")
                .containsExactly(new ChunkRequirement.Points(1));
    }

    @Test
    @DisplayName("A file with no grade prices every chunk by the level ladder")
    void noGradeIsTheLadder() throws Exception {
        assertThat(load("enabled = true").all()).isEmpty();
        assertThat(new ChunkBlockConfiguration(true, ChunkUnlockRules.shipped(), "")
                        .tiers()
                        .all())
                .isEmpty();
    }

    @Test
    @DisplayName("The signs over closed chunks float unless the file turns them off")
    void theSignsAreTheOperators() throws Exception {
        assertThat(ChunkBlockConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("enabled = true"))
                        .signs())
                .isTrue();
        assertThat(ChunkBlockConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("signs = false"))
                        .signs())
                .isFalse();
    }

    @Test
    @DisplayName("Every grade the plugin ships reads, every line of it")
    void theShippedGradesRead() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/modules/chunkblock.conf"), "modules/chunkblock.conf")) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ChunkTiers tiers = load(shipped);

        assertThat(tiers.all()).extracting(ChunkTier::id).containsExactly("common", "epic", "far", "rare");
        assertThat(tiers.all())
                .extracting(tier -> tier.cost().size())
                .describedAs("common, epic, far and rare, with no line lost")
                .containsExactly(3, 4, 4, 3);
        assertThat(tiers.all()).allSatisfy(tier -> {
            assertThat(tier.bypass()).hasSize(1);
            assertThat(tier.rewards()).isNotEmpty();
            assertThat(tier.points()).isPositive();
            assertThat(tier.held()).describedAs("a shipped chunk is bought").isFalse();
        });
        assertThat(tiers.named("epic").orElseThrow().farthest()).isEqualTo(Integer.MAX_VALUE);
    }
}
