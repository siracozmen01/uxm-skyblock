package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The contest block of modules/brix.conf: what is written is read, and what cannot be is the shipped one. */
class TheContestsAreReadFromTheFileTest {

    private static BrixConfiguration.Contests written(String hocon) throws Exception {
        return BrixConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon))
                .contests();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped contests, and a file that says nothing does too")
    void theShippedFile() throws Exception {
        String shipped;
        try (InputStream in = BrixConfiguration.class.getResourceAsStream("/modules/brix.conf")) {
            assertThat(in).isNotNull();
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(written(shipped)).isEqualTo(BrixConfiguration.Contests.SHIPPED);
        assertThat(written("")).isEqualTo(BrixConfiguration.Contests.SHIPPED);
        assertThat(BrixConfiguration.Contests.SHIPPED.places()).hasSize(3);
        assertThat(BrixConfiguration.Contests.SHIPPED.mostStars()).isEqualTo(5);
    }

    @Test
    @DisplayName("Themes, windows, stars and every list are read as written, places in their order")
    void whatIsWritten() throws Exception {
        BrixConfiguration.Contests read = written("""
                contest {
                    themes = ["harbour", " ", "A tall tower"]
                    build = "2h"
                    vote = "30m"
                    most-stars = 10
                    rewards {
                        entered = ["[message] thanks"]
                        places = [["[console] say first", "[message] first"], [], ["[message] third"]]
                    }
                }
                """);
        assertThat(read)
                .isEqualTo(new BrixConfiguration.Contests(
                        List.of("harbour", "A tall tower"),
                        Duration.ofHours(2),
                        Duration.ofMinutes(30),
                        10,
                        List.of("[message] thanks"),
                        List.of(
                                List.of("[console] say first", "[message] first"),
                                List.of(),
                                List.of("[message] third"))));
        assertThat(written("contest { rewards { places = [] } }").places())
                .describedAs("no place paid")
                .isEmpty();
    }

    @Test
    @DisplayName("Stars past 1 to 10, or a window of no time, give the shipped contests")
    void whatCannotBe() throws Exception {
        for (String wrong : List.of(
                "contest { most-stars = 0 }",
                "contest { most-stars = 11 }",
                "contest { build = \"0s\" }",
                "contest { vote = \"0s\" }")) {
            assertThat(written(wrong)).describedAs(wrong).isEqualTo(BrixConfiguration.Contests.SHIPPED);
        }
        assertThat(written("contest { most-stars = 1 }").mostStars()).isEqualTo(1);
        assertThat(written("contest { most-stars = 10 }").mostStars()).isEqualTo(10);
    }
}
