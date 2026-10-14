package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.RestApiConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.health.BukkitServerHealthAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.rest.server.RestServer;
import org.jspecify.annotations.Nullable;

/**
 * Platform composition root orchestrating configuration, persistence, authority,
 * gameplay domain logic, feature modules, and outbound platform integrations.
 */
public final class SkyblockBootstrap implements AutoCloseable {

    private final JavaPlugin plugin;
    private final ConfigurationWiring configWiring;
    private final PersistenceWiring persistenceWiring;
    private final AuthorityWiring authorityWiring;
    private final GameplayWiring gameplayWiring;
    private final IntegrationWiring integrationWiring;
    private @Nullable RestServer restServer;
    private final FeatureModuleWiring featureModuleWiring;

    public SkyblockBootstrap(JavaPlugin plugin, PersistenceWiring persistenceWiring, ConfigurationWiring configWiring) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.persistenceWiring = Objects.requireNonNull(persistenceWiring, "persistenceWiring must not be null");
        this.configWiring = Objects.requireNonNull(configWiring, "configWiring must not be null");

        SchedulerPort scheduler = new FoliaSchedulerAdapter(plugin);
        AdaptiveBackpressureController backpressureController = new AdaptiveBackpressureController(
                new com.uxplima.uxmskyblock.bukkit.performance.ServerTickRate(),
                configWiring.performanceConfig().adaptiveThrottle(),
                configWiring.performanceConfig().tpsThreshold(),
                configWiring.performanceConfig().normalBlocksPerTick(),
                configWiring.performanceConfig().throttledBlocksPerTick(),
                configWiring.performanceConfig().normalChunksPerSec(),
                configWiring.performanceConfig().throttledChunksPerSec());

        IslandAccessService accessService = new IslandAccessService();
        IslandAllianceService allianceService = new IslandAllianceService(
                persistenceWiring.bootstrap().islandAllianceStoragePort(),
                configWiring.allianceConfig().maxAllies(),
                configWiring.allianceConfig().inviteTimeout(),
                configWiring.allianceConfig().friendlyFireShielding(),
                configWiring.allianceConfig().privilegedVisitAccess(),
                configWiring.allianceConfig().allianceChatEnabled());
        TemporaryAccessService temporaryAccessService = new TemporaryAccessService(
                persistenceWiring.bootstrap().temporaryAccessStoragePort(),
                configWiring.temporaryAccessConfig().grantLookupTtl());
        BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter = new BukkitIslandVisitorEvictionAdapter(
                plugin,
                persistenceWiring.bootstrap().islandStoragePort(),
                scheduler,
                configWiring.nodeConfig().worldName(),
                configWiring.messages());
        IslandAdminFreezeService freezeService = new IslandAdminFreezeService(
                persistenceWiring.bootstrap().islandStoragePort(),
                persistenceWiring.bootstrap().islandMutationLock(),
                persistenceWiring.bootstrap().islandAdminFreezePort(),
                visitorEvictionAdapter,
                persistenceWiring.bootstrap().outboxPort());

        // The index answers for every block anybody touches, and a miss has to be filled off the
        // thread the touch arrived on. Built without a scheduler it filled the miss where it stood:
        // a query on the region thread on the first touch of every island. The convenience
        // constructor below this one is what did that, and it also handed the listener the bundled
        // English catalogue, so every refusal a player read ignored the operator's language files.
        SpatialIslandIndex spatialIndex =
                new SpatialIslandIndex(persistenceWiring.bootstrap().islandStoragePort(), scheduler);
        IslandProtectionListener protectionListener = new IslandProtectionListener(
                persistenceWiring.bootstrap().islandStoragePort(),
                accessService,
                allianceService,
                temporaryAccessService,
                freezeService,
                spatialIndex,
                configWiring.messages());

        this.authorityWiring = AuthorityWiring.create(
                configWiring.nodeConfig().nodeId(),
                configWiring.playerStateConfig(),
                persistenceWiring.bootstrap(),
                scheduler,
                protectionListener,
                configWiring.messages());

        ClusterTransportWiring clusterTransportWiring =
                ClusterTransportWiring.create(plugin, configWiring.nodeConfig());

        AtomicReference<SkyblockEconomyBridge> economyBridgeRef = new AtomicReference<>();
        this.gameplayWiring = new GameplayWiring(
                plugin,
                configWiring,
                persistenceWiring.bootstrap(),
                authorityWiring,
                protectionListener,
                accessService,
                allianceService,
                temporaryAccessService,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                backpressureController,
                economyBridgeRef::get,
                clusterTransportWiring.chatTransport(),
                persistenceWiring.objectStoragePort(),
                persistenceWiring.backupBucket());
        // Before the commands are built, so /isadmin top has the displays to place.
        this.gameplayWiring.startTopDisplays(configWiring);

        this.integrationWiring = new IntegrationWiring(
                plugin,
                configWiring,
                persistenceWiring.bootstrap(),
                authorityWiring,
                gameplayWiring,
                clusterTransportWiring);
        economyBridgeRef.set(this.integrationWiring.economyBridge());

        this.featureModuleWiring =
                new FeatureModuleWiring(configWiring, persistenceWiring.bootstrap(), gameplayWiring, integrationWiring);
    }

    public static SkyblockBootstrap createDefault(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        ConfigurationWiring configWiring = ConfigurationLoader.loadAndValidate(plugin);
        PersistenceWiring persistenceWiring =
                PersistenceWiring.resolve(configWiring.rootNode(), configWiring.dataDir());
        return new SkyblockBootstrap(plugin, persistenceWiring, configWiring);
    }

    public void enable() {
        featureModuleWiring.enable();
        // An island's Nether and End stand at its own places in their worlds, which the database does not name.
        java.util.List<String> mirrors = new java.util.ArrayList<>();
        var dimensions = configWiring.dimensionConfig();
        if (dimensions.enabled()) {
            dimensions.mappings().values().forEach(mapping -> mirrors.add(mapping.worldName()));
        }
        gameplayWiring.protectionListener().spatialIndex().mirror(configWiring.islandWorlds(), mirrors);
        for (String world : configWiring.islandWorlds()) {
            gameplayWiring.protectionListener().loadPersistedIslands(world);
        }
        integrationWiring.enable();
        startRestApiIfConfigured();

        BootstrapEventRegistrar.registerEvents(
                Bukkit.getPluginManager(), plugin, featureModuleWiring, gameplayWiring, authorityWiring, configWiring);
    }

    /** What waits for the worlds and the other plugins. See {@link AfterStartup}. */
    public void whenServerIsUp() {
        integrationWiring.whenServerIsUp();
        drawIslandsOnTheWebMap();
        writeShippedPlatforms();
        readWreckTemplates();
        groundTheSpawns();
        noteKindsThatCannotBeMade();
    }

    /**
     * Says once, now that the worlds are loaded, which kinds of island this server cannot make and why. A kind of a
     * mode the operator does not offer is not one a player would miss, so it is not named.
     */
    private void noteKindsThatCannotBeMade() {
        var choices = gameplayWiring.presetChoices();
        com.uxplima.uxmskyblock.bukkit.world.PresetWorlds.ofServer(
                        configWiring.nodeConfig().worldName())
                .notes(gameplayWiring.presetCatalog().allPresets().stream()
                        .filter(choices::offers)
                        .toList())
                .forEach(plugin.getLogger()::warning);
    }

    /** Writes the shipped temple and reads the schematics the Poseidon wreck groups name. */
    @SuppressWarnings("deprecation") // UnsafeValues is where the server says which version its names are of
    private void readWreckTemplates() {
        com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files = gameplayWiring.islandSchematics();
        if (files == null) {
            return;
        }
        var unused = gameplayWiring
                .poseidonWiring()
                .primeTemplates(files, Bukkit.getUnsafe().getDataVersion())
                .thenCompose(read -> gameplayWiring.primeCaveTemplates(
                        files, Bukkit.getUnsafe().getDataVersion()))
                .whenComplete((read, failure) -> {
                    if (failure != null) {
                        plugin.getLogger()
                                .log(
                                        java.util.logging.Level.WARNING,
                                        "The Poseidon templates could not be read.",
                                        failure);
                    }
                });
    }

    /**
     * Lays ground under every island world's spawn that has none, so a player with no island, one who has
     * just reset theirs, has somewhere to stand. The ground is the file schematics/world_spawn.schem,
     * written plain on the first start for an operator to replace with a lobby.
     */
    @SuppressWarnings("deprecation") // UnsafeValues is where the server says which version its names are of
    private void groundTheSpawns() {
        com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files = gameplayWiring.islandSchematics();
        com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround ground =
                new com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround(files);
        CompletableFuture<Boolean> written = files == null
                ? CompletableFuture.completedFuture(false)
                : files.writeIfMissing(
                        com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround.FILE,
                        com.uxplima.uxmskyblock.bukkit.schematic.SpawnGround.shipped(
                                Bukkit.getUnsafe().getDataVersion()));
        SchedulerPort scheduler = gameplayWiring.scheduler();
        var unused = written.handle((done, failure) -> {
            for (String name : configWiring.islandWorlds()) {
                World world = Bukkit.getWorld(name);
                if (world == null) {
                    continue;
                }
                Location spawn = world.getSpawnLocation();
                int chunkX = spawn.getBlockX() >> 4;
                int chunkZ = spawn.getBlockZ() >> 4;
                scheduler.onRegion(name, chunkX, chunkZ, () -> {
                    var unusedLaid = ground.ensure(world, task -> scheduler.onRegion(name, chunkX, chunkZ, task))
                            .thenAccept(any -> {
                                if (any) {
                                    plugin.getLogger()
                                            .info("Laid ground under the spawn of " + name
                                                    + ", where a player with no island stands.");
                                }
                            });
                });
            }
            return null;
        });
    }

    /**
     * Writes the platform each preset and each dimension lays as the schematic file it names, where none
     * stands yet, so the island a server ships is a file an operator can change.
     */
    @SuppressWarnings("deprecation") // UnsafeValues is where the server says which version its names are of
    private void writeShippedPlatforms() {
        java.util.Map<com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType, String> dimensions =
                new java.util.EnumMap<>(com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType.class);
        configWiring.dimensionConfig().mappings().forEach((type, mapping) -> {
            String schematic = mapping.schematic();
            if (schematic != null && !schematic.isBlank()) {
                dimensions.put(type, schematic);
            }
        });
        var unused = gameplayWiring
                .schematicEngine()
                .writeShippedPlatforms(
                        configWiring.presetConfig().presets(),
                        dimensions,
                        Bukkit.getUnsafe().getDataVersion())
                .whenComplete((written, failure) -> {
                    if (failure != null) {
                        plugin.getLogger()
                                .log(
                                        java.util.logging.Level.WARNING,
                                        "The shipped platforms could not be written as schematics.",
                                        failure);
                    } else if (!written.isEmpty()) {
                        plugin.getLogger()
                                .info("Wrote the shipped platforms as schematics: " + String.join(", ", written));
                    }
                });
    }

    /**
     * Draws every island this world holds onto whichever web maps are installed.
     *
     * <p>A map that only learns about islands made since the last restart is a map with holes in it,
     * and the holes are the oldest and largest islands. Until now there were no markers at all:
     * three web map integrations shipped, all three were wired, and nothing ever called one.
     */
    private void drawIslandsOnTheWebMap() {
        for (String world : configWiring.islandWorlds()) {
            integrationWiring
                    .markerSynchroniser()
                    .drawAll(persistenceWiring.bootstrap().islandStoragePort().findAllByWorld(world));
        }
    }

    /**
     * Opens the embedded REST API when the operator asked for it.
     *
     * <p>The module has existed since the enterprise foundation work and nothing ever started it:
     * the plugin did not even depend on it, so five endpoints, the bearer check and the deposit
     * idempotency were code that never ran on a server. It is off by default and refuses to open
     * while the bearer token is still the one the file ships with.
     */
    private void startRestApiIfConfigured() {
        RestApiConfiguration restConfig = RestApiConfiguration.load(configWiring.rootNode());
        if (restConfig.refusedForDefaultToken()) {
            plugin.getLogger()
                    .warning("The REST API is enabled but rest.bearer-token is still the shipped value, "
                            + "so the port was not opened. Set a token of your own.");
            return;
        }
        if (!restConfig.shouldStart()) {
            return;
        }
        RestServer server = new RestServer(
                restConfig.toRestConfiguration(),
                configWiring.nodeConfig().nodeId(),
                persistenceWiring.bootstrap().islandStoragePort(),
                gameplayWiring.bankService(),
                gameplayWiring.leaderboardService(),
                new BukkitServerHealthAdapter(
                        gameplayWiring.protectionListener().spatialIndex()));
        server.start();
        // The feed behind WS /api/v1/events carries exactly what the plugin durably staged. Nothing
        // is invented for the socket, and a viewer that drops a frame never makes the outbox retry.
        integrationWiring
                .outboxDispatcher()
                .registerConsumer(new com.uxplima.uxmskyblock.core.application.event.DeduplicatingOutboxConsumer(
                        configWiring.nodeConfig().nodeId().value() + "-live-feed",
                        persistenceWiring.bootstrap().consumerInboxPort(),
                        server.liveEventFeed()));
        this.restServer = server;
        plugin.getLogger().info("REST API listening on " + restConfig.host() + ":" + server.port());
    }

    public ConfigurationWiring configurationWiring() {
        return configWiring;
    }

    public PersistenceWiring persistenceWiring() {
        return persistenceWiring;
    }

    public AuthorityWiring authorityWiring() {
        return authorityWiring;
    }

    public GameplayWiring gameplayWiring() {
        return gameplayWiring;
    }

    public IntegrationWiring integrationWiring() {
        return integrationWiring;
    }

    public FeatureModuleWiring featureModuleWiring() {
        return featureModuleWiring;
    }

    public PersistenceBootstrap persistenceBootstrap() {
        return persistenceWiring.bootstrap();
    }

    public IslandProtectionListener protectionListener() {
        return gameplayWiring.protectionListener();
    }

    public IslandCommandTree commandTree() {
        return integrationWiring.commandTree();
    }

    public ServerNodeConfiguration nodeConfiguration() {
        return configWiring.nodeConfig();
    }

    public PlayerStateDurabilityConfig playerStateConfig() {
        return configWiring.playerStateConfig();
    }

    public ModuleRegistry moduleRegistry() {
        return featureModuleWiring.moduleRegistry();
    }

    public PlayerSessionCoordinator sessionCoordinator() {
        return authorityWiring.sessionCoordinator();
    }

    public SkyblockEconomyBridge economyBridge() {
        return integrationWiring.economyBridge();
    }

    public IslandControlMenu controlMenu() {
        return integrationWiring.controlMenu();
    }

    public SkyblockPlaceholderExpansion placeholderExpansion() {
        return integrationWiring.placeholderExpansion();
    }

    public TransactionalOutboxDispatcher outboxDispatcher() {
        return integrationWiring.outboxDispatcher();
    }

    public IslandSeasonService seasonService() {
        return gameplayWiring.seasonService();
    }

    public IslandSocialService socialService() {
        return gameplayWiring.socialService();
    }

    public IslandDiscordWebhookService discordService() {
        return integrationWiring.discordService();
    }

    public IslandWarpService warpService() {
        return gameplayWiring.warpService();
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return gameplayWiring.safeTeleportEngine();
    }

    public WarpConfiguration warpConfiguration() {
        return configWiring.warpConfig();
    }

    public IslandVaultService vaultService() {
        return gameplayWiring.vaultService();
    }

    public VaultConfiguration vaultConfiguration() {
        return configWiring.vaultConfig();
    }

    public IslandChatService chatService() {
        return gameplayWiring.chatService();
    }

    public ChatConfiguration chatConfiguration() {
        return configWiring.chatConfig();
    }

    @Nullable public IslandChatListener chatListener() {
        return gameplayWiring.chatListener();
    }

    public IslandInactivityService inactivityService() {
        return gameplayWiring.inactivityService();
    }

    public InactivityConfiguration inactivityConfiguration() {
        return configWiring.inactivityConfig();
    }

    @Override
    public void close() {
        RestServer server = this.restServer;
        if (server != null) {
            server.close();
            this.restServer = null;
        }
        featureModuleWiring.close();
        gameplayWiring.stopTopDisplays();
        gameplayWiring.stopArrivals();
        gameplayWiring.missionService().flushDirtyProgress();
        gameplayWiring.oneBlockWiring().close();
        gameplayWiring.acidIslandWiring().close();
        gameplayWiring.poseidonWiring().close();
        gameplayWiring.strangerRealmsWiring().close();
        gameplayWiring.parkourWiring().close();
        gameplayWiring.tradeWindsWiring().close();
        gameplayWiring.brixWiring().close();
        gameplayWiring.boxedWiring().close();
        gameplayWiring.chunkBlockWiring().close();
        integrationWiring.close();
        authorityWiring.close();
        // Async work already handed out writes to the pool that is about to close. Waiting for it is
        // the difference between a write landing and a stack trace in a log nobody reads.
        if (gameplayWiring.scheduler() instanceof FoliaSchedulerAdapter folia
                && !folia.drainAsync(Duration.ofSeconds(5))) {
            plugin.getLogger()
                    .warning("Some background work was still running at shutdown, so the database was closed "
                            + "under it. Anything it was writing may not have landed.");
        }
        persistenceWiring.close();
    }
}
