package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** A mission names the modes it belongs to and the mission it follows, in the operator's file. */
class AMissionNamesItsModesTest {

    @Test
    @DisplayName(
            "Modes in a list or one alone, a mission it follows; one naming no mode there is, or counting what nothing"
                    + " gives, left out")
    void modesAndAfter() throws Exception {
        Map<String, MissionDefinition> read =
                MissionConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                                missions.catalog {
                                  first { trigger-type = BLOCK_BREAK, modes = ["oneblock", "Boxed"] }
                                  second { trigger-type = BLOCK_BREAK, modes = "oneblock", after = "first" }
                                  plain { trigger-type = BLOCK_BREAK, after = "plain" }
                                  misspelt { trigger-type = BLOCK_BREAK, modes = ["oneblok"] }
                                  named { trigger-type = CUSTOM_MOB_KILL, target-filter = "Boss" }
                                }
                                """))
                        .missions()
                        .stream()
                        .collect(Collectors.toMap(def -> def.id().value(), Function.identity()));

        assertThat(read).containsOnlyKeys("first", "second", "plain");
        assertThat(mission(read, "first").modes()).containsExactlyInAnyOrder(GameModeType.ONEBLOCK, GameModeType.BOXED);
        assertThat(mission(read, "first").after()).isEmpty();
        assertThat(mission(read, "second").modes()).containsExactly(GameModeType.ONEBLOCK);
        assertThat(mission(read, "second").after()).isEqualTo(Optional.of(MissionId.of("first")));
        assertThat(mission(read, "plain").modes()).describedAs("every mode").isEmpty();
        assertThat(mission(read, "plain").after())
                .describedAs("a mission does not follow itself")
                .isEmpty();
    }

    private static MissionDefinition mission(Map<String, MissionDefinition> read, String id) {
        return java.util.Objects.requireNonNull(read.get(id), id);
    }
}
