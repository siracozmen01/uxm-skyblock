package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.uxplima.uxmlib.menu.spec.MenuItemSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pane behind the buttons of every shipped window is the house filler: grey, no tooltip and no name. A pane
 * named {@code " "} still opens an empty box under the cursor, and one named {@code ""} makes the client print the
 * material's own English name. A pane that does something or says something is a button and keeps its own look.
 */
class ShippedFillerTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    @Test
    @DisplayName("every background pane in a shipped window is the house filler")
    void everyBackgroundPaneIsTheHouseFiller() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Path file : files()) {
            MenuSpec spec = new MenuSpecLoader().parse(Files.readString(file, StandardCharsets.UTF_8));
            for (Map.Entry<String, MenuItemSpec> entry : spec.items().entrySet()) {
                MenuItemSpec item = entry.getValue();
                if (isBackground(item) && !isHouseFiller(item)) {
                    wrong.add(MENUS.relativize(file) + " " + entry.getKey() + " (" + item.material() + ")");
                }
            }
        }

        assertThat(wrong)
                .describedAs(
                        "panes that are not GRAY_STAINED_GLASS_PANE with decor { hide-tooltip = true } and no name")
                .isEmpty();
    }

    private static boolean isBackground(MenuItemSpec item) {
        return item.material().toUpperCase(Locale.ROOT).endsWith("_STAINED_GLASS_PANE")
                && !item.click().hasAnyAction()
                && item.lore().isEmpty()
                && item.name().isBlank();
    }

    private static boolean isHouseFiller(MenuItemSpec item) {
        return "GRAY_STAINED_GLASS_PANE".equalsIgnoreCase(item.material())
                && item.name().isEmpty()
                && item.decor().meta().components().hideTooltip().equals(Optional.of(true));
    }

    private static List<Path> files() throws IOException {
        try (Stream<Path> walk = Files.walk(MENUS)) {
            return walk.filter(file -> file.toString().endsWith(".conf"))
                    .sorted()
                    .toList();
        }
    }
}
