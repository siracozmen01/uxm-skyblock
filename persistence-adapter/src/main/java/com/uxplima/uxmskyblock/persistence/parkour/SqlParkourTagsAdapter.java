package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/** Each course's tags in {@code parkour_course_tags}, and its runners' stars in {@code parkour_ratings}. */
public final class SqlParkourTagsAdapter implements ParkourTagsPort {

    private final DataSource dataSource;

    public SqlParkourTagsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Tags tags(IslandId course) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT difficulty, category FROM parkour_course_tags WHERE island_id = ?")) {
            ps.setString(1, course.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Tags(rs.getString(1), rs.getString(2)) : Tags.NONE;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the tags of course " + course, e);
        }
    }

    @Override
    public void tag(IslandId course, Tags tags) {
        String island = course.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            // The update finds the course's row when it has one; one that never had tags inserts it, and of two
            // inserts at once the one that meets the primary key updates instead.
            if (update(conn, island, tags) == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO parkour_course_tags (island_id, difficulty, category) VALUES (?, ?, ?)")) {
                    ps.setString(1, island);
                    nullable(ps, 2, tags.difficulty());
                    nullable(ps, 3, tags.category());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isDuplicate(e)) {
                        throw e;
                    }
                    update(conn, island, tags);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not tag course " + course, e);
        }
    }

    private static int update(Connection conn, String island, Tags tags) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE parkour_course_tags SET difficulty = ?, category = ? WHERE island_id = ?")) {
            nullable(ps, 1, tags.difficulty());
            nullable(ps, 2, tags.category());
            ps.setString(3, island);
            return ps.executeUpdate();
        }
    }

    @Override
    public Rating rating(IslandId course) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT AVG(stars), COUNT(*) FROM parkour_ratings WHERE island_id = ?")) {
            ps.setString(1, course.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(2) == 0) {
                    return Rating.NONE;
                }
                return new Rating(rs.getDouble(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the rating of course " + course, e);
        }
    }

    @Override
    public void rate(IslandId course, PlayerUuid runner, int stars) {
        String island = course.value().toString();
        String player = runner.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            if (stars(conn, island, player, stars) == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO parkour_ratings (island_id, player_uuid, stars) VALUES (?, ?, ?)")) {
                    ps.setString(1, island);
                    ps.setString(2, player);
                    ps.setInt(3, stars);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isDuplicate(e)) {
                        throw e;
                    }
                    stars(conn, island, player, stars);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not rate course " + course, e);
        }
    }

    private static int stars(Connection conn, String island, String player, int stars) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("UPDATE parkour_ratings SET stars = ? WHERE island_id = ? AND player_uuid = ?")) {
            ps.setInt(1, stars);
            ps.setString(2, island);
            ps.setString(3, player);
            return ps.executeUpdate();
        }
    }

    private static void nullable(PreparedStatement ps, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
    }
}
