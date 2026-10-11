package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.render.ItemRenderer;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.text.style.Theme;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A menu says its words in the reader's language, including the lines that carry a live value.
 *
 * <p>Twenty six lines in the shipped menus wrote their words in the file because they carried a
 * value, and the file is one language: a Turkish player read "Pages unlocked" and "Current tier" in
 * English. A catalogue line can now spell the value itself, so a menu file names every word by key.
 */
class EveryMenuWordComesFromTheCatalogueTest extends MockBukkitHarness {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    @org.junit.jupiter.api.io.TempDir
    Path dataDir;

    /** A word of two letters or more, once tags and placeholder tokens are taken out. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}]{2,}");

    @Test
    @DisplayName("Every title, name, lore, message and prompt in a shipped menu is a catalogue key, blank or wordless")
    void everyWordIsAKey() throws IOException {
        List<String> written = new ArrayList<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".conf")).sorted().toList()) {
                ConfigurationNode root =
                        HoconConfigurationLoader.builder().path(file).build().load();
                check(file, "title", root.node("title"), written);
                check(file, "fill-item.name", root.node("fill-item", "name"), written);
                for (Map.Entry<Object, ? extends ConfigurationNode> item :
                        root.node("items").childrenMap().entrySet()) {
                    check(file, item.getKey() + ".name", item.getValue().node("name"), written);
                    for (ConfigurationNode line : item.getValue().node("lore").childrenList()) {
                        check(file, item.getKey() + ".lore", line, written);
                    }
                    for (ConfigurationNode gesture :
                            item.getValue().node("click").childrenMap().values()) {
                        for (ConfigurationNode verb : gesture.childrenList()) {
                            // A step written as a map, such as an input, shows its prompt to the player.
                            check(file, item.getKey() + ".prompt", verb.node("prompt"), written);
                            String line = verb.getString("");
                            if (line.startsWith("message:")) {
                                checkText(file, item.getKey() + ".click", line.substring("message:".length()), written);
                            }
                        }
                    }
                }
            }
        }
        assertThat(written)
                .describedAs("lines every language reads in the file's one language")
                .isEmpty();
    }

    @Test
    @DisplayName("A tile drawn by the menu engine fills the value it spells, in English and in Turkish")
    void aCatalogueLineFillsItsValue() {
        org.mockbukkit.mockbukkit.entity.PlayerMock english = createPlayer("Reader");
        org.mockbukkit.mockbukkit.entity.PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(java.util.Locale.forLanguageTag("tr"));
        // The engine the server builds, so a value it does not answer fails here rather than on a live server.
        SkyblockMenuEngine engine = new SkyblockMenuEngine(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        ItemRenderer renderer = new ItemRenderer(
                new CatalogueMenuWords(Messages.bundled()),
                Theme::defaults,
                engine.bindings().placeholders());
        Map<String, String> opened = Map.of("vault_pages", "3");

        assertThat(plain(renderer.title("tile:3 @menu.vault.page1 pages", MenuContext.of(english, null, 0, opened))))
                .contains("◆ Page 1", "Details", "Pages 3");
        assertThat(plain(renderer.title("tile:3 @menu.vault.page1 pages", MenuContext.of(turkish, null, 0, opened))))
                .contains("◆ Sayfa 1", "Ayrıntılar", "Sayfa 3");
    }

    @Test
    @DisplayName("A message verb naming a key says the catalogue line in the reader's language")
    void aMessageVerbSaysItsKey() {
        org.mockbukkit.mockbukkit.entity.PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(java.util.Locale.forLanguageTag("tr"));
        SkyblockMenuEngine engine = new SkyblockMenuEngine(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        java.util.function.Consumer<com.uxplima.uxmlib.menu.runtime.MenuActionContext> verb =
                engine.bindings().actions().get("message").orElseThrow();

        verb.accept(new com.uxplima.uxmlib.menu.runtime.MenuActionContext(
                MenuContext.of(turkish, null, 0, Map.of()),
                turkish,
                com.uxplima.uxmlib.menu.spec.ClickKind.LEFT,
                Map.of("value", "@menu.bank.shared_note")));

        assertThat(plain(java.util.Objects.requireNonNull(turkish.nextComponentMessage())))
                .isEqualTo("Ada bankası her üyenin ortak bankasıdır. Bir birime tıkla, koy ya da al.");
    }

    @Test
    @DisplayName("Only argument names are asked for, so a colour tag never runs a placeholder")
    void onlyArgumentsAreAsked() {
        Player viewer = createPlayer("Reader");
        AskedFor values = new AskedFor(Map.of("argument_crystals", "12"));

        new CatalogueMenuWords(Messages.bundled()).text(viewer, "menu.bank.island_pool.crystals.value", values);

        assertThat(values.asked).isNotEmpty().allMatch(name -> name.startsWith("argument_"));
    }

    private static void check(Path file, String where, ConfigurationNode node, List<String> written) {
        String value = node.getString();
        if (value != null) {
            checkText(file, where, value, written);
        }
    }

    private static void checkText(Path file, String where, String value, List<String> written) {
        if (value.isBlank() || value.startsWith("@")) {
            return;
        }
        // A tile names a colour, a block of the catalogue and the facts of that block, and no word of its own.
        if (SkyblockTiles.marks(value)) {
            String[] parts = value.trim().split("\\s+", -1);
            if (parts.length < 2 || !parts[1].startsWith("@")) {
                written.add(file.getFileName() + " " + where + ": " + value);
            }
            return;
        }
        String words = value.replaceAll("<[^>]*>", "").replaceAll("%[a-z0-9_]+%", "");
        if (WORD.matcher(words).find()) {
            written.add(file.getFileName() + " " + where + ": " + value);
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** The engine's own shape: holds nothing up front and answers any name it is asked for. */
    private static final class AskedFor extends java.util.AbstractMap<String, String> {
        private final Map<String, String> values;
        private final List<String> asked = new ArrayList<>();

        AskedFor(Map<String, String> values) {
            this.values = new HashMap<>(values);
        }

        @Override
        public @org.jspecify.annotations.Nullable String get(Object key) {
            asked.add(String.valueOf(key));
            return values.get(key);
        }

        @Override
        public java.util.Set<Entry<String, String>> entrySet() {
            return java.util.Set.of();
        }
    }
}
