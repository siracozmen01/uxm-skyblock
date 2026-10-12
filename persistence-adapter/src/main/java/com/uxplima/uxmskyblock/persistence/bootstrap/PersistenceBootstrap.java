package com.uxplima.uxmskyblock.persistence.bootstrap;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort;
import com.uxplima.uxmskyblock.core.application.antiabuse.AntiAbuseStoragePort;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseBackupPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionStoragePort;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.application.event.ConsumerInboxPort;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezePort;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyStoragePort;
import com.uxplima.uxmskyblock.core.application.home.HomeStoragePort;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.notification.NotificationStoragePort;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardStoragePort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialStoragePort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.access.SqlTemporaryAccessAdapter;
import com.uxplima.uxmskyblock.persistence.activity.SqlActivityFeedAdapter;
import com.uxplima.uxmskyblock.persistence.alliance.PlayerIslandAllianceAdapter;
import com.uxplima.uxmskyblock.persistence.antiabuse.SqlAntiAbuseStorageAdapter;
import com.uxplima.uxmskyblock.persistence.backup.PlayerBackupCatalogAdapter;
import com.uxplima.uxmskyblock.persistence.backup.SqlDatabaseBackupAdapter;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.dimension.PlayerIslandDimensionAdapter;
import com.uxplima.uxmskyblock.persistence.economy.PlayerEconomySagaAdapter;
import com.uxplima.uxmskyblock.persistence.event.ConsumerInboxAdapter;
import com.uxplima.uxmskyblock.persistence.event.TransactionalOutboxAdapter;
import com.uxplima.uxmskyblock.persistence.gamemode.SqlGameModeHierarchyAdapter;
import com.uxplima.uxmskyblock.persistence.home.SqlHomeStorageAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerInventoryMutationJournalAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileHandoffFinalizationAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.leaderboard.PlayerIslandLeaderboardAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.notification.SqlNotificationAdapter;
import com.uxplima.uxmskyblock.persistence.profile.PlayerProfileSwitchAdapter;
import com.uxplima.uxmskyblock.persistence.reward.SqlRewardStorageAdapter;
import com.uxplima.uxmskyblock.persistence.season.PlayerIslandSeasonAdapter;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.snapshot.SqlRootRelationalSnapshotAdapter;
import com.uxplima.uxmskyblock.persistence.social.PlayerIslandSocialAdapter;
import com.uxplima.uxmskyblock.persistence.upgrade.CachingIslandUpgradeStorage;
import com.uxplima.uxmskyblock.persistence.upgrade.PlayerIslandUpgradeAdapter;
import com.uxplima.uxmskyblock.persistence.vault.SqlIslandVaultStorageAdapter;
import com.uxplima.uxmskyblock.persistence.warp.SqlIslandWarpStorageAdapter;
import com.uxplima.uxmskyblock.persistence.world.PlayerWorldGridAllocationAdapter;
import com.uxplima.uxmskyblock.persistence.world.SqlSpiralSlotPoolAdapter;

/**
 * Encapsulated persistence composition root establishing database migrations and
 * instantiating all production outbound persistence adapters.
 */
public final class PersistenceBootstrap implements AutoCloseable {

    /**
     * How long a remembered upgrade tier stays good.
     *
     * <p>Every write goes through the cache, so a purchase on this node is visible at once and this
     * only bounds how long it takes to notice one made on another node. An upgrade is bought once
     * and read on every block placed, so a minute is generous in the direction that matters.
     */
    private static final Duration UPGRADE_TIER_CACHE_TTL = Duration.ofMinutes(1);

    private final Database database;
    private final PlayerIslandStorageAdapter islandStorageAdapter;
    private final IslandMutationLock islandMutationLock = new IslandMutationLock();
    private final PlayerIslandBankAdapter islandBankAdapter;
    private final CachingIslandUpgradeStorage islandUpgradeAdapter;
    private final PlayerIslandLeaderboardAdapter islandLeaderboardAdapter;
    private final PlayerBackupCatalogAdapter backupCatalogAdapter;
    private final TransactionalOutboxAdapter outboxAdapter;
    private final ConsumerInboxAdapter consumerInboxAdapter;
    private final PlayerSessionAuthorityAdapter sessionAuthorityAdapter;
    private final PlayerProfileInventoryAdapter inventoryAdapter;
    private final com.uxplima.uxmskyblock.persistence.inventory.SqlCarriedStateAdapter carriedStateAdapter;
    private final PlayerInventoryMutationJournalAdapter mutationJournalAdapter;
    private final com.uxplima.uxmskyblock.persistence.inventory.PlayerTradeJournalAdapter tradeJournalAdapter;
    private final PlayerProfileHandoffFinalizationAdapter handoffFinalizationAdapter;
    private final PlayerProfileSwitchAdapter profileSwitchAdapter;
    private final com.uxplima.uxmskyblock.persistence.profile.SqlProfileTypeAdapter profileTypeAdapter;
    private final SqlSpiralSlotPoolAdapter spiralSlotPoolAdapter;
    private PlayerWorldGridAllocationAdapter worldGridAllocationAdapter;
    private final PlayerEconomySagaAdapter economySagaAdapter;
    private final PlayerIslandSeasonAdapter islandSeasonAdapter;
    private final PlayerIslandSocialAdapter islandSocialAdapter;
    private final PlayerIslandAllianceAdapter islandAllianceAdapter;
    private final SqlTemporaryAccessAdapter temporaryAccessAdapter;
    private final SqlRewardStorageAdapter rewardStorageAdapter;
    private final SqlIslandWarpStorageAdapter islandWarpStorageAdapter;
    private final SqlIslandVaultStorageAdapter islandVaultStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.mission.PlayerIslandMissionAdapter islandMissionAdapter;
    private final SqlAntiAbuseStorageAdapter antiAbuseStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.booster.SqlIslandBoosterStorageAdapter
            islandBoosterStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.bank.SqlIslandBankruptcyStorageAdapter
            islandBankruptcyStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.name.SqlIslandNameStorageAdapter islandNameStorageAdapter;
    private final SqlGameModeHierarchyAdapter gameModeHierarchyAdapter;
    private final SqlDatabaseBackupAdapter databaseBackupAdapter;
    private final SqlRootRelationalSnapshotAdapter rootRelationalSnapshotAdapter;
    private final com.uxplima.uxmskyblock.persistence.snapshot.SqlRestoreProgressAdapter restoreProgressAdapter;
    private final SqlHomeStorageAdapter homeStorageAdapter;
    private final SqlActivityFeedAdapter activityFeedAdapter;
    private final com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockProgressAdapter oneBlockProgressAdapter;
    private final com.uxplima.uxmskyblock.persistence.lifecycle.SqlLifecycleOwedEffectsAdapter
            lifecycleOwedEffectsAdapter;
    private final SqlNotificationAdapter notificationAdapter;
    private final com.uxplima.uxmskyblock.persistence.network.SqlClusterNodesAdapter clusterNodesAdapter;
    private final com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkTerritoryAdapter chunkTerritoryAdapter;
    private final com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkPaymentAdapter chunkPaymentAdapter;
    private final com.uxplima.uxmskyblock.persistence.boundary.SqlIslandEdgeAdapter islandEdgeAdapter;
    private final com.uxplima.uxmskyblock.persistence.acid.SqlAcidIslandsAdapter acidIslandsAdapter;
    private final com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedIslandsAdapter boxedIslandsAdapter;
    private final com.uxplima.uxmskyblock.persistence.poseidon.SqlPoseidonIslandsAdapter poseidonIslandsAdapter;
    private final com.uxplima.uxmskyblock.persistence.stranger.SqlStrangerRealmsAdapter strangerRealmsAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourAdapter parkourAdapter;
    private final com.uxplima.uxmskyblock.persistence.leaderboard.SqlModeStandingsAdapter modeStandingsAdapter;
    private final com.uxplima.uxmskyblock.persistence.brix.SqlBrixPlotsAdapter brixPlotsAdapter;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter vesselsAdapter;
    private final com.uxplima.uxmskyblock.persistence.inventory.VesselCargoJournalAdapter cargoJournal;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyagesAdapter voyages;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketOrdersAdapter marketOrders;
    private final com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter rootAuthority;
    private final PlayerIslandDimensionAdapter islandDimensionAdapter;
    private final com.uxplima.uxmskyblock.persistence.cosmetic.SqlProfileCosmeticStorageAdapter
            profileCosmeticStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.recycle.SqlIslandRecycleStorageAdapter
            islandRecycleStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.recycle.SqlIslandFootprintAdapter islandFootprintAdapter;

    public PersistenceBootstrap(Database database) {
        this.database = Objects.requireNonNull(database, "database");

        // Run migrations up to LATEST_VERSION
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        this.islandStorageAdapter = new PlayerIslandStorageAdapter(database);
        this.islandBankAdapter = new PlayerIslandBankAdapter(database);
        // A block limit is read on every placement, and it reads an upgrade tier. Reading that
        // tier from the database each time was a query per block placed, on the event thread.
        this.islandUpgradeAdapter = new CachingIslandUpgradeStorage(
                new PlayerIslandUpgradeAdapter(database), UPGRADE_TIER_CACHE_TTL, Clock.systemUTC());
        this.islandDimensionAdapter = new PlayerIslandDimensionAdapter(database);
        this.islandLeaderboardAdapter = new PlayerIslandLeaderboardAdapter(database);
        this.backupCatalogAdapter = new PlayerBackupCatalogAdapter(database);
        this.outboxAdapter = new TransactionalOutboxAdapter(database);
        this.consumerInboxAdapter = new ConsumerInboxAdapter(database);
        this.sessionAuthorityAdapter = new PlayerSessionAuthorityAdapter(database);
        this.inventoryAdapter = new PlayerProfileInventoryAdapter(database);
        this.carriedStateAdapter = new com.uxplima.uxmskyblock.persistence.inventory.SqlCarriedStateAdapter(database);
        this.mutationJournalAdapter = new PlayerInventoryMutationJournalAdapter(database);
        this.tradeJournalAdapter =
                new com.uxplima.uxmskyblock.persistence.inventory.PlayerTradeJournalAdapter(database);
        this.handoffFinalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(database);
        this.profileSwitchAdapter = new PlayerProfileSwitchAdapter(database);
        this.profileTypeAdapter = new com.uxplima.uxmskyblock.persistence.profile.SqlProfileTypeAdapter(database);
        this.spiralSlotPoolAdapter = new SqlSpiralSlotPoolAdapter(database);
        this.worldGridAllocationAdapter = new PlayerWorldGridAllocationAdapter(database, spiralSlotPoolAdapter);
        this.economySagaAdapter = new PlayerEconomySagaAdapter(database);
        this.islandSeasonAdapter = new PlayerIslandSeasonAdapter(database);
        this.islandSocialAdapter = new PlayerIslandSocialAdapter(database);
        this.islandAllianceAdapter = new PlayerIslandAllianceAdapter(database);
        this.temporaryAccessAdapter = new SqlTemporaryAccessAdapter(database);
        this.rewardStorageAdapter = new SqlRewardStorageAdapter(database);
        this.islandWarpStorageAdapter = new SqlIslandWarpStorageAdapter(database);
        this.islandVaultStorageAdapter = new SqlIslandVaultStorageAdapter(database);
        this.islandMissionAdapter =
                new com.uxplima.uxmskyblock.persistence.mission.PlayerIslandMissionAdapter(database);
        this.antiAbuseStorageAdapter = new SqlAntiAbuseStorageAdapter(database);
        this.islandBoosterStorageAdapter =
                new com.uxplima.uxmskyblock.persistence.booster.SqlIslandBoosterStorageAdapter(database);
        this.islandBankruptcyStorageAdapter =
                new com.uxplima.uxmskyblock.persistence.bank.SqlIslandBankruptcyStorageAdapter(database);
        this.islandNameStorageAdapter =
                new com.uxplima.uxmskyblock.persistence.name.SqlIslandNameStorageAdapter(database.dataSource());
        this.gameModeHierarchyAdapter = new SqlGameModeHierarchyAdapter(database.dataSource());
        this.databaseBackupAdapter = new SqlDatabaseBackupAdapter(database);
        this.rootRelationalSnapshotAdapter = new SqlRootRelationalSnapshotAdapter(database.dataSource());
        this.restoreProgressAdapter =
                new com.uxplima.uxmskyblock.persistence.snapshot.SqlRestoreProgressAdapter(database.dataSource());
        this.homeStorageAdapter = new SqlHomeStorageAdapter(database.dataSource());
        this.activityFeedAdapter = new SqlActivityFeedAdapter(database.dataSource());
        this.oneBlockProgressAdapter =
                new com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockProgressAdapter(database.dataSource());
        this.lifecycleOwedEffectsAdapter =
                new com.uxplima.uxmskyblock.persistence.lifecycle.SqlLifecycleOwedEffectsAdapter(database.dataSource());
        this.notificationAdapter = new SqlNotificationAdapter(database.dataSource());
        this.clusterNodesAdapter = new com.uxplima.uxmskyblock.persistence.network.SqlClusterNodesAdapter(database);
        this.chunkTerritoryAdapter =
                new com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkTerritoryAdapter(database.dataSource());
        this.chunkPaymentAdapter = new com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkPaymentAdapter(database);
        this.islandEdgeAdapter = new com.uxplima.uxmskyblock.persistence.boundary.SqlIslandEdgeAdapter(database);
        this.acidIslandsAdapter =
                new com.uxplima.uxmskyblock.persistence.acid.SqlAcidIslandsAdapter(database.dataSource());
        this.boxedIslandsAdapter =
                new com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedIslandsAdapter(database.dataSource());
        this.poseidonIslandsAdapter =
                new com.uxplima.uxmskyblock.persistence.poseidon.SqlPoseidonIslandsAdapter(database.dataSource());
        this.strangerRealmsAdapter =
                new com.uxplima.uxmskyblock.persistence.stranger.SqlStrangerRealmsAdapter(database.dataSource());
        this.parkourAdapter = new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourAdapter(database.dataSource());
        this.modeStandingsAdapter =
                new com.uxplima.uxmskyblock.persistence.leaderboard.SqlModeStandingsAdapter(database.dataSource());
        this.brixPlotsAdapter = new com.uxplima.uxmskyblock.persistence.brix.SqlBrixPlotsAdapter(database.dataSource());
        this.vesselsAdapter =
                new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter(database.dataSource());
        this.cargoJournal = new com.uxplima.uxmskyblock.persistence.inventory.VesselCargoJournalAdapter(database);
        this.voyages = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyagesAdapter(database);
        this.marketOrders = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketOrdersAdapter(database);
        this.rootAuthority = new com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter(database);
        this.profileCosmeticStorageAdapter =
                new com.uxplima.uxmskyblock.persistence.cosmetic.SqlProfileCosmeticStorageAdapter(database);
        this.islandRecycleStorageAdapter =
                new com.uxplima.uxmskyblock.persistence.recycle.SqlIslandRecycleStorageAdapter(database);
        this.islandFootprintAdapter =
                new com.uxplima.uxmskyblock.persistence.recycle.SqlIslandFootprintAdapter(database);
    }

    /**
     * Reads whether the database would keep a commit through a power loss, and acts as {@code profile}
     * says.
     *
     * @throws com.uxplima.uxmskyblock.persistence.sql.FatalDurabilityConfigurationException when it
     *     would not and the profile is strict
     */
    public void enforceDurability(com.uxplima.uxmskyblock.persistence.sql.DurabilityCheck.Profile profile) {
        com.uxplima.uxmskyblock.persistence.sql.DurabilityCheck.enforce(database, profile);
    }

    public static PersistenceBootstrap createSqlite(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        // FULL, because the library opens SQLite at NORMAL, and NORMAL loses the last commits when the
        // machine loses power: a bank move or a vault page the player saw land can be gone again.
        Database db = Database.builder()
                .sqlite(databaseFile)
                .synchronous(com.uxplima.uxmlib.storage.sql.DatabaseBuilder.Synchronous.FULL)
                // Every table under an island cascades from it. SQLite ignores that unless the
                // connection asks, so a deleted island left its owner a member of nothing.
                .foreignKeys(true)
                .build();
        return new PersistenceBootstrap(db);
    }

    public static PersistenceBootstrap createSqliteInMemory() {
        Database db = Database.builder().sqliteInMemory().foreignKeys(true).build();
        return new PersistenceBootstrap(db);
    }

    public static PersistenceBootstrap createRemote(String jdbcUrl, String username, String password, int maxPoolSize) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        var unused = com.uxplima.uxmskyblock.persistence.sql.JdbcDrivers.register(jdbcUrl);
        com.uxplima.uxmlib.storage.sql.DatabaseBuilder builder =
                Database.builder().jdbcUrl(jdbcUrl);
        if (username != null && !username.isBlank()) {
            builder.username(username);
        }
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        if (maxPoolSize > 0) {
            builder.maxPoolSize(maxPoolSize);
        }
        return new PersistenceBootstrap(builder.build());
    }

    public IslandStoragePort islandStoragePort() {
        return islandStorageAdapter;
    }

    /**
     * The one lock every service that changes an island goes through.
     *
     * <p>It lives beside the storage port because it guards writes to that port, and it has to be
     * one instance: two services holding two locks is two services holding nothing.
     */
    public IslandMutationLock islandMutationLock() {
        return islandMutationLock;
    }

    public IslandAuthorityPort islandAuthorityPort() {
        return islandStorageAdapter;
    }

    public IslandAdminFreezePort islandAdminFreezePort() {
        return islandStorageAdapter;
    }

    public IslandBankPort islandBankPort() {
        return islandBankAdapter;
    }

    public IslandUpgradeStoragePort islandUpgradeStoragePort() {
        return islandUpgradeAdapter;
    }

    public IslandLeaderboardPort islandLeaderboardPort() {
        return islandLeaderboardAdapter;
    }

    public BackupCatalogPort backupCatalogPort() {
        return backupCatalogAdapter;
    }

    public OutboxPort outboxPort() {
        return outboxAdapter;
    }

    public ConsumerInboxPort consumerInboxPort() {
        return consumerInboxAdapter;
    }

    public PlayerSessionAuthorityPort sessionAuthorityPort() {
        return sessionAuthorityAdapter;
    }

    public ProfileInventoryCheckpointPort inventoryPort() {
        return inventoryAdapter;
    }

    /** What each profile put down in each group of worlds, and the group it carries for. */
    public com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort carriedStatePort() {
        return carriedStateAdapter;
    }

    public InventoryMutationJournalPort mutationJournalPort() {
        return mutationJournalAdapter;
    }

    /** The journal of trades between players, one operation over every side's inventory. */
    public com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort tradeJournalPort() {
        return tradeJournalAdapter;
    }

    public ProfileHandoffFinalizationPort handoffFinalizationPort() {
        return handoffFinalizationAdapter;
    }

    public ProfileSwitchPort profileSwitchPort() {
        return profileSwitchAdapter;
    }

    /** Which ruleset a profile plays under, which nothing read back before. */
    public com.uxplima.uxmskyblock.core.application.profile.ProfileTypePort profileTypePort() {
        return profileTypeAdapter;
    }

    public SpiralSlotPoolPort spiralSlotPoolPort() {
        return spiralSlotPoolAdapter;
    }

    public WorldGridAllocationPort worldGridAllocationPort() {
        return worldGridAllocationAdapter;
    }

    /**
     * Places islands {@code configured} blocks apart, unless islands already stand at another spacing.
     *
     * <p>Called once, before any port is handed out. A world whose islands stand at one spacing keeps
     * it whatever the file says now: a new island placed by another spacing lands inside an old one.
     * The answer is the spacing in force, and a caller that asked for another says so.
     */
    public int useGridSpacing(int configured) {
        int spacing = worldGridAllocationAdapter.spacingInUse().orElse(configured);
        this.worldGridAllocationAdapter = new PlayerWorldGridAllocationAdapter(
                database, new SpiralGridCoordinateAllocator(spacing), spiralSlotPoolAdapter);
        return spacing;
    }

    /** The arithmetic islands are placed by, the one {@link #worldGridAllocationPort()} uses. */
    public SpiralGridCoordinateAllocator gridAllocator() {
        return worldGridAllocationAdapter.allocator();
    }

    public EconomySagaPort economySagaPort() {
        return economySagaAdapter;
    }

    public IslandSeasonStoragePort islandSeasonStoragePort() {
        return islandSeasonAdapter;
    }

    public IslandSocialStoragePort islandSocialStoragePort() {
        return islandSocialAdapter;
    }

    public IslandAllianceStoragePort islandAllianceStoragePort() {
        return islandAllianceAdapter;
    }

    public TemporaryAccessStoragePort temporaryAccessStoragePort() {
        return temporaryAccessAdapter;
    }

    public RewardStoragePort rewardStoragePort() {
        return rewardStorageAdapter;
    }

    public IslandWarpStoragePort islandWarpStoragePort() {
        return islandWarpStorageAdapter;
    }

    public IslandVaultStoragePort islandVaultStoragePort() {
        return islandVaultStorageAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.mission.IslandMissionStoragePort islandMissionStoragePort() {
        return islandMissionAdapter;
    }

    public AntiAbuseStoragePort antiAbuseStoragePort() {
        return antiAbuseStorageAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.booster.IslandBoosterStoragePort islandBoosterStoragePort() {
        return islandBoosterStorageAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyStoragePort islandBankruptcyStoragePort() {
        return islandBankruptcyStorageAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.name.IslandNameStoragePort islandNameStoragePort() {
        return islandNameStorageAdapter;
    }

    public GameModeHierarchyStoragePort gameModeHierarchyStoragePort() {
        return gameModeHierarchyAdapter;
    }

    /** Whether the database hands out a connection that answers, which the doctor asks. */
    public boolean databaseAnswers() {
        try (java.sql.Connection connection = database.connection()) {
            return connection.isValid(2);
        } catch (java.sql.SQLException | RuntimeException refused) {
            return false;
        }
    }

    public DatabaseBackupPort databaseBackupPort() {
        return databaseBackupAdapter;
    }

    public RootRelationalSnapshotPort rootRelationalSnapshotPort() {
        return rootRelationalSnapshotAdapter;
    }

    public HomeStoragePort homeStoragePort() {
        return homeStorageAdapter;
    }

    public ActivityFeedStoragePort activityFeedStoragePort() {
        return activityFeedAdapter;
    }

    /** Which islands are Poseidon islands. */
    public com.uxplima.uxmskyblock.core.application.poseidon.PoseidonIslandsPort poseidonIslandsPort() {
        return poseidonIslandsAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort strangerRealmsPort() {
        return strangerRealmsAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.parkour.ParkourPort parkourPort() {
        return parkourAdapter;
    }

    /** Where the islands of each mode stand, for the boards. */
    public com.uxplima.uxmskyblock.core.application.leaderboard.ModeStandingsPort modeStandingsPort() {
        return modeStandingsAdapter;
    }

    /** Which islands are Brix plots. */
    public com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort brixPlotsPort() {
        return brixPlotsAdapter;
    }

    /** Which islands are TradeWinds vessels. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort vesselsPort() {
        return vesselsAdapter;
    }

    /** The journal of moves between a player's inventory and a vessel's cargo hold. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort cargoJournalPort() {
        return cargoJournal;
    }

    /** Where each TradeWinds vessel is bound. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.VoyagesPort voyagesPort() {
        return voyages;
    }

    /** The orders placed at the TradeWinds ports' markets. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort marketOrdersPort() {
        return marketOrders;
    }

    /** Who writes a root that is not an island, such as a vessel's hold. */
    public com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort rootAuthorityPort() {
        return rootAuthority;
    }

    /** Which islands are Boxed islands, and the advancements each has earned. */
    public com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort boxedIslandsPort() {
        return boxedIslandsAdapter;
    }

    /** Which islands are AcidIsland islands, and where their seas stand. */
    public com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort acidIslandsPort() {
        return acidIslandsAdapter;
    }

    /** The chunks each ChunkBlock island has open. */
    public com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort chunkTerritoryPort() {
        return chunkTerritoryAdapter;
    }

    /** What has been paid toward each closed chunk of a ChunkBlock island, and each island's chunk points. */
    public com.uxplima.uxmskyblock.core.application.chunkblock.ChunkPaymentPort chunkPaymentPort() {
        return chunkPaymentAdapter;
    }

    /** How each island chose to have its edge drawn. */
    public com.uxplima.uxmskyblock.core.application.boundary.IslandEdgePort islandEdgePort() {
        return islandEdgeAdapter;
    }

    /** Which nodes are alive, the world each serves and how loaded it is. */
    public com.uxplima.uxmskyblock.core.application.network.ClusterNodesPort clusterNodesPort() {
        return clusterNodesAdapter;
    }

    /** What lifecycle events still owe players who were away when they happened. */
    public com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleOwedEffectsPort lifecycleOwedEffectsPort() {
        return lifecycleOwedEffectsAdapter;
    }

    /** Where each OneBlock island's block stands and how often it has been broken. */
    public com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort oneBlockProgressPort() {
        return oneBlockProgressAdapter;
    }

    public NotificationStoragePort notificationStoragePort() {
        return notificationAdapter;
    }

    public IslandDimensionStoragePort islandDimensionStoragePort() {
        return islandDimensionAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.cosmetic.ProfileCosmeticStoragePort profileCosmeticStoragePort() {
        return profileCosmeticStorageAdapter;
    }

    /** Where a restore writes down each unit before and after it puts it back. */
    public com.uxplima.uxmskyblock.core.application.snapshot.RestoreProgressPort restoreProgressPort() {
        return restoreProgressAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleOperationPort islandRecycleOperationPort() {
        return islandRecycleStorageAdapter;
    }

    /** Where each island wrote blocks past its border, kept until it is cleared. */
    public com.uxplima.uxmskyblock.core.application.recycle.IslandFootprintPort islandFootprintPort() {
        return islandFootprintAdapter;
    }

    public void registerProfile(PlayerUuid playerUuid, ProfileId profileId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        try (java.sql.Connection conn = database.connection();
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, playerUuid.value().toString());
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to register profile", e);
        }
    }

    @Override
    public void close() {
        if (!database.isClosed()) {
            database.close();
        }
    }
}
