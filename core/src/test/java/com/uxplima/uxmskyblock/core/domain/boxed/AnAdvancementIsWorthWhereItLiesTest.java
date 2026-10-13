package com.uxplima.uxmskyblock.core.domain.boxed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An advancement with no worth of its own is worth what lies where it does: a step deeper in its tab is worth more,
 * and a tab's root, a recipe and one the screen does not show each have a worth the operator writes.
 */
class AnAdvancementIsWorthWhereItLiesTest {

    @Test
    @DisplayName(
            "As shipped, an advancement is worth a block for each step from its root, and roots, recipes and hidden ones nothing")
    void shipped() {
        BoxRules rules = BoxRules.shipped();

        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:story/mine_stone", 1, true)))
                .isEqualTo(1);
        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:nether/all_effects", 6, true)))
                .isEqualTo(6);
        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:story/root", 0, true)))
                .isZero();
        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:recipes/misc/bread", 1, false)))
                .isZero();
        assertThat(rules.blocksFor(new AdvancementFacts("pack:recipes/tools/hammer", 2, true)))
                .isZero();
        assertThat(rules.blocksFor(new AdvancementFacts("pack:quests/trigger", 3, false)))
                .isZero();
    }

    @Test
    @DisplayName(
            "The operator's worth for roots, recipes and hidden ones is used, and a worth of its own wins over all")
    void written() {
        BoxRules rules = new BoxRules(
                5,
                2,
                400,
                Map.of("minecraft:story/root", 7, "pack:deep/one", 1),
                List.of("ignored:"),
                new AdvancementWorth(true, 3, 4, 5));

        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:story/smelt_iron", 3, true)))
                .isEqualTo(6);
        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:end/root", 0, true)))
                .isEqualTo(3);
        assertThat(rules.blocksFor(new AdvancementFacts("pack:recipes/a", 2, true)))
                .isEqualTo(4);
        assertThat(rules.blocksFor(new AdvancementFacts("pack:hidden", 2, false)))
                .isEqualTo(5);
        assertThat(rules.blocksFor(new AdvancementFacts("minecraft:story/root", 0, true)))
                .isEqualTo(7);
        assertThat(rules.blocksFor(new AdvancementFacts("pack:deep/one", 9, true)))
                .isEqualTo(1);
        assertThat(rules.blocksFor(new AdvancementFacts("ignored:recipes/a", 0, false)))
                .isZero();

        BoxRules flat = new BoxRules(5, 2, 400, Map.of(), List.of(), new AdvancementWorth(false, 3, 4, 5));
        assertThat(flat.blocksFor(new AdvancementFacts("minecraft:story/smelt_iron", 3, true)))
                .isEqualTo(2);
        assertThat(flat.blocksFor(new AdvancementFacts("minecraft:end/root", 0, true)))
                .isEqualTo(3);
        assertThat(AdvancementWorth.flat(2)).isEqualTo(new AdvancementWorth(false, 2, 2, 2));
    }

    @Test
    @DisplayName("A recipe is told by its path, and no worth or depth is below nothing")
    void bounds() {
        assertThat(new AdvancementFacts("minecraft:recipes/misc/bread", 1, true).recipe())
                .isTrue();
        assertThat(new AdvancementFacts("recipes/misc/bread", 1, true).recipe()).isTrue();
        assertThat(new AdvancementFacts("minecraft:story/recipes", 1, true).recipe())
                .isFalse();
        assertThat(new AdvancementFacts("pack:recipe_book", 1, true).recipe()).isFalse();
        assertThatThrownBy(() -> new AdvancementFacts("a:b", -1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvancementWorth(true, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvancementWorth(true, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvancementWorth(true, 0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
