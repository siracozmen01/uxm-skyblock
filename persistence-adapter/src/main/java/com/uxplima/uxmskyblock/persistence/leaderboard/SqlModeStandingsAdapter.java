package com.uxplima.uxmskyblock.persistence.leaderboard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.leaderboard.ModeStandingsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Reads each mode's own tables, joined to the islands for their names, and writes nothing. */
public final class SqlModeStandingsAdapter implements ModeStandingsPort {

    private final DataSource dataSource;

    public SqlModeStandingsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<Standing> mostBroken(int limit) {
        return read(
                "the OneBlock islands that broke most",
                "SELECT o.island_id, i.custom_name, o.blocks_broken FROM oneblock_progress o"
                        + " JOIN islands i ON i.id = o.island_id ORDER BY o.blocks_broken DESC, o.island_id",
                limit);
    }

    @Override
    public List<Standing> fastestCourses(int limit) {
        return read(
                "the fastest Parkour courses",
                "SELECT r.island_id, i.custom_name, MIN(r.best_millis) AS best FROM parkour_records r"
                        + " JOIN islands i ON i.id = r.island_id"
                        + " GROUP BY r.island_id, i.custom_name ORDER BY best, r.island_id",
                limit);
    }

    @Override
    public List<Standing> mostEarned(int limit) {
        return read(
                "the largest Boxed islands",
                "SELECT s.island_id, i.custom_name, COALESCE(SUM(a.blocks), 0) AS earned FROM boxed_instance_state s"
                        + " JOIN islands i ON i.id = s.island_id"
                        + " LEFT JOIN boxed_advancements a ON a.island_id = s.island_id"
                        + " GROUP BY s.island_id, i.custom_name ORDER BY earned DESC, s.island_id",
                limit);
    }

    @Override
    public List<Standing> mostChunks(int limit) {
        return read(
                "the widest ChunkBlock islands",
                "SELECT c.island_id, i.custom_name, COUNT(*) AS opened FROM chunkblock_territory_claims c"
                        + " JOIN islands i ON i.id = c.island_id"
                        + " GROUP BY c.island_id, i.custom_name ORDER BY opened DESC, c.island_id",
                limit);
    }

    private List<Standing> read(String what, String sql, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Standing> standings = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && standings.size() < limit) {
                    String id = rs.getString(1);
                    String name = rs.getString(2);
                    // An island nobody named is shown by the start of its id, which reads the same in every language.
                    standings.add(new Standing(
                            IslandId.of(UUID.fromString(id)),
                            name == null || name.isBlank() ? id.substring(0, 8) : name,
                            rs.getLong(3)));
                }
            }
            return standings;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read " + what, e);
        }
    }
}
