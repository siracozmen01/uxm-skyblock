package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** The {@code parkour_courses} and {@code parkour_records} tables. */
public final class SqlParkourAdapter implements ParkourPort {

    private final DataSource dataSource;

    public SqlParkourAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Set<IslandId> findAll() {
        Set<IslandId> all = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id FROM parkour_courses");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(IslandId.of(UUID.fromString(rs.getString(1))));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the Parkour courses", e);
        }
    }

    @Override
    public boolean exists(IslandId course) {
        Objects.requireNonNull(course, "course must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM parkour_courses WHERE island_id = ?")) {
            ps.setString(1, course.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read island " + course + " as a Parkour course", e);
        }
    }

    @Override
    public void add(IslandId course) {
        Objects.requireNonNull(course, "course must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO parkour_courses (island_id) VALUES (?)")) {
            ps.setString(1, course.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return;
            }
            throw new IllegalStateException("Could not record island " + course + " as a Parkour course", e);
        }
    }

    @Override
    public OptionalLong best(IslandId course, PlayerUuid runner) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT best_millis FROM parkour_records WHERE island_id = ? AND player_uuid = ?")) {
            ps.setString(1, course.value().toString());
            ps.setString(2, runner.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the best time on course " + course, e);
        }
    }

    @Override
    public void finish(IslandId course, PlayerUuid runner, long millis) {
        String island = course.value().toString();
        String player = runner.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            // A runner's first finish inserts; every later one keeps the faster of the two times. Two
            // finishes racing to insert meet the primary key, and the loser updates instead.
            if (update(conn, island, player, millis) == 0) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO parkour_records"
                        + " (island_id, player_uuid, best_millis, runs) VALUES (?, ?, ?, 1)")) {
                    ps.setString(1, island);
                    ps.setString(2, player);
                    ps.setLong(3, millis);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isDuplicate(e)) {
                        throw e;
                    }
                    update(conn, island, player, millis);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count a run on course " + course, e);
        }
    }

    private static int update(Connection conn, String island, String player, long millis) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE parkour_records SET runs = runs + 1,"
                + " best_millis = CASE WHEN ? < best_millis THEN ? ELSE best_millis END"
                + " WHERE island_id = ? AND player_uuid = ?")) {
            ps.setLong(1, millis);
            ps.setLong(2, millis);
            ps.setString(3, island);
            ps.setString(4, player);
            return ps.executeUpdate();
        }
    }

    @Override
    public List<Best> top(IslandId course, int limit) {
        List<Best> top = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT player_uuid, best_millis, runs"
                        + " FROM parkour_records WHERE island_id = ? ORDER BY best_millis, player_uuid")) {
            ps.setString(1, course.value().toString());
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && top.size() < limit) {
                    top.add(new Best(PlayerUuid.of(UUID.fromString(rs.getString(1))), rs.getLong(2), rs.getInt(3)));
                }
            }
            return top;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the best times on course " + course, e);
        }
    }

    @Override
    public int clear(IslandId course, java.util.Optional<PlayerUuid> runner) {
        Objects.requireNonNull(course, "course must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        String sql = runner.isPresent()
                ? "DELETE FROM parkour_records WHERE island_id = ? AND player_uuid = ?"
                : "DELETE FROM parkour_records WHERE island_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, course.value().toString());
            if (runner.isPresent()) {
                ps.setString(2, runner.get().value().toString());
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not forget the runs on course " + course, e);
        }
    }

    @Override
    public List<Runs> mostRun(int limit) {
        List<Runs> most = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT r.island_id, i.custom_name, SUM(r.runs) AS total"
                        + " FROM parkour_records r JOIN islands i ON i.id = r.island_id"
                        + " GROUP BY r.island_id, i.custom_name ORDER BY total DESC, r.island_id")) {
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && most.size() < limit) {
                    String id = rs.getString(1);
                    String name = rs.getString(2);
                    // An island nobody named is shown by the start of its id, which reads the same in
                    // every language.
                    most.add(new Runs(
                            IslandId.of(UUID.fromString(id)),
                            name == null || name.isBlank() ? id.substring(0, 8) : name,
                            rs.getLong(3)));
                }
            }
            return most;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the courses run most", e);
        }
    }

    /** Already there. Class 23, or SQLite's code 19. */
    private static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
    }
}
