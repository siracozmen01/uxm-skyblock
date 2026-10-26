package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.spec.ItemType;
import com.uxplima.uxmlib.menu.spec.MenuItemSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A window that turns pages shows both arrows on every page, one that turns nothing drawn dim.
 *
 * <p>A window with one page drew no arrows, and the two slots they stood in were holes in the bottom row.
 *
 * <p>uxmLib draws an arrow on every page now, whether or not it has a page to turn to, so an arrow that is not gated
 * on {@code has-previous} or {@code has-next} covers the dim arrow under it and says "Previous page" where there is
 * none.
 */
class APageArrowIsAlwaysThereTest extends MockBukkitHarness {

    private static final Path MENUS = Path.of("src/main/resources/menus");
    private static final int PREVIOUS = 48;
    private static final int NEXT = 50;

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private IslandWarpBrowseMenu directory;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-warp-directory.conf");
        directory = new IslandWarpBrowseMenu(Messages.bundled());
        directory.useMenuEngine(engine);
        engine.install();
    }

    @Test
    @DisplayName("One page of warps shows both arrows, each saying there is no page to turn to")
    void onePageShowsBothArrowsDim() {
        directory.show(ada, warps(1), entry -> {});
        drain();

        assertThat(arrowAt(PREVIOUS)).isEqualTo("← No page before this one");
        assertThat(arrowAt(NEXT)).isEqualTo("→ No page after this one");
    }

    @Test
    @DisplayName("More warps than a page holds show the arrow that turns forward, and the one back still dim")
    void manyPagesShowTheArrowThatTurns() {
        directory.show(ada, warps(30), entry -> {});
        drain();

        assertThat(arrowAt(PREVIOUS)).isEqualTo("← No page before this one");
        assertThat(arrowAt(NEXT)).isEqualTo("→ Next page");
    }

    @Test
    @DisplayName("Every arrow of every shipped window has a dim arrow under it, in the same slot")
    void everyArrowHasOneUnderIt() throws IOException {
        List<String> arrows = new ArrayList<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".conf")).toList()) {
                MenuSpec spec = new MenuSpecLoader().load(file);
                for (MenuItemSpec arrow : spec.items().values()) {
                    if (arrow.type() != ItemType.PREVIOUS && arrow.type() != ItemType.NEXT) {
                        continue;
                    }
                    arrows.add(file.getFileName() + " " + arrow.type());
                    String gate = arrow.type() == ItemType.PREVIOUS ? "has-previous" : "has-next";
                    assertThat(arrow.view().requirements())
                            .describedAs(
                                    "the %s arrow of %s gives way when it has no page",
                                    arrow.type(), file.getFileName())
                            .anySatisfy(gated -> {
                                assertThat(gated.condition().id()).isEqualTo(gate);
                                assertThat(gated.inverted()).isFalse();
                            });
                    assertThat(spec.items().values())
                            .describedAs("under the %s arrow of %s", arrow.type(), file.getFileName())
                            .anySatisfy(under -> {
                                assertThat(under.type()).isEqualTo(ItemType.NONE);
                                assertThat(under.slots().slots())
                                        .isEqualTo(arrow.slots().slots());
                                assertThat(under.priority()).isLessThan(arrow.priority());
                                assertThat(under.material()).isEqualTo("ARROW");
                            });
                }
            }
        }
        assertThat(arrows).describedAs("the windows that turn pages").hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    @DisplayName("A vault page with no page beside it shows a dim arrow there, not a pane")
    void theVaultShowsADimArrow() {
        MenuSpec page = ShippedTemplates.spec("island-vault-page.conf");

        for (String id : List.of("first-page", "last-page")) {
            MenuItemSpec item = Objects.requireNonNull(page.items().get(id), id);
            assertThat(item.material()).describedAs(id).isEqualTo("ARROW");
            assertThat(item.name()).describedAs(id).startsWith("@menu.button.no_");
        }
    }

    private String arrowAt(int slot) {
        ItemStack item = ada.getOpenInventory().getTopInventory().getItem(slot);
        assertThat(item).describedAs("slot %s", slot).isNotNull();
        assertThat(Objects.requireNonNull(item).getType()).isEqualTo(Material.ARROW);
        return PlainTextComponentSerializer.plainText()
                .serialize(Objects.requireNonNull(item.getItemMeta().displayName()))
                .strip();
    }

    /** Runs the server until nothing it was asked to do is left, the work off the main thread included. */
    private void drain() {
        for (int round = 0; round < 10; round++) {
            server.getScheduler().performOneTick();
            server.getScheduler().waitAsyncTasksFinished();
        }
    }

    private static List<IslandWarpBrowseMenu.Entry> warps(int count) {
        Instant now = Instant.now();
        List<IslandWarpBrowseMenu.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new IslandWarpBrowseMenu.Entry(
                    new IslandWarp(
                            IslandWarpId.of(UUID.randomUUID()),
                            IslandId.of(UUID.randomUUID()),
                            WarpName.of("warp" + i),
                            new WarpLocation("world", 10.0, 70.0, 10.0, 0.0f, 0.0f),
                            "OAK_SIGN",
                            WarpCategory.GENERAL,
                            false,
                            now,
                            now),
                    "Owner" + i));
        }
        return entries;
    }
}
