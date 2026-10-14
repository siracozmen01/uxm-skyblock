package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.List;
import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import org.jspecify.annotations.Nullable;

/**
 * What the island vault writes back when its window closes, before the server stops or the lease runs out, and how a refused write is settled. Split out of {@link IslandVaultWindow}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class VaultCommits {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(VaultCommits.class.getName());

    private final IslandVaultWindow host;

    VaultCommits(IslandVaultWindow host) {
        this.host = host;
    }

    /**
     * Writes the window back under its lease. A lease that expired while the window was open is
     * refused by the service, and the player is told rather than silently losing the edit.
     */
    public void commit(Player player, IslandVaultWindow.VaultHolder holder, ItemStack[] contents) {
        Objects.requireNonNull(holder, "holder must not be null");
        byte[] serialized = BukkitInventorySerializer.serializeItemStacks(holder.stored(contents));
        List<VaultAuditLogEntry> auditTrail = VaultAuditTrail.of(holder, contents);
        IslandVaultWindow.PlayerAtClose playerState = host.stateOf(player, holder);
        if (playerState != null) {
            playerState.session().selfWriteBegun();
        }
        host.schedulerPort.async(() -> {
            try {
                commitPage(holder, serialized, playerState, auditTrail);
                IslandVaultWindow.landed(playerState);
            } catch (RuntimeException e) {
                // The window is already closed and what it held is nowhere: not in the page, because
                // the commit was refused, and not with the player, because they put it in the vault.
                // Telling them it was refused and keeping the items is losing them.
                host.schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    try {
                        host.messages.send(player, "vault.commit_refused");
                        settleTheRefusedCommit(player, holder, contents);
                    } finally {
                        IslandVaultWindow.landed(playerState);
                    }
                });
            }
        });
    }
    /**
     * Writes the page, and the player with it, under the session's write lock.
     *
     * <p>The player is written over the version the session stands at when the lock is taken, not the
     * one it stood at when the window closed: a checkpoint that landed in between moved it on, and a
     * save over the older version was refused and handed the page's items back.
     */
    void commitPage(
            IslandVaultWindow.VaultHolder holder,
            byte[] serialized,
            IslandVaultWindow.@Nullable PlayerAtClose player,
            List<VaultAuditLogEntry> auditTrail) {
        com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId sessionId =
                com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId.fromString(holder.sessionId());
        if (player == null) {
            host.vaultService.commitVaultPage(
                    sessionId, serialized, holder.profileId().toString(), null, auditTrail);
            return;
        }
        ActiveSession session = player.session();
        session.writes().lock();
        try {
            long version = session.lastDurableVersion();
            PlayerStateWrite write = new PlayerStateWrite(
                    session.playerUuid(),
                    player.node(),
                    session.sessionEpoch(),
                    version,
                    player.state().withInventoryNbt(version, player.state().inventoryNbt()));
            host.vaultService.commitVaultPage(
                    sessionId, serialized, holder.profileId().toString(), write, auditTrail);
            // The session now stands at the version the commit wrote, so the next checkpoint builds on it.
            session.setLastDurableVersion(write.writtenVersion());
        } finally {
            session.writes().unlock();
        }
    }
    /**
     * Writes a window the player still has open when the server stops, on the stopping thread.
     *
     * <p>The plugin is disabled before players are disconnected, so the close event of a window open
     * at a stop never reached the listener: the page kept what it held, and the stop wrote the player
     * holding what they had taken out of it. The page and the player are now written together here,
     * before the stop writes the player; a refused write puts the player back where the page says
     * they are. The window stays open, so nothing is written twice.
     */
    public void writeBeforeStop(Player player) {
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        IslandVaultWindow.VaultHolder holder =
                IslandVaultWindow.VaultHolder.of(top).orElse(null);
        if (top == null || holder == null || !holder.takeTheWrite()) {
            return;
        }
        @Nullable ItemStack[] contents = holder.pageOf(top);
        IslandVaultWindow.PlayerAtClose playerState = host.stateOf(player, holder);
        try {
            commitPage(
                    holder,
                    BukkitInventorySerializer.serializeItemStacks(holder.stored(contents)),
                    playerState,
                    VaultAuditTrail.of(holder, contents));
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    "The vault page open for " + player.getName()
                            + " could not be written at a stop; the player is put back as the page stands",
                    e);
            settleTheRefusedCommit(player, holder, contents);
        }
    }
    /**
     * Closes the window while its lease still holds, so what it saves is saved.
     *
     * <p>The lease was never renewed and the window never closed, so a player who left it open past
     * the lease closed it onto a refused commit. The page kept what it held, the player kept what
     * they had taken out of it, and opening it again handed the same items over a second time. The
     * window now closes at five sixths of the lease, which is when the save still lands.
     */
    void closeBeforeTheLeaseRunsOut(Player player, IslandVaultWindow.VaultHolder holder) {
        java.time.Duration lease = host.configuration.leaseDuration();
        java.time.Duration closeAt = lease.multipliedBy(5).dividedBy(6);
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        host.schedulerPort.asyncAfter(
                closeAt,
                () -> host.schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()
                            && IslandVaultWindow.VaultHolder.of(
                                                    player.getOpenInventory().getTopInventory())
                                            .orElse(null)
                                    == holder) {
                        host.messages.send(player, "vault.closed_to_save");
                        player.closeInventory();
                    }
                }));
    }
    /**
     * Puts the player back where the stored page says they are, after a refused commit.
     *
     * <p>A refused commit leaves the page as it was when the window opened. What the window held at
     * close and the page did not is what the player put in, and it goes back to them. What the page
     * held and the window no longer did is what the player took out, and the page still holds it, so
     * it comes back out of the player's inventory. Handing back only what was put in let a player take
     * a page's items, let the commit be refused, and take them again.
     *
     * <p>What does not fit is dropped where they stand. What the player no longer holds cannot be
     * taken back, and is written to the log with who and what, for an administrator to settle.
     */
    void settleTheRefusedCommit(Player player, IslandVaultWindow.VaultHolder holder, ItemStack[] atClose) {
        WhatThePageOwes.Moved moved = WhatThePageOwes.between(holder.openedWith(), atClose);
        List<ItemStack> stillInThePage = moved.takenOut();
        List<ItemStack> putIn = moved.putIn();

        // What the player took out, the page still holds.
        for (ItemStack takenOut : stillInThePage) {
            for (ItemStack kept : player.getInventory().removeItem(takenOut).values()) {
                LOGGER.warning(() -> player.getName() + " took " + kept.getAmount() + " " + kept.getType()
                        + " out of vault page " + holder.page() + " of island " + holder.islandId()
                        + " under a refused save and no longer holds them. The page still holds them.");
            }
        }
        for (ItemStack owed : putIn) {
            for (ItemStack overflow : player.getInventory().addItem(owed).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }
}
