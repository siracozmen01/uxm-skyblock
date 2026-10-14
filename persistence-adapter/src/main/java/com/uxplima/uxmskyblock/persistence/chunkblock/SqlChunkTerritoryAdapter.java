package com.uxplima.uxmskyblock.persistence.chunkblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The chunks each ChunkBlock island opened: the overworld's in {@code chunkblock_territory_claims}, a Nether's or an
 * End's in {@code chunkblock_dimension_claims} under its dimension.
 */
public final class SqlChunkTerritoryAdapter implements ChunkTerritoryPort {

    private final DataSource dataSource;
    private final ChunkScope scope;

    /** The overworld's web. */
    public SqlChunkTerritoryAdapter(DataSource dataSource) {
        this(dataSource, ChunkScope.OVERWORLD);
    }

    /** The web of {@code dimension}, a dimension other than the overworld. */
    public SqlChunkTerritoryAdapter(DataSource dataSource, String dimension) {
        this(dataSource, ChunkScope.of(dimension));
    }

    private SqlChunkTerritoryAdapter(DataSource dataSource, ChunkScope scope) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.scope = scope;
    }

    @Override
    public Map<IslandId, ChunkTerritory> findAll() {
        Map<IslandId, ChunkPos> origins = new HashMap<>();
        Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
        Map<IslandId, java.util.Set<ChunkPos>> bought = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, chunk_x, chunk_z, unlock_order, bought "
                        + "FROM " + scope.claims() + scope.where() + " ORDER BY island_id, unlock_order")) {
            scope.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IslandId islandId = IslandId.of(java.util.UUID.fromString(rs.getString(1)));
                    ChunkPos chunk = new ChunkPos(rs.getInt(2), rs.getInt(3));
                    if (rs.getInt(4) == 0) {
                        origins.put(islandId, chunk);
                    } else {
                        opened.computeIfAbsent(islandId, id -> new ArrayList<>())
                                .add(chunk);
                        if (rs.getInt(5) != 0) {
                            bought.computeIfAbsent(islandId, id -> new java.util.HashSet<>())
                                    .add(chunk);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the ChunkBlock islands", e);
        }
        Map<IslandId, ChunkTerritory> all = new HashMap<>();
        origins.forEach((islandId, origin) -> all.put(
                islandId,
                new ChunkTerritory(
                        origin,
                        opened.getOrDefault(islandId, List.of()),
                        bought.getOrDefault(islandId, java.util.Set.of()))));
        return all;
    }

    @Override
    public Optional<ChunkTerritory> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT chunk_x, chunk_z, unlock_order, bought " + "FROM "
                        + scope.claims() + " WHERE island_id = ?" + scope.and() + " ORDER BY unlock_order")) {
            ps.setString(1, islandId.value().toString());
            scope.bind(ps, 2);
            ChunkPos origin = null;
            List<ChunkPos> opened = new ArrayList<>();
            java.util.Set<ChunkPos> bought = new java.util.HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChunkPos chunk = new ChunkPos(rs.getInt(1), rs.getInt(2));
                    if (rs.getInt(3) == 0) {
                        origin = chunk;
                    } else {
                        opened.add(chunk);
                        if (rs.getInt(4) != 0) {
                            bought.add(chunk);
                        }
                    }
                }
            }
            return origin == null ? Optional.empty() : Optional.of(new ChunkTerritory(origin, opened, bought));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the chunks of island " + islandId, e);
        }
    }

    @Override
    public void start(IslandId islandId, ChunkPos origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        insert(islandId, origin, 0, false);
    }

    @Override
    public boolean open(IslandId islandId, ChunkPos chunk, int order) {
        return open(islandId, chunk, order, false);
    }

    @Override
    public boolean open(IslandId islandId, ChunkPos chunk, int order, boolean bought) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (order < 1) {
            throw new IllegalArgumentException("the first opened chunk is number 1: " + order);
        }
        return insert(islandId, chunk, order, bought);
    }

    /**
     * Closes the chunks and numbers the ones left from one again, in one transaction. A chunk the level held can close
     * while a newer one paid for stays, and the next opening takes the place after the chunks open, so a gap left
     * behind would hold that place and refuse every opening after.
     */
    @Override
    public void close(IslandId islandId, List<ChunkPos> chunks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(chunks, "chunks must not be null");
        String island = islandId.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + scope.claims()
                        + " WHERE island_id = ? AND chunk_x = ? AND chunk_z = ? AND unlock_order > 0" + scope.and())) {
                    for (ChunkPos chunk : chunks) {
                        ps.setString(1, island);
                        ps.setInt(2, chunk.x());
                        ps.setInt(3, chunk.z());
                        scope.bind(ps, 4);
                        ps.executeUpdate();
                    }
                }
                renumber(conn, island, scope);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close chunks of island " + islandId, e);
        }
    }

    /**
     * Numbers the island's opened chunks 1, 2, 3 in the order they were opened. Each moves down or stays, lowest
     * first, so no two ever hold one place on the way.
     */
    private static void renumber(Connection conn, String island, ChunkScope scope) throws SQLException {
        List<int[]> left = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT chunk_x, chunk_z, unlock_order FROM " + scope.claims()
                + " WHERE island_id = ? AND unlock_order > 0" + scope.and() + " ORDER BY unlock_order")) {
            ps.setString(1, island);
            scope.bind(ps, 2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    left.add(new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)});
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + scope.claims() + " SET unlock_order = ? "
                + "WHERE island_id = ? AND chunk_x = ? AND chunk_z = ?" + scope.and())) {
            for (int i = 0; i < left.size(); i++) {
                int[] claim = left.get(i);
                if (claim[2] == i + 1) {
                    continue;
                }
                ps.setInt(1, i + 1);
                ps.setString(2, island);
                ps.setInt(3, claim[0]);
                ps.setInt(4, claim[1]);
                scope.bind(ps, 5);
                ps.executeUpdate();
            }
        }
    }

    private boolean insert(IslandId islandId, ChunkPos chunk, int order, boolean bought) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO " + scope.claims()
                        + " (island_id, chunk_x, chunk_z, unlock_order, bought" + scope.column()
                        + ") VALUES (?, ?, ?, ?, ?"
                        + scope.value() + ")")) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, chunk.x());
            ps.setInt(3, chunk.z());
            ps.setInt(4, order);
            ps.setInt(5, bought ? 1 : 0);
            scope.bind(ps, 6);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // The place or the chunk was taken first.
            if (SqlConstraint.violated(e)) {
                return false;
            }
            throw new IllegalStateException("Could not open a chunk of island " + islandId, e);
        }
    }
}
