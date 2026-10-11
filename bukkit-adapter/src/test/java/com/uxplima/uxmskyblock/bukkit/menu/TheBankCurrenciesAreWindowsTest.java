package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.bukkit.config.BankMoneySpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Every currency the bank keeps, its money among them, is a tile in the bank window, and every tile opens the same
 * window, where a member puts some in or takes some out.
 *
 * <p>The money had seven buttons of its own in the middle of the bank window and the other currencies one row
 * under them, each opening a window like no other.
 */
class TheBankCurrenciesAreWindowsTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private final List<BankCurrencySpec> kept = List.of(
            new BankCurrencySpec(
                    "experience",
                    BankCurrencySpec.Type.EXPERIENCE,
                    10,
                    true,
                    "@bank.currencies.experience",
                    "EXPERIENCE_BOTTLE",
                    Map.of(),
                    List.of(5L, 50L)),
            new BankCurrencySpec(
                    "tokens",
                    BankCurrencySpec.Type.PLACEHOLDER,
                    20,
                    true,
                    "Tokens",
                    "SUNFLOWER",
                    Map.of(),
                    List.of(3L)));

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-bank.conf", "island-bank-currency.conf");
    }

    private BankCurrencyList list(BankMoneySpec money) {
        return new BankCurrencyList(Messages.bundled(), () -> money, () -> kept, line -> "is " + line);
    }

    @Test
    @DisplayName("The money is a tile like every other currency, first, and each tile says what the island holds and"
            + " what the reader carries")
    void everyCurrencyIsATile() {
        BankCurrencyList list = list(BankMoneySpec.DEFAULT);
        list.register(engine);

        List<MenuRow> rows =
                list.rows(ada, Map.of("balance", "1000.00", "held_experience", "42", "carried_experience", "7"));

        assertThat(rows).extracting(row -> row.words().get("id")).containsExactly("primary", "experience", "tokens");
        assertThat(rows.get(0).words())
                .containsEntry("name", "Money")
                .containsEntry("amount", "1000.00")
                .containsEntry("icon", "GOLD_INGOT");
        assertThat(rows.get(1).words())
                .containsEntry("name", "Experience")
                .containsEntry("amount", "42")
                .containsEntry("carried", "7")
                .containsEntry("icon", "EXPERIENCE_BOTTLE");
        assertThat(rows.get(2).words())
                .describedAs("a currency nothing could read for the reader")
                .containsEntry("amount", "0")
                .containsEntry("carried", "?");
        MenuContext drawn = MenuContext.of(ada, null, 0).withEntry(rows.get(1));
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());
        var template = ShippedTemplates.template("island-bank.conf", "currencies");
        assertThat(ShippedTemplates.lore(renderer, template, drawn))
                .contains("Experience")
                .contains("In the bank 42")
                .contains("You carry 7")
                .contains("Click to put some in or take some out")
                .doesNotContain("<entry_");
        assertThat(renderer.materialSpec(template, drawn)).isEqualTo("EXPERIENCE_BOTTLE");
    }

    @Test
    @DisplayName("The operator may stand the money after a currency, and two of one place keep the money first")
    void theMoneyStandsWhereTheOperatorPutsIt() {
        assertThat(list(new BankMoneySpec("Coins", "SUNFLOWER", 15, List.of(1L)))
                        .entries())
                .extracting(BankCurrencyList.Entry::id)
                .containsExactly("experience", "primary", "tokens");
        assertThat(list(new BankMoneySpec("Coins", "SUNFLOWER", 10, List.of(1L)))
                        .entries())
                .extracting(BankCurrencyList.Entry::id)
                .containsExactly("primary", "experience", "tokens");
    }

    @Test
    @DisplayName("A click on a tile opens that currency's window with what it holds, what the reader carries and the"
            + " amounts its buttons move")
    void aTileOpensItsWindow() {
        SkyblockMenuEngine watched = org.mockito.Mockito.spy(engine);
        BankCurrencyList list = list(BankMoneySpec.DEFAULT);
        list.register(watched);
        Map<String, String> bankValues = Map.of("held_experience", "42", "balance", "100", "carried_experience", "7");
        MenuRow row = list.rows(ada, bankValues).get(1);

        ShippedTemplates.click(
                watched,
                BankCurrencyList.OPEN,
                MenuContext.of(ada, null, 0, bankValues).withEntry(row),
                ada,
                ClickKind.LEFT,
                "");

        org.mockito.Mockito.verify(watched)
                .open(
                        ada,
                        BankCurrencyList.FILE,
                        Map.of(
                                "held_experience", "42",
                                "balance", "100",
                                "carried_experience", "7",
                                "currency", "experience",
                                "currency_name", "Experience",
                                "currency_icon", "EXPERIENCE_BOTTLE",
                                "currency_amount", "42",
                                "currency_carried", "7",
                                "quick_1", "5",
                                "quick_2", "50"));
    }

    @Test
    @DisplayName("A currency that names one amount moves it on both buttons, and the money reads the island's money")
    void oneAmountAndTheMoney() {
        SkyblockMenuEngine watched = org.mockito.Mockito.spy(engine);
        BankCurrencyList list = list(BankMoneySpec.DEFAULT);
        list.register(watched);
        Map<String, String> bankValues = Map.of("balance", "100.00");
        List<MenuRow> rows = list.rows(ada, bankValues);

        ShippedTemplates.click(
                watched,
                BankCurrencyList.OPEN,
                MenuContext.of(ada, null, 0, bankValues).withEntry(rows.get(2)),
                ada,
                ClickKind.LEFT,
                "");
        ShippedTemplates.click(
                watched,
                BankCurrencyList.OPEN,
                MenuContext.of(ada, null, 0, bankValues).withEntry(rows.get(0)),
                ada,
                ClickKind.LEFT,
                "");

        org.mockito.Mockito.verify(watched)
                .open(
                        org.mockito.ArgumentMatchers.eq(ada),
                        org.mockito.ArgumentMatchers.eq(BankCurrencyList.FILE),
                        org.mockito.ArgumentMatchers.argThat(
                                (Map<String, String> values) -> "tokens".equals(values.get("currency"))
                                        && "3".equals(values.get("quick_1"))
                                        && "3".equals(values.get("quick_2"))));
        org.mockito.Mockito.verify(watched)
                .open(
                        org.mockito.ArgumentMatchers.eq(ada),
                        org.mockito.ArgumentMatchers.eq(BankCurrencyList.FILE),
                        org.mockito.ArgumentMatchers.argThat(
                                (Map<String, String> values) -> "primary".equals(values.get("currency"))
                                        && "100.00".equals(values.get("currency_amount"))
                                        && "100".equals(values.get("quick_1"))
                                        && "1000".equals(values.get("quick_2"))));
    }

    @Test
    @DisplayName("A button moves its window's currency: the money with no name after the amount, the rest by id")
    void aButtonRunsTheBankCommand() {
        assertThat(BankCurrencyList.lineOf("in 100", "primary")).hasValue("bank deposit 100");
        assertThat(BankCurrencyList.lineOf("out all", "experience")).hasValue("bank withdraw all experience");
        assertThat(BankCurrencyList.lineOf("in 2.5k", "experience")).hasValue("bank deposit 2.5k experience");
        assertThat(BankCurrencyList.lineOf("in ", "experience"))
                .describedAs("a question the player cancelled")
                .isEmpty();
        assertThat(BankCurrencyList.lineOf("in 5", ""))
                .describedAs("a window with no currency")
                .isEmpty();
        assertThat(BankCurrencyList.lineOf("sideways 5", "experience")).isEmpty();
    }

    @Test
    @DisplayName("A click on a button runs the bank command under the server's names")
    void aClickRunsTheCommand() {
        BankCurrencyList list = list(BankMoneySpec.DEFAULT);
        list.register(engine);
        PlayerMock bob = org.mockito.Mockito.spy(createPlayer("Bob"));

        ShippedTemplates.click(
                engine,
                BankCurrencyList.MOVE,
                MenuContext.of(bob, null, 0, Map.of("currency", "experience")),
                bob,
                ClickKind.LEFT,
                "out 50");

        org.mockito.Mockito.verify(bob).performCommand("is bank withdraw 50 experience");
    }

    @Test
    @DisplayName("Every button of a currency's window moves it through the bank command, and none is the money's alone")
    void theWindowMovesItsCurrency() {
        var spec = ShippedTemplates.spec("island-bank-currency.conf");
        Map<String, String> moves = Map.of(
                "deposit_quick_1", "skyblock:bank-move:in %argument_quick_1%",
                "deposit_quick_2", "skyblock:bank-move:in %argument_quick_2%",
                "deposit_custom", "skyblock:bank-move:in %input%",
                "deposit_all", "skyblock:bank-move:in all",
                "withdraw_quick_1", "skyblock:bank-move:out %argument_quick_1%",
                "withdraw_quick_2", "skyblock:bank-move:out %argument_quick_2%",
                "withdraw_custom", "skyblock:bank-move:out %input%",
                "withdraw_all", "skyblock:bank-move:out all");
        moves.forEach((item, verb) -> assertThat(ShippedTemplates.verbs(
                                java.util.Objects.requireNonNull(spec.items().get(item), item))
                        .toString())
                .describedAs(item)
                .contains(verb));
        assertThat(ShippedTemplates.spec("island-bank.conf").items())
                .describedAs("the money's own buttons are gone from the bank window")
                .doesNotContainKeys("deposit_small", "deposit_large", "withdraw_small", "withdraw_large", "balance");
    }

    @Test
    @DisplayName("What a player carries is read for every currency, and one that cannot be read is left out")
    void whatAPlayerCarries() {
        BankCurrencyList list = list(BankMoneySpec.DEFAULT);
        list.useCarried((player, id) -> id.equals("tokens") ? Optional.empty() : Optional.of(id.length() + ""));

        assertThat(list.carriedBy(ada.getUniqueId()))
                .isEqualTo(Map.of("carried_primary", "7", "carried_experience", "10"));
    }

    @Test
    @DisplayName("Once a move lands the bank window and the window of what moved are drawn again")
    void aMoveDrawsTheWindowsAgain() {
        IslandControlMenu control = org.mockito.Mockito.mock(IslandControlMenu.class);
        BankCurrencyList list = list(BankMoneySpec.DEFAULT);
        list.useControl(control);

        list.moved(ada, "experience");

        org.mockito.Mockito.verify(control).refresh(ada, BankCurrencyList.BANK_FILE);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.function.UnaryOperator<Map<String, String>>> extend =
                org.mockito.ArgumentCaptor.forClass(java.util.function.UnaryOperator.class);
        org.mockito.Mockito.verify(control)
                .refresh(
                        org.mockito.ArgumentMatchers.eq(ada),
                        org.mockito.ArgumentMatchers.eq(BankCurrencyList.FILE),
                        extend.capture());
        assertThat(extend.getValue().apply(Map.of("held_experience", "9")))
                .containsEntry("currency", "experience")
                .containsEntry("currency_amount", "9")
                .containsEntry("held_experience", "9");
    }
}
