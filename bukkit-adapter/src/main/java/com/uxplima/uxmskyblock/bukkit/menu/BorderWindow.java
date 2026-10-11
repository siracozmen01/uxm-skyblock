package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.boundary.BorderChoice;
import com.uxplima.uxmskyblock.bukkit.boundary.BorderColour;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * The island border as a window: whether a player sees it, and the colour it is drawn in.
 *
 * <p>{@code /is border} switched the border in chat and offered nothing else. It opens {@code menus/island-border.conf}
 * now, where one tile shows or hides the border and one tile per colour draws it in that colour. Both are kept on the
 * player, so they hold on every island and past a rejoin.
 */
public final class BorderWindow {

    public static final String FILE = "island-border";

    /** The verb that shows the border to a player who hid it, and hides it from one who sees it. */
    static final String TOGGLE = "skyblock:border-toggle";

    /** The verb that draws the border in a colour, {@code skyblock:border-colour:green}. */
    static final String COLOUR = "skyblock:border-colour";

    private final Messages messages;
    private final Supplier<@Nullable IslandBoundaryService> boundaries;
    private volatile @Nullable SkyblockMenuEngine engine;

    public BorderWindow(Messages messages, Supplier<@Nullable IslandBoundaryService> boundaries) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries must not be null");
    }

    /** Hands this window the engine that reads its file, and teaches the engine what its buttons do. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.action(TOGGLE, ctx -> {
            toggle(ctx.player());
            reopen(ctx.player());
        });
        engine.action(
                COLOUR,
                ctx -> BorderColour.of(ctx.arg()).ifPresent(colour -> {
                    paint(ctx.player(), colour);
                    reopen(ctx.player());
                }));
    }

    /**
     * Opens the window on the player's thread, and answers whether it opened. It answers false when the operator
     * removed the file, so the command switches the border as it did before the window.
     */
    public boolean show(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        SkyblockMenuEngine current = this.engine;
        return current != null && current.open(player, FILE, valuesOf(player));
    }

    /** Draws the window again with what changed, for a player who still has it up. */
    private void reopen(Player player) {
        SkyblockMenuEngine current = this.engine;
        if (current != null && current.showing(player, FILE)) {
            current.open(player, FILE, valuesOf(player));
        }
    }

    /** Shows the border to a player who hid it and hides it from one who sees it, keeps it, and says which. */
    public void toggle(Player player) {
        IslandBoundaryService service = boundaries.get();
        if (service == null) {
            messages.send(player, "border.disabled");
            return;
        }
        boolean shown = service.toggleBorder(new PlayerUuid(player.getUniqueId()));
        BorderChoice.keep(player, shown);
        messages.send(player, shown ? "border.enabled" : "border.turned_off");
    }

    /** Draws the border in {@code colour} for this player from now on, and at once where they stand. */
    void paint(Player player, BorderColour colour) {
        BorderChoice.keep(player, colour);
        IslandBoundaryService service = boundaries.get();
        if (service != null) {
            service.redrawBorder(new PlayerUuid(player.getUniqueId()));
        }
        messages.send(
                player,
                "command.border_color_success",
                Placeholder.unparsed("color", messages.words(player, nameOf(colour))));
    }

    /**
     * What the window is drawn with: whether the border is shown, the colour it is drawn in, and, per colour, whether
     * it is the one in use.
     */
    Map<String, String> valuesOf(Player player) {
        Map<String, String> values = new HashMap<>();
        boolean hidden = BorderChoice.hidden(player);
        BorderColour current = BorderChoice.colour(player);
        values.put("border_state", messages.words(player, hidden ? "@menu.border.hidden" : "@menu.border.shown"));
        values.put("border_colour", messages.words(player, nameOf(current)));
        for (BorderColour colour : BorderColour.values()) {
            values.put(
                    "colour_" + colour.id(),
                    messages.words(player, colour == current ? "@menu.border.in_use" : "@menu.border.not_in_use"));
        }
        return Map.copyOf(values);
    }

    private static String nameOf(BorderColour colour) {
        return "@menu.border.colours." + colour.id();
    }
}
