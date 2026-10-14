package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.trade.PlayerTradeSide;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoTransfer;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;

/**
 * A move in or out of a vessel's cargo hold, journalled under the hold's lock, and every open window of the hold drawn again after it. Split out of {@link CargoHolds}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class CargoMoves {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(CargoMoves.class.getName());

    private final CargoHolds host;

    CargoMoves(CargoHolds host) {
        this.host = host;
    }

    Result<InventoryMutationOperationId, String> moveLocked(
            Player player, IslandId vessel, CargoHolds.Direction direction, int slot, ItemStack item) {
        UUID who = player.getUniqueId();
        ActiveSession session = host.sessions.session(who);
        if (session == null) {
            return Result.err("trade.left");
        }
        ReentrantLock writes = session.writes();
        writes.lock();
        try {
            OptionalLong epoch = host.lease.hold(vessel);
            if (epoch.isEmpty()) {
                return Result.err("tradewinds.hold.elsewhere");
            }
            Optional<VesselsPort.Cargo> cargo = host.holds.cargo(vessel);
            if (cargo.isEmpty()) {
                return Result.err("tradewinds.hold.not_vessel");
            }
            byte[] stored = cargo.get().items();
            ItemStack[] hold =
                    CargoHolds.stacks(stored, host.rankOf.apply(vessel).holdSlots());
            List<PlayerTradeSide.Given> gives;
            List<ItemStack> receives;
            CargoTransfer.Cargo after;
            if (direction == CargoHolds.Direction.PUT) {
                gives = List.of(new PlayerTradeSide.Given(slot, item));
                receives = List.of();
                after = snapshot -> {
                    ItemStack[] filled = CargoHolds.copy(hold);
                    return CargoHolds.stow(filled, item.clone())
                            ? Result.ok(BukkitInventorySerializer.serializeItemStacks(filled))
                            : Result.err("tradewinds.hold.full");
                };
            } else {
                ItemStack there = slot < hold.length ? hold[slot] : null;
                if (there == null || !there.isSimilar(item) || there.getAmount() != item.getAmount()) {
                    return Result.err("tradewinds.hold.moved");
                }
                gives = List.of();
                receives = List.of(there.clone());
                after = snapshot -> {
                    ItemStack[] emptied = CargoHolds.copy(hold);
                    emptied[slot] = null;
                    return Result.ok(BukkitInventorySerializer.serializeItemStacks(emptied));
                };
            }
            PlayerTradeSide side = new PlayerTradeSide(
                    player,
                    session,
                    gives,
                    receives,
                    host.thread,
                    () -> host.sessions.fence(who, "a cargo move nobody can tell was written"));
            return host.transfer.move(
                    side,
                    new CargoJournalPort.Hold(vessel, epoch.getAsLong()),
                    cargo.get().version(),
                    stored,
                    after);
        } finally {
            writes.unlock();
        }
    }

    void redraw(IslandId vessel) {
        Set<UUID> watching = host.viewers.get(vessel);
        if (watching == null || watching.isEmpty()) {
            return;
        }
        Optional<VesselsPort.Cargo> cargo;
        try {
            cargo = host.holds.cargo(vessel);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "The cargo hold of vessel " + vessel + " could not be read.");
            return;
        }
        if (cargo.isEmpty()) {
            return;
        }
        byte[] items = cargo.get().items();
        for (UUID viewer : List.copyOf(watching)) {
            host.scheduler.onEntity(PlayerUuid.of(viewer), () -> {
                Player online = Bukkit.getPlayer(viewer);
                if (online == null) {
                    host.forget(vessel, viewer);
                    return;
                }
                Inventory top = online.getOpenInventory().getTopInventory();
                if (top != null
                        && top.getHolder() instanceof CargoHolds.View view
                        && view.vessel().equals(vessel)) {
                    top.setContents(CargoHolds.window(items, top.getSize()));
                } else if (top != null
                        && top.getHolder() instanceof com.uxplima.uxmlib.menu.runtime.MenuHolder menu
                        && menu.ctx().subjectRaw().orElse(null) instanceof CargoHolds.View drawn
                        && drawn.vessel().equals(vessel)
                        && host.engine != null) {
                    drawn.items = items.clone();
                    host.engine.menus().redraw(online, CargoHolds.FILE);
                } else {
                    host.forget(vessel, viewer);
                }
            });
        }
    }
}
