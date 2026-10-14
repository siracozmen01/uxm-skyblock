package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class MissionConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        MissionConfiguration config = MissionConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.missions()).isNotEmpty();
        assertThat(config.missions()).anyMatch(m -> m.id().value().equals("farming_wheat_1"));
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                missions {
                    enabled = true
                    catalog {
                        "custom_mission_1" {
                            branch = "MINING"
                            display-name = "Custom Mining Quest"
                            description = "Break diamond blocks"
                            trigger-type = "BLOCK_BREAK"
                            target-filter = "DIAMOND_BLOCK"
                            required-amount = 10
                            rewards {
                                crystals = 50
                                currency-minor-units = 10000
                                island-exp = 500
                                commands = [
                                    "broadcast Great job {player}!"
                                ]
                            }
                        }
                    }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        MissionConfiguration config = MissionConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.missions()).hasSize(1);
        var mission = config.missions().getFirst();
        assertThat(mission.id().value()).isEqualTo("custom_mission_1");
        assertThat(mission.branch()).isEqualTo(MissionBranch.MINING);
        assertThat(mission.displayName()).isEqualTo("Custom Mining Quest");
        assertThat(mission.triggerType()).isEqualTo(MissionTriggerType.BLOCK_BREAK);
        assertThat(mission.targetFilter()).isEqualTo("DIAMOND_BLOCK");
        assertThat(mission.requiredAmount()).isEqualTo(10L);
        assertThat(mission.reward().crystals()).isEqualTo(50L);
        assertThat(mission.reward().currencyMinorUnits()).isEqualTo(10000L);
        assertThat(mission.reward().islandExp()).isEqualTo(500L);
        assertThat(mission.reward().commands()).containsExactly("broadcast Great job {player}!");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("The shipped file has daily and weekly missions beside the challenges, turning in the server's zone")
    void theShippedFileHasEveryKind() throws IOException {
        MissionConfiguration config = MissionConfiguration.load(HoconConfigurationLoader.builder()
                .path(java.nio.file.Path.of("src/main/resources/modules/missions.conf"))
                .build()
                .load());

        assertThat(config.missions())
                .extracting(com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition::repeat)
                .contains(
                        com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE,
                        com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY,
                        com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.WEEKLY);
        assertThat(config.resetZone()).isEqualTo(java.time.ZoneId.systemDefault());
        assertThat(MissionConfiguration.defaultConfiguration().missions())
                .extracting(com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition::repeat)
                .describedAs("a server whose file lost its catalogue still has every kind")
                .contains(
                        com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY,
                        com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.WEEKLY);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A mission names how often it comes back and the file names the zone; a wrong word is said and "
            + "read as once")
    void repeatAndZoneAreRead() throws IOException {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("""
                missions {
                    reset-zone = "Asia/Tokyo"
                    catalog {
                        a { trigger-type = "BLOCK_BREAK", repeat = "Daily" }
                        b { trigger-type = "BLOCK_BREAK", repeat = "monthly" }
                        c { trigger-type = "BLOCK_BREAK" }
                    }
                }
                """);
        MissionConfiguration config = MissionConfiguration.load(root);

        assertThat(config.resetZone()).isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));
        java.util.Map<String, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat> repeats =
                new java.util.HashMap<>();
        config.missions().forEach(mission -> repeats.put(mission.id().value(), mission.repeat()));
        assertThat(repeats)
                .containsEntry("a", com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY)
                .containsEntry("b", com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE)
                .containsEntry("c", com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE);
        assertThat(MissionConfiguration.zoneOf("Mars/Olympus")).isEqualTo(java.time.ZoneId.systemDefault());
        assertThat(MissionConfiguration.zoneOf("UTC")).isEqualTo(java.time.ZoneId.of("UTC"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A mission that writes lines counts LINES, as many as it has, and a line it cannot count is left out")
    void linesAreRead() throws IOException {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("""
                missions {
                    catalog {
                        miner {
                            trigger-type = "FISHING"
                            required-amount = 40
                            lines = [
                                { trigger-type = "block_break", target-filter = "STONE", required-amount = 64 }
                                { trigger-type = "ITEM_SUBMIT", required-amount = 3 }
                                { trigger-type = "MOB_KILL", target-filter = "ZOMBIE" }
                            ]
                        }
                        plain { trigger-type = "FISHING", required-amount = 4 }
                    }
                }
                """);
        MissionConfiguration config = MissionConfiguration.load(root);
        com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition miner = config.missions().stream()
                .filter(m -> m.id().value().equals("miner"))
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(miner.triggerType())
                .isEqualTo(com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.LINES);
        org.assertj.core.api.Assertions.assertThat(miner.requiredAmount()).isEqualTo(2L);
        org.assertj.core.api.Assertions.assertThat(config.lines().get(miner.id()))
                .containsExactly(
                        new com.uxplima.uxmskyblock.core.domain.mission.MissionLine(
                                com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.BLOCK_BREAK,
                                "STONE",
                                64),
                        new com.uxplima.uxmskyblock.core.domain.mission.MissionLine(
                                com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.MOB_KILL, "ZOMBIE", 1));
        org.assertj.core.api.Assertions.assertThat(config.lines()).containsOnlyKeys(miner.id());
    }
}
