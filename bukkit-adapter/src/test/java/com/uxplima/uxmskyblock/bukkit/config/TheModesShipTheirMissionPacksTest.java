package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The shipped mission file carries a pack for OneBlock, Boxed and Poseidon, each a chain of its own mode. */
class TheModesShipTheirMissionPacksTest {

    private static Map<String, MissionDefinition> shipped() throws Exception {
        return MissionConfiguration.load(HoconConfigurationLoader.builder()
                        .path(Path.of("src/main/resources/modules/missions.conf"))
                        .build()
                        .load())
                .missions()
                .stream()
                .collect(Collectors.toMap(def -> def.id().value(), Function.identity()));
    }

    private static List<MissionDefinition> of(Map<String, MissionDefinition> all, GameModeType mode) {
        return all.values().stream()
                .filter(def -> def.modes().equals(Set.of(mode)))
                .toList();
    }

    @Test
    @DisplayName("Each pack is its mode's alone, and every link of a chain follows a mission the file holds")
    void thePacks() throws Exception {
        Map<String, MissionDefinition> all = shipped();

        assertThat(of(all, GameModeType.ONEBLOCK)).hasSize(7);
        assertThat(of(all, GameModeType.BOXED)).hasSize(3);
        assertThat(of(all, GameModeType.POSEIDON)).hasSize(5);
        for (MissionDefinition def : all.values()) {
            def.after()
                    .ifPresent(first -> assertThat(all)
                            .describedAs("%s follows %s", def.id(), first)
                            .containsKey(first.value()));
        }
        assertThat(chain(all, "poseidon_story_5"))
                .describedAs("the story is one chain from its first chapter")
                .containsExactly(
                        "poseidon_story_5",
                        "poseidon_story_4",
                        "poseidon_story_3",
                        "poseidon_story_2",
                        "poseidon_story_1");
    }

    @Test
    @DisplayName("A phase a OneBlock mission names is one the shipped OneBlock file has")
    void everyPhaseIsShipped() throws Exception {
        Set<String> phases = OneBlockConfiguration.load(
                        HoconConfigurationLoader.builder()
                                .path(Path.of("src/main/resources/modules/oneblock.conf"))
                                .build()
                                .load(),
                        Path.of("src/main/resources/oneblock/phases"))
                .phases()
                .phases()
                .stream()
                .map(OneBlockPhase::key)
                .collect(Collectors.toSet());

        List<String> named = shipped().values().stream()
                .filter(def -> def.triggerType() == MissionTriggerType.ONEBLOCK_PHASE)
                .map(MissionDefinition::targetFilter)
                .toList();

        assertThat(named).hasSize(6);
        assertThat(phases).containsAll(named);
    }

    private static List<String> chain(Map<String, MissionDefinition> all, String last) {
        List<String> links = new java.util.ArrayList<>();
        MissionDefinition at = all.get(last);
        while (at != null) {
            links.add(at.id().value());
            at = at.after().map(MissionId::value).map(all::get).orElse(null);
        }
        return links;
    }
}
