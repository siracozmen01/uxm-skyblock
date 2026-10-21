package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.text.style.Styler;
import com.uxplima.uxmlib.text.style.Theme;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A catalogue line names a role, and {@code theme.conf} says which colour that is.
 *
 * <p>The catalogue was plain MiniMessage, so a line wrote {@code <red>} and the theme never reached the
 * chat. A customer who wanted a different server colour had two thousand lines to edit, and a translator
 * had colours to copy around words.
 */
class TheCatalogueIsPaintedFromTheThemeTest {

    private static final String SHIPPED = """
            prefix = "OLD "
            bank { paid = "<tag:'Bank'> <body>Paid <value>5<body>." }
            plain { line = "<body>No prefix of its own" }
            """;

    @TempDir
    Path dir;

    @Test
    @DisplayName("A role is drawn in the colour the theme gives it")
    void aRoleTakesTheThemesColour() throws IOException {
        MessageProvider provider = provider();
        provider.useStyler(new Styler(theme("roles { value = \"#123456\" }")));

        Component line = provider.getComponent("bank.paid", "en");

        assertThat(colourOf(line, "5")).isEqualTo(TextColor.fromHexString("#123456"));
    }

    @Test
    @DisplayName("A line with a category prefix of its own is not given the catalogue's prefix too")
    void aLineWithItsOwnPrefixKeepsOnlyThatOne() throws IOException {
        MessageProvider provider = provider();

        assertThat(plain(provider.getComponent("bank.paid", "en")))
                .startsWith("Bank ")
                .doesNotContain("OLD");
        assertThat(plain(provider.getComponent("plain.line", "en")))
                .describedAs("a line an operator wrote without one still reads under the catalogue's prefix")
                .startsWith("OLD ");
    }

    @Test
    @DisplayName("English is written in small capitals where the theme says so, and Turkish keeps its letters")
    void theLettersFollowTheLanguage() throws IOException {
        MessageProvider provider = provider();
        read(provider, "tr", "bank { paid = \"<tag:'Banka'> <body>Ödendi <value>5<body>.\" }\n");
        provider.useStyler(new Styler(theme("small-caps { en = true, tr = false }")));

        assertThat(plain(provider.getComponent("bank.paid", "en"))).isEqualTo("ʙᴀɴᴋ ▶ ᴘᴀɪᴅ 5.");
        assertThat(plain(provider.getComponent("bank.paid", "en_us")))
                .describedAs("a client that names its region still reads its language")
                .isEqualTo("ʙᴀɴᴋ ▶ ᴘᴀɪᴅ 5.");
        assertThat(plain(provider.getComponent("bank.paid", "tr"))).isEqualTo("Banka ▶ Ödendi 5.");
    }

    @Test
    @DisplayName("A reload that gives the styler a new theme repaints a line already drawn")
    void aReloadRepaintsTheLines() throws IOException {
        MessageProvider provider = provider();
        Styler styler = new Styler(theme("roles { value = \"#123456\" }"));
        provider.useStyler(styler);
        provider.getComponent("bank.paid", "en");

        styler.reload(theme("roles { value = \"#654321\" }"));

        assertThat(colourOf(provider.getComponent("bank.paid", "en"), "5"))
                .isEqualTo(TextColor.fromHexString("#654321"));
    }

    @Test
    @DisplayName("The shared theme is read first and the plugin's own file wins key by key")
    void thePluginsOwnFileWinsOverTheSharedOne() throws IOException {
        Path data = Files.createDirectories(dir.resolve("plugins").resolve("uxmSkyblock"));
        Path shared = Files.createDirectories(dir.resolve("plugins").resolve(ThemeSource.SHARED_FOLDER));
        Files.writeString(shared.resolve("theme.conf"), "roles { value = \"#111111\", good = \"#222222\" }\n");
        Files.writeString(data.resolve("theme.conf"), "roles { value = \"#333333\" }\n");

        Theme theme = ThemeSource.load(data);

        assertThat(theme.hex("value")).isEqualToIgnoringCase("#333333");
        assertThat(theme.hex("good")).isEqualToIgnoringCase("#222222");
    }

    @Test
    @DisplayName("A key neither file names keeps the value the plugin ships")
    void aShortFileKeepsTheShippedRest() throws IOException {
        Path data = Files.createDirectories(dir.resolve("plugins").resolve("uxmSkyblock"));
        Files.writeString(data.resolve("theme.conf"), "roles { value = \"#333333\" }\n");

        Theme theme = ThemeSource.load(data);

        assertThat(theme.hex("value")).isEqualToIgnoringCase("#333333");
        assertThat(theme.separator()).isEqualTo("▶");
        assertThat(theme.hex("good")).isEqualToIgnoringCase("#4ECCA3");
    }

    @Test
    @DisplayName("The shipped theme is written beside the plugins once, and never over a file that is there")
    void theSharedThemeIsWrittenOnce() throws IOException {
        Path data = Files.createDirectories(dir.resolve("plugins").resolve("uxmSkyblock"));
        Path shared = dir.resolve("plugins").resolve(ThemeSource.SHARED_FOLDER).resolve("theme.conf");

        ThemeSource.saveShared(data, getClass().getClassLoader());
        assertThat(Files.readString(shared)).contains("palette");

        Files.writeString(shared, "roles { value = \"#444444\" }\n");
        ThemeSource.saveShared(data, getClass().getClassLoader());
        assertThat(Files.readString(shared)).isEqualTo("roles { value = \"#444444\" }\n");
    }

    @Test
    @DisplayName("A language a client makes up does not grow what the catalogue keeps")
    void aMadeUpLanguageKeepsNothingNew() throws IOException {
        MessageProvider provider = provider();
        // One language in small capitals and one in its own letters, named here rather than taken from the
        // shipped theme, which writes Turkish in small capitals too.
        provider.useStyler(new Styler(theme("small-caps { en = true, tr = false }")));
        provider.getComponent("bank.paid", "en");
        provider.getComponent("bank.paid", "tr");
        int kept = provider.styledLines();

        for (int made = 0; made < 500; made++) {
            provider.getComponent("bank.paid", "x" + made);
        }

        assertThat(provider.styledLines()).isEqualTo(kept);
    }

    private MessageProvider provider() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        read(provider, "en", SHIPPED);
        return provider;
    }

    private static void read(MessageProvider provider, String locale, String hocon) throws IOException {
        provider.loadFromStream(locale, new ByteArrayInputStream(hocon.getBytes(StandardCharsets.UTF_8)));
    }

    private Theme theme(String hocon) throws IOException {
        // The lines given here over the theme the plugin ships, the way an operator's file sits on it.
        Path file = Files.writeString(Files.createTempFile(dir, "theme", ".conf"), hocon);
        var node = HoconConfigurationLoader.builder().path(file).build().load();
        try (var shipped = getClass().getClassLoader().getResourceAsStream("theme.conf")) {
            String text = new String(java.util.Objects.requireNonNull(shipped).readAllBytes(), StandardCharsets.UTF_8);
            Path base = Files.writeString(Files.createTempFile(dir, "shipped", ".conf"), text);
            node.mergeFrom(HoconConfigurationLoader.builder().path(base).build().load());
        }
        return Theme.from(node);
    }

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    /** The colour the piece of {@code line} that reads {@code text} is drawn in. */
    private static TextColor colourOf(Component line, String text) {
        TextColor found = find(line, text, null);
        assertThat(found).describedAs("a colour on " + text).isNotNull();
        return java.util.Objects.requireNonNull(found);
    }

    private static @Nullable TextColor find(Component node, String text, @Nullable TextColor inherited) {
        @Nullable TextColor here = node.color() != null ? node.color() : inherited;
        if (node instanceof TextComponent piece && piece.content().contains(text)) {
            return here;
        }
        for (Component child : node.children()) {
            @Nullable TextColor found = find(child, text, here);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
