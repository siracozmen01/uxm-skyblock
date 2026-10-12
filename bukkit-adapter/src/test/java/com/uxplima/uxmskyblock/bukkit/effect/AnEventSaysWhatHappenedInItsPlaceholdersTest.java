package com.uxplima.uxmskyblock.bukkit.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** A mode's event says what happened in placeholders of its own, read in every line of its list. */
class AnEventSaysWhatHappenedInItsPlaceholdersTest extends MockBukkitHarness {

    private final List<String> heard = new ArrayList<>();
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        player = createPlayer("Runner");
        server.getCommandMap().register("probe", new Command("probe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                heard.add(String.join(" ", args));
                return true;
            }
        });
    }

    @Test
    @DisplayName("Each placeholder the event names is its value, and one it does not name is left as written")
    void placeholders() {
        new InteractionEffectPlayer()
                .fire(
                        effects("[console] probe %phase% took %blocks% %other%"),
                        "oneblock-phase-began",
                        player,
                        Map.of("phase", "plains", "blocks", "500"));

        assertThat(heard).containsExactly("plains took 500 %other%");
    }

    @Test
    @DisplayName("An event that says nothing fires its lines as written")
    void nothingSaid() {
        new InteractionEffectPlayer().fire(effects("[console] probe %phase%"), "oneblock-phase-began", player);

        assertThat(heard).containsExactly("%phase%");
    }

    private static InteractionEffects effects(String line) {
        return new InteractionEffects(Map.of("oneblock-phase-began", ActionList.parse(List.of(line))));
    }
}
