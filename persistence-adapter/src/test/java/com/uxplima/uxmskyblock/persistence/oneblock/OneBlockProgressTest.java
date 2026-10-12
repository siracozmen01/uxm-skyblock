package com.uxplima.uxmskyblock.persistence.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A OneBlock island's count of breaks is kept in memory, added to the stored count on a schedule, and
 * picked up where it left off by the next server.
 */
class OneBlockProgressTest {

    private static final OneBlockPhases PHASES = new OneBlockPhases(
            List.of(
                    new OneBlockPhase("plains", 3, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 0),
                    new OneBlockPhase("deep", 5, new WeightedPool(Map.of("STONE", 1.0)), WeightedPool.empty(), 0)),
            OneBlockPhases.AfterTheLast.STAY);

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicBoolean writesFail = new AtomicBoolean();

    private Database database;
    private SqlOneBlockProgressAdapter stored;
    private OneBlockProgressPort counting;
    private IslandId island;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        island = island();
        stored = new SqlOneBlockProgressAdapter(database.dataSource());
        counting = new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.start(islandId, x, y, z);
            }

            @Override
            public List<OneBlockIsland> findAll() {
                reads.incrementAndGet();
                return stored.findAll();
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                reads.incrementAndGet();
                return stored.find(islandId);
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {
                if (writesFail.get()) {
                    throw new IllegalStateException("the database went away");
                }
                stored.addBreaks(islandId, breaks);
            }

            @Override
            public void setBreaks(IslandId islandId, long count) {
                if (writesFail.get()) {
                    throw new IllegalStateException("the database went away");
                }
                stored.setBreaks(islandId, count);
            }
        };
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Breaks are counted in memory, written together, and the next server carries on from them")
    void breaksAreCountedAndCarriedOn() {
        OneBlockService service = service();
        service.start(island, 0, 100, 0);

        for (int i = 0; i < 4; i++) {
            service.onBreak(island);
        }
        assertThat(stored.find(island).orElseThrow().blocksBroken())
                .describedAs("nothing written between flushes")
                .isZero();
        assertThat(service.flush()).isEqualTo(1);
        assertThat(stored.find(island).orElseThrow().blocksBroken()).isEqualTo(4);

        OneBlockService next = service();
        assertThat(next.position(island).orElseThrow().phase().key()).isEqualTo("deep");
        assertThat(next.onBreak(island).orElseThrow().position().intoPhase()).isEqualTo(2);
    }

    @Test
    @DisplayName("The break that starts a phase says so, and the block comes from the new phase")
    void aPhaseBeginsOnItsFirstBreak() {
        OneBlockService service = service();
        service.start(island, 0, 100, 0);

        assertThat(service.onBreak(island).orElseThrow().phaseBegan()).isFalse();
        assertThat(service.onBreak(island).orElseThrow().nextBlock()).isEqualTo("DIRT");
        OneBlockService.Broken third = service.onBreak(island).orElseThrow();

        assertThat(third.phaseBegan()).isTrue();
        assertThat(third.nextBlock()).isEqualTo("STONE");
    }

    @Test
    @DisplayName("A failed write keeps its breaks for the next one, and none is counted twice")
    void aFailedWriteKeepsTheBreaks() {
        OneBlockService service = service();
        service.start(island, 0, 100, 0);
        service.onBreak(island);
        service.onBreak(island);

        writesFail.set(true);
        assertThat(service.flush()).isZero();
        writesFail.set(false);
        service.onBreak(island);
        service.flush();
        service.flush();

        assertThat(stored.find(island).orElseThrow().blocksBroken()).isEqualTo(3);
    }

    @Test
    @DisplayName("An operator's count is written whole, lower or higher, and breaks after it are added to it")
    void anOperatorsCountIsWrittenWhole() {
        OneBlockService service = service();
        service.start(island, 0, 100, 0);
        for (int i = 0; i < 5; i++) {
            service.onBreak(island);
        }
        service.flush();

        service.addBroken(island, -3);
        assertThat(stored.find(island).orElseThrow().blocksBroken()).isEqualTo(2);
        service.onBreak(island);
        service.setBroken(island, 1);
        assertThat(stored.find(island).orElseThrow().blocksBroken())
                .describedAs("the break before the set is part of what was set over")
                .isEqualTo(1);
        service.onBreak(island);
        service.flush();
        assertThat(stored.find(island).orElseThrow().blocksBroken()).isEqualTo(2);
    }

    @Test
    @DisplayName("An island that is not a OneBlock island is read once, however many blocks break on it")
    void anOrdinaryIslandIsReadOnce() {
        OneBlockService service = service();

        for (int i = 0; i < 50; i++) {
            assertThat(service.onBreak(island)).isEmpty();
        }

        assertThat(reads).hasValue(1);
    }

    @Test
    @DisplayName("Once primed, every OneBlock island is held and no break asks the database anything")
    void aPrimedServiceNeverReads() throws Exception {
        stored.start(island, 1, 64, 1);
        IslandId ordinary = island();
        OneBlockService service = service();

        assertThat(service.prime()).isEqualTo(1);
        int afterPriming = reads.get();
        assertThat(service.onBreak(island)).isPresent();
        assertThat(service.onBreak(ordinary)).isEmpty();
        assertThat(service.island(island).orElseThrow().x()).isEqualTo(1);

        assertThat(reads).hasValue(afterPriming);

        // A change on another node makes this node forget the island; it is still a OneBlock island.
        service.forgetIsland(island);
        assertThat(stored.find(island).orElseThrow().blocksBroken())
                .describedAs("forgetting wrote the one break")
                .isEqualTo(1);
        assertThat(service.onBreak(island)).isPresent();
        assertThat(service.island(island).orElseThrow().blocksBroken()).isEqualTo(2);
    }

    @Test
    @DisplayName("Forgetting an island writes what it counted, and the row goes when the island does")
    void forgettingWritesAndDeletionCascades() throws Exception {
        OneBlockService service = service();
        service.start(island, 5, 90, -5);
        service.onBreak(island);

        service.forgetIsland(island);
        assertThat(stored.find(island).orElseThrow())
                .isEqualTo(new OneBlockProgressPort.OneBlockIsland(island, 5, 90, -5, 1));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, island.value().toString());
            ps.executeUpdate();
        }
        assertThat(stored.find(island)).isEmpty();
    }

    private OneBlockService service() {
        return new OneBlockService(counting, PHASES, new SplittableRandom(1));
    }

    private IslandId island() throws Exception {
        IslandId id = IslandId.of(UUID.randomUUID());
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, lifecycle, economic_state,"
                                + " administrative_state, level_score, net_worth_minor_units, version)"
                                + " VALUES (?, ?, ?, 'ACTIVE', 'NORMAL', 'NORMAL', 0, 0, 1)")) {
            ps.setString(1, id.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
        return id;
    }
}
