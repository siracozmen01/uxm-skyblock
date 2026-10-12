package com.uxplima.uxmskyblock.persistence.gamemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.network.IslandPlacement;
import com.uxplima.uxmskyblock.core.application.network.PlacementStrategies;
import com.uxplima.uxmskyblock.core.application.network.PlacementStrategy;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.acid.SqlAcidIslandsAdapter;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkTerritoryAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.network.SqlClusterNodesAdapter;
import com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockProgressAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The game mode schema, and the island authority beside it, does the same on every engine a customer
 * runs: PostgreSQL, MySQL, MariaDB and SQLite.
 *
 * <p>The game mode architecture names this test. Each engine was given its own schema and each was
 * trusted to read it the same way, and the lab server showed what that trust costs: PostgreSQL
 * refused a boolean compared with a number and every island creation failed there. Here every
 * migration runs on each engine from nothing, and the rows the game mode layer writes are written,
 * changed and read back: a profile's instance, the island it is bound to, a OneBlock island's count,
 * the island's authority lease with its epoch, which nodes are alive and where a placement sends a
 * visitor without touching that lease, and what a lifecycle event still owes a player.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class GameModeSchemaDialectPortabilityTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

    private static MariaDBContainer<?> mariaDbContainer;
    private static MySQLContainer<?> mySqlContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void startEngines() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        mySqlContainer = DatabaseTestFixture.startMySqlIfEnabled();
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void stopEngines() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (mySqlContainer != null) {
            mySqlContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @DisplayName("SQLite: the game mode schema migrates, writes, changes and reads back")
    void sqlite() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        assertTheSchemaIsPortable(database);
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: the game mode schema migrates, writes, changes and reads back")
    void mariaDb() throws Exception {
        assertTheSchemaIsPortable(DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: the game mode schema migrates, writes, changes and reads back")
    void mySql() throws Exception {
        assertTheSchemaIsPortable(DatabaseTestFixture.connectToContainer(mySqlContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the game mode schema migrates, writes, changes and reads back")
    void postgres() throws Exception {
        assertTheSchemaIsPortable(DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES));
    }

    private static void assertTheSchemaIsPortable(Database database) throws Exception {
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));

            PlayerIslandStorageAdapter islands = new PlayerIslandStorageAdapter(database);
            SqlGameModeHierarchyAdapter hierarchy = new SqlGameModeHierarchyAdapter(database.dataSource());
            SqlOneBlockProgressAdapter oneBlock = new SqlOneBlockProgressAdapter(database.dataSource());

            PlayerUuid owner = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            account(database, owner, profile);
            IslandId islandId = IslandId.of(UUID.randomUUID());
            islands.saveIsland(
                    Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), owner, profile, Instant.now()),
                    IslandLocation.fromCenterAndRadius(islandId, "skyblock", 0, 0, 100));

            // A profile's instance, written, moved to another mode and read back.
            Instant created = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            GameModeInstance instance = GameModeInstance.create(
                    GameModeInstanceId.random(), profile, GameModeType.SKYBLOCK, "classic", created);
            hierarchy.saveGameModeInstance(instance);
            hierarchy.saveGameModeInstance(new GameModeInstance(
                    instance.id(), profile, GameModeType.ONEBLOCK, "oneblock", created, created.plusSeconds(60)));
            assertThat(hierarchy.findInstanceByProfileId(profile)).get().satisfies(read -> {
                assertThat(read.id()).isEqualTo(instance.id());
                assertThat(read.gameModeType()).isEqualTo(GameModeType.ONEBLOCK);
                assertThat(read.rulesetConfig()).isEqualTo("oneblock");
                assertThat(read.updatedAt()).isAfter(read.createdAt());
            });

            // The island the instance is bound to, found from either side.
            hierarchy.savePrimaryGameplayRootRef(PrimaryGameplayRootRef.forIsland(
                    instance.id(), islandId.value().toString(), created));
            assertThat(hierarchy.findRootRefByInstanceId(instance.id()))
                    .get()
                    .extracting(PrimaryGameplayRootRef::rootId)
                    .isEqualTo(islandId.value().toString());
            assertThat(hierarchy.findRootRefByRootId(islandId.value().toString(), "ISLAND"))
                    .get()
                    .extracting(PrimaryGameplayRootRef::gameModeInstanceId)
                    .isEqualTo(instance.id());

            // A OneBlock island's block and count, added to twice.
            oneBlock.start(islandId, 0, 100, 0);
            oneBlock.addBreaks(islandId, 3);
            oneBlock.addBreaks(islandId, 4);
            assertThat(oneBlock.find(islandId)).get().satisfies(read -> {
                assertThat(read.y()).isEqualTo(100);
                assertThat(read.blocksBroken()).isEqualTo(7);
            });
            assertThat(oneBlock.findAll()).extracting(read -> read.islandId()).contains(islandId);
            // An operator's count, written whole, lower and higher.
            oneBlock.setBreaks(islandId, 2);
            assertThat(oneBlock.find(islandId).orElseThrow().blocksBroken()).isEqualTo(2);
            oneBlock.setBreaks(islandId, 7);
            assertThat(oneBlock.find(islandId).orElseThrow().blocksBroken()).isEqualTo(7);

            // The island's authority: taken, renewed under its epoch, refused under a stale one.
            IslandAuthorityOutcome taken = islands.acquireAuthority(islandId, NODE, 60);
            assertThat(taken.isSuccess()).isTrue();
            long epoch = islands.findAuthority(islandId).orElseThrow().authorityEpoch();
            assertThat(islands.renewAuthority(islandId, NODE, epoch, 60).isSuccess())
                    .isTrue();
            assertThat(islands.renewAuthority(islandId, NODE, epoch + 5, 60).isRejected())
                    .isTrue();
            assertThat(islands.findAuthority(islandId).orElseThrow().leaseExpiresAt())
                    .isAfter(Instant.now());

            // Which nodes are alive: published twice, one in another world, one heard from long ago.
            SqlClusterNodesAdapter nodes = new SqlClusterNodesAdapter(database);
            nodes.publish(new NodeHealth(NODE, true, 3, 100, 12.5), "skyblock");
            nodes.publish(new NodeHealth(NODE, true, 4, 100, 11.0), "skyblock");
            nodes.publish(new NodeHealth(ServerNodeId.of("node-elsewhere"), true, 0, 100, 5.0), "elsewhere");
            try (Connection conn = database.connection();
                    PreparedStatement stmt = conn.prepareStatement("INSERT INTO cluster_nodes (node_id, world_name, "
                            + "hosted, capacity, average_mspt, last_seen) "
                            + "VALUES ('node-stopped', 'skyblock', 0, 100, 1.0, '2000-01-01 00:00:00')")) {
                stmt.executeUpdate();
            }
            assertThat(nodes.serving("skyblock", Duration.ofMinutes(4)))
                    .containsExactly(new NodeHealth(NODE, true, 4, 100, 11.0));

            // A placement reads those rows and the island's lease stays exactly as it was.
            IslandAuthorityRecord before = islands.findAuthority(islandId).orElseThrow();
            String leaseBefore = lease(database, islandId);
            IslandPlacement placement = new IslandPlacement(
                    PlacementStrategies.shipped(PlacementStrategy.DEFAULT_MSPT_CEILING),
                    PlacementStrategies.LEAST_LOADED,
                    nodes,
                    id -> islands.findLocationByIslandId(id).map(IslandLocation::worldName),
                    id -> hierarchy.findRootRefByRootId(id.value().toString(), "ISLAND"),
                    Duration.ofMinutes(4));
            assertThat(placement.recommend(islandId)).contains(NODE);
            assertThat(islands.findAuthority(islandId)).get().satisfies(after -> {
                assertThat(after.authoritativeNode()).isEqualTo(before.authoritativeNode());
                assertThat(after.authorityEpoch()).isEqualTo(before.authorityEpoch());
            });
            assertThat(lease(database, islandId)).isEqualTo(leaseBefore);

            // What a lifecycle event owes a player who was away: owed once however often, paid in part.
            com.uxplima.uxmskyblock.persistence.lifecycle.SqlLifecycleOwedEffectsAdapter owedEffects =
                    new com.uxplima.uxmskyblock.persistence.lifecycle.SqlLifecycleOwedEffectsAdapter(
                            database.dataSource());
            owedEffects.owe(owner, java.util.Set.of(LifecycleEffect.CLEAR_INVENTORY, LifecycleEffect.SEND_TO_SPAWN));
            owedEffects.owe(owner, java.util.Set.of(LifecycleEffect.CLEAR_INVENTORY));
            assertThat(owedEffects.owed(owner))
                    .containsExactlyInAnyOrder(LifecycleEffect.CLEAR_INVENTORY, LifecycleEffect.SEND_TO_SPAWN);
            owedEffects.settle(owner, java.util.Set.of(LifecycleEffect.CLEAR_INVENTORY));
            assertThat(owedEffects.owed(owner)).containsExactly(LifecycleEffect.SEND_TO_SPAWN);
            assertThat(owedEffects.owed(PlayerUuid.of(UUID.randomUUID()))).isEmpty();

            // A ChunkBlock island's chunks: started once, opened in order, a taken place refused, closed.
            SqlChunkTerritoryAdapter chunks = new SqlChunkTerritoryAdapter(database.dataSource());
            chunks.start(islandId, new ChunkPos(0, 0));
            chunks.start(islandId, new ChunkPos(5, 5));
            assertThat(chunks.open(islandId, new ChunkPos(1, 0), 1)).isTrue();
            assertThat(chunks.open(islandId, new ChunkPos(0, 1), 1))
                    .describedAs("two openings at once: the second finds its place taken")
                    .isFalse();
            assertThat(chunks.open(islandId, new ChunkPos(1, 0), 2)).isFalse();
            assertThat(chunks.open(islandId, new ChunkPos(-1, 0), 2)).isTrue();
            assertThat(chunks.find(islandId)).get().satisfies(territory -> {
                assertThat(territory.origin()).isEqualTo(new ChunkPos(0, 0));
                assertThat(territory.opened()).containsExactly(new ChunkPos(1, 0), new ChunkPos(-1, 0));
            });
            chunks.close(islandId, java.util.List.of(new ChunkPos(-1, 0), new ChunkPos(0, 0)));
            assertThat(chunks.find(islandId).orElseThrow().opened()).containsExactly(new ChunkPos(1, 0));
            assertThat(chunks.find(islandId).orElseThrow().origin())
                    .describedAs("the first chunk is never closed")
                    .isEqualTo(new ChunkPos(0, 0));
            assertThat(chunks.open(islandId, new ChunkPos(-1, 0), 2)).isTrue();
            assertThat(chunks.findAll().get(islandId).opened())
                    .containsExactly(new ChunkPos(1, 0), new ChunkPos(-1, 0));

            // An AcidIsland island's sea level: recorded once, a second record keeps the first.
            SqlAcidIslandsAdapter acid = new SqlAcidIslandsAdapter(database.dataSource());
            acid.add(islandId, 97);
            acid.add(islandId, 40);
            assertThat(acid.findAll()).containsEntry(islandId, 97);
            assertThat(acid.find(islandId)).hasValue(97);

            // A Boxed island's advancements: each earned once, summed, gone with the island.
            com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedIslandsAdapter boxed =
                    new com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedIslandsAdapter(database.dataSource());
            boxed.add(islandId);
            boxed.add(islandId);
            assertThat(boxed.find(islandId)).hasValue(0);
            assertThat(boxed.earn(islandId, "minecraft:story/mine_stone", 1)).isTrue();
            assertThat(boxed.earn(islandId, "minecraft:story/mine_diamond", 3)).isTrue();
            assertThat(boxed.earn(islandId, "minecraft:story/mine_stone", 1)).isFalse();
            assertThat(boxed.find(islandId)).hasValue(4);
            assertThat(boxed.findAll()).containsEntry(islandId, 4L);
            // An operator's change is one row of its own: made by the first, added to by the next, and summed in.
            boxed.grant(islandId, 5);
            assertThat(boxed.find(islandId)).hasValue(9);
            boxed.grant(islandId, -5);
            assertThat(boxed.find(islandId)).hasValue(4);
            assertThat(boxed.earn(
                            islandId, com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort.OPERATOR, 1))
                    .describedAs("the operator's row is there, once")
                    .isFalse();

            // A Poseidon island: recorded once, found, gone with the island.
            com.uxplima.uxmskyblock.persistence.poseidon.SqlPoseidonIslandsAdapter poseidon =
                    new com.uxplima.uxmskyblock.persistence.poseidon.SqlPoseidonIslandsAdapter(database.dataSource());
            poseidon.add(islandId);
            poseidon.add(islandId);
            assertThat(poseidon.exists(islandId)).isTrue();
            assertThat(poseidon.findAll()).contains(islandId);

            // A StrangerRealms island: recorded once, found, gone with the island.
            com.uxplima.uxmskyblock.persistence.stranger.SqlStrangerRealmsAdapter stranger =
                    new com.uxplima.uxmskyblock.persistence.stranger.SqlStrangerRealmsAdapter(database.dataSource());
            stranger.add(islandId);
            stranger.add(islandId);
            assertThat(stranger.exists(islandId)).isTrue();
            assertThat(stranger.findAll()).contains(islandId);
            // A Brix plot: recorded once, found, gone with the island.
            com.uxplima.uxmskyblock.persistence.brix.SqlBrixPlotsAdapter brix =
                    new com.uxplima.uxmskyblock.persistence.brix.SqlBrixPlotsAdapter(database.dataSource());
            brix.add(islandId);
            brix.add(islandId);
            assertThat(brix.exists(islandId)).isTrue();
            assertThat(brix.findAll()).containsExactly(islandId);
            // A TradeWinds vessel: recorded once, found, gone with the island.
            com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter vessels =
                    new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter(database.dataSource());
            vessels.add(islandId);
            vessels.add(islandId);
            assertThat(vessels.exists(islandId)).isTrue();
            assertThat(vessels.findAll()).containsExactly(islandId);
            // Its hold, a sale and a purchase at a port's market and a voyage, under its lease.
            long lease = ((com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome.Success)
                            new com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter(database)
                                    .acquire(
                                            com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease.rootOf(
                                                    islandId),
                                            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-a"),
                                            30))
                    .epoch();
            com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketOrdersAdapter market =
                    new com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketOrdersAdapter(database);
            var node = com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-a");
            var sale = new com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.Order(
                    UUID.randomUUID(),
                    islandId,
                    "bay",
                    com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.Kind.SALE,
                    "WHEAT",
                    10,
                    200,
                    owner,
                    com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.State.OWED,
                    0);
            assertThat(market.recordSale(
                            sale,
                            new com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.HoldWrite(
                                    lease, node, 1, new byte[] {1, 2, 3})))
                    .isTrue();
            assertThat(vessels.cargo(islandId).orElseThrow().items()).containsExactly(1, 2, 3);
            var purchase = new com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.Order(
                    UUID.randomUUID(),
                    islandId,
                    "bay",
                    com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.Kind.PURCHASE,
                    "WHEAT",
                    10,
                    500,
                    owner,
                    com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.State.PAYING,
                    0);
            market.recordPurchase(purchase);
            market.attempt(purchase.id(), 2);
            assertThat(market.deliver(
                            purchase.id(),
                            new com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.HoldWrite(
                                    lease, node, 2, new byte[] {4})))
                    .isTrue();
            assertThat(market.settle(
                            sale.id(),
                            com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.State.OWED,
                            com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.State.REFUNDING))
                    .isTrue();
            assertThat(market.find(sale.id()).orElseThrow().attempts()).isZero();
            assertThat(market.standing(islandId, "bay")).isEqualTo(700);
            assertThat(market.tradeVolume(islandId)).isEqualTo(700);
            // An operator's rank and standing: set whole, a port's standing made where there was none.
            assertThat(market.setTradeVolume(islandId, 5_000)).isTrue();
            assertThat(market.tradeVolume(islandId)).isEqualTo(5_000);
            assertThat(market.setTradeVolume(IslandId.of(UUID.randomUUID()), 1)).isFalse();
            market.setStanding(islandId, "bay", 40);
            market.setStanding(islandId, "cove", 90);
            assertThat(market.standing(islandId, "bay")).isEqualTo(40);
            assertThat(market.standing(islandId, "cove")).isEqualTo(90);
            assertThat(market.setTradeVolume(islandId, 700)).isTrue();
            market.setStanding(islandId, "bay", 700);
            assertThat(market.vesselsWithOpenOrders()).containsExactly(islandId);
            assertThat(market.open(islandId)).hasSize(1);
            var voyagesTable = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyagesAdapter(database);
            java.time.Instant arrives = java.time.Instant.parse("2030-01-01T00:00:00Z");
            assertThat(voyagesTable.setSail(
                            islandId,
                            lease,
                            node,
                            new com.uxplima.uxmskyblock.core.application.tradewinds.VoyagesPort.Voyage("bay", arrives)))
                    .isTrue();
            assertThat(voyagesTable.setSail(
                            islandId,
                            lease,
                            node,
                            new com.uxplima.uxmskyblock.core.application.tradewinds.VoyagesPort.Voyage(
                                    "cove", arrives)))
                    .isTrue();
            assertThat(voyagesTable.voyage(islandId).orElseThrow().portId()).isEqualTo("cove");
            assertThat(voyagesTable.voyage(islandId).orElseThrow().arrivesAt()).isEqualTo(arrives);
            // A Parkour course: recorded once, a runner's first finish and a faster and a slower one.
            com.uxplima.uxmskyblock.persistence.parkour.SqlParkourAdapter parkour =
                    new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourAdapter(database.dataSource());
            com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid runner =
                    com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(java.util.UUID.randomUUID());
            parkour.add(islandId);
            parkour.add(islandId);
            assertThat(parkour.exists(islandId)).isTrue();
            assertThat(parkour.best(islandId, runner)).isEmpty();
            parkour.finish(islandId, runner, 30_000);
            parkour.finish(islandId, runner, 25_000);
            parkour.finish(islandId, runner, 40_000);
            com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid second =
                    com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(java.util.UUID.randomUUID());
            parkour.finish(islandId, second, 50_000);
            assertThat(parkour.best(islandId, runner)).hasValue(25_000);
            assertThat(parkour.top(islandId, 5))
                    .containsExactly(
                            new com.uxplima.uxmskyblock.core.application.parkour.ParkourPort.Best(runner, 25_000, 3),
                            new com.uxplima.uxmskyblock.core.application.parkour.ParkourPort.Best(second, 50_000, 1));
            assertThat(parkour.mostRun(5)).singleElement().satisfies(most -> {
                assertThat(most.course()).isEqualTo(islandId);
                assertThat(most.runs())
                        .describedAs("every runner's finishes on the course, together")
                        .isEqualTo(4);
                assertThat(most.name()).isNotBlank();
            });

            // Each mode's board reads its own table: a second island behind the first on each, and faster on its
            // course, ranks where it should.
            PlayerUuid otherOwner = PlayerUuid.of(UUID.randomUUID());
            ProfileId otherProfile = ProfileId.of(UUID.randomUUID());
            account(database, otherOwner, otherProfile);
            IslandId other = IslandId.of(UUID.randomUUID());
            islands.saveIsland(
                    Island.create(
                            other,
                            IslandBounds.fromCenterAndRadius(400, 0, 100),
                            otherOwner,
                            otherProfile,
                            Instant.now()),
                    IslandLocation.fromCenterAndRadius(other, "skyblock", 400, 0, 100));
            oneBlock.start(other, 400, 100, 0);
            oneBlock.addBreaks(other, 2);
            parkour.add(other);
            parkour.finish(other, runner, 10_000);
            boxed.add(other);
            chunks.start(other, new ChunkPos(25, 0));
            com.uxplima.uxmskyblock.persistence.leaderboard.SqlModeStandingsAdapter standings =
                    new com.uxplima.uxmskyblock.persistence.leaderboard.SqlModeStandingsAdapter(database.dataSource());
            assertThat(standings.mostBroken(5))
                    .extracting(s -> s.islandId() + " " + s.value())
                    .containsExactly(islandId + " 7", other + " 2");
            assertThat(standings.fastestCourses(5))
                    .extracting(s -> s.islandId() + " " + s.value())
                    .describedAs("each course by the best anybody ran it in, the fastest first")
                    .containsExactly(other + " 10000", islandId + " 25000");
            assertThat(standings.mostEarned(5))
                    .extracting(s -> s.islandId() + " " + s.value())
                    .describedAs("a box that earned nothing is on the board at nothing")
                    .containsExactly(islandId + " 4", other + " 0");
            assertThat(standings.mostChunks(5))
                    .extracting(s -> s.islandId() + " " + s.value())
                    .describedAs("the chunk it started with and the two it opened")
                    .containsExactly(islandId + " 3", other + " 1");
            assertThat(standings.mostChunks(1)).hasSize(1);
            assertThat(standings.mostBroken(5))
                    .allSatisfy(s -> assertThat(s.name()).isNotBlank());
            islands.deleteIsland(other);
            assertThat(standings.mostBroken(5)).extracting(s -> s.islandId()).containsExactly(islandId);
            assertThat(stranger.farthestReach())
                    .describedAs("the edge of the island furthest from the centre, on x or z")
                    .isGreaterThan(0);

            // Deleting the island takes its OneBlock row, its chunks and its sea with it.
            islands.deleteIsland(islandId);
            assertThat(acid.findAll()).doesNotContainKey(islandId);
            assertThat(acid.find(islandId)).isEmpty();
            assertThat(boxed.find(islandId)).isEmpty();
            assertThat(poseidon.exists(islandId)).isFalse();
            assertThat(stranger.exists(islandId)).isFalse();
            assertThat(stranger.farthestReach()).isZero();
            assertThat(parkour.exists(islandId)).isFalse();
            assertThat(brix.exists(islandId)).isFalse();
            assertThat(brix.findAll()).isEmpty();
            assertThat(vessels.exists(islandId)).isFalse();
            assertThat(vessels.findAll()).isEmpty();
            assertThat(market.vesselsWithOpenOrders()).isEmpty();
            assertThat(voyagesTable.voyage(islandId)).isEmpty();
            assertThat(parkour.top(islandId, 5)).isEmpty();
            assertThat(parkour.mostRun(5)).isEmpty();
            assertThat(oneBlock.find(islandId)).isEmpty();
            assertThat(chunks.find(islandId)).isEmpty();
            assertThat(standings.mostBroken(5)).isEmpty();
            assertThat(standings.fastestCourses(5)).isEmpty();
            assertThat(standings.mostEarned(5)).isEmpty();
            assertThat(standings.mostChunks(5)).isEmpty();
        } finally {
            if (!database.isClosed()) {
                database.close();
            }
        }
    }

    /** The island's lease row as the database holds it, read without the adapter. */
    private static String lease(Database database, IslandId islandId) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("SELECT authoritative_node, authority_epoch, "
                        + "lease_expires_at, last_heartbeat_at FROM island_authorities WHERE island_id = ?")) {
            stmt.setString(1, islandId.value().toString());
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1) + " " + rs.getLong(2) + " " + rs.getString(3) + " " + rs.getString(4);
            }
        }
    }

    private static void account(Database database, PlayerUuid owner, ProfileId profile) throws Exception {
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, owner.value().toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, owner.value().toString());
                stmt.executeUpdate();
            }
        }
    }
}
