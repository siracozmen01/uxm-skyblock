package com.uxplima.uxmskyblock.core.domain.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One grammar of requirement lines for every mode: what to have, what to hand over, and from whose purse. */
class OneRequirementGrammarTest {

    @Test
    @DisplayName("A line that says neither is taken when it can be handed over, and had when it cannot")
    void theDefaultMode() {
        assertThat(RequirementLines.parse("money 2500.5"))
                .contains(new Requirement.Money(250050, Requirement.Source.PLAYER, Requirement.Mode.TAKE));
        assertThat(RequirementLines.parse("currency Points 30 island"))
                .contains(new Requirement.Currency("points", 30, Requirement.Source.ISLAND, Requirement.Mode.TAKE));
        assertThat(RequirementLines.parse("item oak_log 96"))
                .contains(new Requirement.Items("minecraft:oak_log", 96, Requirement.Mode.TAKE));
        assertThat(RequirementLines.parse("points 2")).contains(new Requirement.Points(2, Requirement.Mode.TAKE));
        assertThat(RequirementLines.parse("level 10")).contains(new Requirement.Level(10));
        assertThat(RequirementLines.parse("level ladder")).contains(new Requirement.Level(Requirement.Level.LADDER));
        assertThat(RequirementLines.parse("permission island.box.grow"))
                .contains(new Requirement.Permission("island.box.grow"));
        assertThat(RequirementLines.parse("wait 1h30m")).contains(new Requirement.Wait(Duration.ofMinutes(90)));
    }

    @Test
    @DisplayName("A line says have or take, and from the player or the island's bank")
    void haveOrTake() {
        assertThat(RequirementLines.parse("have money 100 bank"))
                .contains(new Requirement.Money(10000, Requirement.Source.ISLAND, Requirement.Mode.HAVE));
        assertThat(RequirementLines.parse("TAKE item diamond 3"))
                .contains(new Requirement.Items("minecraft:diamond", 3, Requirement.Mode.TAKE));
        assertThat(RequirementLines.parse("have items minecraft:emerald 4"))
                .contains(new Requirement.Items("minecraft:emerald", 4, Requirement.Mode.HAVE));
        assertThat(RequirementLines.parse("have level 5")).contains(new Requirement.Level(5));
        assertThat(RequirementLines.parse("have wait 2d")).contains(new Requirement.Wait(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("A level, a permission and a wait are never taken, and a line that says nothing is no line")
    void whatIsNoLine() {
        assertThat(RequirementLines.parse("take level 5")).isEmpty();
        assertThat(RequirementLines.parse("take permission a.b")).isEmpty();
        assertThat(RequirementLines.parse("take wait 1h")).isEmpty();
        assertThat(RequirementLines.parse("money 50 somewhere")).isEmpty();
        assertThat(RequirementLines.parse("money 0")).isEmpty();
        assertThat(RequirementLines.parse("money 1 player extra")).isEmpty();
        assertThat(RequirementLines.parse("item stone")).isEmpty();
        assertThat(RequirementLines.parse("wait soon")).isEmpty();
        assertThat(RequirementLines.parse("have")).isEmpty();
        assertThat(RequirementLines.parse("")).isEmpty();
        assertThat(RequirementLines.parse("dance 3")).isEmpty();
    }

    @Test
    @DisplayName("A time is days, hours, minutes and seconds written together, and nothing else")
    void durations() {
        assertThat(RequirementLines.duration("1d2h3m4s"))
                .contains(Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4));
        assertThat(RequirementLines.duration("45s")).contains(Duration.ofSeconds(45));
        assertThat(RequirementLines.duration("1h 30m")).isEmpty();
        assertThat(RequirementLines.duration("1h30"))
                .describedAs("a number with no unit at the end")
                .isEmpty();
        assertThat(RequirementLines.duration("2mx")).isEmpty();
        assertThat(RequirementLines.duration("h")).isEmpty();
        assertThat(RequirementLines.duration("")).isEmpty();
    }
}
