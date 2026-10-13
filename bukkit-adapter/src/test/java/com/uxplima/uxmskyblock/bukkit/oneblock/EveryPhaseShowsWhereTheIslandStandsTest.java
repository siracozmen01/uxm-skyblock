package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The phases menu lists every phase of the files: the ones an island reached, the one it is in and the ones still
 * locked to it, each with its wait, what it asks and what its preview says it holds. The panel's phase tile opens it.
 */
class EveryPhaseShowsWhereTheIslandStandsTest extends MockBukkitHarness {

    @TempDir
    Path dataDir;

    private OneBlockConfiguration config;
    private OneBlockPhasesMenu menu;
    private PlayerMock player;

    @BeforeEach
    void files() throws Exception {
        config = OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                after-last-phase = "repeat"
                phases = [ {
                    name = "plains"
                    length = 4
                    blocks { DIRT = 1 }
                    preview = "@oneblock.previews.plains"
                    next = ["underground", "snow"]
                }, {
                    name = "underground"
                    length = 5
                    blocks { STONE = 1 }
                    wait = "30s"
                    requires = [ "have level 25", "take money 5000" ]
                    next = ["plains"]
                }, {
                    name = "snow"
                    length = 3
                    blocks { SNOW_BLOCK = 1 }
                    preview = "Ice, and nothing but ice."
                } ]
                """));
        menu = new OneBlockPhasesMenu(() -> config, Messages.bundled());
        player = createPlayer("Breaker");
    }

    /** The way an island that picked the underground goes: plains, then underground, then plains again. */
    private OneBlockPhases underground() {
        return config.phases().route(Map.of("plains", "underground"));
    }

    private static Map<String, String> words(List<MenuRow> rows, String key) {
        return rows.stream()
                .filter(row -> key.equals(row.words().get("key")))
                .findFirst()
                .orElseThrow()
                .words();
    }

    @Test
    @DisplayName("A phase passed is reached, the one the island is in is here now, a branch it did not pick is locked")
    void standing() {
        List<MenuRow> rows = menu.rows(player, underground(), 5);

        assertThat(rows).extracting(row -> row.words().get("key")).containsExactly("plains", "underground", "snow");
        assertThat(words(rows, "plains"))
                .containsEntry("state", "reached")
                .containsEntry("colour", "good")
                .containsEntry("number", "1")
                .containsEntry("blocks", "4")
                .containsEntry("icon", OneBlockConfiguration.DEFAULT_ICON)
                .containsEntry("facts", "number length state")
                .containsEntry(
                        "reward",
                        "Grass, dirt and wood to build with, farm animals, and a treasure chest at break 100.");
        assertThat(words(rows, "underground"))
                .containsEntry("state", "here now")
                .containsEntry("colour", "accent")
                .containsEntry("facts", "number length state wait requires -description")
                .containsEntry("wait", "0:30")
                .containsEntry("reward", "");
        assertThat(words(rows, "underground"))
                .describedAs("each line as the breaker is told it, set in under the first")
                .containsEntry("requires", "• island level 25\n    • 5000 of your money");
        assertThat(words(rows, "snow"))
                .containsEntry("state", "locked")
                .containsEntry("colour", "muted")
                .containsEntry("number", "3")
                .containsEntry("facts", "number length state")
                .containsEntry("reward", "Ice, and nothing but ice.");
    }

    @Test
    @DisplayName("A phase ahead on the way is locked, and on the next pass every phase of the way is reached")
    void ahead() {
        assertThat(words(menu.rows(player, underground(), 1), "underground")).containsEntry("state", "locked");

        List<MenuRow> again = menu.rows(player, underground(), 10);

        assertThat(words(again, "plains")).containsEntry("state", "here now");
        assertThat(words(again, "underground")).containsEntry("state", "reached");
        assertThat(words(again, "snow")).containsEntry("state", "locked");
    }

    @Test
    @DisplayName("The shipped file draws a phase's tile with every fact it has, and leaves the others off")
    void drawn() {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "oneblock-phases.conf");
        menu.useMenuEngine(engine);
        List<MenuRow> rows = menu.rows(player, underground(), 5);

        String underground = ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                ShippedTemplates.template("oneblock-phases.conf", "phases"),
                MenuContext.of(player, null, 0).withEntry(rows.get(1)));
        String plains = ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                ShippedTemplates.template("oneblock-phases.conf", "phases"),
                MenuContext.of(player, null, 0).withEntry(rows.get(0)));

        assertThat(underground)
                .contains("Underground", "here now", "5 breaks", "0:30", "25", "5000")
                .doesNotContain("<entry_", "%entry_", "Description");
        assertThat(plains).contains("reached", "Description", "treasure chest").doesNotContain("Waits", "Asks");
    }

    @Test
    @DisplayName("The panel's phase tile opens every phase for the player's island")
    void opened() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        ProfileId profile = new ProfileId(UUID.randomUUID());
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        service.start(islandId, 0, 100, 0);
        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findIslandIdByProfileId(profile)).thenReturn(Optional.of(islandId));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        OneBlockPanel panel =
                new OneBlockPanel(service, islands, scheduler, Messages.bundled(), id -> Optional.of(profile));
        SkyblockMenuEngine engine =
                ShippedTemplates.engineWith(dataDir, "island-oneblock.conf", "oneblock-phases.conf");
        panel.useMenuEngine(engine);
        menu.useMenuEngine(engine);
        panel.usePhasesMenu(menu);
        assertThat(ShippedTemplates.verbs(java.util.Objects.requireNonNull(ShippedTemplates.spec("island-oneblock.conf")
                                .items()
                                .get("phase")))
                        .get(ClickKind.ANY))
                .contains(OneBlockPanel.PHASES);

        ShippedTemplates.click(
                engine, OneBlockPanel.PHASES, MenuContext.of(player, null, 0), player, ClickKind.LEFT, "");

        long deadline = System.currentTimeMillis() + 5000;
        while (!engine.showing(player, OneBlockPhasesMenu.MENU) && System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            Thread.onSpinWait();
        }
        assertThat(engine.showing(player, OneBlockPhasesMenu.MENU)).isTrue();
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new HashMap<>();
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}

            @Override
            public void setBreaks(IslandId islandId, long count) {}
        };
    }
}
