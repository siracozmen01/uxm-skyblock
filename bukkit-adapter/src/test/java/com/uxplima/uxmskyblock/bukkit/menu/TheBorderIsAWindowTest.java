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
                .containsExactlyInAnyOrder(
                        "toggle",
                        "blue",
                        "green",
                        "red",
                        "back",
                        "edges",
                        "previous",
                        "no-previous",
                        "next",
                        "no-next");
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

    /** An island's choices in memory, and a window that may choose them. */
    private final java.util.Map<com.uxplima.uxmskyblock.core.domain.identity.IslandId, String> stored =
            new java.util.HashMap<>();

    private final java.util.Set<com.uxplima.uxmskyblock.core.domain.identity.IslandId> ownEdge =
            new java.util.HashSet<>();

    private com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges edges() {
        var port = new com.uxplima.uxmskyblock.core.application.boundary.IslandEdgePort() {
            @Override
            public java.util.Optional<String> find(com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId) {
                return java.util.Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public java.util.Map<com.uxplima.uxmskyblock.core.domain.identity.IslandId, String> findAll() {
                return java.util.Map.copyOf(stored);
            }

            @Override
            public void keep(com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId, String style) {
                stored.put(islandId, style);
            }
        };
        var walls = new com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration(
                java.util.Map.of(
                        "mist",
                        com.uxplima.uxmskyblock.bukkit.wall.WallStyle.colour("mist", 0x8A93A1, 22),
                        "glass",
                        com.uxplima.uxmskyblock.bukkit.wall.WallStyle.block("glass", "minecraft:glass")),
                "mist",
                -64,
                320,
                64);
        return new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges(port, walls, ownEdge::contains);
    }

    @Test
    @DisplayName("The island's edge is a tile per way, the border, each wall and nothing, the one in use marked")
    void theEdgeIsATilePerWay() {
        var known = edges();
        var island = com.uxplima.uxmskyblock.core.domain.identity.IslandId.of(java.util.UUID.randomUUID());
        var mine = java.util.Optional.of(new BorderWindow.Own(island, true));
        known.choose(island, "glass");

        var rows = window.edgeRows(ada, known, mine);

        assertThat(rows).extracting(MenuRow::handle).containsExactly("border", "glass", "mist", "none");
        assertThat(rows)
                .extracting(row -> row.words().get("name"))
                .containsExactly("The border", "Glass wall", "Mist wall", "Nothing");
        assertThat(rows.get(1).words()).containsEntry("state", "In use").containsEntry("material", "GLASS");
        assertThat(rows.get(2).words())
                .containsEntry("state", "Not in use")
                .containsEntry("material", "LIGHT_GRAY_STAINED_GLASS");
        assertThat(rows.get(0).words()).containsEntry("material", "LIGHT_BLUE_STAINED_GLASS");
        assertThat(rows.get(3).words()).containsEntry("material", "BARRIER");

        assertThat(window.edgeRows(ada, known, java.util.Optional.empty()))
                .describedAs("no island")
                .isEmpty();
        ownEdge.add(island);
        assertThat(window.edgeRows(ada, known, mine))
                .describedAs("a ChunkBlock island shows none")
                .isEmpty();
    }

    @Test
    @DisplayName("A role that may change settings chooses the edge; one that may not, or a ChunkBlock island, is told")
    void choosingTheEdge() {
        var known = edges();
        var island = com.uxplima.uxmskyblock.core.domain.identity.IslandId.of(java.util.UUID.randomUUID());
        var may = new java.util.concurrent.atomic.AtomicBoolean(false);
        window.useEdges(
                known,
                player -> java.util.Optional.of(new BorderWindow.Own(island, may.get())),
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort());

        window.choose(ada, "mist");
        assertThat(said()).contains("cannot change how its edge is drawn");
        assertThat(stored).isEmpty();

        may.set(true);
        window.choose(ada, "mist");
        assertThat(said()).contains("drawn as Mist wall now");
        assertThat(known.styleOf(island)).isEqualTo("mist");

        window.choose(ada, "lava");
        assertThat(said()).contains("draws no edge called lava");

        ownEdge.add(island);
        window.choose(ada, "glass");
        assertThat(said()).contains("the wall around its open chunks");
        assertThat(stored).containsEntry(island, "mist");

        window.useEdges(
                known,
                player -> java.util.Optional.empty(),
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort());
        window.choose(ada, "glass");
        assertThat(said()).contains("do not belong to an island");
    }

    @Test
    @DisplayName("With the island's edge to choose, the window opens with its tiles once the island is read")
    void theWindowOpensWithTheEdge() {
        var known = edges();
        var island = com.uxplima.uxmskyblock.core.domain.identity.IslandId.of(java.util.UUID.randomUUID());
        window.useEdges(
                known,
                player -> java.util.Optional.of(new BorderWindow.Own(island, true)),
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort());
        engine.install();

        assertThat(window.show(ada)).isTrue();
        assertThat(engine.bindings().list(BorderWindow.EDGES).orElseThrow().apply(MenuContext.of(ada, null, 0)))
                .hasSize(4);

        MenuRow mist = window.edgeRows(ada, known, java.util.Optional.of(new BorderWindow.Own(island, true)))
                .get(2);
        ShippedTemplates.click(
                engine, BorderWindow.EDGE, MenuContext.of(ada, null, 0).withEntry(mist), ada, ClickKind.LEFT, "");
        assertThat(stored).containsEntry(island, "mist");
    }
}
