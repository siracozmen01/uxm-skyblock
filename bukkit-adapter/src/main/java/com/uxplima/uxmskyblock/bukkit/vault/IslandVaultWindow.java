package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageBusyException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPermissionDeniedException;
import org.jspecify.annotations.Nullable;

/**
 * The shared island vault as a container a player can actually open.
 *
 * <p>The service, its escrow, its pessimistic lease and its V19 table have been here since the
 * vault work and nothing ever opened one. This is the missing half: a chest sized window over one
 * page, held under the lease the service hands out, written back when the window closes.
 *
 * <p>It builds a plain container rather than a menu, because a vault page is storage a player edits
 * freely. A menu engine window is the wrong shape: every slot would have to be a button.
 */
public final class IslandVaultWindow {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandVaultWindow.class.getName());

    /** How a moment is written in the log, in the server's own zone. */
    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(java.time.ZoneId.systemDefault());

    /**
     * Marks an open vault window and carries what the close handler needs to commit it.
     *
     * <p>{@code openedWith} is the page as it stood when the window opened, one entry per slot, air
     * where the slot was empty. A refused commit leaves the stored page exactly that, so the
     * difference between it and what the window holds at close is what the player put in, and that
     * is what has to go back to them. The same difference, read slot by slot, is what the audit log
     * records.
     *
     * <p>{@code mayDeposit} and {@code mayWithdraw} are what the viewer's role lets them do with the
     * page. They are read once, where the page is opened, and carried here so a click can ask the
     * holder rather than the database: that is the only way to answer on the thread a click arrives
     * on.
     *
     * <p>{@code pageSlots} names the window slot that shows each slot of the page, in page order. The
     * window built in code shows the page from its first slot; a menu file shows it wherever its
     * content region says. {@code beyond} is what the stored page holds past the slots the window
     * shows. A page written when the page size was larger keeps it: the window cannot show it, and
     * writing the page without it deleted it.
     *
     * <p>A window drawn from a menu file is the holder of nothing, so the menu carries this as its
     * subject and {@link #of} finds it there. {@code written} is set by whichever write comes first,
     * the close or the stop, so the page is never written twice.
     */
    public record VaultHolder(
            IslandId islandId,
            int page,
            ProfileId profileId,
            String sessionId,
            List<ItemStack> openedWith,
            boolean mayDeposit,
            boolean mayWithdraw,
            List<Integer> pageSlots,
            List<ItemStack> beyond,
            java.util.concurrent.atomic.AtomicBoolean written)
            implements InventoryHolder, com.uxplima.uxmskyblock.bukkit.session.WritesPlayerStateItself {

        public VaultHolder {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            Objects.requireNonNull(openedWith, "openedWith must not be null");
            Objects.requireNonNull(pageSlots, "pageSlots must not be null");
            Objects.requireNonNull(beyond, "beyond must not be null");
            Objects.requireNonNull(written, "written must not be null");
            openedWith = List.copyOf(openedWith);
            pageSlots = List.copyOf(pageSlots);
            beyond = List.copyOf(beyond);
            if (pageSlots.size() != openedWith.size()) {
                throw new IllegalArgumentException(
                        "the window shows " + pageSlots.size() + " slots of a page of " + openedWith.size());
            }
        }

        /** The window built in code: the page from its first slot, nothing past it. */
        public VaultHolder(
                IslandId islandId,
                int page,
                ProfileId profileId,
                String sessionId,
                List<ItemStack> openedWith,
                boolean mayDeposit,
                boolean mayWithdraw) {
            this(
                    islandId,
                    page,
                    profileId,
                    sessionId,
                    openedWith,
                    mayDeposit,
                    mayWithdraw,
                    java.util.stream.IntStream.range(0, openedWith.size())
                            .boxed()
                            .toList(),
                    List.of(),
                    new java.util.concurrent.atomic.AtomicBoolean());
        }

        /** The vault page {@code window} shows, whether the window was built in code or drawn from a file. */
        public static Optional<VaultHolder> of(@Nullable Inventory window) {
            if (window == null) {
                return Optional.empty();
            }
            InventoryHolder holder = window.getHolder();
            if (holder instanceof VaultHolder vault) {
                return Optional.of(vault);
            }
            if (holder instanceof com.uxplima.uxmlib.menu.runtime.MenuHolder menu
                    && menu.ctx().subjectRaw().orElse(null) instanceof VaultHolder vault) {
                return Optional.of(vault);
            }
            return Optional.empty();
        }

        /** The page as {@code window} holds it, one entry per page slot, null where the slot is empty. */
        public @Nullable ItemStack[] pageOf(Inventory window) {
            @Nullable ItemStack[] shown = new ItemStack[pageSlots.size()];
            for (int index = 0; index < pageSlots.size(); index++) {
                int slot = pageSlots.get(index);
                shown[index] = slot < window.getSize() ? window.getItem(slot) : null;
            }
            return shown;
        }

        /** What the stored page is written as: {@code shown}, and after it what the window could not show. */
        public @Nullable ItemStack[] stored(@Nullable ItemStack[] shown) {
            @Nullable ItemStack[] stored = java.util.Arrays.copyOf(shown, shown.length + beyond.size());
            for (int index = 0; index < beyond.size(); index++) {
                ItemStack kept = beyond.get(index);
                stored[shown.length + index] = kept.getType().isAir() ? null : kept.clone();
            }
            return stored;
        }

        /** Whether this call is the one that writes the page: true once, for the close or the stop. */
        boolean takeTheWrite() {
            return written.compareAndSet(false, true);
        }

        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("The holder is a marker; the inventory owns it, not the reverse.");
        }

        /** The player as the stored page leaves them: see {@link WhatThePageOwes#asStored}. */
        @Override
        public ItemStack @Nullable [] inventoryAsStored(Player player, Inventory window) {
            return WhatThePageOwes.asStored(
                    player.getInventory().getContents(),
                    player.getItemOnCursor(),
                    WhatThePageOwes.between(openedWith, pageOf(window)));
        }
    }

    /** The menu file a page is drawn from. */
    public static final String FILE = "island-vault-page";

    /** The region of that file that holds the page. */
    public static final String PAGE = "skyblock:vault-page";

    /** The verb that writes the page and opens the one it names. */
    public static final String TURN = "skyblock:vault-open";

    private final IslandVaultService vaultService;
    private final IslandStoragePort islandStoragePort;
    private final SchedulerPort schedulerPort;
    private final VaultConfiguration configuration;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private @Nullable SkyblockMenuEngine engine;

    /** Whether the file was found unable to hold a page, said once rather than on every open. */
    private final java.util.concurrent.atomic.AtomicBoolean saidTheFileCannotHoldAPage =
            new java.util.concurrent.atomic.AtomicBoolean();

    public IslandVaultWindow(
            IslandVaultService vaultService,
            IslandStoragePort islandStoragePort,
            SchedulerPort schedulerPort,
            VaultConfiguration configuration,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /**
     * Draws a page from {@code menus/island-vault-page.conf} from now on: the window around the page
     * is the operator's, and the page is the region the file names. The window built in code stays
     * for a server whose file is gone or cannot hold a page.
     */
    public void useMenuEngine(SkyblockMenuEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        engine.bindings().content(PAGE, new VaultPageContent(this));
        engine.action(TURN, ctx -> {
            try {
                int page = Integer.parseInt(ctx.arg().strip());
                // Closing writes the page this window holds; the one named is another page, under its own lease.
                ctx.player().closeInventory();
                open(ctx.player(), page);
            } catch (NumberFormatException notAPage) {
                LOGGER.warning(() -> FILE + ": " + TURN + " names no page: " + ctx.arg());
            }
        });
    }

    /** Opens {@code page} of the island's vault for {@code player}, or says why it cannot. */
    public void open(Player player, int page) {
        Objects.requireNonNull(player, "player must not be null");
        if (com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.holds(player)) {
            messages.send(player, "sealed.refused");
            return;
        }
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            Optional<Island> optIsland = islandStoragePort.findIslandById(optIslandId.get());
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            IslandRole role = roleOf(island, profileId);

            try {
                IslandVaultService.VaultOpenResult opened = vaultService.openVaultPage(
                        island, profileId, role, page, player.getUniqueId(), configuration.leaseDuration());
                boolean mayDeposit = role.hasPermission(IslandPermission.VAULT_DEPOSIT);
                boolean mayWithdraw = role.hasPermission(IslandPermission.VAULT_WITHDRAW);
                int pages = vaultService.getMaxAllowedPages(island.id());
                schedulerPort.onEntity(
                        playerUuid,
                        () -> show(
                                player,
                                island.id(),
                                new Opened(page, pages, opened),
                                profileId,
                                mayDeposit,
                                mayWithdraw));
            } catch (VaultPermissionDeniedException denied) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "vault.no_permission"));
            } catch (VaultPageLimitExceededException limit) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> messages.send(
                                player,
                                "vault.page_out_of_range",
                                Placeholder.unparsed("page", Integer.toString(page)),
                                Placeholder.unparsed("max", Integer.toString(limit.maxAllowedPages()))));
            } catch (VaultPageBusyException busy) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> messages.send(
                                player, "vault.busy", Placeholder.unparsed("page", Integer.toString(page))));
            }
        });
    }

    /**
     * Sends the island's management what the vault has seen.
     *
     * <p>The audit entries were written and no one could read them: the service call that returns
     * them had no caller anywhere. A record nobody can look at is not a record.
     *
     * <p>The bar is the island's management permission, not the one that opens the vault. Every
     * member can open the chest; who took what out of it is the owner's question.
     */
    public void showLog(Player player, int limit) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Island> optIsland =
                    islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandStoragePort::findIslandById);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!roleOf(island, profileId).hasPermission(IslandPermission.SETTINGS_MODIFY)) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "vault.log_no_permission"));
                return;
            }

            List<VaultAuditLogEntry> entries = vaultService.getRecentAuditLogs(island.id(), limit);
            schedulerPort.onEntity(playerUuid, () -> sendLog(player, island, entries));
        });
    }

    private void sendLog(Player player, Island island, List<VaultAuditLogEntry> entries) {
        if (entries.isEmpty()) {
            messages.send(player, "vault.log_empty");
            return;
        }
        messages.send(player, "vault.log_header", Placeholder.unparsed("count", Integer.toString(entries.size())));
        for (VaultAuditLogEntry entry : entries) {
            messages.send(
                    player,
                    "vault.log_entry",
                    Placeholder.unparsed("actor", nameOf(island, entry.actorProfileId())),
                    Placeholder.unparsed("action", entry.actionType().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("quantity", Integer.toString(entry.quantity())),
                    Placeholder.unparsed("item", entry.itemSummary()),
                    Placeholder.unparsed("page", Integer.toString(entry.page())),
                    Placeholder.unparsed("slot", Integer.toString(entry.slot())),
                    Placeholder.unparsed("when", WHEN.format(entry.createdAt())));
        }
    }

    /**
     * The name behind a profile id, or the id itself when the island no longer holds that member.
     *
     * <p>The island is already in hand, so this costs no lookup. A member who has left keeps their
     * entries: a record that forgets who did something is worth less than the id.
     */
    private static String nameOf(Island island, String actorProfileId) {
        for (IslandMember member : island.members().values()) {
            if (member.profileId().toString().equals(actorProfileId)) {
                String name =
                        Bukkit.getOfflinePlayer(member.playerUuid().value()).getName();
                return name != null ? name : actorProfileId;
            }
        }
        return actorProfileId;
    }

    /** A page the service leased, which page it is and how many the island has. */
    private record Opened(int page, int pages, IslandVaultService.VaultOpenResult result) {}

    private void show(
            Player player,
            IslandId islandId,
            Opened opened,
            ProfileId profileId,
            boolean mayDeposit,
            boolean mayWithdraw) {
        if (!player.isOnline()) {
            vaultService.abortVaultPage(opened.result().session().sessionId());
            return;
        }
        int page = opened.page();
        VaultPage vaultPage = opened.result().page();
        @Nullable ItemStack[] stored = BukkitInventorySerializer.deserializeItemStacks(vaultPage.contentsNbt());
        int shown = configuration.slotsPerPage();
        SkyblockMenuEngine files = this.engine;
        List<Integer> fileSlots = files == null ? null : pageSlotsOf(files);
        VaultHolder holder = new VaultHolder(
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
                && files.openHolding(player, FILE, holder, valuesOf(holder, opened.pages()))) {
            closeBeforeTheLeaseRunsOut(player, holder);
            return;
        }
        Inventory inventory = Bukkit.createInventory(
                holder,
                shown,
                messages.renderPlain(player, "vault.title", Placeholder.unparsed("page", Integer.toString(page))));

        for (int slot = 0; slot < Math.min(stored.length, inventory.getSize()); slot++) {
            inventory.setItem(slot, stored[slot]);
        }
        player.openInventory(inventory);
        closeBeforeTheLeaseRunsOut(player, holder);
    }

    /**
     * The slots of the file that hold a page, or null when the file is gone or holds a page of another
     * size. A region of another size would show part of a page or leave slots that belong to none.
     */
    @Nullable List<Integer> pageSlotsOf(SkyblockMenuEngine files) {
        Optional<com.uxplima.uxmlib.menu.spec.MenuSpec> spec = files.spec(FILE);
        if (spec.isEmpty()) {
            return null;
        }
        com.uxplima.uxmlib.menu.spec.ContentRegionSpec region =
                spec.get().contents().get(PAGE);
        int size = configuration.slotsPerPage();
        if (region == null || region.slots().slots().size() != size) {
            if (saidTheFileCannotHoldAPage.compareAndSet(false, true)) {
                LOGGER.warning(() -> "menus/" + FILE + ".conf needs a content region \"" + PAGE + "\" of " + size
                        + " slots, as many as slots-per-page in modules/vault.conf. The vault opens in the"
                        + " window built in code until it has one.");
            }
            return null;
        }
        return region.slots().slots();
    }

    /** What the file is told about the page: which it is, how many there are and what the role allows. */
    static java.util.Map<String, String> valuesOf(VaultHolder holder, int pages) {
        String access;
        if (holder.mayDeposit() && holder.mayWithdraw()) {
            access = "<key:menu.vault_page.access_both>";
        } else if (holder.mayWithdraw()) {
            access = "<key:menu.vault_page.access_take>";
        } else if (holder.mayDeposit()) {
            access = "<key:menu.vault_page.access_put>";
        } else {
            access = "<key:menu.vault_page.access_look>";
        }
        return java.util.Map.of(
                "page", Integer.toString(holder.page()),
                "pages", Integer.toString(pages),
                "previous_page", Integer.toString(holder.page() - 1),
                "next_page", Integer.toString(holder.page() + 1),
                "pages_after", Integer.toString(Math.max(0, pages - holder.page())),
                "access", access);
    }

    /**
     * Closes the window while its lease still holds, so what it saves is saved.
     *
     * <p>The lease was never renewed and the window never closed, so a player who left it open past
     * the lease closed it onto a refused commit. The page kept what it held, the player kept what
     * they had taken out of it, and opening it again handed the same items over a second time. The
     * window now closes at five sixths of the lease, which is when the save still lands.
     */
    void closeBeforeTheLeaseRunsOut(Player player, VaultHolder holder) {
        java.time.Duration lease = configuration.leaseDuration();
        java.time.Duration closeAt = lease.multipliedBy(5).dividedBy(6);
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        schedulerPort.asyncAfter(
                closeAt,
                () -> schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()
                            && VaultHolder.of(player.getOpenInventory().getTopInventory())
                                            .orElse(null)
                                    == holder) {
                        messages.send(player, "vault.closed_to_save");
                        player.closeInventory();
                    }
                }));
    }

    /**
     * Tells a player their role does not let them move items that way.
     *
     * <p>The two directions read differently to the player: one says they may not take anything
     * out, the other that they may not put anything in. The window says it because the window owns
     * the catalogue, and the click arrives on the player's own thread already.
     */
    public void sayTheRoleRefused(Player player, boolean takingOut) {
        messages.send(player, takingOut ? "vault.no_withdraw" : "vault.no_deposit");
    }

    /**
     * Writes the window back under its lease. A lease that expired while the window was open is
     * refused by the service, and the player is told rather than silently losing the edit.
     */
    public void commit(Player player, VaultHolder holder, ItemStack[] contents) {
        Objects.requireNonNull(holder, "holder must not be null");
        byte[] serialized = BukkitInventorySerializer.serializeItemStacks(holder.stored(contents));
        List<VaultAuditLogEntry> auditTrail = VaultAuditTrail.of(holder, contents);
        PlayerAtClose playerState = stateOf(player, holder);
        if (playerState != null) {
            playerState.session().selfWriteBegun();
        }
        schedulerPort.async(() -> {
            try {
                commitPage(holder, serialized, playerState, auditTrail);
                landed(playerState);
            } catch (RuntimeException e) {
                // The window is already closed and what it held is nowhere: not in the page, because
                // the commit was refused, and not with the player, because they put it in the vault.
                // Telling them it was refused and keeping the items is losing them.
                schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    try {
                        messages.send(player, "vault.commit_refused");
                        settleTheRefusedCommit(player, holder, contents);
                    } finally {
                        landed(playerState);
                    }
                });
            }
        });
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
        VaultHolder holder = VaultHolder.of(top).orElse(null);
        if (top == null || holder == null || !holder.takeTheWrite()) {
            return;
        }
        @Nullable ItemStack[] contents = holder.pageOf(top);
        PlayerAtClose playerState = stateOf(player, holder);
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
     * Writes the page, and the player with it, under the session's write lock.
     *
     * <p>The player is written over the version the session stands at when the lock is taken, not the
     * one it stood at when the window closed: a checkpoint that landed in between moved it on, and a
     * save over the older version was refused and handed the page's items back.
     */
    private void commitPage(
            VaultHolder holder,
            byte[] serialized,
            @Nullable PlayerAtClose player,
            List<VaultAuditLogEntry> auditTrail) {
        com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId sessionId =
                com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId.fromString(holder.sessionId());
        if (player == null) {
            vaultService.commitVaultPage(
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
            vaultService.commitVaultPage(
                    sessionId, serialized, holder.profileId().toString(), write, auditTrail);
            // The session now stands at the version the commit wrote, so the next checkpoint builds on it.
            session.setLastDurableVersion(write.writtenVersion());
        } finally {
            session.writes().unlock();
        }
    }

    private static void landed(@Nullable PlayerAtClose playerState) {
        if (playerState != null) {
            playerState.session().selfWriteLanded();
        }
    }

    /** What the player holds as the window closes, and the session it is written under. */
    private record PlayerAtClose(
            ActiveSession session,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node,
            com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord state) {}

    /**
     * What the player holds as the window closes, to be written with the page.
     *
     * <p>The page was written alone and the player's inventory at the next checkpoint, up to a minute
     * later. A crash in between kept what the player had put in the vault in both places, or lost what
     * they had taken out. Both are now written in one transaction, under the player's session. A
     * player with no session here has nothing to write, and the page is written alone as before.
     */
    private @Nullable PlayerAtClose stateOf(Player player, VaultHolder holder) {
        PlayerSessionCoordinator sessions = this.sessionCoordinator;
        if (sessions == null) {
            return null;
        }
        ActiveSession session = sessions.getActiveSession(player.getUniqueId());
        if (session == null || !session.activeProfileId().equals(holder.profileId())) {
            return null;
        }
        return new PlayerAtClose(
                session,
                sessions.nodeId(),
                BukkitInventorySerializer.snapshotPlayer(
                        player, session.activeProfileId(), session.lastDurableVersion()));
    }

    private IslandRole roleOf(Island island, ProfileId profileId) {
        IslandMember member = island.members().get(profileId);
        return member != null ? member.role() : IslandRole.VISITOR;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
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
    private void settleTheRefusedCommit(Player player, VaultHolder holder, ItemStack[] atClose) {
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
