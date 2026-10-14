package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A mission or an upgrade is named in the reader's language, as a preset already was.
 *
 * <p>Missions and upgrades are named in their own files, and a file is one language: a Turkish
 * player read "Harvest 50 crops of wheat on your island" in the missions menu on a live server. A
 * name written as {@code @key} is read from the catalogue for each viewer, and every name shipped,
 * in the files and in the defaults behind them, is written that way.
 */
class EveryConfiguredNameIsSaidInTheReadersLanguageTest extends MockBukkitHarness {

    private static final Path MODULES = Path.of("src/main/resources/modules");
    private static final Pattern WRITTEN = Pattern.compile("(?m)^\\s*(display-name|description)\\s*=\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("Every mission and upgrade name the shipped files write is a key both catalogues answer")
    void theShippedFilesNameByKey() throws Exception {
        List<String> names = new ArrayList<>();
        for (String file : List.of("missions.conf", "upgrades.conf")) {
            Matcher found = WRITTEN.matcher(Files.readString(MODULES.resolve(file)));
            while (found.find()) {
                names.add(found.group(2));
            }
        }
        assertThat(names)
                .describedAs(
                        "twenty-six missions, the packs of three modes among them, and fourteen upgrades, seven of them limits")
                .hasSize(66);
        assertEveryNameIsAnsweredKey(names);
    }

    @Test
    @DisplayName("The defaults behind the files name by key as well")
    void theDefaultsNameByKey() {
        List<String> names = new ArrayList<>();
        for (MissionDefinition mission :
                MissionConfiguration.defaultConfiguration().missions()) {
            names.add(mission.displayName());
            names.add(mission.description());
        }
        for (UpgradeDefinition upgrade :
                UpgradesConfiguration.defaultConfiguration().definitions().values()) {
            names.add(upgrade.displayName());
        }
        assertEveryNameIsAnsweredKey(names);
    }

    @Test
    @DisplayName("A key is read in the viewer's language, and a plain name is shown as written")
    void wordsReadsForTheViewer() {
        Messages messages = Messages.bundled();
        PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));
        PlayerMock english = createPlayer("Reader");

        assertThat(messages.words(turkish, "@missions.catalog.farming_wheat_1.description"))
                .isEqualTo("Adanda 50 buğday hasat et");
        assertThat(messages.words(english, "@missions.catalog.farming_wheat_1.description"))
                .isEqualTo("Harvest 50 crops of wheat on your island");
        assertThat(messages.words(turkish, "My own mission")).isEqualTo("My own mission");
    }

    private static void assertEveryNameIsAnsweredKey(List<String> names) {
        MessageProvider provider = Messages.bundled().provider();
        List<String> wrong = new ArrayList<>();
        for (String name : names) {
            if (!name.startsWith("@")) {
                wrong.add("written in one language: " + name);
                continue;
            }
            String key = name.substring(1);
            for (String locale : List.of("en", "tr")) {
                if (!provider.getKeys(locale).contains(key)) {
                    wrong.add(locale + " has no " + key);
                }
            }
        }
        assertThat(wrong).isEmpty();
    }
}
