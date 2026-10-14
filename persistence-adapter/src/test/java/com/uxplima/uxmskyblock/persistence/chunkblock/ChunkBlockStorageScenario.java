package com.uxplima.uxmskyblock.persistence.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with a ChunkBlock island's chunks, what is paid toward them, and its chunk points. */
final class ChunkBlockStorageScenario {

    private ChunkBlockStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        seedIsland(database, island);
        seedIsland(database, other);
        territory(database, island);
        payments(database, island, other);
        charges(database, island);
        IslandId webbed = IslandId.of(UUID.randomUUID());
        seedIsland(database, webbed);
        dimensions(database, webbed);
    }

    /**
     * A Nether and an End each keep a web of their own beside the overworld's, at the very same chunks, and what is
     * paid toward a chunk is kept apart by dimension; every row goes with its island.
     */
    private static void dimensions(Database database, IslandId island) throws SQLException {
        SqlChunkTerritoryAdapter overworld = new SqlChunkTerritoryAdapter(database.dataSource());
        SqlChunkTerritoryAdapter nether = new SqlChunkTerritoryAdapter(database.dataSource(), "the_nether");
        SqlChunkTerritoryAdapter end = new SqlChunkTerritoryAdapter(database.dataSource(), "the_end");
        overworld.start(island, new ChunkPos(0, 0));
        overworld.open(island, new ChunkPos(1, 0), 1, false);
        nether.start(island, new ChunkPos(0, 0));
        assertThat(nether.open(island, new ChunkPos(0, 1), 1, true)).isTrue();
        assertThat(nether.open(island, new ChunkPos(0, 1), 2, false))
                .describedAs("opened once")
                .isFalse();
        assertThat(nether.open(island, new ChunkPos(2, 0), 1, false))
                .describedAs("the place is taken")
                .isFalse();
        assertThat(nether.open(island, new ChunkPos(-1, 0), 2, false)).isTrue();

        assertThat(end.find(island))
                .describedAs("an End not started has no web")
                .isEmpty();
        assertThat(overworld.find(island).orElseThrow().opened()).containsExactly(new ChunkPos(1, 0));
        assertThat(nether.find(island).orElseThrow().opened()).containsExactly(new ChunkPos(0, 1), new ChunkPos(-1, 0));
        assertThat(nether.find(island).orElseThrow().bought()).containsExactly(new ChunkPos(0, 1));
        assertThat(nether.findAll()).containsOnlyKeys(island);
        assertThat(end.findAll()).isEmpty();
        assertThat(overworld
                        .findAll()
                        .getOrDefault(island, ChunkTerritory.startingAt(new ChunkPos(9, 9)))
                        .opened())
                .containsExactly(new ChunkPos(1, 0));

        // The End's web, numbered from 1 too, is left alone when the Nether's is numbered again.
        end.start(island, new ChunkPos(0, 0));
        assertThat(end.open(island, new ChunkPos(5, 5), 1, false)).isTrue();
        assertThat(end.open(island, new ChunkPos(6, 5), 2, false)).isTrue();
        nether.close(island, java.util.List.of(new ChunkPos(0, 1)));
        assertThat(nether.find(island).orElseThrow().opened())
                .describedAs("closed and numbered again in its own web")
                .containsExactly(new ChunkPos(-1, 0));
        assertThat(nether.open(island, new ChunkPos(0, 1), 2, false)).isTrue();
        assertThat(nether.open(island, new ChunkPos(0, -1), 3, false))
                .describedAs("numbered 1 and 2 again, the third place is free")
                .isTrue();
        assertThat(end.find(island).orElseThrow().opened()).containsExactly(new ChunkPos(5, 5), new ChunkPos(6, 5));
        overworld.close(island, java.util.List.of(new ChunkPos(1, 0)));
        assertThat(nether.find(island).orElseThrow().opened()).hasSize(3);

        SqlChunkPaymentAdapter overworldPaid = new SqlChunkPaymentAdapter(database);
        SqlChunkPaymentAdapter netherPaid = new SqlChunkPaymentAdapter(database, "the_nether");
        ChunkPos chunk = new ChunkPos(3, 3);
        overworldPaid.add(island, chunk, "item:cobblestone", 10);
        netherPaid.add(island, chunk, "item:cobblestone", 4);
        netherPaid.add(island, chunk, "item:cobblestone", 5);
        assertThat(overworldPaid.find(island, chunk).paid()).containsOnly(java.util.Map.entry("item:cobblestone", 10L));
        assertThat(netherPaid.find(island, chunk).paid()).containsOnly(java.util.Map.entry("item:cobblestone", 9L));
        netherPaid.clear(island, chunk);
        assertThat(netherPaid.find(island, chunk).paid()).isEmpty();
        assertThat(overworldPaid.find(island, chunk).paid())
                .describedAs("the overworld's stays")
                .hasSize(1);
        netherPaid.add(island, chunk, "item:netherrack", 2);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, island.value().toString());
            ps.executeUpdate();
        }
        assertThat(nether.find(island))
                .describedAs("the web goes with the island")
                .isEmpty();
        assertThat(netherPaid.find(island, chunk).paid()).isEmpty();
    }

    /**
     * A payment out of the island's bank is charged and recorded together, adds to what was paid on every engine,
     * and a charge the bank refuses records nothing.
     */
    private static void charges(Database database, IslandId island) throws SQLException {
        String node = "node-chunk";
        long epoch = 7;
        claimAuthority(database, island, node, epoch);
        com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter bank =
                new com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter(database);
        bank.createBank(island);
        UUID actor = UUID.randomUUID();
        assertThat(bank.executeTransaction(
                        island,
                        actor,
                        "PRIMARY",
                        2,
                        10_000,
                        "seed",
                        node,
                        epoch,
                        bank.findBankByIslandId(island).orElseThrow().version(),
                        UUID.randomUUID(),
                        "seed-1"))
                .isInstanceOf(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.Success.class);
        SqlChunkPaymentAdapter payments = new SqlChunkPaymentAdapter(database);
        ChunkPos chunk = new ChunkPos(-1, 0);

        for (long amount : new long[] {3_000, 4_000}) {
            UUID operation = UUID.randomUUID();
            var outcome = payments.chargeIslandAndRecord(
                            new com.uxplima.uxmskyblock.core.application.chunkblock.ChunkIslandCharge(
                                    island,
                                    chunk,
                                    "money:island",
                                    actor,
                                    "PRIMARY",
                                    2,
                                    amount,
                                    "Chunk -1,0",
                                    node,
                                    epoch,
                                    bank.findBankByIslandId(island)
                                            .orElseThrow()
                                            .version(),
                                    operation,
                                    "chunk-" + operation))
                    .orElseThrow();
            assertThat(outcome)
                    .isInstanceOf(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.Success.class);
        }

        assertThat(payments.find(island, chunk).paid()).containsOnly(java.util.Map.entry("money:island", 7_000L));
        assertThat(bank.findBankByIslandId(island).orElseThrow().primaryBalanceMinorUnits())
                .isEqualTo(3_000);

        UUID refused = UUID.randomUUID();
        var outcome = payments.chargeIslandAndRecord(
                        new com.uxplima.uxmskyblock.core.application.chunkblock.ChunkIslandCharge(
                                island,
                                chunk,
                                "money:island",
                                actor,
                                "PRIMARY",
                                2,
                                5_000,
                                "Chunk -1,0",
                                node,
                                epoch,
                                bank.findBankByIslandId(island).orElseThrow().version(),
                                refused,
                                "chunk-" + refused))
                .orElseThrow();
        assertThat(outcome)
                .isInstanceOf(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.InsufficientFunds.class);
        assertThat(payments.find(island, chunk).paid())
                .describedAs("a refused charge records nothing")
                .containsOnly(java.util.Map.entry("money:island", 7_000L));
        assertThat(bank.findBankByIslandId(island).orElseThrow().primaryBalanceMinorUnits())
                .isEqualTo(3_000);

        // The same chunk in the island's Nether: charged and recorded apart, and added to there on every engine.
        SqlChunkPaymentAdapter netherPayments = new SqlChunkPaymentAdapter(database, "the_nether");
        for (long amount : new long[] {1_000, 500}) {
            UUID operation = UUID.randomUUID();
            assertThat(netherPayments
                            .chargeIslandAndRecord(
                                    new com.uxplima.uxmskyblock.core.application.chunkblock.ChunkIslandCharge(
                                            island,
                                            chunk,
                                            "money:island",
                                            actor,
                                            "PRIMARY",
                                            2,
                                            amount,
                                            "Chunk -1,0",
                                            node,
                                            epoch,
                                            bank.findBankByIslandId(island)
                                                    .orElseThrow()
                                                    .version(),
                                            operation,
                                            "chunk-" + operation))
                            .orElseThrow())
                    .isInstanceOf(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.Success.class);
        }
        assertThat(netherPayments.find(island, chunk).paid()).containsOnly(java.util.Map.entry("money:island", 1_500L));
        assertThat(payments.find(island, chunk).paid()).containsOnly(java.util.Map.entry("money:island", 7_000L));
    }

    private static void claimAuthority(Database database, IslandId island, String node, long epoch)
            throws SQLException {
        String later = database.dialect() == com.uxplima.uxmlib.storage.sql.Dialect.POSTGRES
                ? "CURRENT_TIMESTAMP + INTERVAL '60 seconds'"
                : database.dialect() == com.uxplima.uxmlib.storage.sql.Dialect.SQLITE
                        ? "datetime('now', '+60 seconds')"
                        : "CURRENT_TIMESTAMP + INTERVAL 60 SECOND";
        try (Connection conn = database.connection();
                java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO island_authorities (island_id, authoritative_node, authority_epoch,"
                    + " lease_expires_at, last_heartbeat_at) VALUES ('" + island.value() + "', '" + node + "', "
                    + epoch + ", " + later + ", CURRENT_TIMESTAMP)");
        }
    }

    /** A chunk paid for is read back as paid for, and closing a held chunk older than it leaves room to open. */
    private static void territory(Database database, IslandId island) {
        SqlChunkTerritoryAdapter claims = new SqlChunkTerritoryAdapter(database.dataSource());
        ChunkPos origin = new ChunkPos(0, 0);
        ChunkPos held = new ChunkPos(1, 0);
        ChunkPos paid = new ChunkPos(2, 0);
        claims.start(island, origin);

        assertThat(claims.open(island, held, 1)).isTrue();
        assertThat(claims.open(island, paid, 2, true)).isTrue();
        assertThat(claims.open(island, new ChunkPos(3, 0), 2))
                .describedAs("the place is taken")
                .isFalse();

        ChunkTerritory read = claims.find(island).orElseThrow();
        assertThat(read.opened()).containsExactly(held, paid);
        assertThat(read.bought()).containsExactly(paid);
        assertThat(java.util.Objects.requireNonNull(claims.findAll().get(island))
                        .bought())
                .containsExactly(paid);

        claims.close(island, List.of(held));

        ChunkTerritory after = claims.find(island).orElseThrow();
        assertThat(after.opened()).containsExactly(paid);
        assertThat(after.isBought(paid)).isTrue();
        assertThat(claims.open(island, new ChunkPos(0, 1), after.opened().size() + 1))
                .describedAs("the chunks left are numbered again, so the next place is free")
                .isTrue();
        assertThat(claims.find(island).orElseThrow().opened()).containsExactly(paid, new ChunkPos(0, 1));
    }

    /** Payments add up line by line and chunk by chunk, are forgotten with their chunk, and points never go below. */
    private static void payments(Database database, IslandId island, IslandId other) {
        SqlChunkPaymentAdapter payments = new SqlChunkPaymentAdapter(database);
        ChunkPos chunk = new ChunkPos(5, -3);
        ChunkPos beside = new ChunkPos(6, -3);

        assertThat(payments.find(island, chunk)).isEqualTo(ChunkPayments.none());
        payments.add(island, chunk, "item:minecraft:oak_log", 40);
        payments.add(island, chunk, "item:minecraft:oak_log", 56);
        payments.add(island, chunk, "money:player", 1_000_000_000_000L);
        payments.add(island, beside, "points", 3);
        payments.add(other, chunk, "points", 9);

        ChunkPayments read = payments.find(island, chunk);
        assertThat(read.paid())
                .containsOnly(
                        java.util.Map.entry("item:minecraft:oak_log", 96L),
                        java.util.Map.entry("money:player", 1_000_000_000_000L));

        payments.clear(island, chunk);
        assertThat(payments.find(island, chunk)).isEqualTo(ChunkPayments.none());
        assertThat(payments.find(island, beside).paid()).containsOnly(java.util.Map.entry("points", 3L));
        assertThat(payments.find(other, chunk).paid()).containsOnly(java.util.Map.entry("points", 9L));

        assertThat(payments.points(island)).isZero();
        assertThat(payments.spendPoints(island, 1)).describedAs("none held").isFalse();
        payments.addPoints(island, 10);
        payments.addPoints(island, 5);
        assertThat(payments.points(island)).isEqualTo(15);
        assertThat(payments.spendPoints(island, 16)).isFalse();
        assertThat(payments.points(island))
                .describedAs("a refused spend takes nothing")
                .isEqualTo(15);
        assertThat(payments.spendPoints(island, 15)).isTrue();
        assertThat(payments.points(island)).isZero();
        assertThat(payments.points(other)).isZero();
    }

    private static void seedIsland(Database database, IslandId island) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, "
                                + "updated_at) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
    }
}
