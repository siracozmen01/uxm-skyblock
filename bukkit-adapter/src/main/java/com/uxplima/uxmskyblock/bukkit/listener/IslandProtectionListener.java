package com.uxplima.uxmskyblock.bukkit.listener;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import org.jspecify.annotations.Nullable;

/**
 * Inbound Bukkit listener enforcing island protection boundaries, permissions, and environmental flags.
 */
public final class IslandProtectionListener implements Listener {

    private final IslandStoragePort islandStoragePort;
    private final IslandAccessService accessService;
    private final @Nullable IslandAllianceService allianceService;
    private final @Nullable TemporaryAccessService temporaryAccessService;
    private volatile @Nullable IslandAdminFreezeService freezeService;
    private final SpatialIslandIndex spatialIndex;
    private final Map<PlayerUuid, ProfileId> activeProfiles = new ConcurrentHashMap<>();
    /**
     * Which island a profile belongs to, remembered rather than asked for.
     *
     * <p>The friendly fire check runs on every hit of every fight, and it used to ask the database
     * twice each time, on the thread the damage event is delivered on. An empty answer is cached as
     * well, because a player with no island is asked about just as often as one with an island.
     */
    private final Map<ProfileId, Optional<IslandId>> islandByProfile = new ConcurrentHashMap<>();

    private final Messages messages;

    private volatile @Nullable Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier;
    private volatile @Nullable Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider;
    private volatile @Nullable Function<ProfileId, ProfileType> profileTypeProvider;

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService,
            @Nullable IslandAdminFreezeService freezeService,
            @Nullable SpatialIslandIndex spatialIndex,
            Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.allianceService = allianceService;
        this.temporaryAccessService = temporaryAccessService;
        this.freezeService = freezeService;
        this.spatialIndex = spatialIndex != null ? spatialIndex : new SpatialIslandIndex(islandStoragePort, null);
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService,
            @Nullable IslandAdminFreezeService freezeService) {
        this(
                islandStoragePort,
                accessService,
                allianceService,
                temporaryAccessService,
                freezeService,
                null,
                Messages.bundled());
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService) {
        this(islandStoragePort, accessService, allianceService, temporaryAccessService, null, null, Messages.bundled());
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService) {
        this(islandStoragePort, accessService, allianceService, null, null, null, Messages.bundled());
    }

    public IslandProtectionListener(IslandStoragePort islandStoragePort, IslandAccessService accessService) {
        this(islandStoragePort, accessService, null, null, null, null, Messages.bundled());
    }

    public void setFreezeService(@Nullable IslandAdminFreezeService freezeService) {
        this.freezeService = freezeService;
    }

    private volatile com.uxplima.uxmskyblock.core.application.backup.@Nullable CaptureQuiesce captureQuiesce;

    /** The islands a backup is reading, which nobody changes until it has. */
    public void setCaptureQuiesce(com.uxplima.uxmskyblock.core.application.backup.@Nullable CaptureQuiesce quiesce) {
        this.captureQuiesce = quiesce;
    }

    private boolean isStaffInspector(Player player) {
        return player.isOp()
                || player.hasPermission("uxmskyblock.admin.bypass")
                || player.hasPermission("uxmskyblock.admin.inspect")
                || player.hasPermission("uxmskyblock.admin.freeze");
    }

    private boolean isIslandFrozen(Island island) {
        if (freezeService != null) {
            return freezeService.isFrozen(island.id()) || island.isFrozen();
        }
        return island.isFrozen();
    }

    public IslandStoragePort islandStoragePort() {
        return islandStoragePort;
    }

    public SpatialIslandIndex spatialIndex() {
        return spatialIndex;
    }

    public void setActiveProfile(PlayerUuid playerUuid, ProfileId profileId) {
        activeProfiles.put(playerUuid, profileId);
    }

    public void removeActiveProfile(PlayerUuid playerUuid) {
        ProfileId profileId = activeProfiles.remove(playerUuid);
        if (profileId != null) {
            islandByProfile.remove(profileId);
        }
    }

    /**
     * The island a profile belongs to, from memory after the first answer. Package private so the
     * guard against the database call returning to this path can ask it directly.
     */
    Optional<IslandId> islandOf(ProfileId profileId) {
        return islandByProfile.computeIfAbsent(profileId, islandStoragePort::findIslandIdByProfileId);
    }

    public void cacheIsland(Island island) {
        if (org.bukkit.Bukkit.getServer() != null
                && !org.bukkit.Bukkit.getWorlds().isEmpty()) {
            for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                spatialIndex.indexIsland(island, world.getName());
            }
        } else {
            spatialIndex.indexIsland(island, "world");
            spatialIndex.indexIsland(island, "skyblock_world");
        }
    }

    public void cacheIsland(Island island, String worldName) {
        spatialIndex.indexIsland(island, worldName);
    }

    public void invalidateIsland(IslandId islandId) {
        spatialIndex.removeIsland(islandId);
        islandByProfile
                .values()
                .removeIf(cached -> cached.isPresent() && cached.get().equals(islandId));
    }

    /**
     * Loads all persisted islands located in the target world on startup to initialize
     * the in-memory spatial protection cache.
     *
     * @param worldName target world identifier
     */
    public void loadPersistedIslands(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        spatialIndex.warmFromStorage(worldName);
    }

    public Optional<Island> findIslandAt(Location location) {
        return spatialIndex.findIslandAt(location);
    }

    /** The island in {@code worldName} whose land holds the block column at {@code x}, {@code z}. */
    public Optional<Island> findIslandAt(String worldName, int x, int z) {
        return spatialIndex.findIslandAt(worldName, x, z);
    }

    public void setNodeIdentitySupplier(Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier) {
        this.nodeIdentitySupplier = nodeIdentitySupplier;
    }

    public void setSessionRecordProvider(Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider) {
        this.sessionRecordProvider = sessionRecordProvider;
    }

    public void setProfileTypeProvider(Function<ProfileId, ProfileType> profileTypeProvider) {
        this.profileTypeProvider = profileTypeProvider;
    }

    private boolean hasTemporaryAccess(
            PlayerUuid playerUuid, ProfileId profileId, IslandId islandId, PermissionKey permission) {
        if (temporaryAccessService == null) {
            return false;
        }
        CurrentNodeProcessIdentity identity = nodeIdentitySupplier != null
                ? nodeIdentitySupplier.get()
                : new CurrentNodeProcessIdentity("unknown", "default");
        ProfileType type = profileTypeProvider != null ? profileTypeProvider.apply(profileId) : ProfileType.CLASSIC;
        PlayerSessionRecord sessionRecord = sessionRecordProvider != null && playerUuid != null
                ? sessionRecordProvider.apply(playerUuid).orElse(null)
                : null;

        return temporaryAccessService.hasAccess(
                "ISLAND",
                islandId.value().toString(),
                profileId,
                permission,
                Instant.now(),
                identity,
                sessionRecord,
                type);
    }

    /**
     * What this listener says about a player doing one thing at one place.
     *
     * <p>Four handlers asked the same four questions in the same order and wrote the answer out
     * four times: is there an island here, is it frozen, does the role allow it, and is there a
     * temporary grant that does. One place to ask them is one place to fix them, and the rules
     * that come after this one ask the same four.
     *
     * <p>The grant is looked up under the key the permission registry publishes for that
     * permission. The interact handler used to ask for {@code uxm:interact}, which is not a key an
     * operator's trust list can hold: the list names {@code uxm:interact.natural}. A player trusted
     * to interact could not, and nothing said so.
     */
    public Verdict mayDoHere(Player player, Location location, IslandPermission permission) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return Verdict.ALLOWED;
        }
        Optional<Island> optIsland = findIslandAt(location);
        if (optIsland.isEmpty()) {
            return Verdict.ALLOWED;
        }
        Island island = optIsland.get();
        if (isIslandFrozen(island)) {
            return isStaffInspector(player) ? Verdict.ALLOWED : Verdict.FROZEN;
        }
        com.uxplima.uxmskyblock.core.application.backup.CaptureQuiesce quiesce = this.captureQuiesce;
        if (quiesce != null && quiesce.isQuiesced(island.id())) {
            return Verdict.BACKING_UP;
        }
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = activeProfiles.get(playerUuid);
        if (profileId == null) {
            return Verdict.REFUSED;
        }
        if (accessService.checkPermission(island, profileId, permission)) {
            return Verdict.ALLOWED;
        }
        PermissionKey key = StandardPermissions.fromIslandPermission(permission);
        if (key != null && hasTemporaryAccess(playerUuid, profileId, island.id(), key)) {
            return Verdict.ALLOWED;
        }
        return Verdict.REFUSED;
    }

    /** The three answers a rule here can give. */
    public enum Verdict {
        /** Nothing here refuses it. */
        ALLOWED,
        /** The island is under an administrative freeze. */
        FROZEN,
        /** A backup is reading the island, for as long as its capture takes and no longer. */
        BACKING_UP,
        /** The role, and any grant standing in for it, do not allow it. */
        REFUSED;

        /** The line a player refused this way is told, where {@code refusalKey} is the one the rule names. */
        public String messageKey(String refusalKey) {
            return switch (this) {
                case FROZEN -> "protection.frozen";
                case BACKING_UP -> "protection.backing_up";
                case ALLOWED, REFUSED -> refusalKey;
            };
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        refuseUnlessAllowed(
                event,
                event.getPlayer(),
                event.getBlock().getLocation(),
                IslandPermission.BLOCK_BREAK,
                "protection.block_break_denied");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        refuseUnlessAllowed(
                event,
                event.getPlayer(),
                event.getBlock().getLocation(),
                IslandPermission.BLOCK_PLACE,
                "protection.block_place_denied");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.hasBlock() || event.getClickedBlock() == null) {
            return;
        }
        refuseUnlessAllowed(
                event,
                event.getPlayer(),
                event.getClickedBlock().getLocation(),
                IslandPermission.NATURAL_INTERACT,
                "protection.interact_denied");
    }

    /** Cancels {@code event} and says why, unless this listener allows it here. */
    private void refuseUnlessAllowed(
            Cancellable event, Player player, Location location, IslandPermission permission, String refusalKey) {
        Verdict verdict = mayDoHere(player, location, permission);
        if (verdict == Verdict.ALLOWED) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(messages.render(player, verdict.messageKey(refusalKey)));
    }

    /**
     * The container a player just opened, against the permission their role holds.
     *
     * <p>The role editor publishes six container permissions and nothing ever read one, so a role
     * saying a member may not open a chest let them open every chest on the island. The interact
     * rule is not the same rule: it says whether a player may touch anything here at all, and a
     * member who may build is past it long before they reach a chest.
     *
     * <p>An inventory held by no block is the player's own, or a window this plugin drew. Neither
     * belongs to an island and neither is checked here.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Location location = blockLocationOf(event.getInventory());
        if (location == null) {
            return;
        }
        IslandPermission required = containerPermissionOf(event.getInventory().getType());
        if (required == null) {
            return;
        }
        if (!standsThere(event.getInventory().getType(), location.getBlock())) {
            // A window drawn where the player stands, such as the anvil a menu asks a name in:
            // Paper gives it the player's place, but no anvil or chest is there to protect.
            return;
        }
        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }

        refuseUnlessAllowed(event, player, location, required, "protection.chest_access_denied");
    }

    /**
     * Where the block holding this inventory stands, or null when no block holds it.
     *
     * <p>The holder answers this and the inventory's own location does not: a window this plugin
     * draws is an inventory like any other, and asking it where it is gets an answer that means
     * nothing. A block holder is a container in the world and nothing else is.
     */
    private static @Nullable Location blockLocationOf(org.bukkit.inventory.Inventory inventory) {
        return switch (inventory.getHolder()) {
            case org.bukkit.inventory.BlockInventoryHolder block ->
                block.getBlock().getLocation();
            case org.bukkit.block.DoubleChest chest -> chest.getLocation();
            case null, default -> null;
        };
    }

    /**
     * Whether the block at an inventory's place is the block that holds that inventory.
     *
     * <p>A menu that asks a player to type something opens an anvil window, and Paper gives that
     * window the place the player stands. Read as a real anvil there, it was refused wherever the
     * player's role could not use an anvil, and on another island or at spawn setting a home or
     * inviting a member from the menu said storage access was denied.
     */
    private static boolean standsThere(InventoryType type, org.bukkit.block.Block block) {
        org.bukkit.Material material = block.getType();
        return switch (type) {
            case ANVIL -> org.bukkit.Tag.ANVIL.isTagged(material);
            case SMITHING -> material == org.bukkit.Material.SMITHING_TABLE;
            case BEACON -> material == org.bukkit.Material.BEACON;
            default -> block.getState() instanceof org.bukkit.inventory.InventoryHolder;
        };
    }

    /**
     * Which permission an inventory type asks for, or null when it asks for none of ours.
     *
     * <p>A container the permission list does not name by itself is a chest as far as this rule
     * goes: an operator who takes container access off a role means every container, and a hopper
     * holding the same items as the chest beside it is not an exception they intended.
     */
    private static @Nullable IslandPermission containerPermissionOf(InventoryType type) {
        return switch (type) {
            case FURNACE, BLAST_FURNACE, SMOKER -> IslandPermission.FURNACE_USE;
            case SHULKER_BOX -> IslandPermission.SHULKER_OPEN;
            case BARREL -> IslandPermission.BARREL_OPEN;
            case ANVIL, SMITHING -> IslandPermission.ANVIL_USE;
            case BEACON -> IslandPermission.BEACON_MODIFY;
            // An ender chest is not on this list. What it shows is the player's own, wherever
            // they open it, so an island has nothing to protect there.
            case CHEST, DISPENSER, DROPPER, HOPPER, BREWING, CHISELED_BOOKSHELF -> IslandPermission.CHEST_OPEN;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player damager) {
            if (damager.hasPermission("uxmskyblock.admin.bypass")) {
                return;
            }
            findIslandAt(event.getEntity().getLocation()).ifPresent(island -> {
                if (isIslandFrozen(island)) {
                    if (!isStaffInspector(damager)) {
                        event.setCancelled(true);
                        damager.sendMessage(messages.render(damager, "protection.frozen"));
                    }
                    return;
                }
                if (!island.flags().isEnabled(IslandFlags.PVP)) {
                    event.setCancelled(true);
                    damager.sendMessage(messages.render(damager, "protection.pvp_denied"));
                    return;
                }
                if (allianceService != null && allianceService.isFriendlyFireShieldingEnabled()) {
                    ProfileId damagerProfile = activeProfiles.get(new PlayerUuid(damager.getUniqueId()));
                    ProfileId victimProfile = activeProfiles.get(new PlayerUuid(victim.getUniqueId()));
                    if (damagerProfile != null && victimProfile != null) {
                        Optional<IslandId> damagerIsland = islandOf(damagerProfile);
                        Optional<IslandId> victimIsland = islandOf(victimProfile);
                        if (damagerIsland.isPresent()
                                && victimIsland.isPresent()
                                && allianceService.areAllied(damagerIsland.get(), victimIsland.get())) {
                            event.setCancelled(true);
                        }
                    }
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (isStaffInspector(player)) {
            return;
        }
        Location loc = player.getLocation();
        if (loc == null) {
            return;
        }
        findIslandAt(loc).ifPresent(island -> {
            if (isIslandFrozen(island)) {
                event.setCancelled(true);
                player.sendMessage(messages.render(player, "protection.frozen"));
            }
        });
    }
}
