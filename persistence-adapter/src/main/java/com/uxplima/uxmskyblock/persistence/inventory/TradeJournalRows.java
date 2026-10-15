package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort.Holder;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort.Participant;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;

/**
 * The rows a trade's journal reads and writes inside the connection that moves it: the trade's state locked, every side set, a side's inventory written and fingerprinted, and whether another side still holds its half. Split out of {@link PlayerTradeJournalAdapter}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class TradeJournalRows {

    private final PlayerTradeJournalAdapter host;

    TradeJournalRows(PlayerTradeJournalAdapter host) {
        this.host = host;
    }

    /** Whether a side other than {@code index}, not put back yet, holds what the trade gives it. */
    boolean anotherHoldsItsHalf(Connection conn, List<Participant> recorded, int index) throws SQLException {
        for (Participant side : recorded) {
            if (side.index() != index
                    && side.applyState() != ParticipantApplyState.REVERTED
                    && durableFingerprint(conn, side.profile()).equals(side.afterFingerprint())) {
                return true;
            }
        }
        return false;
    }
    /** What {@code profile}'s durable inventory holds, as the journal fingerprints it, its row locked. */
    String durableFingerprint(Connection conn, ProfileId profile) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.selectDurableInventory)) {
            ps.setString(1, profile.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return InventoryFingerprint.of(rs.next() ? rs.getBytes(1) : null);
            }
        }
    }

    boolean writeInventory(Connection conn, Holder holder, byte[] inventory, long over) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(host.sql.updateInventoryOcc())) {
            ps.setBytes(1, inventory);
            ps.setString(2, holder.profile().value().toString());
            ps.setLong(3, over);
            if (ps.executeUpdate() != 1) {
                return false;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(host.sql.updateSessionLastDurableVersion())) {
            ps.setLong(1, over + 1);
            ps.setString(2, holder.player().value().toString());
            ps.executeUpdate();
        }
        return true;
    }
    /** The trade's state, its row locked for the rest of the transaction. */
    Optional<String> lockedState(Connection conn, InventoryMutationOperationId operationId) throws SQLException {
        return PlayerTradeJournalAdapter.readState(conn, operationId, host.sql.selectJournalForUpdate());
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
