package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.bukkit.config.BankMoneySpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import org.jspecify.annotations.Nullable;

/**
 * Every currency an island bank keeps, its money among them, as one tile each in the bank window, and the window of
 * one of them, where a member puts some in or takes some out.
 *
 * <p>The money had seven buttons of its own in the middle of the bank window and every other currency a tile in the
 * row under them, whose window looked like no other. Each currency is now one tile, and each opens the same window:
 * the amounts its file names, one the player types, and all of it.
 *
 * <p>What the island holds of each was read with the rest of the island's values, as {@code held_<id>}, and what
 * the reader carries as {@code carried_<id>}, so drawing a tile reads nothing.
 */
public final class BankCurrencyList {

    /** The list a menu file names, and the window of one currency. */
    static final String SOURCE = "skyblock:bank-currencies";

    public static final String FILE = "island-bank-currency";

    /** The bank window the tiles stand in. */
    public static final String BANK_FILE = "island-bank";

    /** The verb a currency's tile runs: the window of that currency. */
    static final String OPEN = "skyblock:bank-currency";

    /** The verb a button of that window runs: {@code in <amount>} or {@code out <amount>}, of its currency. */
    static final String MOVE = "skyblock:bank-move";

    /** The value a held amount is read from, for the currency it ends with. */
    public static final String HELD = "held_";

    /** The value what the reader carries is read from, for the currency it ends with. */
    public static final String CARRIED = "carried_";

    /** The id the island's own money goes by in these windows, one no operator currency may take. */
    public static final String MONEY = "primary";

    /** How many of a currency's one-click amounts its window is handed. */
    static final int QUICK = 2;

    /** One currency as the window draws it. */
    public record Entry(String id, String displayName, String icon, int order, List<Long> quick) {
        public Entry {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(displayName, "displayName must not be null");
            Objects.requireNonNull(icon, "icon must not be null");
            quick = List.copyOf(Objects.requireNonNull(quick, "quick must not be null"));
        }

        /** Whether this is the island's own money. */
        public boolean money() {
            return MONEY.equals(id);
        }
    }

    /** What a player carries of a currency, by id, read where it lives, or nothing when it cannot be read. */
    @FunctionalInterface
    public interface Carried {
        Optional<String> of(UUID player, String id);

        /** Nothing is ever known. */
        static Carried unknown() {
            return (player, id) -> Optional.empty();
        }
    }

    private final Messages messages;
    private final Supplier<BankMoneySpec> money;
    private final Supplier<List<BankCurrencySpec>> currencies;
    private final UnaryOperator<String> typed;
    private volatile Carried carried = Carried.unknown();
    private volatile @Nullable IslandControlMenu control;

    /**
     * @param money how the island's own money is drawn
     * @param currencies the other currencies the bank keeps, turned on
     * @param typed an island command line under the operator's names, as {@code bank deposit 50 experience}
     */
    public BankCurrencyList(
            Messages messages,
            Supplier<BankMoneySpec> money,
            Supplier<List<BankCurrencySpec>> currencies,
            UnaryOperator<String> typed) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.currencies = Objects.requireNonNull(currencies, "currencies must not be null");
        this.typed = Objects.requireNonNull(typed, "typed must not be null");
    }

    /** Hands the windows how to read what a player carries. */
    public void useCarried(Carried carried) {
        this.carried = Objects.requireNonNull(carried, "carried must not be null");
    }

    /** Hands the windows the menu that draws them again with fresh values once a move lands. */
    public void useControl(@Nullable IslandControlMenu control) {
        this.control = control;
    }

    /** Registers the list, the verb that opens one currency's window and the verb that moves it. */
    public void register(SkyblockMenuEngine engine) {
        Objects.requireNonNull(engine, "engine must not be null");
        engine.bindings().list(SOURCE, ctx -> rows(ctx.viewer(), ctx.arguments()));
        engine.action(
                OPEN,
                ctx -> MenuRow.handle(ctx.context(), Entry.class).ifPresent(entry -> {
                    Map<String, String> values = new HashMap<>(ctx.context().arguments());
                    values.putAll(valuesOf(ctx.player(), entry, ctx.context().arguments()));
                    engine.open(ctx.player(), FILE, values);
                }));
        engine.action(MOVE, ctx -> {
            String currency = ctx.context().arguments().getOrDefault("currency", "");
            lineOf(ctx.arg(), currency).ifPresent(line -> ctx.player().performCommand(typed.apply(line)));
        });
    }

    /**
     * The bank command a button runs: {@code in 100} of the money is {@code bank deposit 100}, and {@code out all}
     * of experience is {@code bank withdraw all experience}. Nothing for a button that names no amount, as a
     * cancelled question leaves it.
     */
    static Optional<String> lineOf(String written, String currency) {
        String[] words = written.strip().split("\\s+", 2);
        if (words.length < 2 || words[1].isBlank() || currency.isBlank()) {
            return Optional.empty();
        }
        String verb =
                switch (words[0]) {
                    case "in" -> "bank deposit ";
                    case "out" -> "bank withdraw ";
                    default -> "";
                };
        if (verb.isEmpty()) {
            return Optional.empty();
        }
        String amount = words[1].strip();
        return Optional.of(verb + amount + (MONEY.equals(currency) ? "" : " " + currency));
    }

    /** Every currency the bank keeps, the money among them, in the operator's order. */
    List<Entry> entries() {
        BankMoneySpec own = money.get();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(MONEY, own.displayName(), own.icon(), own.order(), own.quick()));
        for (BankCurrencySpec spec : currencies.get()) {
            entries.add(new Entry(spec.id(), spec.displayName(), spec.icon(), spec.order(), spec.quick()));
        }
        // The money stands first among currencies of one place.
        entries.sort(Comparator.comparingInt(Entry::order)
                .thenComparing(entry -> !entry.money())
                .thenComparing(Entry::id));
        return List.copyOf(entries);
    }

    /** What {@code player} carries of every currency, as {@code carried_<id>}. Call off the player's thread. */
    public Map<String, String> carriedBy(UUID player) {
        Map<String, String> values = new HashMap<>();
        Carried reader = this.carried;
        for (Entry entry : entries()) {
            reader.of(player, entry.id()).ifPresent(amount -> values.put(CARRIED + entry.id(), amount));
        }
        return Map.copyOf(values);
    }

    /** One row per currency: its name, its icon, what the island holds of it and what the reader carries. */
    List<MenuRow> rows(Player viewer, Map<String, String> values) {
        List<MenuRow> rows = new ArrayList<>();
        for (Entry entry : entries()) {
            Map<String, String> own = valuesOf(viewer, entry, values);
            rows.add(new MenuRow(
                    Map.of(
                            "id", entry.id(),
                            "name", own.getOrDefault("currency_name", entry.id()),
                            "amount", own.getOrDefault("currency_amount", "0"),
                            "carried", own.getOrDefault("currency_carried", "?"),
                            "icon", entry.icon()),
                    entry));
        }
        return List.copyOf(rows);
    }

    /**
     * What a currency's window is opened with: its id and name, what the island holds and the reader carries, and
     * the amounts its buttons move in one click.
     */
    private Map<String, String> valuesOf(Player viewer, Entry entry, Map<String, String> values) {
        Map<String, String> own = new HashMap<>();
        own.put("currency", entry.id());
        own.put("currency_name", messages.words(viewer, entry.displayName()));
        own.put("currency_icon", entry.icon());
        own.put(
                "currency_amount",
                entry.money() ? values.getOrDefault("balance", "0") : values.getOrDefault(HELD + entry.id(), "0"));
        own.put("currency_carried", values.getOrDefault(CARRIED + entry.id(), "?"));
        List<Long> quick = entry.quick().isEmpty() ? BankCurrencySpec.DEFAULT_QUICK : entry.quick();
        for (int i = 0; i < QUICK; i++) {
            // A currency that names one amount moves it on both buttons.
            own.put("quick_" + (i + 1), Long.toString(quick.get(Math.min(i, quick.size() - 1))));
        }
        return Map.copyOf(own);
    }

    /**
     * Draws the bank window and the window of {@code currency} again with the values they stand at now, for a
     * player who still has one of them up. A move is said in chat, and the window shows what it left.
     */
    public void moved(Player player, String currency) {
        IslandControlMenu menu = this.control;
        if (menu == null) {
            return;
        }
        menu.refresh(player, BANK_FILE);
        entries().stream()
                .filter(entry -> entry.id().equals(currency))
                .findFirst()
                .ifPresent(entry -> menu.refresh(player, FILE, values -> {
                    Map<String, String> both = new HashMap<>(values);
                    both.putAll(valuesOf(player, entry, values));
                    return Map.copyOf(both);
                }));
    }
}
