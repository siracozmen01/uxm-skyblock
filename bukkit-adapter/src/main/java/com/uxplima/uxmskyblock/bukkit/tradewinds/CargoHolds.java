package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.trade.OnThePlayersThread;
import com.uxplima.uxmskyblock.bukkit.trade.PlayerTradeSide;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoTransfer;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * The cargo holds of TradeWinds vessels: a chest window anyone of a vessel's team aboard it opens with
 * {@code /is cargo}.
 *
 * <p>A click on a stack in the hold takes it into the player's inventory, and a click on a stack in the
 * player's own inventory puts it into the hold. Nothing else moves an item in the window. Every move is
 * one operation across two owners, the player and the vessel, carried out through the
 * {@link CargoTransfer} under the player's write lock and the vessel's lease. The hold lives in durable
 * storage and is read fresh for every move, so two players of one team never move the same stack.
 *
 * <p>While a move is carried out its player's inventory holds still: {@link #moving} names them for a
 * {@link com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener}.
 */
public final class CargoHolds {

    /** A move in or out of a vessel's cargo hold, journalled under the hold's lock, and every open window of the hold drawn again after it. */
    private final CargoMoves moves = new CargoMoves(this);

    private static final Logger LOGGER = Logger.getLogger(CargoHolds.class.getName());

    /** Whose session a move is written under, and how a player is taken off the server. */
    public interface Sessions {

        /** The session {@code player} plays under here, or null when they may not move items now. */
        @Nullable ActiveSession session(UUID player);

        /** Takes {@code player} off the server, so recovery settles what a move left open. */
        void fence(UUID player, String why);
    }

    /** The menu file a hold is drawn from. */
    public static final String FILE = "vessel-cargo";

    /** The region of that file the hold shows in. */
    public static final String REGION = "tradewinds:hold";

    /**
     * The window a hold is shown in, holding which vessel it shows. A window drawn from the file
     * carries it as its subject, with the hold as last read: drawing it reads nothing.
     */
    public static final class View implements InventoryHolder {

        private final IslandId vessel;
        private final int slots;
        volatile byte[] items;
        private @Nullable Inventory inventory;

        View(IslandId vessel) {
            this(vessel, 0, new byte[0]);
        }

        View(IslandId vessel, int slots, byte[] items) {
            this.vessel = vessel;
            this.slots = slots;
            this.items = items.clone();
        }

        public IslandId vessel() {
            return vessel;
        }

        /** How many slots the vessel's rank gives its hold. */
        int slots() {
            return slots;
        }

        /** The hold as last read, one stack for each of its slots, null where a slot is empty. */
        @Nullable ItemStack[] shown() {
            return window(items, slots);
        }

        @Override
        public Inventory getInventory() {
            return Objects.requireNonNull(inventory, "the window is not open yet");
        }
    }

    private final VesselService vessels;
    final VesselsPort holds;
    final VesselLease lease;
    final CargoTransfer transfer;
    private final Function<Location, Optional<Island>> islandAt;
    final Sessions sessions;
    final SchedulerPort scheduler;
    private final Messages messages;
    private final Predicate<Player> sealed;
    final Function<IslandId, Ranks.Rank> rankOf;
    final OnThePlayersThread thread;
    private final Crew crew;
    private final Set<UUID> moving = ConcurrentHashMap.newKeySet();

    @Nullable SkyblockMenuEngine engine;

    /** Whether the file was found unable to show a hold, said once rather than on every open. */
    private final java.util.concurrent.atomic.AtomicBoolean saidTheFileCannotShowAHold =
            new java.util.concurrent.atomic.AtomicBoolean();

    final Map<IslandId, Set<UUID>> viewers = new ConcurrentHashMap<>();

    @SuppressWarnings("TooManyParameters")
    public CargoHolds(
            VesselService vessels,
            VesselsPort holds,
            VesselLease lease,
            CargoTransfer transfer,
            Function<Location, Optional<Island>> islandAt,
            Sessions sessions,
            SchedulerPort scheduler,
            Messages messages,
            Predicate<Player> sealed,
            Function<IslandId, Ranks.Rank> rankOf) {
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.sealed = Objects.requireNonNull(sealed, "sealed");
        this.rankOf = Objects.requireNonNull(rankOf, "rankOf");
        this.thread = new OnThePlayersThread(scheduler);
        this.crew = new Crew(vessels, islandAt, sessions);
    }

    /**
     * Draws a hold from {@code menus/vessel-cargo.conf} from now on: the window is the operator's, and
     * the hold is the region the file names. The window built in code stays for a server whose file
     * is gone or whose region is smaller than a rank's hold.
     */
    public void useMenuEngine(SkyblockMenuEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        engine.bindings().content(REGION, new HoldContent(this));
    }

    /** Whether the file shows a hold of {@code slots}: a region read only and large enough for it. */
    private boolean fileShows(SkyblockMenuEngine files, int slots) {
        Optional<com.uxplima.uxmlib.menu.spec.MenuSpec> spec = files.spec(FILE);
        if (spec.isEmpty()) {
            return false;
        }
        com.uxplima.uxmlib.menu.spec.ContentRegionSpec region =
                spec.get().contents().get(REGION);
        boolean shows =
                region != null && !region.editable() && region.slots().slots().size() >= slots;
        if (!shows && saidTheFileCannotShowAHold.compareAndSet(false, true)) {
            LOGGER.warning(() -> "menus/" + FILE + ".conf needs a content region \"" + REGION + "\", not editable, of"
                    + " as many slots as the largest hold of modules/tradewinds.conf. A hold it cannot show opens in"
                    + " the window built in code.");
        }
        return shows;
    }

    /** Whether a move of {@code player}'s is being carried out now, when nothing may change their inventory. */
    public boolean moving(UUID player) {
        return moving.contains(player);
    }

    /** Opens the hold of the vessel {@code player} stands on, when they are of its team. */
    public void open(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islandAt.apply(at);
        if (island.isEmpty() || !vessels.isVessel(island.get().id())) {
            messages.send(player, "tradewinds.hold.not_vessel");
            return;
        }
        if (sealed.test(player)) {
            messages.send(player, "tradewinds.hold.sealed");
            return;
        }
        IslandId vessel = island.get().id();
        Result<IslandId, String> allowed = crew.aboard(player, vessel, IslandPermission.VAULT_VIEW);
        if (!allowed.isOk()) {
            messages.send(player, allowed.errorOrThrow());
            return;
        }
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            Optional<VesselsPort.Cargo> cargo;
            Ranks.Rank found;
            try {
                cargo = holds.cargo(vessel);
                found = rankOf.apply(vessel);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The cargo hold of vessel " + vessel + " could not be read.");
                scheduler.onEntity(who, () -> messages.send(player, "tradewinds.hold.busy"));
                return;
            }
            Optional<VesselsPort.Cargo> read = cargo;
            Ranks.Rank rank = found;
            scheduler.onEntity(who, () -> {
                if (read.isEmpty()) {
                    messages.send(player, "tradewinds.hold.busy");
                    return;
                }
                SkyblockMenuEngine files = this.engine;
                if (files != null && fileShows(files, rank.holdSlots())) {
                    View drawn = new View(vessel, rank.holdSlots(), read.get().items());
                    viewers.computeIfAbsent(vessel, key -> ConcurrentHashMap.newKeySet())
                            .add(player.getUniqueId());
                    if (files.openHolding(
                            player,
                            FILE,
                            drawn,
                            Map.of("vessel_rank", Names.plain(messages, player, "ranks", rank.id())))) {
                        return;
                    }
                }
                View view = new View(vessel);
                Inventory window = Bukkit.createInventory(
                        view,
                        rank.holdSlots(),
                        messages.renderPlain(
                                player,
                                "tradewinds.hold.title",
                                Names.of(messages, player, "argument_vessel_rank", "ranks", rank.id())));
                view.inventory = window;
                window.setContents(window(read.get().items(), rank.holdSlots()));
                viewers.computeIfAbsent(vessel, key -> ConcurrentHashMap.newKeySet())
                        .add(player.getUniqueId());
                player.openInventory(window);
            });
        });
    }

    /** Answers a click in a hold window: a stack moves between the hold and the player, nothing else. */
    public void onClick(InventoryClickEvent event) {
        View view = viewOf(event.getView());
        if (view == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        boolean taking = clicked.getHolder() instanceof View;
        int slot = event.getSlot();
        ItemStack shown = taking ? clicked.getItem(slot) : player.getInventory().getItem(slot);
        request(player, view, taking, slot, shown);
    }

    /**
     * {@code player} asks to move the stack {@code shown} between the hold and their own inventory: out
     * of the hold's {@code slot} when {@code taking}, out of their own {@code slot} when not. Both
     * windows ask through here, so a file window refuses what the window built in code refuses.
     */
    void request(Player player, View view, boolean taking, int slot, @Nullable ItemStack shown) {
        if (moving(player.getUniqueId())) {
            return;
        }
        if (sealed.test(player)) {
            // What a creative place made never goes into a hold, and nothing from a hold goes into one.
            messages.send(player, "tradewinds.hold.sealed");
            return;
        }
        // Asked again on every click: a window stays open after its player left the crew or the vessel.
        Result<IslandId, String> allowed = crew.aboard(
                player, view.vessel(), taking ? IslandPermission.VAULT_WITHDRAW : IslandPermission.VAULT_DEPOSIT);
        if (!allowed.isOk()) {
            messages.send(player, allowed.errorOrThrow());
            if (!"tradewinds.hold.not_allowed".equals(allowed.errorOrThrow())) {
                player.closeInventory();
            }
            return;
        }
        if (shown == null || shown.getType().isAir()) {
            return;
        }
        if (taking) {
            move(player, view.vessel(), Direction.TAKE, slot, shown.clone());
            return;
        }
        if (slot < 0 || slot >= PlayerTradeSide.STORAGE_SLOTS) {
            return;
        }
        move(player, view.vessel(), Direction.PUT, slot, shown.clone());
    }

    /** A drag in a hold window would move items; it does nothing. */
    public void onDrag(InventoryDragEvent event) {
        if (viewOf(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    /** A player who closes a hold window no longer sees it change. */
    public void onClose(InventoryCloseEvent event) {
        View view = viewOf(event.getView());
        if (view != null) {
            forget(view.vessel(), event.getPlayer().getUniqueId());
        }
    }

    /** A player who leaves sees no hold. */
    public void onLeave(UUID player) {
        viewers.values().forEach(watching -> watching.remove(player));
    }

    enum Direction {
        PUT,
        TAKE
    }

    void move(Player player, IslandId vessel, Direction direction, int slot, ItemStack item) {
        UUID who = player.getUniqueId();
        if (!moving.add(who)) {
            return;
        }
        scheduler.async(() -> {
            Result<InventoryMutationOperationId, String> done;
            try {
                done = moves.moveLocked(player, vessel, direction, slot, item);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "A cargo move of " + who + " on vessel " + vessel + " failed", e);
                done = Result.err(TradeExchange.NOT_SAVED);
            } finally {
                moving.remove(who);
            }
            Result<InventoryMutationOperationId, String> outcome = done;
            if (!outcome.isOk()) {
                scheduler.onEntity(PlayerUuid.of(who), () -> messages.send(player, keyOf(outcome.errorOrThrow())));
            }
            moves.redraw(vessel);
        });
    }

    void forget(IslandId vessel, UUID player) {
        Set<UUID> watching = viewers.get(vessel);
        if (watching != null) {
            watching.remove(player);
        }
    }

    /** The hold's items, at least {@code slots} of them: more when it holds more than its rank shows. */
    static ItemStack[] stacks(byte[] stored, int slots) {
        ItemStack[] items =
                stored.length == 0 ? new ItemStack[0] : BukkitInventorySerializer.deserializeItemStacks(stored);
        return items.length >= slots ? items : Arrays.copyOf(items, slots);
    }

    /** The hold's items as a window of {@code size} slots shows them. */
    static ItemStack[] window(byte[] stored, int size) {
        return Arrays.copyOf(stacks(stored, size), size);
    }

    /** The message key of a move that did not happen: a trade's reasons are the hold's own. */
    static String keyOf(String why) {
        return why.startsWith("trade.") ? "tradewinds.hold." + why.substring("trade.".length()) : why;
    }

    /** Stows {@code item} in the hold, onto its own kind first, and says whether all of it fit. */
    static boolean stow(ItemStack[] hold, ItemStack item) {
        int remaining = item.getAmount();
        int most = item.getMaxStackSize();
        for (int i = 0; i < hold.length && remaining > 0; i++) {
            ItemStack slot = hold[i];
            if (slot != null && slot.isSimilar(item) && slot.getAmount() < most) {
                int moved = Math.min(remaining, most - slot.getAmount());
                slot.setAmount(slot.getAmount() + moved);
                remaining -= moved;
            }
        }
        for (int i = 0; i < hold.length && remaining > 0; i++) {
            if (hold[i] == null || hold[i].getType().isAir()) {
                ItemStack placed = item.clone();
                placed.setAmount(Math.min(remaining, most));
                hold[i] = placed;
                remaining -= placed.getAmount();
            }
        }
        return remaining == 0;
    }

    static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static @Nullable View viewOf(org.bukkit.inventory.InventoryView view) {
        @Nullable Inventory top = view.getTopInventory();
        return top != null && top.getHolder() instanceof View hold ? hold : null;
    }
}
