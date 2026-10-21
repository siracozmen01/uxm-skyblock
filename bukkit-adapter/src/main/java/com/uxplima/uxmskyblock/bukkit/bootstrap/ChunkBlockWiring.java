package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockStart;
import com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The ChunkBlock game mode, while the operator lets islands be ChunkBlock islands. */
public final class ChunkBlockWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ChunkBlockWiring.class.getName());

    private final ChunkBlockConfiguration config;
    private final ChunkBlockService service;
    private final com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener;
    private final com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable TerritoryWalls walls;
    private final com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable ChunkSigns signs;

    /** The webs of the island's Nether and End, each its own, for the dimensions a world is named for. */
    private final List<Web> dimensions;

    /** The beats that show Bedrock players the edge, while they run. */
    private final List<AutoCloseable> bedrockEdges = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The window a click on a sign opens, handed over once the commands are wired. */
    private volatile java.util.function.BiConsumer<org.bukkit.entity.Player, ChunkPos> openWindow =
            (player, chunk) -> {};

    /**
     * One dimension's web: its own chunks, its edge, its walls and its signs, guarding the dimension's world alone.
     *
     * @param dimension the dimension's key, as the dimension tables keep it
     * @param world the name of the dimension's world
     */
    public record Web(
            String dimension,
            String world,
            ChunkBlockService service,
            com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener,
            com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable TerritoryWalls walls,
            com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable ChunkSigns signs,
            SignWindow window) {}

    /** The window a click on one web's sign opens, handed over once the commands are wired. */
    public static final class SignWindow {

        private volatile java.util.function.BiConsumer<org.bukkit.entity.Player, ChunkPos> shown;

        SignWindow(java.util.function.BiConsumer<org.bukkit.entity.Player, ChunkPos> shown) {
            this.shown = shown;
        }

        /** Hands the signs the window a member's click opens. */
        public void set(java.util.function.BiConsumer<org.bukkit.entity.Player, ChunkPos> window) {
            this.shown = Objects.requireNonNull(window, "window must not be null");
        }

        void open(org.bukkit.entity.Player player, ChunkPos chunk) {
            shown.accept(player, chunk);
        }
    }

    public ChunkBlockWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            com.uxplima.uxmskyblock.bukkit.chunkblock.SignPainter painter) {
        this.config = Objects.requireNonNull(configuration.chunkBlockConfig(), "chunkBlockConfig must not be null");
        Objects.requireNonNull(painter, "painter must not be null");
        // The worlds of the island's Nether and End, while dimensions are on: each holds a web of its own.
        java.util.Map<String, String> dimensionWorlds = new java.util.LinkedHashMap<>();
        var dimensionConfig = configuration.dimensionConfig();
        if (config.enabled() && dimensionConfig.enabled()) {
            dimensionConfig.mappings().forEach((type, mapping) -> {
                if (type != com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType.OVERWORLD) {
                    dimensionWorlds.put(type.id().value(), mapping.worldName());
                }
            });
        }
        java.util.Set<String> elsewhere = java.util.Set.copyOf(dimensionWorlds.values());
        java.util.function.Function<
                        com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                        java.util.Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation>>
                homes = persistence.islandStoragePort()::findLocationByIslandId;
        this.service =
                new ChunkBlockService(persistence.chunkTerritoryPort(), config.rules(), config.keepLevelChunks());
        Web overworld = web(
                configuration,
                scheduler,
                islands,
                activeProfile,
                "overworld",
                "",
                service,
                world -> !elsewhere.contains(world),
                homes,
                homes,
                new SignWindow((player, chunk) -> openWindow.accept(player, chunk)),
                painter);
        this.listener = overworld.listener();
        this.walls = overworld.walls();
        this.signs = overworld.signs();
        List<Web> webs = new java.util.ArrayList<>();
        dimensionWorlds.forEach((dimension, world) -> {
            ChunkBlockService own = new ChunkBlockService(
                    persistence.chunkTerritoryPort(dimension), config.rules(), config.keepLevelChunks());
            // A sign or a wall of the dimension stands in its world, at the island's own ground over it.
            webs.add(web(
                    configuration,
                    scheduler,
                    islands,
                    activeProfile,
                    dimension,
                    world,
                    own,
                    world::equals,
                    homes,
                    islandId -> homes.apply(islandId)
                            .map(home -> new com.uxplima.uxmskyblock.core.domain.island.IslandLocation(
                                    home.islandId(),
                                    world,
                                    home.bounds(),
                                    home.spawnX(),
                                    home.spawnY(),
                                    home.spawnZ(),
                                    home.spawnYaw(),
                                    home.spawnPitch())),
                    new SignWindow((player, chunk) -> {}),
                    painter));
        });
        this.dimensions = List.copyOf(webs);
        // Every territory into memory before anybody steps near a closed chunk, so no step is a query.
        scheduler.async(() -> {
            for (ChunkBlockService each : services()) {
                try {
                    int count = each.prime();
                    LOGGER.fine(() -> count + " ChunkBlock webs are in memory.");
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "The ChunkBlock islands could not be read ahead.", e);
                }
            }
        });
    }

    /** Builds one dimension's edge, walls, signs and Bedrock dust over {@code own}, guarding {@code guarded} worlds. */
    private Web web(
            ConfigurationWiring configuration,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            String dimension,
            String world,
            ChunkBlockService own,
            java.util.function.Predicate<String> guarded,
            java.util.function.Function<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            java.util.Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation>>
                    homes,
            java.util.function.Function<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            java.util.Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation>>
                    locations,
            SignWindow window,
            com.uxplima.uxmskyblock.bukkit.chunkblock.SignPainter painter) {
        var edge = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener(
                own,
                islands,
                scheduler,
                configuration.messages(),
                // Whoever a closing chunk puts out goes to the island's spawn, whatever the dimension.
                homes,
                config.bypassPermission());
        edge.guarding(guarded);
        // A fallen level closes chunks off the main thread; whoever stands in one is moved out.
        own.whenClosed(edge::moveOut);
        // The open ground is walled where it meets closed ground, in the style modules/walls.conf names.
        var wallConfig = configuration.wallConfig();
        var wall = wallConfig
                .chunkBlockStyle()
                .map(style -> new com.uxplima.uxmskyblock.bukkit.chunkblock.TerritoryWalls(
                        own,
                        islands,
                        scheduler,
                        new com.uxplima.uxmskyblock.bukkit.wall.WallPanels(wallConfig),
                        style,
                        islandId -> locations
                                .apply(islandId)
                                .map(com.uxplima.uxmskyblock.core.domain.island.IslandLocation::worldName)))
                .orElse(null);
        if (wall != null) {
            wall.guarding(guarded);
            own.whenOpened(wall::opened);
            own.whenClosed(wall::closed);
        }
        // A sign over each closed chunk an island can open next, which a member clicks for its price.
        var sign = config.signs()
                ? new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkSigns(
                        own,
                        config.tiers(),
                        islands,
                        scheduler,
                        painter,
                        configuration.messages(),
                        locations,
                        activeProfile,
                        window::open)
                : null;
        if (sign != null) {
            sign.guarding(guarded);
            own.whenOpened(sign::opened);
            own.whenClosed(sign::closed);
        }
        // A Bedrock client draws no wall, so a Bedrock player is shown the edge as dust.
        var bedrock = wallConfig.bedrock();
        if (config.enabled()
                && bedrock.enabled()
                && wallConfig.chunkBlockStyle().isPresent()) {
            var edges = new com.uxplima.uxmskyblock.bukkit.chunkblock.BedrockEdges(
                    own,
                    islands,
                    scheduler,
                    bedrockPlayers(),
                    bedrock,
                    com.uxplima.uxmskyblock.bukkit.chunkblock.BedrockEdges.dust(bedrock));
            edges.guarding(guarded);
            bedrockEdges.add(edges.start());
        }
        return new Web(dimension, world, own, edge, wall, sign, window);
    }

    public ChunkBlockService service() {
        return service;
    }

    /** The webs of the island's Nether and End, while dimensions are on. */
    public List<Web> dimensions() {
        return dimensions;
    }

    /** Every web's service, the overworld's first. */
    public List<ChunkBlockService> services() {
        List<ChunkBlockService> all = new java.util.ArrayList<>();
        all.add(service);
        dimensions.forEach(web -> all.add(web.service()));
        return List.copyOf(all);
    }

    /** Closes in every web the chunks an island at {@code level} can no longer hold. */
    public void onLevel(com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId, long level) {
        for (ChunkBlockService each : services()) {
            each.onLevel(islandId, level);
        }
    }

    /**
     * The panel behind the chunks command and the ChunkBlock placeholders, drawn from the operator's
     * menu file, or nothing while the operator does not let islands be ChunkBlock islands.
     */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable ChunkBlockPanel panel(
            com.uxplima.uxmskyblock.bukkit.menu.@org.jspecify.annotations.Nullable SkyblockMenuEngine engine,
            com.uxplima.uxmskyblock.core.application.island.IslandStoragePort islands,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf) {
        if (!config.enabled()) {
            return null;
        }
        var panel = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockPanel(
                service, islands, scheduler, messages, activeProfile, levelOf);
        panel.useMenuEngine(engine);
        return panel;
    }

    /**
     * Paying for chunks by their grades: what players hand in, what the island's bank pays, and the rewards opening
     * one fires.
     */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurchase purchase(
            PersistenceBootstrap persistence,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankService bank,
            com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge economy,
            com.uxplima.uxmskyblock.core.application.island.IslandAccessService access,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node) {
        return purchase(
                service,
                persistence.chunkPaymentPort(),
                persistence,
                bank,
                economy,
                access,
                scheduler,
                messages,
                activeProfile,
                levelOf,
                node);
    }

    /** Paying for the chunks of one dimension's web, with what is paid kept under that dimension. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurchase purchase(
            Web web,
            PersistenceBootstrap persistence,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankService bank,
            com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge economy,
            com.uxplima.uxmskyblock.core.application.island.IslandAccessService access,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node) {
        return purchase(
                web.service(),
                persistence.chunkPaymentPort(web.dimension()),
                persistence,
                bank,
                economy,
                access,
                scheduler,
                messages,
                activeProfile,
                levelOf,
                node);
    }

    private com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurchase purchase(
            ChunkBlockService own,
            com.uxplima.uxmskyblock.core.application.chunkblock.ChunkPaymentPort payments,
            PersistenceBootstrap persistence,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankService bank,
            com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge economy,
            com.uxplima.uxmskyblock.core.application.island.IslandAccessService access,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node) {
        var unlocks = new com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks(
                own, payments, config.tiers(), persistence.islandBankPort(), bank, persistence.islandAuthorityPort());
        var purse = new com.uxplima.uxmskyblock.bukkit.chunkblock.PlayerChunkPurse(
                new com.uxplima.uxmskyblock.bukkit.chunkblock.PlayerChunkPurse.Money() {
                    @Override
                    public double balance(org.bukkit.entity.Player player) {
                        return economy.balance(player);
                    }

                    @Override
                    public boolean withdraw(org.bukkit.entity.Player player, double amount) {
                        return economy.withdrawWallet(player, amount);
                    }

                    @Override
                    public boolean deposit(org.bukkit.entity.Player player, double amount) {
                        return economy.depositWallet(player, amount);
                    }
                },
                id -> economy.wallets().flatMap(wallets -> wallets.currency(id)));
        var rewards = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkRewards(
                config.tiers(), new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, messages));
        return new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurchase(
                unlocks,
                persistence.islandStoragePort(),
                access,
                scheduler,
                messages,
                purse,
                rewards,
                activeProfile,
                levelOf,
                node);
    }

    /** The signs over closed chunks, while the operator lets them float. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable ChunkSigns signs() {
        return signs;
    }

    /** Hands the signs the window a member's click opens. */
    public void useWindow(com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkWindow window) {
        this.openWindow = window::show;
    }

    /** The edge of every territory, registered while the operator lets islands be ChunkBlock islands. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener() {
        return listener;
    }

    /** The wall around every territory's open ground, while the operator names a style for it. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.@org.jspecify.annotations.Nullable TerritoryWalls walls() {
        return walls;
    }

    public ChunkBlockConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /**
     * The action that starts an island's territory, while ChunkBlock is enabled, and none otherwise,
     * so a ChunkBlock preset is not offered.
     */
    public List<CreationActionProvider<IslandStart>> startActions() {
        if (!config.enabled()) {
            return List.of();
        }
        java.util.Map<String, ChunkBlockService> byWorld = new java.util.HashMap<>();
        dimensions.forEach(web -> byWorld.put(web.world(), web.service()));
        return List.of(new ChunkBlockStart(service, world -> java.util.Optional.ofNullable(byWorld.get(world))));
    }

    /** Whether a player plays on Bedrock, asked of Floodgate the first time a beat runs, once the server is up. */
    static java.util.function.Predicate<java.util.UUID> bedrockPlayers() {
        var detector = new java.util.concurrent.atomic.AtomicReference<
                com.uxplima.uxmskyblock.bukkit.bedrock.@org.jspecify.annotations.Nullable LateBedrockDetector>();
        return player -> {
            var known = detector.get();
            if (known == null) {
                known = com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockDetector.forServer(
                        org.bukkit.Bukkit.getServer());
                detector.set(known);
            }
            return known.isBedrock(player);
        };
    }

    /** Stops showing Bedrock players the edge, before the server stops. */
    @Override
    public void close() {
        for (AutoCloseable running : bedrockEdges) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the Bedrock edges failed.", e);
            }
        }
        bedrockEdges.clear();
    }
}
