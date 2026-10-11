package com.uxplima.uxmskyblock.core.application.gamemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each creation action says where it writes past the island it is handed, and the start adds them up. */
class AnActionSaysWhereItWritesTest {

    private static CreationActionProvider<String> action(String id, List<CreationReach> reach) {
        return new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return id;
            }

            @Override
            public void apply(String context) {}

            @Override
            public List<CreationReach> reach(String context) {
                return reach;
            }
        };
    }

    @Test
    @DisplayName("A start reaches as far as each of its actions says, and an action nobody provides nowhere")
    void aStartReachesAsItsActionsSay() {
        CreationActions<String> actions = new CreationActions<>();
        actions.register(action("uxm:grid", List.of(new CreationReach("skyblock", 48))));
        actions.register(action("uxm:mirror", List.of(new CreationReach("skyblock_nether", 63))));
        actions.register(new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return "uxm:platform";
            }

            @Override
            public void apply(String context) {}
        });

        assertThat(actions.reachOf(List.of("uxm:platform", "UXM:Grid", "uxm:mirror", "uxm:unknown"), "here"))
                .containsExactly(new CreationReach("skyblock", 48), new CreationReach("skyblock_nether", 63));
    }
}
