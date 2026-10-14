package com.uxplima.uxmskyblock.persistence.bootstrap;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort;
import com.uxplima.uxmskyblock.core.application.antiabuse.AntiAbuseStoragePort;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionStoragePort;
import com.uxplima.uxmskyblock.core.application.home.HomeStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.notification.NotificationStoragePort;
import com.uxplima.uxmskyblock.core.application.reward.RewardStoragePort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialStoragePort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpStoragePort;
import com.uxplima.uxmskyblock.persistence.access.SqlTemporaryAccessAdapter;
import com.uxplima.uxmskyblock.persistence.activity.SqlActivityFeedAdapter;
import com.uxplima.uxmskyblock.persistence.alliance.PlayerIslandAllianceAdapter;
import com.uxplima.uxmskyblock.persistence.antiabuse.SqlAntiAbuseStorageAdapter;
import com.uxplima.uxmskyblock.persistence.dimension.PlayerIslandDimensionAdapter;
import com.uxplima.uxmskyblock.persistence.home.SqlHomeStorageAdapter;
import com.uxplima.uxmskyblock.persistence.leaderboard.PlayerIslandLeaderboardAdapter;
import com.uxplima.uxmskyblock.persistence.notification.SqlNotificationAdapter;
import com.uxplima.uxmskyblock.persistence.reward.SqlRewardStorageAdapter;
import com.uxplima.uxmskyblock.persistence.season.PlayerIslandSeasonAdapter;
import com.uxplima.uxmskyblock.persistence.social.PlayerIslandSocialAdapter;
import com.uxplima.uxmskyblock.persistence.vault.SqlIslandVaultStorageAdapter;
import com.uxplima.uxmskyblock.persistence.warp.SqlIslandWarpStorageAdapter;

/**
 * The adapters of the island's features that stand on their own tables: seasons, the social side, alliances, temporary
 * access, rewards, warps, the vault, missions, anti abuse, boosters, bankruptcy, names, homes, the activity feed,
 * notifications, cosmetics, the leaderboard and the dimensions. Each is built on the database alone, once
 * {@link PersistenceBootstrap}, which extends this, has run the migrations. Split out of it so neither file is over
 * the size the standards draw a line at; every caller still asks the bootstrap.
 */
abstract class FeatureAdapters extends ModeAdapters {

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
    private final SqlHomeStorageAdapter homeStorageAdapter;
    private final SqlActivityFeedAdapter activityFeedAdapter;
    private final SqlNotificationAdapter notificationAdapter;
    private final com.uxplima.uxmskyblock.persistence.cosmetic.SqlProfileCosmeticStorageAdapter
            profileCosmeticStorageAdapter;
    private final PlayerIslandLeaderboardAdapter islandLeaderboardAdapter;
    private final PlayerIslandDimensionAdapter islandDimensionAdapter;

    FeatureAdapters(Database database) {
        super(database);
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
        this.homeStorageAdapter = new SqlHomeStorageAdapter(database.dataSource());
        this.activityFeedAdapter = new SqlActivityFeedAdapter(database.dataSource());
        this.notificationAdapter = new SqlNotificationAdapter(database.dataSource());
        this.profileCosmeticStorageAdapter =
                new com.uxplima.uxmskyblock.persistence.cosmetic.SqlProfileCosmeticStorageAdapter(database);
        this.islandLeaderboardAdapter = new PlayerIslandLeaderboardAdapter(database);
        this.islandDimensionAdapter = new PlayerIslandDimensionAdapter(database);
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

    public HomeStoragePort homeStoragePort() {
        return homeStorageAdapter;
    }

    public ActivityFeedStoragePort activityFeedStoragePort() {
        return activityFeedAdapter;
    }

    public NotificationStoragePort notificationStoragePort() {
        return notificationAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.cosmetic.ProfileCosmeticStoragePort profileCosmeticStoragePort() {
        return profileCosmeticStorageAdapter;
    }

    public IslandLeaderboardPort islandLeaderboardPort() {
        return islandLeaderboardAdapter;
    }

    public IslandDimensionStoragePort islandDimensionStoragePort() {
        return islandDimensionAdapter;
    }
}
