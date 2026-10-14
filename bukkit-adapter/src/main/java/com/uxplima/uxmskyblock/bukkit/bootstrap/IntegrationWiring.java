package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.bedrock.BedrockDetector;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockDetector;
import com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockScreen;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.discord.JavaHttpClientDiscordAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.PlaceholderCacheEviction;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.network.BukkitVelocityBridge;
import com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.BlueMapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.CompositeWebMapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.DynmapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
import com.uxplima.uxmskyblock.bukkit.webmap.Pl3xMapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.WebMapAdapter;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.DeduplicatingOutboxConsumer;
import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityService;
import com.uxplima.uxmskyblock.core.application.network.ClusterRoutingDirectoryPort;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.VelocityBridgePort;
import com.uxplima.uxmskyblock.core.application.webmap.IslandWebMapService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Encapsulates outbound integrations: Economy, Discord webhooks, Outbox event streaming,
 * Velocity proxy bridges, Placeholders, WebMaps, Menus, Commands, and the Public Skyblock API.
 */
public final class IntegrationWiring implements AutoCloseable {

    private final JavaPlugin plugin;
    private final ServerNodeId serverNodeId;
    private final SkyblockEconomyBridge economyBridge;
    private final BedrockDetector bedrockDetector;
    private final BedrockScreen bedrockScreen;
    private final BedrockFormService bedrockFormService;
    private final WorldDimensionSnapshotAdapter worldDimensionSnapshotAdapter;
    private final CompositeWebMapAdapter webMapAdapter;
    private final IslandWebMapService islandWebMapService;
    private final IslandMarkerSynchroniser markerSynchroniser;
    private final IslandControlMenu controlMenu;
    private final SkyblockMenuEngine menuEngine;
    private final SkyblockPlaceholderExpansion placeholderExpansion;
    private final TransactionalOutboxDispatcher outboxDispatcher;
    private final IslandAuthorityService authorityService;
    private final ClusterPlacementWiring clusterPlacement;
    private final Housekeeping housekeeping;

    private @org.jspecify.annotations.Nullable AutoCloseable sagaRecovery;
    private final @org.jspecify.annotations.Nullable AutoCloseable domainEvents;
    private final java.time.Duration authorityHeartbeatInterval;
    private final com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler;

    private @org.jspecify.annotations.Nullable AutoCloseable authorityHeartbeat;
    private final ClusterTransportWiring clusterTransport;
    private final DurableEventTransportPort eventTransport;
    private final VelocityBridgePort velocityBridge;
    private final ClusterRoutingDirectoryPort clusterRoutingDirectory;
    private final IslandNetworkRouter networkRouter;
    private final IslandDiscordWebhookService discordService;
    private final MessageProvider messageProvider;
    private final Messages messages;
    private final IslandCommandTree commandTree;
    private final BukkitSkyblockApiBridge apiBridge;

    public IntegrationWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay) {
        this(
                plugin,
                config,
                persistence,
                authority,
                gameplay,
                ClusterTransportWiring.create(plugin, config.nodeConfig()));
    }

    public IntegrationWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay,
            ClusterTransportWiring clusterTransport) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.clusterTransport = Objects.requireNonNull(clusterTransport, "clusterTransport must not be null");
        this.serverNodeId = config.nodeConfig().nodeId();
        String worldName = config.nodeConfig().worldName();

        // The catalogue is built once, where the rest of the configuration is read.
        this.messages = config.messages();
        this.messageProvider = this.messages.provider();

        this.economyBridge = SkyblockEconomyBridge.createDefault(
                gameplay.bankService(),
                gameplay.scheduler(),
                persistence.economySagaPort(),
                config.bankConfig().currencies());

        this.bedrockDetector = LateBedrockDetector.forServer(plugin.getServer());
        this.bedrockScreen = LateBedrockScreen.forServer(plugin.getServer());
        this.bedrockFormService = new BedrockFormService(bedrockDetector, bedrockScreen, this.messages);
        if (gameplay.resetConfirmationMenu() != null) {
            gameplay.resetConfirmationMenu().setBedrockFormService(this.bedrockFormService);
        }
        if (gameplay.missionsMenu() != null) {
            gameplay.missionsMenu().setBedrockFormService(this.bedrockFormService);
        }
        if (gameplay.boosterMenu() != null) {
            gameplay.boosterMenu().setBedrockFormService(this.bedrockFormService);
        }
        if (gameplay.shopMenu() != null) {
            gameplay.shopMenu().setBedrockFormService(this.bedrockFormService);
        }
        if (gameplay.warpBrowseMenu() != null) {
            gameplay.warpBrowseMenu().setBedrockFormService(this.bedrockFormService);
        }

        this.worldDimensionSnapshotAdapter = new WorldDimensionSnapshotAdapter(plugin, persistence.islandStoragePort());
        this.webMapAdapter = new CompositeWebMapAdapter(List.of(
                new DynmapAdapter(plugin, config.webMapConfig().layer()),
                new BlueMapAdapter(plugin),
                new Pl3xMapAdapter(plugin)));
        this.islandWebMapService = new IslandWebMapService(config.webMapConfig().look());
        // Dynmap, BlueMap and Pl3xMap were all built, all wired into a composite, and nothing ever
        // called one. Every server running this with Dynmap installed had a map with no islands.
        this.markerSynchroniser = new IslandMarkerSynchroniser(
                this.webMapAdapter,
                gameplay.locationService(),
                gameplay.scheduler(),
                config.webMapConfig().look().words());

        this.controlMenu = new IslandControlMenu(
                persistence.islandStoragePort(),
                persistence.islandBankPort(),
                persistence.islandUpgradeStoragePort(),
                gameplay.locationService(),
                gameplay.scheduler(),
                worldName,
                authority.sessionCoordinator(),
                this.bedrockFormService,
                this.messages);

        // The menu files are the menus. Three of them shipped from the beginning and nothing read
        // one, so an operator who moved a slot and restarted saw no change.
        this.menuEngine = new SkyblockMenuEngine(plugin, this.messages, config.dataDir());
        this.menuEngine.loadSpecs();
        this.menuEngine.install();
        this.controlMenu.useMenuEngine(this.menuEngine);
        this.controlMenu.useVaultPages(gameplay.vaultService()::getMaxAllowedPages);
        this.controlMenu.useUpgradeStanding(gameplay.upgradeService()::standing);
        this.controlMenu.useUpgradeWords(new com.uxplima.uxmskyblock.bukkit.menu.UpgradeWords(
                this.messages,
                gameplay.upgradeService()::definitions,
                config.generatorsConfig(),
                config.limitConfig().quotas()));
        WindowFiles.connect(gameplay, this.menuEngine, this.controlMenu);

        this.placeholderExpansion = new SkyblockPlaceholderExpansion(
                persistence.islandStoragePort(),
                persistence.islandBankPort(),
                persistence.islandUpgradeStoragePort(),
                persistence.islandLeaderboardPort(),
                gameplay.scheduler(),
                authority.sessionCoordinator());
        this.placeholderExpansion.useUpgradeStanding(gameplay.upgradeService()::standing);

        this.outboxDispatcher = new TransactionalOutboxDispatcher(
                persistence.outboxPort(), gameplay.scheduler(), serverNodeId.value() + "-outbox");

        // The authority lease was taken once, when an island was made, and nothing ever renewed it.
        // Every write that needs authority is refused once it runs out, so an island stopped being
        // able to use its own bank one lease after it was created. This is the missing heartbeat.
        this.scheduler = gameplay.scheduler();
        this.housekeeping = new Housekeeping(gameplay, persistence, config.notificationConfig());
        this.authorityService = new IslandAuthorityService(
                persistence.islandAuthorityPort(),
                serverNodeId,
                config.islandWorlds(),
                config.nodeConfig().authorityLease());
        this.authorityHeartbeatInterval = config.nodeConfig().authorityHeartbeatInterval();
        this.eventTransport = this.clusterTransport.eventTransport();
        // Delivery is at least once: a worker whose claim lease runs out while it is delivering
        // loses the row to another worker, which delivers it again. The consumer inbox was built
        // for exactly that and nothing ever wrote a row to it, so a fenced claim published the
        // same event twice.
        this.outboxDispatcher.sweepInboxToo(persistence.consumerInboxPort());
        this.outboxDispatcher.registerConsumer(new DeduplicatingOutboxConsumer(
                serverNodeId.value() + "-event-transport",
                persistence.consumerInboxPort(),
                event -> this.eventTransport.publish("uxmskyblock:stream:domain_events", event)));
        // The island's feed is written out of what the bank committed, so a feed that will not write
        // leaves the move standing and is asked again. One name on every node: each line once.
        this.outboxDispatcher.registerConsumer(new DeduplicatingOutboxConsumer(
                com.uxplima.uxmskyblock.core.application.activity.ActivityFeedProjection.CONSUMER_NAME,
                persistence.consumerInboxPort(),
                new com.uxplima.uxmskyblock.core.application.activity.ActivityFeedProjection(
                        gameplay.activityFeedService(),
                        uuid -> java.util.Optional.ofNullable(
                                plugin.getServer().getOfflinePlayer(uuid).getName()))));

        // And somebody reads it. Every node published onto this stream and no node ever subscribed,
        // so a node that froze an island, archived one or handed one to a new owner told every
        // other node and every other node went on answering out of what it remembered.
        //
        // The group is this node's own, because every node has to hear every event: a group shared
        // between them would hand each event to one node and leave the rest stale, which is the
        // thing being fixed.
        this.domainEvents = DomainEventSubscription.subscribe(serverNodeId, this.eventTransport, gameplay, persistence);

        this.velocityBridge =
                new BukkitVelocityBridge(plugin, gameplay.scheduler(), authority.sessionCoordinator()::handOff);
        this.clusterRoutingDirectory = this.clusterTransport.clusterRoutingDirectory();
        this.networkRouter = new IslandNetworkRouter(
                serverNodeId,
                persistence.islandAuthorityPort(),
                velocityBridge,
                clusterRoutingDirectory,
                config.nodeConfig().routeCacheTtl());
        this.clusterPlacement = new ClusterPlacementWiring(
                config.nodeConfig(),
                persistence.clusterNodesPort(),
                persistence.islandStoragePort(),
                gameplay.gameModeHierarchyService(),
                this::averageMspt);
        this.networkRouter.usePlacement(clusterPlacement.placement());

        this.discordService = new IslandDiscordWebhookService(
                new JavaHttpClientDiscordAdapter(),
                config.discordConfig().webhookUrls(),
                config.discordConfig().enabled(),
                config.discordConfig().botUsername(),
                config.discordConfig().avatarUrl(),
                config.discordConfig().rateLimitPerSecond(),
                config.discordConfig().embeds());

        // The service was built, given a URL per topic, rate limited and queued, and nothing ever
        // told it anything: not one of its notification methods had a caller. These are the two
        // that know when something worth announcing happened.
        gameplay.allianceService().setAnnouncer(this.discordService);
        gameplay.freezeService().setAnnouncer(this.discordService);

        this.commandTree =
                IslandCommandWiring.build(this, plugin, config, persistence, authority, gameplay, housekeeping);

        this.apiBridge = new BukkitSkyblockApiBridge(
                persistence.islandStoragePort(),
                persistence.islandBankPort(),
                persistence.islandLeaderboardPort(),
                gameplay.bankService(),
                gameplay.createIslandUseCase(),
                serverNodeId,
                gameplay.scheduler(),
                authority.sessionCoordinator(),
                worldName);
        this.apiBridge.useLeaderboards(gameplay.leaderboardMetrics());
    }

    public void enable() {
        if (!Guis.isInstalled()) {
            Guis.install(plugin);
        }
        outboxDispatcher.start();
        // The first beat runs at once: a server that has been down longer than the lease has to take
        // its islands back before anybody tries to bank on one.
        beat();
        this.authorityHeartbeat =
                scheduler.repeatAsync(this::beat, authorityHeartbeatInterval, authorityHeartbeatInterval);
        economyBridge
                .economyRebinder()
                .ifPresent(rebinder -> plugin.getServer().getPluginManager().registerEvents(rebinder, plugin));
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new PlaceholderCacheEviction(placeholderExpansion), plugin);
        commandTree.register(plugin);
        apiBridge.register();
        housekeeping.start();
    }

    /**
     * What waits for the server to finish loading: PlaceholderAPI, the economy a half done saga pays
     * back through, and the world a half done reset still has to empty.
     */
    public void whenServerIsUp() {
        placeholderExpansion.registerExpansion("uxplima", plugin.getPluginMeta().getVersion());
        economyBridge.recoverPendingSagas(serverNodeId);
        this.sagaRecovery = economyBridge.keepRecoveringSagas(serverNodeId);
        housekeeping.recoverAfterStart();
    }

    private void closeQuietly(@org.jspecify.annotations.Nullable AutoCloseable task, String what) {
        if (task == null) {
            return;
        }
        try {
            task.close();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                    .log(java.util.logging.Level.WARNING, "Stopping " + what + " failed.", e);
        }
    }

    private void closeAuthorityHeartbeat() {
        @org.jspecify.annotations.Nullable AutoCloseable beat = this.authorityHeartbeat;
        this.authorityHeartbeat = null;
        if (beat == null) {
            return;
        }
        try {
            beat.close();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                    .log(java.util.logging.Level.WARNING, "Stopping the island authority heartbeat failed.", e);
        }
    }

    /** One heartbeat: this node's leases pushed forward, then its health published to the cluster. */
    private void beat() {
        clusterPlacement.presence().beat(authorityService.heartbeat());
    }

    /** The server's tick time, or 0 where the platform keeps none for the whole server. */
    private double averageMspt() {
        try {
            return plugin.getServer().getAverageTickTime();
        } catch (UnsupportedOperationException perRegion) {
            return 0.0;
        }
    }

    /** The placement strategies an operator may name. Another plugin registers its own here. */
    public com.uxplima.uxmskyblock.core.application.network.PlacementStrategies placementStrategies() {
        return clusterPlacement.strategies();
    }

    /** The heartbeat that keeps this node's authority alive. */
    public IslandAuthorityService authorityService() {
        return authorityService;
    }

    public SkyblockEconomyBridge economyBridge() {
        return economyBridge;
    }

    public BedrockDetector bedrockDetector() {
        return bedrockDetector;
    }

    public BedrockScreen bedrockScreen() {
        return bedrockScreen;
    }

    public BedrockFormService bedrockFormService() {
        return bedrockFormService;
    }

    public WorldDimensionSnapshotAdapter worldDimensionSnapshotAdapter() {
        return worldDimensionSnapshotAdapter;
    }

    public WebMapAdapter webMapAdapter() {
        return webMapAdapter;
    }

    /** Puts islands on the web map and takes them off again. */
    public IslandMarkerSynchroniser markerSynchroniser() {
        return markerSynchroniser;
    }

    public IslandWebMapService islandWebMapService() {
        return islandWebMapService;
    }

    /** The menu engine, so a feature can register the verbs its own menu files name. */
    public SkyblockMenuEngine menuEngine() {
        return menuEngine;
    }

    public IslandControlMenu controlMenu() {
        return controlMenu;
    }

    public SkyblockPlaceholderExpansion placeholderExpansion() {
        return placeholderExpansion;
    }

    public TransactionalOutboxDispatcher outboxDispatcher() {
        return outboxDispatcher;
    }

    public DurableEventTransportPort eventTransport() {
        return eventTransport;
    }

    public VelocityBridgePort velocityBridge() {
        return velocityBridge;
    }

    public ClusterRoutingDirectoryPort clusterRoutingDirectory() {
        return clusterRoutingDirectory;
    }

    public IslandNetworkRouter networkRouter() {
        return networkRouter;
    }

    public IslandDiscordWebhookService discordService() {
        return discordService;
    }

    public Messages messages() {
        return messages;
    }

    public MessageProvider messageProvider() {
        return messageProvider;
    }

    public IslandCommandTree commandTree() {
        return commandTree;
    }

    public BukkitSkyblockApiBridge apiBridge() {
        return apiBridge;
    }

    public ClusterTransportWiring clusterTransport() {
        return clusterTransport;
    }

    public IslandChatTransportPort chatTransport() {
        return clusterTransport.chatTransport();
    }

    @Override
    public void close() {
        closeAuthorityHeartbeat();
        housekeeping.close();
        closeQuietly(this.sagaRecovery, "the economy saga recovery");
        this.sagaRecovery = null;
        closeQuietly(this.domainEvents, "the island change listener");
        menuEngine.close();
        discordService.close();
        outboxDispatcher.close();
        clusterTransport.close();
        if (Guis.isInstalled()) {
            Guis.uninstall();
        }
        apiBridge.unregister();
    }
}
