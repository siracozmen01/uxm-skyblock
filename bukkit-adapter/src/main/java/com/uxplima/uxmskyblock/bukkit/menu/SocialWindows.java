package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import org.jspecify.annotations.Nullable;

/**
 * The guestbook of the island a player stands on, their bookmarks and their island's alliances,
 * each a window of one tile per line.
 *
 * <p>Each was a header and a line per entry in chat. A guestbook entry was moderated by typing an id the chat line
 * printed, a bookmarked island was visited by typing whose it was, and an alliance offer was answered by typing the
 * other owner's name. The windows are files under {@code menus/}, and every click runs the island command it stands
 * for under the names this server gave it, so the window and the command refuse the same things.
 */
public final class SocialWindows {

    /** The menu files, one for each list. */
    public static final String GUESTBOOK_FILE = "island-guestbook";

    public static final String BOOKMARKS_FILE = "island-bookmarks";
    public static final String ALLIANCES_FILE = "island-alliances";

    /** The lists those files draw from. */
    static final String GUESTBOOK = "skyblock:guestbook";

    static final String BOOKMARKS = "skyblock:bookmarks";
    static final String ALLIES = "skyblock:allies";
    static final String OFFERS = "skyblock:alliance-offers";

    /** The verbs a tile runs. */
    static final String PIN = "skyblock:guestbook-pin";

    static final String HIDE = "skyblock:guestbook-hide";
    static final String DELETE = "skyblock:guestbook-delete";
    static final String VISIT = "skyblock:island-visit";
    static final String BREAK = "skyblock:alliance-break";
    static final String ACCEPT = "skyblock:alliance-accept";
    static final String DECLINE = "skyblock:alliance-decline";

    /** One entry of a guestbook, by the id its moderation commands take. */
    public record Entry(String id, String message, boolean pinned, boolean hidden) {
        public Entry {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    /** An island as the reader knows it, and whose it is, which is what the commands about it take. */
    public record Place(String name, @Nullable String owner) {
        public Place {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    private final Messages messages;
    private final UnaryOperator<String> typed;
    private volatile @Nullable SkyblockMenuEngine engine;

    /** @param typed an island command line under the operator's names, as {@code visit Ada} */
    public SocialWindows(Messages messages, UnaryOperator<String> typed) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.typed = Objects.requireNonNull(typed, "typed must not be null");
    }

    /** Hands these windows the engine that reads their files, and teaches it what a click on a tile does. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        for (String list : List.of(GUESTBOOK, BOOKMARKS, ALLIES, OFFERS)) {
            engine.handedList(list);
        }
        entryVerb(engine, PIN, entry -> "guestbook " + (entry.pinned() ? "unpin " : "pin ") + entry.id());
        entryVerb(engine, HIDE, entry -> "guestbook " + (entry.hidden() ? "show " : "hide ") + entry.id());
        entryVerb(engine, DELETE, entry -> "guestbook delete " + entry.id());
        placeVerb(engine, VISIT, owner -> "visit " + owner);
        placeVerb(engine, BREAK, owner -> "alliance break " + owner);
        placeVerb(engine, ACCEPT, owner -> "alliance accept " + owner);
        placeVerb(engine, DECLINE, owner -> "alliance decline " + owner);
    }

    private void entryVerb(SkyblockMenuEngine target, String verb, java.util.function.Function<Entry, String> line) {
        target.action(
                verb,
                ctx -> MenuRow.handle(ctx.context(), Entry.class).ifPresent(entry -> {
                    ctx.player().closeInventory();
                    ctx.player().performCommand(typed.apply(line.apply(entry)));
                }));
    }

    private void placeVerb(SkyblockMenuEngine target, String verb, UnaryOperator<String> line) {
        target.action(
                verb,
                ctx -> MenuRow.handle(ctx.context(), Place.class)
                        .map(Place::owner)
                        .ifPresent(owner -> {
                            ctx.player().closeInventory();
                            ctx.player().performCommand(typed.apply(line.apply(owner)));
                        }));
    }

    /** One row per entry: what it says, and whether it is pinned, hidden or neither. */
    List<MenuRow> entryRows(Player reader, List<Entry> entries) {
        List<MenuRow> rows = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            String state = entry.hidden()
                    ? "@menu.guestbook.state_hidden"
                    : entry.pinned() ? "@menu.guestbook.state_pinned" : "@menu.guestbook.state_shown";
            rows.add(new MenuRow(
                    Map.of(
                            "message", entry.message(),
                            "state", messages.words(reader, state),
                            "material", entry.hidden() ? "GRAY_DYE" : entry.pinned() ? "PAPER" : "WRITTEN_BOOK"),
                    entry));
        }
        return List.copyOf(rows);
    }

    /** One row per island: its name, and whose it is, or a question mark when the server does not know. */
    static List<MenuRow> placeRows(List<Place> places) {
        List<MenuRow> rows = new ArrayList<>(places.size());
        for (Place place : places) {
            String owner = place.owner();
            rows.add(new MenuRow(Map.of("name", place.name(), "owner", owner != null ? owner : "?"), place));
        }
        return List.copyOf(rows);
    }

    /** Shows the guestbook of the island the player stands on, with whether they may moderate it. */
    public boolean showGuestbook(Player player, List<Entry> entries) {
        return open(
                player,
                GUESTBOOK_FILE,
                Map.of("count", Integer.toString(entries.size())),
                Map.of(GUESTBOOK, entryRows(player, entries)));
    }

    /** Shows the islands the player bookmarked. */
    public boolean showBookmarks(Player player, List<Place> places) {
        return open(
                player,
                BOOKMARKS_FILE,
                Map.of("count", Integer.toString(places.size())),
                Map.of(BOOKMARKS, placeRows(places)));
    }

    /** Shows the island's allies and the offers of alliance waiting for an answer. */
    public boolean showAlliances(Player player, List<Place> allies, List<Place> offers) {
        return open(
                player,
                ALLIANCES_FILE,
                Map.of("count", Integer.toString(allies.size()), "offers", Integer.toString(offers.size())),
                Map.of(ALLIES, placeRows(allies), OFFERS, placeRows(offers)));
    }

    private boolean open(Player player, String file, Map<String, String> values, Map<String, List<?>> lists) {
        Objects.requireNonNull(player, "player must not be null");
        SkyblockMenuEngine current = this.engine;
        return current != null && current.open(player, file, values, lists);
    }
}
