package com.uxplima.uxmskyblock.persistence.activity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotal;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotals;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * SQL persistence adapter for user-facing activity events feed (Section 2.19 & 2.42).
 */
public final class SqlActivityFeedAdapter implements ActivityFeedStoragePort {

    private static final String COLUMNS = """
            event_id, instance_id, actor_profile_id, event_type,
            visibility, payload_type_id, payload_schema_version,
            payload_data, created_at, actor_player, amount_currency, amount_minor
            """;

    private final DataSource dataSource;

    public SqlActivityFeedAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void appendEvent(ActivityEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        String sql = "INSERT INTO activity_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, event.eventId().toString());
            stmt.setString(2, event.instanceId());
            stmt.setString(
                    3,
                    event.actorProfileId() != null
                            ? event.actorProfileId().value().toString()
                            : null);
            stmt.setString(4, event.eventType().name());
            stmt.setString(5, event.visibility().name());
            stmt.setString(6, event.payloadTypeId());
            stmt.setInt(7, event.payloadSchemaVersion());
            stmt.setString(8, event.payloadData());
            stmt.setTimestamp(9, Timestamp.from(event.createdAt()));
            stmt.setString(10, event.actorPlayer() != null ? event.actorPlayer().toString() : null);
            ActivityAmount amount = event.amount();
            stmt.setString(11, amount != null ? amount.currency() : null);
            if (amount != null) {
                stmt.setLong(12, amount.minorUnits());
            } else {
                stmt.setNull(12, Types.BIGINT);
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to append activity event " + event.eventId(), e);
        }
    }

    @Override
    public List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        return find(ActivityQuery.all(instanceId), 0, Math.max(1, limit));
    }

    @Override
    public List<ActivityEvent> find(ActivityQuery query, int offset, int limit) {
        Objects.requireNonNull(query, "query must not be null");
        Where where = Where.of(query);
        // The event id breaks a tie between two lines written in the same instant, so a page never
        // repeats a line the page before it showed.
        String sql = "SELECT " + COLUMNS + " FROM activity_events" + where.sql
                + " ORDER BY created_at DESC, event_id DESC LIMIT ? OFFSET ?";
        List<ActivityEvent> events = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            int next = where.bind(stmt);
            stmt.setInt(next, Math.max(1, limit));
            stmt.setInt(next + 1, Math.max(0, offset));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    events.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find activity events for instance " + query.instanceId(), e);
        }
        return events;
    }

    @Override
    public int count(ActivityQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Where where = Where.of(query);
        String sql = "SELECT COUNT(*) FROM activity_events" + where.sql;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            where.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count activity events for instance " + query.instanceId(), e);
        }
    }

    @Override
    public List<ActivityTotal> totals(ActivityQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Where where = Where.of(query);
        String sql = "SELECT actor_player, event_type, amount_currency, SUM(amount_minor), COUNT(*)"
                + " FROM activity_events" + where.sql
                + " AND actor_player IS NOT NULL AND amount_currency IS NOT NULL AND amount_minor IS NOT NULL"
                + " GROUP BY actor_player, event_type, amount_currency";
        List<ActivityTotal> totals = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            where.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    totals.add(new ActivityTotal(
                            UUID.fromString(rs.getString(1)),
                            ActivityEventType.valueOf(rs.getString(2)),
                            rs.getString(3),
                            Math.abs(rs.getLong(4)),
                            rs.getInt(5)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add up activity events for instance " + query.instanceId(), e);
        }
        totals.sort(ActivityTotals.ORDER);
        return List.copyOf(totals);
    }

    @Override
    public int purgeEventsBefore(Instant before) {
        Objects.requireNonNull(before, "before must not be null");

        String sql = "DELETE FROM activity_events WHERE created_at < ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(before));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge activity events before " + before, e);
        }
    }

    private static ActivityEvent mapRow(ResultSet rs) throws SQLException {
        UUID eventId = UUID.fromString(rs.getString("event_id"));
        String instanceId = rs.getString("instance_id");
        String actorProfileStr = rs.getString("actor_profile_id");
        ProfileId actorProfileId = actorProfileStr != null ? ProfileId.of(UUID.fromString(actorProfileStr)) : null;
        ActivityEventType eventType = ActivityEventType.valueOf(rs.getString("event_type"));
        ActivityVisibility visibility = ActivityVisibility.valueOf(rs.getString("visibility"));
        String payloadTypeId = rs.getString("payload_type_id");
        int payloadSchemaVersion = rs.getInt("payload_schema_version");
        String payloadData = rs.getString("payload_data");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        String actorPlayer = rs.getString("actor_player");
        String currency = rs.getString("amount_currency");
        long minorUnits = rs.getLong("amount_minor");
        boolean moved = !rs.wasNull() && currency != null && minorUnits != 0;

        return new ActivityEvent(
                eventId,
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                createdAt,
                actorPlayer != null ? UUID.fromString(actorPlayer) : null,
                moved ? new ActivityAmount(currency, minorUnits) : null);
    }

    /** The WHERE clause of a query and the values it binds, in the order it binds them. */
    private record Where(String sql, List<Object> values) {

        static Where of(ActivityQuery query) {
            StringBuilder sql = new StringBuilder(" WHERE instance_id = ?");
            List<Object> values = new ArrayList<>();
            values.add(query.instanceId());
            if (query.player() != null) {
                sql.append(" AND actor_player = ?");
                values.add(query.player().toString());
            }
            if (!query.types().isEmpty()) {
                sql.append(" AND event_type IN (");
                // Sorted, so the same query is always the same statement.
                List<ActivityEventType> types = query.types().stream().sorted().toList();
                for (int i = 0; i < types.size(); i++) {
                    sql.append(i == 0 ? "?" : ", ?");
                    values.add(types.get(i).name());
                }
                sql.append(')');
            }
            if (query.since() != null) {
                sql.append(" AND created_at >= ?");
                values.add(Timestamp.from(query.since()));
            }
            return new Where(sql.toString(), List.copyOf(values));
        }

        /** Binds the values from the first parameter on, and answers the index of the next one. */
        int bind(PreparedStatement stmt) throws SQLException {
            int index = 1;
            for (Object value : values) {
                if (value instanceof Timestamp timestamp) {
                    stmt.setTimestamp(index++, timestamp);
                } else {
                    stmt.setString(index++, (String) value);
                }
            }
            return index;
        }
    }
}
