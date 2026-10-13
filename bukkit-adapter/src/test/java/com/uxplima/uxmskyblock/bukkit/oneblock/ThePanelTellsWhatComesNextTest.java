package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The OneBlock panel and its placeholders tell what comes next: the phase, the next moment, and the pass. */
class ThePanelTellsWhatComesNextTest extends MockBukkitHarness {

    private OneBlockPanel panel(String after, boolean files) throws Exception {
        OneBlockConfiguration config =
                OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        after-last-phase = "%s"
                        phases = [ {
                            name = "plains"
                            length = 10
                            blocks { DIRT = 1 }
                            moments { 4 { block = "CHEST" }, 7 { block = "CHEST" } }
                        }, {
                            name = "underground"
                            length = 5
                            blocks { STONE = 1 }
                        } ]
                        """.formatted(after)));
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        OneBlockPanel panel = new OneBlockPanel(
                service,
                mock(IslandStoragePort.class),
                mock(SchedulerPort.class),
                Messages.bundled(),
                id -> Optional.empty());
        if (files) {
            panel.useConfiguration(() -> config);
        }
        return panel;
    }

    @Test
    @DisplayName("The next phase along the way, the first again past the last when the phases repeat, and the pass")
    void nextPhaseAndPass() throws Exception {
        OneBlockPanel repeats = panel("repeat", true);

        assertThat(repeats.values(null, 2))
                .containsEntry("next_phase", "Underground")
                .containsEntry("next_phase_id", "underground")
                .containsEntry("cycle", "1");
        assertThat(repeats.values(null, 12))
                .containsEntry("next_phase_id", "plains")
                .containsEntry("cycle", "1");
        assertThat(repeats.values(null, 16)).containsEntry("cycle", "2");

        assertThat(panel("stay", true).values(null, 12))
                .describedAs("an island that stays in the last phase has nothing after it")
                .containsEntry("next_phase", "")
                .containsEntry("next_phase_id", "");
    }

    @Test
    @DisplayName("The breaks until the next moment of the phase, none once they are all past or with no files")
    void nextMoment() throws Exception {
        OneBlockPanel panel = panel("repeat", true);

        assertThat(panel.values(null, 1)).containsEntry("next_moment", "3");
        assertThat(panel.values(null, 4)).containsEntry("next_moment", "3");
        assertThat(panel.values(null, 7)).containsEntry("next_moment", "");
        assertThat(panel.values(null, 11))
                .describedAs("underground writes none")
                .containsEntry("next_moment", "");
        assertThat(panel("repeat", false).values(null, 1)).containsEntry("next_moment", "");
    }

    private OneBlockProgressPort memory() {
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {}

            @Override
            public List<OneBlockIsland> findAll() {
                return List.of();
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.empty();
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}

            @Override
            public void setBreaks(IslandId islandId, long count) {}
        };
    }
}
