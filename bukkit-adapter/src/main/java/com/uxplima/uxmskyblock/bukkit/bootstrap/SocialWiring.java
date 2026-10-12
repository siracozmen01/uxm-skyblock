package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandChatDeliveryAdapter;
import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandWarpBrowseMenu;
import com.uxplima.uxmskyblock.bukkit.notification.IslandNotificationListener;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultListener;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates social interactions, alliances, temporary access, team chat,
 * warps, and vaults.
 */
public final class SocialWiring {

    private final IslandSocialService socialService;
    private final IslandAllianceService allianceService;
    private final TemporaryAccessService temporaryAccessService;
    private final IslandChatService chatService;
    private final @Nullable IslandChatListener chatListener;
    private final SafeTeleportEngine safeTeleportEngine;
    private final IslandWarpService warpService;
    private final @Nullable IslandWarpBrowseMenu warpBrowseMenu;
    private final IslandVaultService vaultService;
    private final HomeService homeService;
    private final ActivityFeedService activityFeedService;
    private final NotificationService notificationService;
    private final IslandNotificationListener notificationListener;
    private final @Nullable IslandVaultWindow vaultWindow;
    private final @Nullable IslandVaultListener vaultListener;

    public SocialWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            IslandUpgradeService upgradeService,
            IslandChatTransportPort chatTransport,
            SchedulerPort scheduler) {
        this.allianceService = Objects.requireNonNull(allianceService, "allianceService must not be null");
        this.temporaryAccessService =
                Objects.requireNonNull(temporaryAccessService, "temporaryAccessService must not be null");

        this.socialService = new IslandSocialService(
                persistence.islandSocialStoragePort(),
                RatingPolicy.standardFiveStar(),
                persistence.islandStoragePort(),
                config.socialConfig().minDwellTime(),
                config.socialConfig().priorWeight(),
                config.socialConfig().priorMean(),
                config.socialConfig().maxPinned(),
                config.socialConfig().maxMessageLength());

        this.safeTeleportEngine = new SafeTeleportEngine(config.warpConfig().searchRadius());
        this.warpService = new IslandWarpService(
                persistence.islandWarpStoragePort(),
                this.safeTeleportEngine,
                upgradeService,
                config.warpConfig().baseWarpLimit(),
                (targetIslandId, visitorProfileId) -> {
                    if (!config.allianceConfig().privilegedVisitAccess()) {
                        return false;
                    }
                    return persistence
                            .islandStoragePort()
                            .findIslandIdByProfileId(visitorProfileId)
                            .map(visitorIslandId -> allianceService.canPrivilegedVisit(visitorIslandId, targetIslandId))
                            .orElse(false);
                });

        // The directory drew every warp's name and never its icon, which every warp has carried
        // since the warp work. The window is what draws it.
        this.warpBrowseMenu =
                config.moduleSettings().isModuleEnabled("warps") ? new IslandWarpBrowseMenu(config.messages()) : null;

        this.vaultService = new IslandVaultService(
                persistence.islandVaultStoragePort(),
                upgradeService,
                config.vaultConfig().basePages(),
                config.vaultConfig().maxPages(),
                config.vaultConfig().leaseDuration(),
                config.vaultConfig().auditLogLimit());

        IslandChatTransportPort actualChatTransport =
                Objects.requireNonNull(chatTransport, "chatTransport must not be null");
        BukkitIslandChatDeliveryAdapter chatDelivery =
                new BukkitIslandChatDeliveryAdapter(config.chatConfig(), scheduler, config.messages());
        BukkitIslandOnlineMemberProvider chatMemberProvider = new BukkitIslandOnlineMemberProvider();
        this.chatService = new IslandChatService(
                persistence.islandStoragePort(),
                actualChatTransport,
                chatDelivery,
                chatMemberProvider,
                config.chatConfig().rateLimitMessagesPerSecond(),
                // The alliance service has carried an alliance-chat switch since it was written and
                // nothing asked it, because there was no channel to switch on. A server with the
                // switch off has no ally lookup, so the alliance channel is not offered at all.
                allianceService.isAllianceChatEnabled() ? allianceService::getAllies : null);
        this.chatListener = config.moduleSettings().isModuleEnabled("chat")
                ? new IslandChatListener(
                        this.chatService, authority.activeProfileProvider(), config.messages(), chatMemberProvider)
                : null;
        if (this.chatListener != null) {
            authority.sessionCoordinator().whenSessionActive(this.chatListener::onSessionActive);
            authority.sessionCoordinator().whenProfileLeft(this.chatListener::onProfileLeft);
        }

        this.homeService = new HomeService(
                persistence.homeStoragePort(), config.homeConfig().limitPolicy());

        this.activityFeedService = new ActivityFeedService(persistence.activityFeedStoragePort());
        // Whoever did a thing is online doing it, so the profile they act under is one of the online players'.
        var sessions = authority.sessionCoordinator();
        this.activityFeedService.usePlayers(profile -> org.bukkit.Bukkit.getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getUniqueId)
                .filter(online ->
                        sessions.activeProfile(online).filter(profile::equals).isPresent())
                .findFirst());
        this.notificationService = new NotificationService(persistence.notificationStoragePort());
        this.notificationListener = new IslandNotificationListener(
                this.notificationService, scheduler, config.messages(), authority.sessionCoordinator());
        authority.sessionCoordinator().whenSessionActive(this.notificationListener::onSessionActive);

        if (config.vaultConfig().enabled()) {
            this.vaultWindow = new IslandVaultWindow(
                    this.vaultService,
                    persistence.islandStoragePort(),
                    scheduler,
                    config.vaultConfig(),
                    config.messages(),
                    authority.sessionCoordinator());
            this.vaultListener = new IslandVaultListener(this.vaultWindow);
            authority.sessionCoordinator().whenStopping(this.vaultWindow::writeBeforeStop);
        } else {
            this.vaultWindow = null;
            this.vaultListener = null;
        }
    }

    public HomeService homeService() {
        return homeService;
    }

    public IslandSocialService socialService() {
        return socialService;
    }

    public IslandAllianceService allianceService() {
        return allianceService;
    }

    public TemporaryAccessService temporaryAccessService() {
        return temporaryAccessService;
    }

    public IslandChatService chatService() {
        return chatService;
    }

    public @Nullable IslandChatListener chatListener() {
        return chatListener;
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return safeTeleportEngine;
    }

    public IslandWarpService warpService() {
        return warpService;
    }

    /** The window the public directory opens in. Absent when warps are off on this node. */
    public @Nullable IslandWarpBrowseMenu warpBrowseMenu() {
        return warpBrowseMenu;
    }

    public ActivityFeedService activityFeedService() {
        return activityFeedService;
    }

    public NotificationService notificationService() {
        return notificationService;
    }

    public IslandNotificationListener notificationListener() {
        return notificationListener;
    }

    public @Nullable IslandVaultWindow vaultWindow() {
        return vaultWindow;
    }

    public @Nullable IslandVaultListener vaultListener() {
        return vaultListener;
    }

    public IslandVaultService vaultService() {
        return vaultService;
    }
}
