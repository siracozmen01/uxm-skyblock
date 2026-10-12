package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The feed is a window of its lines, narrowed by buttons the menu file writes: one member, some kinds of line, a
 * stretch of time. Beside it a window says who put in and who took out how much, under the same filters.
 */
class TheFeedIsAWindowTest extends MockBukkitHarness {

    private static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BERK = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private ActivityWindow window;
    private final List<ActivityWindow.View> reopened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-activity.conf", "island-activity-totals.conf");
        window = new ActivityWindow(Messages.bundled());
        window.useMenuEngine(engine);
        window.onChange((player, view) -> reopened.add(view));
    }

    private void click(String verb, String arg) {
        ShippedTemplates.click(engine, verb, MenuContext.of(ada, null, 0), ada, ClickKind.LEFT, arg);
    }

    private String lore(String file, String item, MenuContext ctx) {
        return ShippedTemplates.lore(ShippedTemplates.renderer(engine, Messages.bundled()), itemOf(file, item), ctx);
    }

    /** A button of {@code file}, or the template its list stamps once per row. */
    private static com.uxplima.uxmlib.menu.spec.MenuItemSpec itemOf(String file, String item) {
        com.uxplima.uxmlib.menu.spec.MenuItemSpec spec = java.util.Objects.requireNonNull(
                ShippedTemplates.spec(file).items().get(item), item + " in " + file);
        return spec.list().isPresent() ? spec.list().orElseThrow().template() : spec;
    }

    /** What the window would be given now, as the menu file reads it. */
    private MenuContext withFilters() {
        return MenuContext.of(ada, null, 0, window.filterWords(ada, uuid -> uuid.equals(ALI) ? "Ali" : "Berk"));
    }

    @Test
    @DisplayName("A line of the feed is a tile of the line as the feed writes it")
    void aLineOfTheFeed() {
        MenuRow row = ActivityWindow.lineRows(List.of(new ActivityWindow.Line("5m ago Bo put 50 in the bank.", "5m")))
                .get(0);

        assertThat(lore(
                        "island-activity.conf",
                        "lines",
                        MenuContext.of(ada, null, 0).withEntry(row)))
                .contains("On your island")
                .contains("5m ago Bo put 50 in the bank.")
                .doesNotContain("<entry_");
    }

    @Test
    @DisplayName("A kind button narrows the feed to the kinds it names, and the window says which")
    void aKindButtonNarrows() {
        click(ActivityWindow.KIND, "bank=BANK_DEPOSIT,BANK_WITHDRAW");

        ActivityWindow.Filter filter = window.filterOf(ada.getUniqueId());
        assertThat(filter.types())
                .containsExactlyInAnyOrder(ActivityEventType.BANK_DEPOSIT, ActivityEventType.BANK_WITHDRAW);
        assertThat(reopened).containsExactly(ActivityWindow.View.LINES);
        assertThat(lore("island-activity.conf", "kind-bank", withFilters())).contains("Showing The bank");

        click(ActivityWindow.KIND, "all");
        assertThat(window.filterOf(ada.getUniqueId()).types())
                .describedAs("all shows every kind again")
                .isEmpty();
        assertThat(lore("island-activity.conf", "kind-all", withFilters())).contains("Showing Everything");
    }

    @Test
    @DisplayName("A kind the plugin does not know is left out, and the known ones still narrow")
    void anUnknownKindIsLeftOut() {
        ActivityWindow.Filter filter =
                ActivityWindow.kind(window.filterOf(ada.getUniqueId()), "mix=BANK_DEPOSIT,NOT_A_KIND");

        assertThat(filter.types()).containsExactly(ActivityEventType.BANK_DEPOSIT);
        assertThat(filter.kind()).isEqualTo("mix");
    }

    @Test
    @DisplayName(
            "A time button narrows the feed to a stretch, all of it widens it, and a stretch it cannot read changes nothing")
    void aTimeButtonNarrows() {
        click(ActivityWindow.SINCE, "7d");
        ActivityWindow.Filter week = window.filterOf(ada.getUniqueId());
        assertThat(week.since()).isEqualTo(Duration.ofDays(7));
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        assertThat(week.query("island-1", now))
                .isEqualTo(new ActivityQuery("island-1", null, Set.of(), now.minus(Duration.ofDays(7))));
        assertThat(lore("island-activity.conf", "period-week", withFilters())).contains("Showing 7d");

        click(ActivityWindow.SINCE, "soon");
        assertThat(window.filterOf(ada.getUniqueId())).isEqualTo(week);

        click(ActivityWindow.SINCE, "all");
        assertThat(window.filterOf(ada.getUniqueId()).since()).isNull();
        assertThat(lore("island-activity.conf", "period-all", withFilters())).contains("Showing All of it");
    }

    @Test
    @DisplayName("The member button moves through the members the feed names, then back round to everyone")
    void theMemberButtonMovesOn() {
        window.showLines(ada, List.of(), List.of(ALI, BERK), uuid -> "?");

        click(ActivityWindow.MEMBER, "next");
        assertThat(window.filterOf(ada.getUniqueId()).player()).isEqualTo(ALI);
        assertThat(lore("island-activity.conf", "member", withFilters())).contains("Showing Ali");
        click(ActivityWindow.MEMBER, "next");
        assertThat(window.filterOf(ada.getUniqueId()).player()).isEqualTo(BERK);
        click(ActivityWindow.MEMBER, "next");
        assertThat(window.filterOf(ada.getUniqueId()).player())
                .describedAs("past the last, everyone")
                .isNull();
        assertThat(lore("island-activity.conf", "member", withFilters())).contains("Showing Everyone");

        click(ActivityWindow.MEMBER, "next");
        click(ActivityWindow.MEMBER, "all");
        assertThat(window.filterOf(ada.getUniqueId()).player()).isNull();
    }

    @Test
    @DisplayName("Filters stay with the viewer until they leave, and each viewer has their own")
    void filtersAreTheViewers() {
        PlayerMock bo = createPlayer("Bo");
        click(ActivityWindow.KIND, "bank=BANK_DEPOSIT");

        assertThat(window.filterOf(bo.getUniqueId()).types()).isEmpty();
        window.forget(ada.getUniqueId());
        assertThat(window.filterOf(ada.getUniqueId()).types()).isEmpty();
    }

    @Test
    @DisplayName("The totals button opens the bank by member, and the way back opens the feed")
    void theTotalsWindow() {
        click(ActivityWindow.OPEN_TOTALS, "");
        click(ActivityWindow.OPEN_LINES, "");
        assertThat(reopened).containsExactly(ActivityWindow.View.TOTALS, ActivityWindow.View.LINES);

        List<MenuRow> rows = window.totalRows(
                ada,
                List.of(
                        new ActivityWindow.Total("Ali", true, "600", 2),
                        new ActivityWindow.Total("Berk", false, "200", 1)));
        assertThat(rows.get(0).words()).containsEntry("material", "LIME_DYE").containsEntry("way", "Put in");
        assertThat(rows.get(1).words()).containsEntry("material", "RED_DYE").containsEntry("way", "Took out");
        assertThat(lore(
                        "island-activity-totals.conf",
                        "totals",
                        MenuContext.of(ada, null, 0).withEntry(rows.get(0))))
                .contains("Ali")
                .contains("Put in 600")
                .contains("Moves 2")
                .doesNotContain("<entry_");
    }

    @Test
    @DisplayName("Every kind the shipped buttons name is a kind the feed has")
    void theShippedKindsAreKinds() {
        for (String item : List.of("kind-bank", "kind-members", "kind-progress")) {
            String token = ShippedTemplates.verbs(itemOf("island-activity.conf", item)).values().stream()
                    .flatMap(List::stream)
                    .filter(id -> id.startsWith(ActivityWindow.KIND + ":"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(item + " names no kind"));
            String argument = token.substring(ActivityWindow.KIND.length() + 1);
            int written = argument.substring(argument.indexOf('=') + 1).split(",", -1).length;
            assertThat(ActivityWindow.kind(ActivityWindow.Filter.everything(), argument)
                            .types())
                    .describedAs(item)
                    .hasSize(written);
        }
    }

    @Test
    @DisplayName("Both windows open from their files, and answer false with no engine so the command says it in chat")
    void bothWindowsOpen() {
        ActivityWindow bare = new ActivityWindow(Messages.bundled());
        assertThat(bare.showLines(ada, List.of(), List.of(), uuid -> "?")).isFalse();
        assertThat(bare.showTotals(ada, List.of(), uuid -> "?")).isFalse();

        engine.install();
        assertThat(window.showLines(ada, List.of(new ActivityWindow.Line("x", "1m")), List.of(), uuid -> "?"))
                .isTrue();
        assertThat(window.showTotals(ada, List.of(new ActivityWindow.Total("Ali", true, "5", 1)), uuid -> "?"))
                .isTrue();
        assertThat(engine.bindings().list(ActivityWindow.TOTALS).orElseThrow().apply(MenuContext.of(ada, null, 0)))
                .hasSize(1);
    }
}
