package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.boundary.BorderChoice;
import com.uxplima.uxmskyblock.bukkit.boundary.BorderColour;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration;
import com.uxplima.uxmskyblock.bukkit.wall.WallStyle;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
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

    /** The list of the ways an island's edge can be drawn, one tile each. */
    static final String EDGES = "skyblock:edge-styles";

    /** The verb a way's tile runs: the island's edge is drawn that way from now on. */
    static final String EDGE = "skyblock:edge-choose";

    /** The island a player belongs to, and whether their role may choose how its edge is drawn. */
    public record Own(IslandId island, boolean mayChoose) {
        public Own {
            Objects.requireNonNull(island, "island must not be null");
        }
    }

    private final Messages messages;
    private final Supplier<@Nullable IslandBoundaryService> boundaries;
    private volatile @Nullable SkyblockMenuEngine engine;
    private volatile @Nullable IslandEdges edges;
    private volatile @Nullable SchedulerPort scheduler;
    private volatile Function<UUID, Optional<Own>> own = player -> Optional.empty();

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
        engine.handedList(EDGES);
        engine.action(
                EDGE, ctx -> MenuRow.handle(ctx.context(), String.class).ifPresent(way -> choose(ctx.player(), way)));
        engine.action(
                COLOUR,
                ctx -> BorderColour.of(ctx.arg()).ifPresent(colour -> {
                    paint(ctx.player(), colour);
                    reopen(ctx.player());
                }));
    }

    /**
     * Lets the window choose how the player's island edge is drawn.
     *
     * @param own the island a player belongs to and whether they may choose, read off the main thread
     */
    public void useEdges(IslandEdges edges, Function<UUID, Optional<Own>> own, SchedulerPort scheduler) {
        this.edges = Objects.requireNonNull(edges, "edges must not be null");
        this.own = Objects.requireNonNull(own, "own must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /**
     * Opens the window on the player's thread, and answers whether it opened. It answers false when the operator
     * removed the file, so the command switches the border as it did before the window. With the island's edge to
     * choose, the island is read off the main thread first, and the window opens a moment later.
     */
    public boolean show(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        SkyblockMenuEngine current = this.engine;
        IslandEdges known = this.edges;
        SchedulerPort tasks = this.scheduler;
        if (current == null) {
            return false;
        }
        if (known == null || tasks == null) {
            return current.open(player, FILE, valuesOf(player), Map.of(EDGES, List.of()));
        }
        if (!current.has(FILE)) {
            return false;
        }
        tasks.async(() -> {
            List<MenuRow> ways = edgeRows(player, known, own.apply(player.getUniqueId()));
            tasks.onEntity(
                    new PlayerUuid(player.getUniqueId()),
                    () -> current.open(player, FILE, valuesOf(player), Map.of(EDGES, ways)));
        });
        return true;
    }

    /** Draws the window again with what changed, for a player who still has it up. */
    private void reopen(Player player) {
        SkyblockMenuEngine current = this.engine;
        if (current != null && current.showing(player, FILE)) {
            show(player);
        }
    }

    /**
     * One tile for each way the island's edge can be drawn, spelled {@code <entry_<name>>}: its {@code name}, its
     * {@code state}, in use or not, and the {@code material} it wears. None for a player with no island, or one whose
     * island edges its own ground.
     */
    List<MenuRow> edgeRows(Player player, IslandEdges known, Optional<Own> mine) {
        if (mine.isEmpty() || !known.choosable(mine.get().island())) {
            return List.of();
        }
        String now = known.styleOf(mine.get().island());
        List<MenuRow> rows = new ArrayList<>();
        for (String way : known.choices()) {
            rows.add(new MenuRow(
                    Map.of(
                            "name", nameOf(player, way),
                            "state",
                                    messages.words(
                                            player,
                                            way.equals(now) ? "@menu.border.in_use" : "@menu.border.not_in_use"),
                            "material", materialOf(known, way)),
                    way));
        }
        return List.copyOf(rows);
    }

    /** Draws the island's edge {@code way} from now on, if the player's role may choose, and says how it went. */
    void choose(Player player, String way) {
        IslandEdges known = this.edges;
        SchedulerPort tasks = this.scheduler;
        if (known == null || tasks == null) {
            return;
        }
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        tasks.async(() -> {
            Optional<Own> mine = own.apply(player.getUniqueId());
            String said;
            if (mine.isEmpty()) {
                said = "error.no_island";
            } else if (!known.choosable(mine.get().island())) {
                said = "border.edge_own";
            } else if (!mine.get().mayChoose()) {
                said = "border.edge_no_right";
            } else {
                said = known.choose(mine.get().island(), way) ? "border.edge_chosen" : "border.edge_unknown";
            }
            tasks.onEntity(uuid, () -> {
                messages.send(player, said, Placeholder.unparsed("edge", nameOf(player, way)));
                reopen(player);
            });
        });
    }

    /** A way's name in the player's language: the catalogue's when it has one, its id when it does not. */
    String nameOf(Player player, String way) {
        return messages.named(player, "menu.border.edges", way, way);
    }

    /** The item a way's tile wears: the block of a block wall, glass for a colour wall, a barrier for nothing. */
    static String materialOf(IslandEdges known, String way) {
        if (WallConfiguration.BORDER.equals(way)) {
            return "LIGHT_BLUE_STAINED_GLASS";
        }
        if (WallConfiguration.NONE.equals(way)) {
            return "BARRIER";
        }
        return known.wallStyle(way)
                .filter(style -> style.kind() == WallStyle.Kind.BLOCK)
                .map(style -> {
                    org.bukkit.Material block = org.bukkit.Material.matchMaterial(style.block());
                    return block != null && block.isItem() ? block.name() : "LIGHT_GRAY_STAINED_GLASS";
                })
                .orElse("LIGHT_GRAY_STAINED_GLASS");
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
