package com.uxplima.uxmskyblock.bukkit.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockDetector;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmlib.bedrock.BedrockWidget;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise service presenting native Bedrock Forms (SimpleForm, ModalForm, CustomForm)
 * to Bedrock/Floodgate players without disruption to Java players (Section 2.16).
 */
public final class BedrockFormService {

    private final BedrockDetector detector;
    private final BedrockScreen screen;
    private final Messages messages;

    public BedrockFormService(BedrockDetector detector, BedrockScreen screen, Messages messages) {
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.screen = Objects.requireNonNull(screen, "screen must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public Messages messages() {
        return messages;
    }

    /**
     * A Floodgate form takes a string rather than a component, so a catalogue line is drawn for this
     * viewer and then flattened. The catalogue is the only source: a fallback written here
     * would be a second copy of the words, in one language, that no translator can reach.
     */
    private String text(Player player, String key, TagResolver... resolvers) {
        return FormText.of(messages.renderPlain(player, key, resolvers));
    }

    /** One button of a generic form: what it says, and what it does when it is pressed. */
    public record Choice(String label, Runnable action) {
        public Choice {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(action, "action must not be null");
        }
    }

    /**
     * The form every window that is a list of buttons can use, so a new window does not need a new
     * method here. The chest version of the window decides what the buttons are; this decides
     * nothing but how they are drawn.
     */
    public void openChoiceForm(Player player, String titleKey, String bodyKey, List<Choice> choices) {
        openChoiceForm(player, titleKey, bodyKey, List.of(), choices);
    }

    /** The same form, its body read with {@code said} for the placeholders it writes. */
    public void openChoiceForm(
            Player player, String titleKey, String bodyKey, List<TagResolver> said, List<Choice> choices) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(choices, "choices must not be null");
        Objects.requireNonNull(said, "said must not be null");
        List<BedrockButton> buttons = choices.stream()
                .map(choice -> new BedrockButton(choice.label(), null))
                .toList();
        screen.sendSimpleForm(
                player,
                text(player, titleKey),
                text(player, bodyKey, said.toArray(TagResolver[]::new)),
                buttons,
                index -> {
                    if (index >= 0 && index < choices.size()) {
                        choices.get(index).action().run();
                    }
                });
    }

    public boolean isBedrock(Player player) {
        return player != null && detector.isBedrock(player.getUniqueId());
    }

    public boolean isBedrock(UUID uuid) {
        return uuid != null && detector.isBedrock(uuid);
    }

    /**
     * Sends a SimpleForm for Island Control to a Bedrock player.
     */
    public void openIslandControlForm(
            Player player,
            Island island,
            @Nullable Runnable onHome,
            @Nullable Runnable onWarps,
            @Nullable Runnable onBank,
            @Nullable Runnable onMembers,
            @Nullable Runnable onSettings) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(island, "island must not be null");
        List<BedrockButton> buttons = List.of(
                new BedrockButton(text(player, "bedrock.button_home"), null),
                new BedrockButton(text(player, "bedrock.button_warps"), null),
                new BedrockButton(text(player, "bedrock.button_bank"), null),
                new BedrockButton(text(player, "bedrock.button_members"), null),
                new BedrockButton(text(player, "bedrock.button_settings"), null));

        screen.sendSimpleForm(
                player,
                text(player, "bedrock.control_title"),
                text(player, "bedrock.control_content"),
                buttons,
                index -> {
                    switch (index) {
                        case 0 -> {
                            if (onHome != null) {
                                onHome.run();
                            }
                        }
                        case 1 -> {
                            if (onWarps != null) {
                                onWarps.run();
                            }
                        }
                        case 2 -> {
                            if (onBank != null) {
                                onBank.run();
                            }
                        }
                        case 3 -> {
                            if (onMembers != null) {
                                onMembers.run();
                            }
                        }
                        case 4 -> {
                            if (onSettings != null) {
                                onSettings.run();
                            }
                        }
                        default -> {
                            // unknown index
                        }
                    }
                });
    }

    /**
     * Sends a SimpleForm displaying available Island Warps.
     */
    public void openWarpDirectoryForm(Player player, List<IslandWarp> warps, @Nullable Consumer<IslandWarp> onSelect) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(warps, "warps must not be null");
        List<BedrockButton> buttons = new ArrayList<>();
        for (IslandWarp warp : warps) {
            buttons.add(new BedrockButton(
                    text(
                            player,
                            "bedrock.warp_button",
                            Placeholder.unparsed("name", warp.name().value()),
                            Placeholder.unparsed(
                                    "category",
                                    messages.named(
                                            player,
                                            "warp.categories",
                                            warp.category().name(),
                                            warp.category().name()))),
                    null));
        }

        screen.sendSimpleForm(
                player, text(player, "bedrock.warps_title"), text(player, "bedrock.warps_content"), buttons, index -> {
                    if (index >= 0 && index < warps.size() && onSelect != null) {
                        onSelect.accept(warps.get(index));
                    }
                });
    }

    /**
     * Sends a ModalForm for two-button confirmation (e.g. Island Reset/Deletion).
     */
    public void openConfirmationModal(
            Player player,
            String title,
            String content,
            @Nullable String confirmButton,
            @Nullable String cancelButton,
            @Nullable Runnable onConfirm,
            @Nullable Runnable onCancel) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(title, "title must not be null");
        String defaultConfirm = text(player, "bedrock.modal_confirm");
        String defaultCancel = text(player, "bedrock.modal_cancel");
        screen.sendModalForm(
                player,
                title,
                content,
                confirmButton != null ? confirmButton : defaultConfirm,
                cancelButton != null ? cancelButton : defaultCancel,
                onConfirm != null ? onConfirm : () -> {},
                onCancel != null ? onCancel : () -> {});
    }

    /**
     * Sends a CustomForm for Island Settings with toggles.
     */
    public void openIslandSettingsForm(
            Player player,
            IslandFlags currentFlags,
            @Nullable Consumer<IslandFlags> onSave,
            @Nullable Runnable onClose) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(currentFlags, "currentFlags must not be null");
        List<BedrockWidget> widgets = List.of(
                new BedrockWidget.Label(text(player, "bedrock.settings_label")),
                new BedrockWidget.Toggle(
                        "PVP", text(player, "bedrock.flag_pvp"), currentFlags.isEnabled(IslandFlags.PVP)),
                new BedrockWidget.Toggle(
                        "FIRE_SPREAD",
                        text(player, "bedrock.flag_fire_spread"),
                        currentFlags.isEnabled(IslandFlags.FIRE_SPREAD)),
                new BedrockWidget.Toggle(
                        "MONSTER_SPAWN",
                        text(player, "bedrock.flag_monster_spawn"),
                        currentFlags.isEnabled(IslandFlags.MONSTER_SPAWN)),
                new BedrockWidget.Toggle(
                        "ANIMAL_SPAWN",
                        text(player, "bedrock.flag_animal_spawn"),
                        currentFlags.isEnabled(IslandFlags.ANIMAL_SPAWN)),
                new BedrockWidget.Toggle(
                        "VISITOR_ACCESS",
                        text(player, "bedrock.flag_visitor_access"),
                        currentFlags.isEnabled(IslandFlags.VISITOR_ACCESS)),
                new BedrockWidget.Toggle(
                        "EXPLOSION_DAMAGE",
                        text(player, "bedrock.flag_explosion_damage"),
                        currentFlags.isEnabled(IslandFlags.EXPLOSION_DAMAGE)));

        screen.sendCustomForm(
                player,
                text(player, "bedrock.settings_title"),
                null,
                widgets,
                submittedMap -> {
                    if (onSave != null) {
                        IslandFlags updated = currentFlags;
                        for (Map.Entry<String, String> entry : submittedMap.entrySet()) {
                            updated = updated.withFlag(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
                        }
                        onSave.accept(updated);
                    }
                },
                onClose != null ? onClose : () -> {});
    }

    public BedrockDetector detector() {
        return detector;
    }

    public BedrockScreen screen() {
        return screen;
    }
}
