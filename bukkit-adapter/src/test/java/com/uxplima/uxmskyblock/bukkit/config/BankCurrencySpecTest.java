package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The currencies an island bank keeps are the operator's list, read from {@code modules/bank.conf}. */
class BankCurrencySpecTest {

    private static ConfigurationNode read(String hocon) throws Exception {
        return HoconConfigurationLoader.builder().buildAndLoadString(hocon);
    }

    @Test
    @DisplayName("The shipped file keeps experience, points and diamonds, every one on, and the money first")
    void theShippedFile() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/bank.conf"))
                .build()
                .load();

        BankConfiguration config = BankConfiguration.load(root);

        assertThat(config.currencies())
                .extracting(BankCurrencySpec::id)
                .containsExactly("experience", "points", "diamonds");
        assertThat(config.enabledCurrencies())
                .describedAs("a currency whose plugin is missing stays out by itself, so every one ships on")
                .extracting(BankCurrencySpec::id)
                .containsExactly("experience", "points", "diamonds");
        assertThat(config.currencies())
                .extracting(BankCurrencySpec::quick)
                .containsExactly(List.of(10L, 100L), List.of(10L, 100L), List.of(1L, 16L));
        assertThat(config.money())
                .isEqualTo(new BankMoneySpec("@bank.currencies.money", "GOLD_INGOT", 0, List.of(100L, 1000L)));
        BankCurrencySpec experience = config.currencies().get(0);
        assertThat(experience.type()).isEqualTo(BankCurrencySpec.Type.EXPERIENCE);
        assertThat(experience.option("unit")).hasValue("points");
        assertThat(experience.displayName()).isEqualTo("@bank.currencies.experience");
        assertThat(config.currencies().get(2).option("material")).hasValue("minecraft:diamond");
    }

    @Test
    @DisplayName("Currencies stand in the operator's order, and two of one order by their ids")
    void theOperatorsOrder() throws Exception {
        List<BankCurrencySpec> specs = BankCurrencySpec.load(read("""
                bank.currencies {
                    zeta { type = "experience", order = 5 }
                    beta { type = "PlayerPoints", order = 10 }
                    alpha { type = "item", order = 10, material = "minecraft:emerald" }
                }
                """));

        assertThat(specs).extracting(BankCurrencySpec::id).containsExactly("zeta", "alpha", "beta");
        assertThat(specs.get(2).type()).isEqualTo(BankCurrencySpec.Type.PLAYERPOINTS);
    }

    @Test
    @DisplayName("A currency the file leaves short is on, named by its id, and wears its type's icon")
    void whatTheFileLeavesOut() throws Exception {
        BankCurrencySpec spec = BankCurrencySpec.load(read("bank.currencies.shards { type = \"experience\" }"))
                .get(0);

        assertThat(spec.enabled()).isTrue();
        assertThat(spec.order()).isEqualTo(100);
        assertThat(spec.displayName()).isEqualTo("shards");
        assertThat(spec.icon()).isEqualTo("EXPERIENCE_BOTTLE");
        assertThat(spec.option("unit")).isEmpty();
        assertThat(spec.quick()).isEqualTo(BankCurrencySpec.DEFAULT_QUICK);
    }

    @Test
    @DisplayName("The amounts a click moves are whole and above nothing, each once, the smallest first")
    void theQuickAmounts() throws Exception {
        BankCurrencySpec spec = BankCurrencySpec.load(
                        read("bank.currencies.shards { type = \"experience\", quick = [64, 0, 8, -3, 8] }"))
                .get(0);

        assertThat(spec.quick()).containsExactly(8L, 64L);
        assertThat(spec.options()).doesNotContainKey("quick");
    }

    @Test
    @DisplayName("A quick list with nothing usable in it moves the shipped amounts")
    void noUsableQuickAmount() throws Exception {
        BankCurrencySpec spec = BankCurrencySpec.load(
                        read("bank.currencies.shards { type = \"experience\", quick = [0] }"))
                .get(0);

        assertThat(spec.quick()).isEqualTo(BankCurrencySpec.DEFAULT_QUICK);
    }

    @Test
    @DisplayName("The money is drawn as the file says, and as shipped where it says nothing")
    void theMoney() throws Exception {
        assertThat(BankMoneySpec.load(read("""
                        bank.money { display-name = "Coins", icon = "SUNFLOWER", order = 50, quick = [5] }
                        """))).isEqualTo(new BankMoneySpec("Coins", "SUNFLOWER", 50, List.of(5L)));
        assertThat(BankMoneySpec.load(read("bank.upkeep.enabled = false"))).isEqualTo(BankMoneySpec.DEFAULT);
        assertThat(BankMoneySpec.DEFAULT.format())
                .describedAs("the economy writes it as shipped")
                .isEqualTo("economy");
        assertThat(BankMoneySpec.load(read("bank.money.format = \"<amount> coins\""))
                        .format())
                .isEqualTo("<amount> coins");
        assertThat(BankMoneySpec.load(read("bank.money.format = \" \"")).format())
                .describedAs("a blank format is the economy's")
                .isEqualTo("economy");
    }

    @Test
    @DisplayName(
            "A currency of no known type, or under a name the bank keeps for itself, stays out and the rest stay in")
    void aWrongLineClosesNothing() throws Exception {
        List<BankCurrencySpec> specs = BankCurrencySpec.load(read("""
                bank.currencies {
                    gold { type = "goldplugin" }
                    vault { type = "vault" }
                    "two words" { type = "vault" }
                    Coins { type = "vault" }
                }
                """));

        assertThat(specs).extracting(BankCurrencySpec::id).containsExactly("coins");
    }

    @Test
    @DisplayName("A file with no currencies keeps none beside the island's money")
    void noCurrencies() throws Exception {
        assertThat(BankConfiguration.load(read("bank.upkeep.enabled = false")).currencies())
                .isEmpty();
    }
}
