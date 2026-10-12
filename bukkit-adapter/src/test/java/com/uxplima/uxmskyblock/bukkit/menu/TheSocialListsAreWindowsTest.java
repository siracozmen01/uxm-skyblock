package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A guestbook, the bookmarks and the alliances are windows drawn from their files, one tile per line, and
 * a click runs the island command the tile stands for under the names this server gave it.
 */
class TheSocialListsAreWindowsTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final List<String> typed = new ArrayList<>();
    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private SocialWindows windows;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(
                dataDir, "island-guestbook.conf", "island-bookmarks.conf", "island-alliances.conf");
        windows = new SocialWindows(Messages.bundled(), line -> {
            typed.add(line);
            return "unregistered " + line;
        });
        windows.useMenuEngine(engine);
    }

    private String lore(String file, String item, MenuRow row) {
        return ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                ShippedTemplates.template(file, item),
                MenuContext.of(ada, null, 0).withEntry(row));
    }

    private void click(String verb, MenuRow row, ClickKind kind) {
        ShippedTemplates.click(engine, verb, MenuContext.of(ada, null, 0).withEntry(row), ada, kind, "");
    }

    @Test
    @DisplayName("A guestbook entry says what it says and whether it is pinned, and a click moderates it by its id")
    void aGuestbookEntry() {
        List<MenuRow> rows = windows.entryRows(
                ada,
                List.of(
                        new SocialWindows.Entry("e1", "Lovely farm!", true, false),
                        new SocialWindows.Entry("e2", "spam", false, true)));

        assertThat(rows.get(0).words()).containsEntry("state", "Pinned").containsEntry("material", "PAPER");
        assertThat(rows.get(1).words()).containsEntry("state", "Hidden").containsEntry("material", "GRAY_DYE");
        assertThat(lore("island-guestbook.conf", "entries", rows.get(0)))
                .contains("Lovely farm!")
                .contains("Now Pinned")
                .contains("Shift right click to delete it")
                .doesNotContain("<entry_");
        click(SocialWindows.PIN, rows.get(0), ClickKind.LEFT);
        click(SocialWindows.PIN, rows.get(1), ClickKind.LEFT);
        click(SocialWindows.HIDE, rows.get(0), ClickKind.RIGHT);
        click(SocialWindows.HIDE, rows.get(1), ClickKind.RIGHT);
        click(SocialWindows.DELETE, rows.get(1), ClickKind.SHIFT_RIGHT);
        assertThat(typed)
                .containsExactly(
                        "guestbook unpin e1",
                        "guestbook pin e2",
                        "guestbook hide e1",
                        "guestbook show e2",
                        "guestbook delete e2");
    }

    @Test
    @DisplayName("A bookmark is a tile of the island and its owner, and a click visits the owner's island")
    void aBookmark() {
        List<MenuRow> rows = SocialWindows.placeRows(
                List.of(new SocialWindows.Place("Sunny Isle", "Bo"), new SocialWindows.Place("lost-key", null)));

        assertThat(lore("island-bookmarks.conf", "islands", rows.get(0)))
                .contains("Sunny Isle")
                .contains("Owner Bo")
                .doesNotContain("<entry_");
        assertThat(rows.get(1).words()).containsEntry("owner", "?");
        click(SocialWindows.VISIT, rows.get(0), ClickKind.LEFT);
        click(SocialWindows.VISIT, rows.get(1), ClickKind.LEFT);
        assertThat(typed)
                .describedAs("an island whose owner is unknown visits nobody")
                .containsExactly("visit Bo");
    }

    @Test
    @DisplayName("An ally is visited or broken with, and an offer is taken or turned down, by its owner's name")
    void alliesAndOffers() {
        MenuRow ally = SocialWindows.placeRows(List.of(new SocialWindows.Place("Rock", "Cy")))
                .get(0);
        MenuRow offer = SocialWindows.placeRows(List.of(new SocialWindows.Place("Reef", "Di")))
                .get(0);

        assertThat(lore("island-alliances.conf", "allies", ally))
                .contains("Rock")
                .contains("break the alliance");
        assertThat(lore("island-alliances.conf", "offers", offer))
                .contains("Reef")
                .contains("turn it down");
        click(SocialWindows.BREAK, ally, ClickKind.SHIFT_RIGHT);
        click(SocialWindows.ACCEPT, offer, ClickKind.LEFT);
        click(SocialWindows.DECLINE, offer, ClickKind.RIGHT);
        assertThat(typed).containsExactly("alliance break Cy", "alliance accept Di", "alliance decline Di");
    }

    @Test
    @DisplayName("Each window opens from its file, and answers false with no engine so the command says it in chat")
    void eachWindowOpens() {
        SocialWindows bare = new SocialWindows(Messages.bundled(), line -> line);
        assertThat(bare.showGuestbook(ada, List.of())).isFalse();
        assertThat(bare.showBookmarks(ada, List.of())).isFalse();
        assertThat(bare.showAlliances(ada, List.of(), List.of())).isFalse();

        engine.install();
        assertThat(windows.showGuestbook(ada, List.of())).isTrue();
        assertThat(windows.showBookmarks(ada, List.of())).isTrue();
        assertThat(windows.showAlliances(ada, List.of(new SocialWindows.Place("Rock", "Cy")), List.of()))
                .isTrue();
        assertThat(engine.bindings().list(SocialWindows.ALLIES).orElseThrow().apply(MenuContext.of(ada, null, 0)))
                .hasSize(1);
    }
}
