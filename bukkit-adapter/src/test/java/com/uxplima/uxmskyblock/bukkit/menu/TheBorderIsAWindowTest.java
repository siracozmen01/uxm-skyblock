package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.Objects;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.boundary.BorderChoice;
import com.uxplima.uxmskyblock.bukkit.boundary.BorderColour;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.boundary.WorldBorderPacketPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is border} opens a window: one tile shows or hides the border, and one tile per colour draws it in that
 * colour. Both are kept on the player.
 */
class TheBorderIsAWindowTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final WorldBorderPacketPort port = mock(WorldBorderPacketPort.class);
    private final IslandBoundaryService boundaries = new IslandBoundaryService(port);
    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private BorderWindow window;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-border.conf");
        window = new BorderWindow(Messages.bundled(), () -> boundaries);
        window.useMenuEngine(engine);
    }

    private String lore(String item) {
        var spec = ShippedTemplates.spec("island-border.conf");
        return ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                Objects.requireNonNull(spec.items().get(item), item),
                MenuContext.of(ada, null, 0, window.valuesOf(ada)));
    }

    private String said() {
        return PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(ada.nextComponentMessage()));
    }

    @Test
    @DisplayName("The window shows whether the border is shown and which colour is in use, blue at first")
    void theWindowSaysWhereItStands() {
        assertThat(ShippedTemplates.spec("island-border.conf").items().keySet().stream()
                        .filter(id -> !id.startsWith("__")))
                .containsExactlyInAnyOrder("toggle", "blue", "green", "red", "back");
        assertThat(lore("toggle")).contains("Shown").doesNotContain("Hidden");
        assertThat(lore("blue")).contains("In use");
        assertThat(lore("green")).contains("Not in use");

        BorderChoice.keep(ada, false);
        BorderChoice.keep(ada, BorderColour.RED);

        assertThat(lore("toggle")).contains("Hidden").doesNotContain("Shown");
        assertThat(lore("red")).contains("In use");
        assertThat(lore("blue")).contains("Not in use");
    }

    @Test
    @DisplayName("The toggle hides the wall where the player stands and keeps that, and a second click shows it")
    void theToggleHidesAndShows() {
        PlayerUuid uuid = new PlayerUuid(ada.getUniqueId());
        boundaries.handlePlayerEnterIsland(uuid, IslandBounds.fromCenterAndRadius(0, 0, 25));
        MenuContext drawn = MenuContext.of(ada, null, 0);

        ShippedTemplates.click(engine, BorderWindow.TOGGLE, drawn, ada, ClickKind.LEFT, "");

        verify(port).resetWorldBorder(uuid);
        assertThat(BorderChoice.hidden(ada)).isTrue();
        assertThat(said()).contains("hidden");

        ShippedTemplates.click(engine, BorderWindow.TOGGLE, drawn, ada, ClickKind.LEFT, "");

        assertThat(BorderChoice.hidden(ada)).isFalse();
        assertThat(said()).contains("shown");
    }

    @Test
    @DisplayName("A colour is kept on the player and the wall is drawn again where they stand")
    void aColourIsKeptAndDrawn() {
        PlayerUuid uuid = new PlayerUuid(ada.getUniqueId());
        boundaries.handlePlayerEnterIsland(uuid, IslandBounds.fromCenterAndRadius(0, 0, 25));
        org.mockito.Mockito.clearInvocations(port);

        ShippedTemplates.click(engine, BorderWindow.COLOUR, MenuContext.of(ada, null, 0), ada, ClickKind.LEFT, "green");

        assertThat(BorderChoice.colour(ada)).isEqualTo(BorderColour.GREEN);
        verify(port).sendWorldBorder(uuid, 0, 0, 25, 0.0, 0L);
        assertThat(said()).contains("green");

        ShippedTemplates.click(
                engine, BorderWindow.COLOUR, MenuContext.of(ada, null, 0), ada, ClickKind.LEFT, "purple");
        assertThat(BorderChoice.colour(ada)).describedAs("no such colour").isEqualTo(BorderColour.GREEN);
    }

    @Test
    @DisplayName("The window opens from its file, and with no engine the command is told it did not")
    void theWindowOpensFromItsFile() {
        assertThat(new BorderWindow(Messages.bundled(), () -> boundaries).show(ada))
                .isFalse();

        engine.install();
        assertThat(window.show(ada)).isTrue();
    }
}
