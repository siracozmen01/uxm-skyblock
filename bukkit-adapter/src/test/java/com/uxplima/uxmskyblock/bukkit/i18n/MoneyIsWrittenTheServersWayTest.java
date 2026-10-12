package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every amount of money was written with a dollar sign in the language files, on a server whose money is coins,
 * gems or anything else. An amount is written the way the economy plugin writes its own, or after the operator's
 * pattern, and no language file writes a sign of its own.
 */
class MoneyIsWrittenTheServersWayTest {

    @AfterEach
    void plainAgain() {
        MoneyText.reset();
    }

    @Test
    @DisplayName("The economy's own way, sign and grouping and all")
    void theEconomysWay() {
        MoneyText.use(MoneyText.writer("economy", amount -> String.format(java.util.Locale.ROOT, "⛂%,.2f", amount)));

        assertThat(MoneyText.of(123_450)).isEqualTo("⛂1,234.50");
        assertThat(MoneyText.ofUnits(25)).isEqualTo("⛂25.00");
    }

    @Test
    @DisplayName("The operator's pattern, the number where <amount> stands")
    void theOperatorsPattern() {
        MoneyText.use(MoneyText.writer("<amount> coins", amount -> "ignored"));

        assertThat(MoneyText.of(2550)).isEqualTo("25.50 coins");
        assertThat(MoneyText.writer("$<amount>", null).apply(3)).isEqualTo("$3.00");
    }

    @Test
    @DisplayName("No economy, an economy that fails, or one that writes nothing: the amount is written plain")
    void plainWhenTheEconomyCannot() {
        DoubleFunction<String> failing = amount -> {
            throw new IllegalStateException("no provider");
        };

        assertThat(MoneyText.writer("economy", null).apply(5)).isEqualTo("5.00");
        assertThat(MoneyText.writer("economy", failing).apply(5)).isEqualTo("5.00");
        assertThat(MoneyText.writer("ECONOMY", amount -> " ").apply(5)).isEqualTo("5.00");
        assertThat(MoneyText.writer("economy", amount -> null).apply(5)).isEqualTo("5.00");
        assertThat(MoneyText.of(500))
                .describedAs("plain until the server says otherwise")
                .isEqualTo("5.00");
    }

    @Test
    @DisplayName("No language file puts a sign in front of an amount")
    void noLanguageWritesASign() throws Exception {
        for (String file : new String[] {"messages_en.conf", "messages_tr.conf"}) {
            String text = Files.readString(Path.of("src/main/resources/messages", file), StandardCharsets.UTF_8);
            assertThat(text).describedAs(file).doesNotContain("$<");
        }
    }
}
