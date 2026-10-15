package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort.Hold;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The rows a cargo move's journal reads and writes inside the connection that moves it: the move's state locked, every participant set and written, the hold's lease and version read, and the player's inventory fingerprinted. Split out of {@link VesselCargoJournalAdapter}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class CargoJournalRows {

    private final VesselCargoJournalAdapter host;

    CargoJournalRows(VesselCargoJournalAdapter host) {
        this.host = host;
    }

    /** Why this node may not write the vessel's hold now, or empty while its lease is this node's and live. */
    Optional<String> leaseRefusal(Connection conn, Hold hold, ServerNodeId node) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.selectLease)) {
            ps.setString(1, CargoJournalPort.LEASE_PROVIDER);
            ps.setString(2, hold.vessel().value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.of("LEASE_NOT_FOUND");
                }
                if (!node.value().equals(rs.getString("authoritative_node"))) {
                    return Optional.of("WRONG_NODE");
                }
                if (rs.getLong("authority_epoch") != hold.leaseEpoch()) {
                    return Optional.of("STALE_EPOCH");
                }
                if (rs.getInt("lease_valid") != 1) {
                    return Optional.of("LEASE_EXPIRED");
                }
                return Optional.empty();
            }
        }
    }

    Optional<Long> cargoVersion(Connection conn, Hold hold) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.selectCargo)) {
            ps.setString(1, hold.vessel().value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("cargo_version")) : Optional.empty();
            }
        }
    }

    String durableFingerprint(Connection conn, ProfileId profile) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.selectDurableInventory)) {
            ps.setString(1, profile.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return InventoryFingerprint.of(rs.next() ? rs.getBytes(1) : null);
            }
        }
    }

    @SuppressWarnings("TooManyParameters")
    void writeParticipant(
            Connection conn,
            InventoryMutationOperationId operationId,
            int index,
            String inventoryType,
            String ownerType,
            String owner,
            long expectedVersion,
            String authorityType,
            String authorityId,
            long authorityEpoch,
            String before,
            String after,
            String payload)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.insertParticipant)) {
            ps.setString(1, operationId.value().toString());
            ps.setInt(2, index);
            ps.setString(3, inventoryType);
            ps.setString(4, ownerType);
            ps.setString(5, owner);
            ps.setLong(6, expectedVersion);
            ps.setString(7, authorityType);
            ps.setString(8, authorityId);
            ps.setLong(9, authorityEpoch);
            ps.setString(10, before);
            ps.setString(11, after);
            ps.setString(12, payload);
            ps.executeUpdate();
        }
    }

    Optional<String> lockedState(Connection conn, InventoryMutationOperationId operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.sql.selectJournalForUpdate())) {
            ps.setString(1, operationId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("state")) : Optional.empty();
            }
        }
    }

    void setEveryParticipant(Connection conn, InventoryMutationOperationId operationId, ParticipantApplyState to)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.updateParticipantsState)) {
            ps.setString(1, to.name());
            ps.setString(2, operationId.value().toString());
            ps.executeUpdate();
        }
    }
}
