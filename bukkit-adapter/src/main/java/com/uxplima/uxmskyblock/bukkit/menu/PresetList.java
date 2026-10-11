package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.PresetChoices;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of island a server offers as a window, one tile each: a click starts that island.
 *
 * <p>A server could offer nine kinds and a player saw none of them. {@code /is create} made the default island, and
 * the others were names a player had to read somewhere and type. The window is {@code menus/island-create.conf},
 * and a click runs {@code /is create <kind>} under the names this server gave it, so the command decides as it
 * always did.
 */
public final class PresetList {

    /** The menu file that draws the kinds, and the list it draws them from. */
    public static final String FILE = "island-create";

    static final String PRESETS = "skyblock:presets";

    /** The verb a kind's tile runs to start that island. */
    static final String CREATE = "skyblock:preset-create";

    private final Messages messages;
    private final Supplier<List<StarterPreset>> presets;
    private final Supplier<PresetChoices> choices;
    private final UnaryOperator<String> typed;
    private volatile @Nullable SkyblockMenuEngine engine;

    /**
     * @param presets the kinds this server can start, in the operator's order
     * @param typed an island command line under the operator's names, as {@code create classic}
     */
    public PresetList(
            Messages messages,
            Supplier<List<StarterPreset>> presets,
            Supplier<PresetChoices> choices,
            UnaryOperator<String> typed) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.presets = Objects.requireNonNull(presets, "presets must not be null");
        this.choices = Objects.requireNonNull(choices, "choices must not be null");
        this.typed = Objects.requireNonNull(typed, "typed must not be null");
    }

    /** Hands this window the engine that reads its file, and teaches the engine what a click on a kind does. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.bindings().list(PRESETS, ctx -> rows(ctx.viewer()));
        engine.action(
                CREATE,
                ctx -> MenuRow.handle(ctx.context(), StarterPreset.class).ifPresent(preset -> {
                    Player player = ctx.player();
                    if (!choices.get().allows(player, preset.id())) {
                        player.sendMessage(messages.render(
                                player,
                                "create.preset_locked",
                                Placeholder.unparsed("preset", messages.words(player, preset.displayName()))));
                        return;
                    }
                    player.closeInventory();
                    player.performCommand(typed.apply("create " + preset.id()));
                }));
    }

    /** One row per kind: its name, what it is, the item it wears, and whether this player may start it. */
    List<MenuRow> rows(Player viewer) {
        PresetChoices current = choices.get();
        List<MenuRow> rows = new ArrayList<>();
        for (StarterPreset preset : current.ordered(presets.get())) {
            boolean open = current.allows(viewer, preset.id());
            rows.add(new MenuRow(
                    Map.of(
                            "id", preset.id(),
                            "name", messages.words(viewer, preset.displayName()),
                            "description", messages.words(viewer, preset.description()),
                            "icon", current.lookOf(preset.id()).icon(),
                            "access",
                                    messages.words(
                                            viewer, open ? "@menu.create.access_open" : "@menu.create.access_locked")),
                    preset));
        }
        return List.copyOf(rows);
    }

    /**
     * Shows the kinds to a player, on the thread that owns them, and answers whether a window opened. It answers
     * false when the operator removed the file, so the command makes the default island instead. It answers false
     * as well when the server offers one kind: a window with one tile is a click for nothing, and a OneBlock server
     * makes its island at once.
     */
    public boolean show(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        SkyblockMenuEngine current = this.engine;
        int count = presets.get().size();
        return current != null && count > 1 && current.open(player, FILE, Map.of("count", Integer.toString(count)));
    }
}
