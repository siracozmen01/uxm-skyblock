package com.uxplima.uxmskyblock.persistence.oneblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributionPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/** {@link OneBlockContributionPort} over the {@code oneblock_member_breaks} table, on every supported database. */
public final class SqlOneBlockContributionAdapter implements OneBlockContributionPort {

    private final DataSource dataSource;

    public SqlOneBlockContributionAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<Contribution> findAll() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT island_id, player_uuid, breaks FROM oneblock_member_breaks");
                ResultSet rs = ps.executeQuery()) {
            List<Contribution> all = new ArrayList<>();
            while (rs.next()) {
                all.add(new Contribution(
                        IslandId.fromString(rs.getString(1)),
                        PlayerUuid.of(java.util.UUID.fromString(rs.getString(2))),
                        rs.getLong(3)));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the OneBlock breaks of each player", e);
        }
    }

    /** Adds to the player's row, or writes it when there is none; of two first breaks at once the second adds. */
    @Override
    public void add(IslandId islandId, PlayerUuid player, long breaks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(player, "player must not be null");
        if (breaks < 1) {
            throw new IllegalArgumentException("breaks must be at least one: " + breaks);
        }
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE oneblock_member_breaks SET breaks = breaks + ? WHERE island_id = ? AND player_uuid = ?")) {
                fill(ps, islandId, player, breaks);
                if (ps.executeUpdate() == 1) {
                    return;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oneblock_member_breaks (breaks, island_id, player_uuid) VALUES (?, ?, ?)")) {
                fill(ps, islandId, player, breaks);
                ps.executeUpdate();
            } catch (SQLException taken) {
                if (!SqlConstraint.violated(taken)) {
                    throw taken;
                }
                add(islandId, player, breaks);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not add the breaks of " + player + " on OneBlock island " + islandId, e);
        }
    }

    private static void fill(PreparedStatement ps, IslandId islandId, PlayerUuid player, long breaks)
            throws SQLException {
        ps.setLong(1, breaks);
        ps.setString(2, islandId.value().toString());
        ps.setString(3, player.value().toString());
    }

    @Override
    public void clear(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("DELETE FROM oneblock_member_breaks WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not forget the breaks of each player on OneBlock island " + islandId, e);
        }
    }
}
