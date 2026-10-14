package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import org.jspecify.annotations.Nullable;

/**
 * One page of the island vault drawn in its window: the stored items, the page tiles and the lease the page is held under. Split out of {@link IslandVaultWindow}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class VaultPages {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(VaultPages.class.getName());

    private final IslandVaultWindow host;

    VaultPages(IslandVaultWindow host) {
        this.host = host;
    }

    void show(
            Player player,
            IslandId islandId,
            IslandVaultWindow.Opened opened,
            ProfileId profileId,
            boolean mayDeposit,
            boolean mayWithdraw) {
        if (!player.isOnline()) {
            host.vaultService.abortVaultPage(opened.result().session().sessionId());
            return;
        }
        int page = opened.page();
        VaultPage vaultPage = opened.result().page();
        @Nullable ItemStack[] stored = BukkitInventorySerializer.deserializeItemStacks(vaultPage.contentsNbt());
        int shown = host.configuration.slotsPerPage();
        SkyblockMenuEngine files = host.engine;
        List<Integer> fileSlots = files == null ? null : host.pageSlotsOf(files);
        IslandVaultWindow.VaultHolder holder = new IslandVaultWindow.VaultHolder(
                islandId,
                page,
                profileId,
                opened.result().session().sessionId().value().toString(),
                WhatThePageOwes.openedWith(stored, shown),
                mayDeposit,
                mayWithdraw,
                fileSlots != null
                        ? fileSlots
                        : java.util.stream.IntStream.range(0, shown).boxed().toList(),
                WhatThePageOwes.beyond(stored, shown),
                new java.util.concurrent.atomic.AtomicBoolean());
        if (!holder.beyond().isEmpty()) {
            LOGGER.warning(() -> "Vault page " + page + " of island " + islandId + " holds items past the " + shown
                    + " slots a page has now. They are kept, and shown again when slots-per-page is raised.");
        }
        if (files != null
                && fileSlots != null
                && files.openHolding(
                        player, IslandVaultWindow.FILE, holder, IslandVaultWindow.valuesOf(holder, opened.pages()))) {
            host.commits.closeBeforeTheLeaseRunsOut(player, holder);
            return;
        }
        Inventory inventory = Bukkit.createInventory(
                holder,
                shown,
                host.messages.renderPlain(player, "vault.title", Placeholder.unparsed("page", Integer.toString(page))));

        for (int slot = 0; slot < Math.min(stored.length, inventory.getSize()); slot++) {
            inventory.setItem(slot, stored[slot]);
        }
        player.openInventory(inventory);
        host.commits.closeBeforeTheLeaseRunsOut(player, holder);
    }
}
