package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.CarriedState;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * {@link CarriedStatePort} on {@code carried_states} and {@code carried_groups}, written with
 * {@code profile_inventories} in one transaction.
 *
 * <p>A crossing takes the session row first, as a checkpoint does ({@code BEGIN IMMEDIATE} on SQLite, a row lock
 * elsewhere), checks the same authority and that no journal holds the inventory, and moves the inventory's
 * version as a checkpoint does. A checkpoint or a final write taken before the crossing and landing after it is
 * refused on that version, so what the player put down is never written back over what they picked up.
 */
public final class SqlCarriedStateAdapter implements CarriedStatePort {

    private static final String BETWEEN = "\n";
    private static final String COLUMNS = "inventory_nbt, enderchest_nbt, experience_points, health, food_level, "
            + "saturation, active_potion_effects_nbt, advancements";

    private final Database database;
    private final Dialect dialect;
    private final String authoritySql;
    private final String updateInventorySql;

    public SqlCarriedStateAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        SupportedDialects.require(dialect, "carried state persistence");
        this.authoritySql = PlayerProfileInventoryAdapter.buildSelectSessionAuthoritySql(dialect);
        this.updateInventorySql = PlayerProfileInventoryAdapter.buildUpdateInventoryOccSql();
    }

    @Override
    public Optional<String> carriedGroup(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId");
        try (Connection conn = database.connection()) {
            return group(conn, profileId);
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to read the group " + profileId + " carries for", e);
        }
    }

    @Override
    public String carryFirst(ProfileId profileId, String group) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(group, "group");
        String insert =
                switch (dialect) {
                    case SQLITE -> "INSERT OR IGNORE INTO carried_groups (profile_id, state_group) VALUES (?, ?)";
                    case MYSQL -> "INSERT IGNORE INTO carried_groups (profile_id, state_group) VALUES (?, ?)";
                    default ->
                        "INSERT INTO carried_groups (profile_id, state_group) VALUES (?, ?) "
                                + "ON CONFLICT (profile_id) DO NOTHING";
                };
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, group);
                ps.executeUpdate();
            }
            return group(conn, profileId).orElse(group);
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to record the group " + profileId + " carries for", e);
        }
    }

    @Override
    public Crossed cross(Crossing crossing) {
        Objects.requireNonNull(crossing, "crossing");
        try (Connection conn = database.connection()) {
            if (dialect == Dialect.SQLITE) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("BEGIN IMMEDIATE");
                }
            } else {
                conn.setAutoCommit(false);
            }
            try {
                Crossed crossed = crossWithin(conn, crossing);
                if (crossed instanceof Crossed.Done) {
                    commit(conn);
                } else {
                    rollback(conn);
                }
                return crossed;
            } catch (SQLException | RuntimeException e) {
                rollback(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException(
                    "Failed to cross " + crossing.profileId() + " from " + crossing.from() + " to " + crossing.to(), e);
        }
    }

    private Crossed crossWithin(Connection conn, Crossing crossing) throws SQLException {
        ProfileId profile = crossing.profileId();
        if (!PlayerProfileInventoryAdapter.authorityHolds(
                        conn, authoritySql, crossing.playerUuid(), profile, crossing.node(), crossing.epoch())
                || PlayerProfileInventoryAdapter.journalOwns(conn, profile)) {
            return new Crossed.Refused("the session is not held here, or a journal holds the inventory");
        }
        Optional<String> carried = group(conn, profile);
        if (!carried.equals(Optional.of(crossing.from()))) {
            return new Crossed.Refused("the profile carries for " + carried.orElse("no group"));
        }
        CarriedState coming = state(conn, profile, crossing.to()).orElse(CarriedState.FRESH);
        ProfileInventoryRecord leaving = crossing.leaving();
        ProfileInventoryRecord arriving = crossing.parts().arriving(leaving, coming, leaving.version() + 1);
        try (PreparedStatement ps = conn.prepareStatement(updateInventorySql)) {
            int next = PlayerStateColumns.bind(ps, 1, arriving);
            ps.setString(next, profile.value().toString());
            ps.setLong(next + 1, leaving.version());
            if (ps.executeUpdate() != 1) {
                return new Crossed.Refused("the inventory moved past version " + leaving.version());
            }
        }
        forget(conn, profile, crossing.from());
        keep(conn, profile, crossing.from(), CarriedState.of(leaving, crossing.leavingAdvancements()));
        forget(conn, profile, crossing.to());
        try (PreparedStatement ps =
                conn.prepareStatement("UPDATE carried_groups SET state_group = ? WHERE profile_id = ?")) {
            ps.setString(1, crossing.to());
            ps.setString(2, profile.value().toString());
            ps.executeUpdate();
        }
        return new Crossed.Done(
                arriving, crossing.parts().advancementsArriving(crossing.leavingAdvancements(), coming));
    }

    private static Optional<String> group(Connection conn, ProfileId profileId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT state_group FROM carried_groups WHERE profile_id = ?")) {
            ps.setString(1, profileId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static Optional<CarriedState> state(Connection conn, ProfileId profileId, String group)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM carried_states WHERE profile_id = ? AND state_group = ?")) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, group);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String advancements = rs.getString("advancements");
                return Optional.of(new CarriedState(
                        rs.getBytes("inventory_nbt"),
                        rs.getBytes("enderchest_nbt"),
                        rs.getInt("experience_points"),
                        rs.getDouble("health"),
                        rs.getInt("food_level"),
                        rs.getFloat("saturation"),
                        rs.getBytes("active_potion_effects_nbt"),
                        advancements == null || advancements.isEmpty()
                                ? List.of()
                                : Arrays.asList(advancements.split(BETWEEN))));
            }
        }
    }

    private static void keep(Connection conn, ProfileId profileId, String group, CarriedState state)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO carried_states (profile_id, state_group, "
                + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, group);
            ps.setBytes(3, state.inventoryNbt());
            ps.setBytes(4, state.enderchestNbt());
            ps.setInt(5, state.experiencePoints());
            ps.setDouble(6, state.health());
            ps.setInt(7, state.foodLevel());
            ps.setFloat(8, state.saturation());
            byte[] effects = state.effectsNbt();
            if (effects != null) {
                ps.setBytes(9, effects);
            } else {
                ps.setNull(9, Types.BINARY);
            }
            ps.setString(10, String.join(BETWEEN, state.advancements()));
            ps.executeUpdate();
        }
    }

    private static void forget(Connection conn, ProfileId profileId, String group) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("DELETE FROM carried_states WHERE profile_id = ? AND state_group = ?")) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, group);
            ps.executeUpdate();
        }
    }

    private void commit(Connection conn) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("COMMIT");
            }
        } else {
            conn.commit();
        }
    }

    private void rollback(Connection conn) {
        if (dialect == Dialect.SQLITE) {
            PlayerProfileInventoryAdapter.rollbackSqlite(conn);
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Already rolled back or the connection is gone; nothing was committed.
        }
    }
}
