package com.uxplima.uxmskyblock.bukkit.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every interaction the code fires is one the file names, and the other way round.
 *
 * <p>An interaction the code fires and the file never names is silent on a server with the shipped
 * file, and nothing says so: the operator has no line to find and no line to edit. An interaction
 * the file names and nothing fires is a line they can write all day for nothing.
 */
class EveryMilestoneFiresWhatWasWrittenTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path SHIPPED = Path.of("src/main/resources/modules/effects.conf");
    private static final Path LOOT = Path.of("src/main/resources/loot");

    /**
     * {@code fire(written, "kinetic-ward", ...)} and {@code fireMilestone("island-created", ...)}, within one statement:
     * a call that names its interaction by a variable ends before any quote, and is not read as the next one found.
     */
    private static final Pattern FIRED = Pattern.compile("fire(?:Milestone)?\\([^\";]*\"([a-z-]+)\"");

    /** A shipped loot table's rarity names the entry fired for whoever finds it: {@code effects = "loot-rare"}. */
    private static final Pattern NAMED_BY_LOOT = Pattern.compile("effects\\s*=\\s*\"([a-z-]+)\"");

    @Test
    @DisplayName("Every interaction the code fires is one the shipped file names")
    void everyfiredInteractionIsShipped() throws IOException {
        Set<String> shipped =
                EffectsConfiguration.defaultConfiguration().byInteraction().keySet();

        TreeSet<String> unnamed = new TreeSet<>(firedInteractions());
        unnamed.removeAll(shipped);

        assertThat(unnamed)
                .describedAs("the code fires these and the shipped file names none of them, so they "
                        + "are silent and the operator has no line to find")
                .isEmpty();
    }

    @Test
    @DisplayName("Every interaction the shipped file names is one something fires")
    void everyshippedInteractionIsFired() throws IOException {
        TreeSet<String> unfired = new TreeSet<>(
                EffectsConfiguration.defaultConfiguration().byInteraction().keySet());
        unfired.removeAll(firedInteractions());

        assertThat(unfired)
                .describedAs("the file names these and nothing fires them, so an operator can write "
                        + "a line there all day for nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("The shipped file and the code's fallback name the same interactions")
    void thefileAndTheDefaultAgree() throws IOException {
        String file = Files.readString(SHIPPED, StandardCharsets.UTF_8);

        for (String interaction :
                EffectsConfiguration.defaultConfiguration().byInteraction().keySet()) {
            assertThat(file)
                    .describedAs("%s is in the code's fallback and not in the file it ships", interaction)
                    .contains(interaction);
        }
    }

    @Test
    @DisplayName("The interactions this plugin fires today are the ones it says it fires")
    void thelistIsWhatItSaysItIs() throws IOException {
        assertThat(firedInteractions())
                .containsExactlyInAnyOrderElementsOf(List.of(
                        "kinetic-ward",
                        "obsidian-recovery",
                        "limit-refused",
                        "mission-completed",
                        "island-created",
                        "member-joined",
                        "upgrade-bought",
                        "oneblock-phase-began",
                        "oneblock-creature-coming",
                        "acid-sea-burns",
                        "acid-tide-falling",
                        "acid-tide-rising",
                        "acid-rain-burns",
                        "acid-water-drunk",
                        "acid-clean-water-drunk",
                        "acid-clean-water-filled",
                        "acid-vent-fumes",
                        "acid-item-dissolved",
                        "boxed-grew",
                        "boxed-moved",
                        "poseidon-dry-air",
                        "poseidon-sun-burns",
                        "poseidon-still-drowns",
                        "brix-plot-entered",
                        "brix-plot-left",
                        "parkour-course-entered",
                        "parkour-course-left",
                        "parkour-run-started",
                        "parkour-checkpoint",
                        "parkour-finished",
                        "parkour-record",
                        "loot-rare",
                        "loot-legendary",
                        "oneblock-phase-finished",
                        "oneblock-moment",
                        "oneblock-lucky-break",
                        "vessel-sailed",
                        "vessel-contract-taken",
                        "vessel-contract-delivered",
                        "vessel-contract-failed",
                        "vessel-storm",
                        "vessel-pirates-toll",
                        "vessel-pirates-boarded",
                        "vessel-find",
                        "vessel-find-lost",
                        "vessel-customs-seized",
                        "vessel-customs-cleared",
                        "vessel-plunder",
                        "vessel-plundered",
                        "upside-down-mirrored"));
    }

    private static Set<String> firedInteractions() throws IOException {
        TreeSet<String> fired = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = FIRED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    fired.add(matcher.group(1));
                }
            }
        }
        assertThat(fired).describedAs("the scan really found the calls").isNotEmpty();
        try (Stream<Path> files = Files.list(LOOT)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".conf")).toList()) {
                Matcher matcher = NAMED_BY_LOOT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    fired.add(matcher.group(1));
                }
            }
        }
        return fired;
    }
}
