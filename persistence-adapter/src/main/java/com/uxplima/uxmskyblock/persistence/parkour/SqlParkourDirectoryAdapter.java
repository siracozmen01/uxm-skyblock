package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourDirectoryPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The Parkour courses with their island's name and owner, tags, rating, record and runs, in one read. */
public final class SqlParkourDirectoryAdapter implements ParkourDirectoryPort {

    private final DataSource dataSource;

    public SqlParkourDirectoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<Listing> directory(int limit) {
        List<Listing> listings = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT c.island_id, i.custom_name, i.owner_account_uuid,"
                        + " t.difficulty, t.category,"
                        + " (SELECT AVG(r.stars) FROM parkour_ratings r WHERE r.island_id = c.island_id),"
                        + " (SELECT COUNT(*) FROM parkour_ratings r WHERE r.island_id = c.island_id),"
                        + " (SELECT MIN(p.best_millis) FROM parkour_records p WHERE p.island_id = c.island_id),"
                        + " (SELECT COALESCE(SUM(p.runs), 0) FROM parkour_records p WHERE p.island_id = c.island_id)"
                        + " AS runs"
                        + " FROM parkour_courses c JOIN islands i ON i.id = c.island_id"
                        + " LEFT JOIN parkour_course_tags t ON t.island_id = c.island_id"
                        + " ORDER BY runs DESC, c.island_id")) {
            ps.setMaxRows(Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long record = rs.getLong(8);
                    boolean finished = !rs.wasNull();
                    listings.add(new Listing(
                            IslandId.of(UUID.fromString(rs.getString(1))),
                            rs.getString(2),
                            UUID.fromString(rs.getString(3)),
                            new ParkourTagsPort.Tags(rs.getString(4), rs.getString(5)),
                            rs.getDouble(6),
                            rs.getInt(7),
                            finished ? record : null,
                            rs.getLong(9)));
                }
            }
            return List.copyOf(listings);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the directory of Parkour courses", e);
        }
    }
}
