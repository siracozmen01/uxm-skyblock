package com.uxplima.uxmskyblock.persistence.oneblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockBranchPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/** {@link OneBlockBranchPort} over the {@code oneblock_branches} table, on every supported database. */
public final class SqlOneBlockBranchAdapter implements OneBlockBranchPort {

    private final DataSource dataSource;

    public SqlOneBlockBranchAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<Pick> findAll() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, phase, chosen FROM oneblock_branches");
                ResultSet rs = ps.executeQuery()) {
            return read(rs);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the OneBlock picks", e);
        }
    }

    @Override
    public List<Pick> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT island_id, phase, chosen FROM oneblock_branches WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the picks of OneBlock island " + islandId, e);
        }
    }

    private static List<Pick> read(ResultSet rs) throws SQLException {
        List<Pick> all = new ArrayList<>();
        while (rs.next()) {
            all.add(new Pick(IslandId.fromString(rs.getString(1)), rs.getString(2), rs.getString(3)));
        }
        return all;
    }

    /** Changes the island's pick for the phase, or writes it when there is none; of two at once the second changes it. */
    @Override
    public void pick(Pick pick) {
        Objects.requireNonNull(pick, "pick must not be null");
        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE oneblock_branches SET chosen = ? WHERE island_id = ? AND phase = ?")) {
                    fill(ps, pick);
                    if (ps.executeUpdate() == 1) {
                        return;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO oneblock_branches (chosen, island_id, phase) VALUES (?, ?, ?)")) {
                    fill(ps, pick);
                    ps.executeUpdate();
                    return;
                } catch (SQLException taken) {
                    if (!SqlConstraint.violated(taken)) {
                        throw taken;
                    }
                    // Written between the two statements, or the island is gone: change it once more, then give up.
                }
            }
            throw new IllegalStateException("The pick of OneBlock island " + pick.islandId() + " found no row twice");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the pick of OneBlock island " + pick.islandId(), e);
        }
    }

    private static void fill(PreparedStatement ps, Pick pick) throws SQLException {
        ps.setString(1, pick.chosen());
        ps.setString(2, pick.islandId().value().toString());
        ps.setString(3, pick.phase());
    }

    @Override
    public void clear(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM oneblock_branches WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not forget the picks of OneBlock island " + islandId, e);
        }
    }
}
