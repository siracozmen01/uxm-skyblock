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
        SqlChunkPaymentAdapter payments = new SqlChunkPaymentAdapter(database.dataSource());
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
