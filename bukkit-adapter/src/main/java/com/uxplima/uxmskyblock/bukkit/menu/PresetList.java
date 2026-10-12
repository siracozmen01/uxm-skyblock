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

    /** The verb a kind's tile runs to look at that island before choosing it. */
    static final String PREVIEW = "skyblock:preset-preview";

    private final Messages messages;
    private final Supplier<List<StarterPreset>> presets;
    private final Supplier<PresetChoices> choices;
    private final UnaryOperator<String> typed;
    private volatile @Nullable SkyblockMenuEngine engine;
    private volatile com.uxplima.uxmskyblock.bukkit.preview.@Nullable PresetPreviews previews;
    private volatile com.uxplima.uxmskyblock.bukkit.bedrock.@Nullable BedrockFormService forms;
    private volatile com.uxplima.uxmskyblock.core.application.scheduler.@Nullable SchedulerPort scheduler;

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
                    com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService bedrock = forms;
                    if (bedrock != null && bedrock.isBedrock(player)) {
                        // A form button holds a title and a line: what the kind is waits on a form of its own.
                        askBedrock(bedrock, player, preset);
                        return;
                    }
                    create(player, preset);
                }));
        engine.action(
                PREVIEW,
                ctx -> MenuRow.handle(ctx.context(), StarterPreset.class)
                        .ifPresent(preset -> preview(ctx.player(), preset)));
    }

    /** Hands this window the looks before choosing, and the forms a Bedrock player is asked in. */
    public void usePreviews(
            com.uxplima.uxmskyblock.bukkit.preview.PresetPreviews previews,
            com.uxplima.uxmskyblock.bukkit.bedrock.@Nullable BedrockFormService forms,
            com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler) {
        this.previews = Objects.requireNonNull(previews, "previews must not be null");
        this.forms = forms;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /** Starts the kind for {@code player}, when it is open to them. */
    void create(Player player, StarterPreset preset) {
        if (!choices.get().allows(player, preset.id())) {
            player.sendMessage(messages.render(
                    player,
                    "create.preset_locked",
                    Placeholder.unparsed("preset", messages.words(player, preset.displayName()))));
            return;
        }
        player.closeInventory();
        player.performCommand(typed.apply("create " + preset.id()));
    }

    /** Shows {@code player} the start island of the kind, when looks are on. */
    void preview(Player player, StarterPreset preset) {
        com.uxplima.uxmskyblock.bukkit.preview.PresetPreviews shown = previews;
        if (shown == null) {
            return;
        }
        player.closeInventory();
        shown.show(player, preset);
    }

    /** What the kind is, then start it, look at it, or go back to the kinds. */
    private void askBedrock(
            com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService bedrock, Player player, StarterPreset preset) {
        List<com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.Choice> asked = new ArrayList<>();
        asked.add(new com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.Choice(
                label(player, "menu.create.form_create"), () -> onThread(player, () -> create(player, preset))));
        com.uxplima.uxmskyblock.bukkit.preview.PresetPreviews shown = previews;
        if (shown != null && shown.enabled()) {
            asked.add(new com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.Choice(
                    label(player, "menu.create.form_preview"), () -> onThread(player, () -> preview(player, preset))));
        }
        asked.add(new com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.Choice(
                label(player, "menu.create.form_back"), () -> onThread(player, () -> show(player))));
        bedrock.openChoiceForm(
                player,
                "menu.create.form_title",
                "menu.create.form_body",
                List.of(
                        Placeholder.unparsed("preset", messages.words(player, preset.displayName())),
                        Placeholder.unparsed("description", messages.words(player, preset.description())),
                        Placeholder.unparsed(
                                "access",
                                messages.words(
                                        player,
                                        choices.get().allows(player, preset.id())
                                                ? "@menu.create.access_open"
                                                : "@menu.create.access_locked"))),
                asked);
    }

    /** The kinds as a form of their own, each its name and whether it is open to the player. */
    private void showBedrock(com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService bedrock, Player player) {
        List<com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.Choice> kinds = new ArrayList<>();
        for (MenuRow row : rows(player)) {
            StarterPreset preset = (StarterPreset) Objects.requireNonNull(row.handle());
            kinds.add(new com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.Choice(
                    com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(messages.renderPlain(
                            player,
                            "menu.create.form_kind",
                            Placeholder.unparsed("preset", row.words().getOrDefault("name", "")),
                            Placeholder.unparsed("access", row.words().getOrDefault("access", "")))),
                    () -> onThread(player, () -> askBedrock(bedrock, player, preset))));
        }
        bedrock.openChoiceForm(
                player,
                "menu.create.title",
                "menu.create.form_kinds",
                List.of(Placeholder.unparsed("count", Integer.toString(kinds.size()))),
                kinds);
    }

    private String label(Player player, String key) {
        return com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(messages.renderPlain(player, key));
    }

    /** A form answers off the player's thread; what it does is done on it. */
    private void onThread(Player player, Runnable task) {
        com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort hop = scheduler;
        if (hop == null) {
            task.run();
            return;
        }
        hop.onEntity(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(player.getUniqueId()), task);
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
        com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService bedrock = forms;
        if (current != null && count > 1 && bedrock != null && bedrock.isBedrock(player)) {
            // A kind is tapped once to read what it is, and that form starts it or shows it.
            showBedrock(bedrock, player);
            return true;
        }
        return current != null && count > 1 && current.open(player, FILE, Map.of("count", Integer.toString(count)));
    }
}
