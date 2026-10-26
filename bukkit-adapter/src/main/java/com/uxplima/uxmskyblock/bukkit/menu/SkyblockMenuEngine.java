package com.uxplima.uxmskyblock.bukkit.menu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmlib.common.Log;
import com.uxplima.uxmlib.gui.GuiText;
import com.uxplima.uxmlib.gui.anvil.AnvilInput;
import com.uxplima.uxmlib.gui.input.TextInputInstaller;
import com.uxplima.uxmlib.gui.style.MenuSoundsFile;
import com.uxplima.uxmlib.menu.MenuBasics;
import com.uxplima.uxmlib.menu.Menus;
import com.uxplima.uxmlib.menu.binding.MenuBindings;
import com.uxplima.uxmlib.menu.binding.PlaceholderRegistry;
import com.uxplima.uxmlib.menu.render.ItemRenderer;
import com.uxplima.uxmlib.menu.render.MenuRenderer;
import com.uxplima.uxmlib.menu.runtime.MenuActionContext;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.runtime.MenuListener;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import com.uxplima.uxmlib.scheduler.PaperScheduler;
import com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockDetector;
import com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockScreen;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * The menu engine, reading this server's own menu files.
 *
 * <p>Three menu files shipped with the plugin from the beginning and nothing ever read one. An
 * operator who opened {@code menus/island-main.conf}, moved a slot and restarted saw no change,
 * because every slot, material, title and lore was decided in Java. That is the opposite of what the
 * files and the documentation both promise.
 *
 * <p>This holds the engine and the vocabulary of verbs a menu file may name. The file says what to
 * run; this says what each verb means. Nothing here decides a colour, a word or a layout.
 */
public final class SkyblockMenuEngine implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SkyblockMenuEngine.class.getName());

    private final MenuBindings bindings = new MenuBindings();
    private final Menus menus;
    private final MenuListener listener;

    /** The click, page and refusal sounds every window plays, read from config.conf under menu.sounds. */
    private final MenuSoundsFile sounds;

    private final Path menusDir;
    private final AnvilInput anvilInput;
    private final TextInputInstaller.Installed textInput;
    private final List<String> loaded = new ArrayList<>();

    /**
     * The live values each viewer's menus were last opened with.
     *
     * <p>A menu file says {@code open:island-bank} and the engine opens it on the click thread,
     * where reading a balance from the database is exactly what must not happen. The values were
     * gathered moments earlier, when the player opened the panel these menus hang off, so the
     * remembered set is what the sub menu shows. A balance a few seconds old on a page the player
     * navigated to is the right trade against a query under their cursor.
     */
    private final Map<UUID, Map<String, String>> lastValues = new ConcurrentHashMap<>();

    /** The lists each viewer's last code-gathered window handed over, by the id its file names. */
    private final Map<UUID, Map<String, List<?>>> lastLists = new ConcurrentHashMap<>();

    public SkyblockMenuEngine(Plugin plugin, Messages messages, Path dataDir) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(dataDir, "dataDir must not be null");

        this.menusDir = dataDir.resolve("menus");
        GuiText words = new CatalogueMenuWords(messages);
        // Asked each time a tile is drawn, so a reload that gives the styler a new theme repaints
        // the menus with the chat.
        com.uxplima.uxmlib.text.style.Styler styler = messages.styler();
        ItemRenderer itemRenderer = new ItemRenderer(words, styler::theme, bindings.placeholders());
        MenuRenderer renderer = new MenuRenderer(itemRenderer, bindings.conditions(), bindings.contents());
        PaperScheduler scheduler = new PaperScheduler(plugin);

        this.menus = Menus.builder(renderer, scheduler, bindings.lists())
                .openRegistries(bindings.actions(), bindings.conditions())
                .bedrock(
                        LateBedrockDetector.forServer(Bukkit.getServer()),
                        LateBedrockScreen.forServer(Bukkit.getServer()))
                .pagedLists(bindings.pagedLists())
                .build();
        // The text prompt behind an input: step. Without it a menu file can ask for a warp name and
        // the engine has nowhere to ask it, so the step cancels and the button does nothing. It is
        // also how a Bedrock player gets asked: the seam sends them a native Cumulus form instead of
        // an anvil, which is what "Bedrock is the floor" means for a menu that needs a word typed.
        AnvilInput anvil = new AnvilInput(plugin);
        anvil.install();
        this.anvilInput = anvil;
        this.textInput = TextInputInstaller.install(
                plugin,
                dataDir,
                anvil,
                words,
                scheduler,
                Log.of(LOGGER),
                LateBedrockDetector.forServer(Bukkit.getServer()),
                LateBedrockScreen.forServer(Bukkit.getServer()));

        this.sounds = MenuSoundsFile.read(dataDir.resolve("config.conf"), "menu.sounds");
        this.listener = MenuListener.builder(renderer, bindings.actions(), bindings.conditions(), scheduler, plugin)
                .pagedLists(bindings.pagedLists())
                .textPrompt(this.textInput.textInput()::promptResolved)
                .contents(bindings.contents())
                .feedback(sounds)
                .build();

        // close, open, command, message and sound mean the same in every plugin, so they come from
        // the library. Anything a skyblock menu can do that a generic menu cannot is registered by
        // the feature that owns it, through bindings().
        // Four of the five: close, command, message and sound. Not the library's open, because that
        // one carries no values and a sub menu opened without them shows blanks where a balance
        // should be. The registry refuses a second registration under the same name, which is right:
        // a verb that quietly changed meaning depending on wiring order would be worse.
        MenuBasics.register(bindings);
        answerArguments(bindings.placeholders());
        answerEntries(bindings.placeholders());
        // message:@key says the catalogue line in the reader's language. The library's message verb
        // draws its words as written, and a file is one language.
        bindings.action("message", ctx -> {
            String line = ctx.arg().strip();
            if (line.startsWith("@")) {
                messages.sendPlain(ctx.player(), line.substring(1));
            } else {
                ctx.player().sendMessage(messages.provider().renderTemplate(line));
            }
        });
        bindings.condition("at-least", SkyblockMenuEngine::atLeast);

        bindings.action("open", ctx -> {
            String target = ctx.arg().strip();
            menus.open(ctx.player(), target, null, 0, valuesFor(ctx.player().getUniqueId()));
        });
    }

    /**
     * Answers {@code argument_<name>} from the values the menu was opened with, for a catalogue line.
     *
     * <p>A menu file's own {@code %argument_<name>%} is filled by the engine. A catalogue line names
     * its values as {@code <argument_<name>>} and asks for them by name, and the engine answers a name
     * only through this registry, so without this every such line printed its tag.
     */
    static void answerArguments(PlaceholderRegistry placeholders) {
        placeholders.fallback(
                id -> id.startsWith(ARGUMENT),
                (id, ctx) -> ctx.arguments().getOrDefault(id.substring(ARGUMENT.length()), ""));
    }

    private static final String ARGUMENT = "argument_";

    /**
     * Answers {@code entry_<name>} from the {@link MenuRow} a list stamped the tile for. A list whose
     * rows are some other type registers its own {@code entry_} words, and an exact one is asked first.
     */
    static void answerEntries(PlaceholderRegistry placeholders) {
        placeholders.fallback(
                id -> id.startsWith(ENTRY),
                (id, ctx) -> MenuRow.of(ctx)
                        .map(row -> row.words().getOrDefault(id.substring(ENTRY.length()), ""))
                        .orElse(""));
    }

    private static final String ENTRY = "entry_";

    /**
     * {@code at-least:<value> <number>}: whether the value the menu was opened with is at least the
     * number, so a file can show a vault page an island has and a locked one in its place otherwise.
     *
     * <p>A value that is missing or not a number is not at least anything, and neither is a line that
     * does not name both halves: a locked tile shown by mistake costs a click, an open one costs a page.
     */
    static boolean atLeast(MenuContext ctx, Map<String, String> args) {
        java.util.Scanner words = new java.util.Scanner(args.getOrDefault("value", ""));
        String name = words.hasNext() ? words.next() : "";
        String number = words.hasNext() ? words.next() : "";
        if (name.isEmpty() || number.isEmpty() || words.hasNext()) {
            return false;
        }
        try {
            long have = Long.parseLong(ctx.arguments().getOrDefault(name, "").strip());
            return have >= Long.parseLong(number);
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** What the viewer's menus were last opened with, or nothing when they have opened none. */
    private Map<String, String> valuesFor(UUID viewer) {
        return lastValues.getOrDefault(viewer, Map.of());
    }

    /** Forgets a viewer's values, for a quit or a profile switch. */
    public void forget(UUID viewer) {
        lastValues.remove(Objects.requireNonNull(viewer, "viewer must not be null"));
        lastLists.remove(viewer);
    }

    /**
     * Registers a list a window gathers in code and hands over when it opens, under the id its file names
     * as {@code list { source = "<id>" }}.
     */
    public void handedList(String id) {
        Objects.requireNonNull(id, "id must not be null");
        bindings.list(
                id,
                ctx -> lastLists
                        .getOrDefault(ctx.viewer().getUniqueId(), Map.of())
                        .getOrDefault(id, List.of()));
    }

    /** Reads the menu sounds in config.conf again. */
    public void reloadSounds() {
        sounds.reload();
    }

    /**
     * Reads every {@code menus/*.conf} the operator has, registering each under its file name.
     *
     * <p>A file that will not parse is reported and skipped. One broken menu must not stop a server:
     * the operator gets the file name and the reason, and every other menu still opens.
     */
    public void loadSpecs() {
        if (!Files.isDirectory(menusDir)) {
            return;
        }
        MenuSpecLoader loader = new MenuSpecLoader();
        try (Stream<Path> files = Files.list(menusDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".conf")) {
                    continue;
                }
                String id = name.substring(0, name.length() - ".conf".length()).toLowerCase(Locale.ROOT);
                try {
                    MenuSpec spec = loader.load(file);
                    menus.registerSpec(id, spec);
                    loaded.add(id);
                } catch (RuntimeException e) {
                    LOGGER.log(
                            Level.WARNING,
                            e,
                            () -> "The menu file " + file + " could not be read, so the menu it "
                                    + "describes will not open. Every other menu still works.");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "The menus folder " + menusDir + " could not be listed.");
        }
    }

    /** Starts listening for clicks. Called once, after every feature has registered its verbs. */
    public void install() {
        listener.install();
    }

    /** Where a feature registers the verbs its own menus name. */
    public MenuBindings bindings() {
        return bindings;
    }

    public Menus menus() {
        return menus;
    }

    /** The ids of the menu files that loaded, which is what an operator sees in a diagnostic. */
    public List<String> loadedSpecs() {
        return List.copyOf(loaded);
    }

    /** Whether a menu of this id is registered, so a caller can fall back when the file is missing. */
    public boolean has(String specId) {
        return menus.registeredSpec(specId).isPresent();
    }

    /**
     * Opens a menu with live values bound as {@code %argument_<name>%} tokens.
     *
     * <p>The values are gathered by the caller, off the main thread where they come from a database,
     * and passed in. That is the seam between what a file says and what a server knows.
     */
    public boolean open(Player viewer, String specId, Map<String, String> values) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(specId, "specId must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (!has(specId)) {
            return false;
        }
        lastValues.put(viewer.getUniqueId(), Map.copyOf(values));
        menus.open(viewer, specId, null, 0, values);
        return true;
    }

    /**
     * Opens a menu with live values and the lists the caller gathered, each under the id a
     * {@link #handedList} registered. The rows were read off the player's thread, so drawing them reads
     * nothing.
     */
    public boolean open(Player viewer, String specId, Map<String, String> values, Map<String, List<?>> lists) {
        Objects.requireNonNull(lists, "lists must not be null");
        if (!has(specId)) {
            return false;
        }
        lastLists.put(viewer.getUniqueId(), Map.copyOf(lists));
        return open(viewer, specId, values);
    }

    /**
     * Opens a menu that carries {@code subject}: the record a window that holds items paints its
     * regions from and hands them back to. The record stays with the window, so a click and the close
     * read it without a lookup.
     */
    public boolean openHolding(Player viewer, String specId, Object subject, Map<String, String> values) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(specId, "specId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (!has(specId)) {
            return false;
        }
        lastValues.put(viewer.getUniqueId(), Map.copyOf(values));
        menus.open(viewer, specId, subject, 0, values);
        return true;
    }

    /**
     * Hands {@code viewer} fresh lists and draws {@code specId} again in place, when it is still the
     * window they have up. The other lists the viewer was handed stay, so a window opened since keeps
     * its own rows.
     */
    public void redraw(Player viewer, String specId, Map<String, List<?>> lists) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(specId, "specId must not be null");
        Objects.requireNonNull(lists, "lists must not be null");
        lastLists.merge(viewer.getUniqueId(), Map.copyOf(lists), (held, fresh) -> {
            Map<String, List<?>> both = new java.util.HashMap<>(held);
            both.putAll(fresh);
            return Map.copyOf(both);
        });
        menus.redraw(viewer, specId);
    }

    /** Whether {@code viewer} has the window of {@code specId} up. Call on the viewer's thread. */
    public boolean showing(Player viewer, String specId) {
        org.bukkit.inventory.Inventory top = viewer.getOpenInventory().getTopInventory();
        return top != null && menus.menuIdOf(top).filter(specId::equals).isPresent();
    }

    /** Registers one verb a skyblock menu file may name. */
    public void action(String id, java.util.function.Consumer<MenuActionContext> handler) {
        bindings.action(id, handler);
    }

    public Optional<MenuSpec> spec(String specId) {
        return menus.registeredSpec(specId);
    }

    @Override
    public void close() {
        lastValues.clear();
        lastLists.clear();
        textInput.uninstall().run();
        anvilInput.uninstall();
        listener.uninstall();
        menus.shutdown();
    }
}
