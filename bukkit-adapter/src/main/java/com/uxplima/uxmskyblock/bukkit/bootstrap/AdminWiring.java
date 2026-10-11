package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitIslandArchivalAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitIslandRecycleAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitPlayerActivityProvider;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseDisasterBackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Handles administrative domain services, backup/restore snapshots, inactivity scans,
 * island recycling/voiding, and anti-abuse subsystems.
 */
public final class AdminWiring {

    private final IslandAdminFreezeService freezeService;
    private final IslandInactivityService inactivityService;
    private final FoliaIslandVoidingAdapter voidingAdapter;
    private final WorldDimensionSnapshotPort worldDimensionSnapshotPort;
    private final NbtIslandBackupAdapter islandBackupAdapter;
    private final IslandRecycleService recycleService;
    private final @Nullable IslandResetConfirmationMenu resetConfirmationMenu;
    private final ObjectStoragePort objectStoragePort;
    private final BackupService backupService;
    private final IslandRestoreService islandRestoreService;
    private final IslandBackupService islandBackupService;
    private final DatabaseDisasterBackupService databaseBackupService;
    private final IslandAntiAbuseService antiAbuseService;
    private final @Nullable IslandAntiAbuseListener antiAbuseListener;

    public AdminWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            IslandCacheEviction cacheEviction,
            ObjectStoragePort objectStorage) {
        this.freezeService = Objects.requireNonNull(freezeService, "freezeService must not be null");
        this.voidingAdapter = new FoliaIslandVoidingAdapter(scheduler, backpressureController);

        BukkitIslandArchivalAdapter archivalAdapter =
                new BukkitIslandArchivalAdapter(protectionListener, visitorEvictionAdapter);
        this.recycleAdapter = new BukkitIslandRecycleAdapter(
                persistence.islandStoragePort(),
                persistence.worldGridAllocationPort(),
                persistence.spiralSlotPoolPort(),
                voidingAdapter,
                protectionListener,
                visitorEvictionAdapter);

        BukkitPlayerActivityProvider activityProvider = new BukkitPlayerActivityProvider();
        this.inactivityService = new IslandInactivityService(
                persistence.islandStoragePort(),
                activityProvider,
                config.inactivityConfig().toPolicy(),
                archivalAdapter,
                recycleAdapter,
                persistence.outboxPort(),
                // The same lock the other island writers hold, or it locks nothing.
                persistence.islandMutationLock());

        WorldDimensionSnapshotAdapter snapshots = new WorldDimensionSnapshotAdapter(
                plugin,
                persistence.islandStoragePort(),
                scheduler,
                config.dimensionConfig(),
                // Putting an island back writes as many blocks as clearing one, so it is paced by
                // the same number the operator set for clearing.
                backpressureController);
        java.time.Duration captureTimeout = captureTimeoutOf(config.rootNode());
        snapshots.captureWithin(captureTimeout);
        this.worldDimensionSnapshotPort = snapshots;
        this.islandBackupAdapter = new NbtIslandBackupAdapter(
                plugin.getDataFolder(), this.worldDimensionSnapshotPort, persistence.gameModeHierarchyStoragePort());
        this.recycleService = new IslandRecycleService(
                persistence.islandStoragePort(),
                persistence.worldGridAllocationPort(),
                persistence.spiralSlotPoolPort(),
                voidingAdapter,
                islandBackupAdapter,
                persistence.outboxPort(),
                persistence.islandRecycleOperationPort(),
                java.time.Clock.systemUTC(),
                config.antiAbuseConfig().resetChallengeTtl(),
                cacheEviction);
        this.resetConfirmationMenu = new IslandResetConfirmationMenu(
                recycleService,
                persistence.islandStoragePort(),
                authority.sessionCoordinator(),
                scheduler,
                config.messages());

        this.objectStoragePort = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        // A mirror is published to destination by destination, so a backup one of them refused is
        // recorded as partial rather than as done or as lost.
        this.backupService = new BackupService(
                persistence.backupCatalogPort(),
                com.uxplima.uxmskyblock.core.application.storage.MirroredObjectStorage.destinationsOf(
                        this.objectStoragePort));
        this.islandRestoreService = new IslandRestoreService(
                persistence.backupCatalogPort(),
                this.objectStoragePort,
                persistence.rootRelationalSnapshotPort(),
                this.worldDimensionSnapshotPort);
        this.islandRestoreService.quarantineWith(this.freezeService);
        this.islandRestoreService.recordProgressIn(persistence.restoreProgressPort());
        this.islandBackupService = new IslandBackupService(
                this.backupService,
                persistence.rootRelationalSnapshotPort(),
                this.worldDimensionSnapshotPort,
                pluginVersionOf(plugin));
        // The island holds still while it is read, for as long as the capture may take and no longer.
        com.uxplima.uxmskyblock.core.application.backup.CaptureQuiesce quiesce =
                new com.uxplima.uxmskyblock.core.application.backup.CaptureQuiesce();
        this.islandBackupService.quiesceWith(quiesce, captureTimeout);
        protectionListener.setCaptureQuiesce(quiesce);

        // The second kind of backup the persistence specification publishes. The port, its adapter
        // and the word in the catalog table were all written and no command drove any of them.
        this.databaseBackupService = new DatabaseDisasterBackupService(
                this.backupService, persistence.databaseBackupPort(), pluginVersionOf(plugin));

        this.antiAbuseService = new IslandAntiAbuseService(
                persistence.antiAbuseStoragePort(),
                config.antiAbuseConfig().purgeInventoryOnReset(),
                config.antiAbuseConfig().quarantineDuration(),
                config.antiAbuseConfig().resetCooldown(),
                config.antiAbuseConfig().maxResetsPerDay(),
                config.antiAbuseConfig().resetWindowDuration(),
                config.antiAbuseConfig().coopJoinCooldown(),
                config.antiAbuseConfig().quarantineLookupTtl(),
                java.time.Clock.systemUTC());
        // A reset that finished while its player was away still empties their inventory, when they
        // next play, if the operator asked for that.
        authority
                .sessionCoordinator()
                .whenSessionActive(new com.uxplima.uxmskyblock.bukkit.antiabuse.ResetInventoryPurge(
                        this.antiAbuseService, scheduler)::onSessionActive);
        this.antiAbuseListener = config.moduleSettings().isModuleEnabled("anti-abuse")
                // The same index the protection listener uses, so an island one of them has seen is
                // an island the other has too, and so a miss is filled off the thread rather than
                // where the touch arrived.
                ? new IslandAntiAbuseListener(
                        persistence.islandStoragePort(),
                        this.antiAbuseService,
                        config.antiAbuseConfig(),
                        protectionListener.spatialIndex(),
                        config.messages())
                : null;
    }

    public IslandAdminFreezeService freezeService() {
        return freezeService;
    }

    public IslandInactivityService inactivityService() {
        return inactivityService;
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return voidingAdapter;
    }

    public WorldDimensionSnapshotPort worldDimensionSnapshotPort() {
        return worldDimensionSnapshotPort;
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return islandBackupAdapter;
    }

    private final BukkitIslandRecycleAdapter recycleAdapter;

    /** Clears an island, on every path that removes one, over every square it wrote in. */
    public void useWipeAreas(com.uxplima.uxmskyblock.core.application.recycle.IslandWipeAreas areas) {
        recycleService.useWipeAreas(areas);
        recycleAdapter.useWipeAreas(areas);
    }

    public IslandRecycleService recycleService() {
        return recycleService;
    }

    public @Nullable IslandResetConfirmationMenu resetConfirmationMenu() {
        return resetConfirmationMenu;
    }

    public ObjectStoragePort objectStoragePort() {
        return objectStoragePort;
    }

    /**
     * The plugin's own version, for the provenance field of a backup manifest.
     *
     * <p>A server that does not publish its plugin metadata still takes backups. The version is
     * written down so a reader knows what made the file, and a file that cannot say is better than
     * no file at all.
     */
    private static String pluginVersionOf(org.bukkit.plugin.Plugin plugin) {
        try {
            io.papermc.paper.plugin.configuration.PluginMeta meta = plugin.getPluginMeta();
            String version = meta.getVersion();
            return version.isBlank() ? "unknown" : version;
        } catch (RuntimeException | LinkageError unavailable) {
            return "unknown";
        }
    }

    /** The thing that makes the backups the restore command puts back. */
    public IslandBackupService islandBackupService() {
        return islandBackupService;
    }

    public BackupService backupService() {
        return backupService;
    }

    /** What takes the whole database out, for the day the database is gone. */
    public DatabaseDisasterBackupService databaseBackupService() {
        return databaseBackupService;
    }

    public IslandRestoreService islandRestoreService() {
        return islandRestoreService;
    }

    public IslandAntiAbuseService antiAbuseService() {
        return antiAbuseService;
    }

    public @Nullable IslandAntiAbuseListener antiAbuseListener() {
        return antiAbuseListener;
    }

    /**
     * {@code storage.capture-timeout}: how long a backup waits for every region to hand its chunks
     * over. A value that does not read as a positive duration keeps the default, and says so.
     */
    static java.time.Duration captureTimeoutOf(
            org.spongepowered.configurate.@org.jspecify.annotations.Nullable ConfigurationNode rootNode) {
        String raw = rootNode == null
                ? null
                : rootNode.node("storage", "capture-timeout").getString();
        if (raw == null || raw.isBlank()) {
            return WorldDimensionSnapshotAdapter.DEFAULT_CAPTURE_TIMEOUT;
        }
        try {
            java.time.Duration parsed = com.uxplima.uxmlib.common.Durations.parse(raw.strip());
            if (!parsed.isNegative() && !parsed.isZero()) {
                return parsed;
            }
        } catch (IllegalArgumentException unreadable) {
            // Reported below with the value that was written.
        }
        java.util.logging.Logger.getLogger(AdminWiring.class.getName())
                .warning(() -> "storage.capture-timeout '" + raw + "' is not a positive duration, so "
                        + WorldDimensionSnapshotAdapter.DEFAULT_CAPTURE_TIMEOUT + " is used.");
        return WorldDimensionSnapshotAdapter.DEFAULT_CAPTURE_TIMEOUT;
    }
}
