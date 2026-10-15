package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * The journal of moves between a player's inventory and a vessel's cargo hold, on the same two tables as
 * every journaled inventory mutation.
 *
 * <p>Participant 0 is the player's inventory, fenced by their {@code player_sessions} row; participant 1
 * is the vessel's hold, fenced by its {@code mode_owned_authorities} lease. Every phase locks them in that
 * order, the player before the vessel. The player's inventory as the move found it is kept in their
 * participant's delta payload, so recovery can put it back.
 */
public final class VesselCargoJournalAdapter implements CargoJournalPort {

    /** The rows a cargo move's journal reads and writes inside the connection that moves it: the move's state locked, every participant set and written, the hold's lease and version read, and the player's inventory fingerprinted. */
    private final CargoJournalRows rows = new CargoJournalRows(this);

    private static final Pattern BEFORE = Pattern.compile("\"before\"\\s*:\\s*\"([A-Za-z0-9+/=]*)\"");

    private final Database database;
    final InventoryMutationJournalSql sql;
    private final JournalTransaction transaction;
    private final SessionAuthorityGate authority;
    private final String insertJournal;
    final String insertParticipant;
    final String selectLease;
    final String selectCargo;
    final String selectDurableInventory;
    private final String selectParticipants;
    final String updateParticipantsState;
    private final String updateCargo;

    public VesselCargoJournalAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        Dialect dialect = database.dialect();
        this.sql = InventoryMutationJournalSql.forDialect(dialect);
        this.transaction = new JournalTransaction(database);
        this.authority = new SessionAuthorityGate(sql.selectSessionAuthority());
        String lock = dialect == Dialect.SQLITE ? "" : " FOR UPDATE";
        String json = dialect == Dialect.POSTGRES ? "CAST(? AS json)" : "?";
        this.insertJournal = "INSERT INTO inventory_mutation_journals "
                + "(operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at) "
                + "VALUES (?, '" + OPERATION_TYPE + "', 'INTENT', 2, " + json
                + ", ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        this.insertParticipant = "INSERT INTO inventory_mutation_participants "
                + "(operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                + "expected_version, authority_type, authority_id, authority_epoch, "
                + "before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', " + json + ", CURRENT_TIMESTAMP)";
        this.selectLease = "SELECT authoritative_node, authority_epoch, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM mode_owned_authorities WHERE provider_id = ? AND root_key = ?" + lock;
        this.selectCargo = "SELECT cargo, cargo_version FROM tradewinds_vessels WHERE island_id = ?" + lock;
        this.selectDurableInventory = "SELECT inventory_nbt FROM profile_inventories WHERE profile_id = ?" + lock;
        this.selectParticipants = "SELECT participant_index, owner_root_id, before_fingerprint, after_fingerprint, "
                + "durable_apply_state, mutation_delta_payload FROM inventory_mutation_participants "
                + "WHERE operation_id = ? ORDER BY participant_index" + lock;
        this.updateParticipantsState = "UPDATE inventory_mutation_participants "
                + "SET durable_apply_state = ?, updated_at = CURRENT_TIMESTAMP WHERE operation_id = ?";
        this.updateCargo = "UPDATE tradewinds_vessels SET cargo = ?, cargo_version = cargo_version + 1 "
                + "WHERE island_id = ? AND cargo_version = ?";
    }

    @Override
    public InventoryMutationJournalOutcome recordIntent(
            InventoryMutationOperationId operationId, ServerNodeId node, Move move, Duration expiry) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(expiry, "expiry");
        return transaction.run("recordCargoIntent", conn -> {
            if (rows.lockedState(conn, operationId).isPresent()) {
                return InventoryMutationJournalOutcome.conflict("OPERATION_CONFLICT");
            }
            Optional<String> refusal = refusal(conn, move.player(), node);
            if (refusal.isEmpty()) {
                refusal = rows.leaseRefusal(conn, move.hold(), node);
            }
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.selectInventoryVersion())) {
                ps.setString(1, move.player().profile().value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != move.playerVersion()) {
                        return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                    }
                }
            }
            Optional<Long> cargoVersion = rows.cargoVersion(conn, move.hold());
            if (cargoVersion.isEmpty() || cargoVersion.get() != move.cargoVersion()) {
                return InventoryMutationJournalOutcome.rejected("CARGO_VERSION_MISMATCH");
            }
            // The player as the move found them, one version on: from here only this move writes them.
            if (!writeInventory(conn, move.player(), move.playerBefore(), move.playerVersion())) {
                return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
            }
            try (PreparedStatement ps = conn.prepareStatement(insertJournal)) {
                ps.setString(1, operationId.value().toString());
                ps.setString(2, "{}");
                ps.setTimestamp(3, new Timestamp(System.currentTimeMillis() + expiry.toMillis()));
                ps.executeUpdate();
            }
            rows.writeParticipant(
                    conn,
                    operationId,
                    0,
                    "PLAYER_INVENTORY",
                    "PROFILE",
                    move.player().profile().value().toString(),
                    move.playerVersion(),
                    "PLAYER_SESSION",
                    move.player().player().value().toString(),
                    move.player().sessionEpoch(),
                    InventoryFingerprint.of(move.playerBefore()),
                    InventoryFingerprint.of(move.playerAfter()),
                    "{\"before\":\"" + Base64.getEncoder().encodeToString(move.playerBefore()) + "\"}");
            rows.writeParticipant(
                    conn,
                    operationId,
                    1,
                    "VESSEL_CARGO",
                    "VESSEL",
                    move.hold().vessel().value().toString(),
                    move.cargoVersion(),
                    "MODE_OWNED",
                    LEASE_PROVIDER,
                    move.hold().leaseEpoch(),
                    InventoryFingerprint.of(move.cargoBefore()),
                    InventoryFingerprint.of(move.cargoAfter()),
                    "{}");
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index) {
        Objects.requireNonNull(operationId, "operationId");
        return transaction.run("markCargoSideApplied", conn -> {
            if (!rows.lockedState(conn, operationId).map("INTENT"::equals).orElse(false)) {
                return InventoryMutationJournalOutcome.rejected("MOVE_NOT_OPEN");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.updateParticipantState())) {
                ps.setString(1, ParticipantApplyState.APPLIED.name());
                ps.setString(2, operationId.value().toString());
                ps.setInt(3, index);
                return ps.executeUpdate() == 1
                        ? InventoryMutationJournalOutcome.success()
                        : InventoryMutationJournalOutcome.rejected("PARTICIPANT_NOT_FOUND");
            }
        });
    }

    @Override
    public InventoryMutationJournalOutcome commit(
            InventoryMutationOperationId operationId, ServerNodeId node, Move move) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(move, "move");
        return transaction.run("commitCargoMove", conn -> {
            Optional<String> state = rows.lockedState(conn, operationId);
            if (state.isEmpty()) {
                return InventoryMutationJournalOutcome.rejected("JOURNAL_NOT_FOUND");
            }
            if ("COMMITTED".equals(state.get())) {
                return InventoryMutationJournalOutcome.success();
            }
            if (!"INTENT".equals(state.get())) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }
            Optional<String> refusal = refusal(conn, move.player(), node);
            if (refusal.isEmpty()) {
                refusal = rows.leaseRefusal(conn, move.hold(), node);
            }
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            List<Recorded> sides = participants(conn, operationId);
            if (sides.size() != 2
                    || !sides.get(0)
                            .owner()
                            .equals(move.player().profile().value().toString())
                    || !sides.get(1).owner().equals(move.hold().vessel().value().toString())
                    || sides.stream().anyMatch(side -> side.applyState() == ParticipantApplyState.REVERTED)) {
                return InventoryMutationJournalOutcome.rejected("PARTICIPANT_MISMATCH");
            }
            // The intent moved the player one version on; the commit writes over that one.
            if (!writeInventory(conn, move.player(), move.playerAfter(), move.playerVersion() + 1)) {
                return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
            }
            try (PreparedStatement ps = conn.prepareStatement(updateCargo)) {
                ps.setBytes(1, move.cargoAfter());
                ps.setString(2, move.hold().vessel().value().toString());
                ps.setLong(3, move.cargoVersion());
                if (ps.executeUpdate() != 1) {
                    return InventoryMutationJournalOutcome.rejected("CARGO_VERSION_MISMATCH");
                }
            }
            rows.setEveryParticipant(conn, operationId, ParticipantApplyState.APPLIED);
            setState(conn, operationId, InventoryMutationJournalState.COMMITTED);
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public InventoryMutationJournalOutcome abort(
            InventoryMutationOperationId operationId, ServerNodeId node, TradeJournalPort.Holder player) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(player, "player");
        return transaction.run("abortCargoMove", conn -> {
            Optional<String> state = rows.lockedState(conn, operationId);
            if (state.isEmpty()) {
                return InventoryMutationJournalOutcome.rejected("JOURNAL_NOT_FOUND");
            }
            if ("ABORTED".equals(state.get())) {
                return InventoryMutationJournalOutcome.success();
            }
            if (!"INTENT".equals(state.get())) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }
            Optional<String> refusal =
                    authority.refusal(conn, player.player(), player.profile(), node, player.sessionEpoch(), false);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            Recorded mine = participants(conn, operationId).get(0);
            if (!rows.durableFingerprint(conn, player.profile()).equals(mine.before())) {
                // A player who left mid-move with their half: recovery puts them back.
                return InventoryMutationJournalOutcome.rejected("SIDE_WRITTEN");
            }
            rows.setEveryParticipant(conn, operationId, ParticipantApplyState.REVERTED);
            setState(conn, operationId, InventoryMutationJournalState.ABORTED);
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public List<InventoryMutationOperationId> findOpenMoves(ProfileId profile) {
        Objects.requireNonNull(profile, "profile");
        String find = "SELECT j.operation_id FROM inventory_mutation_journals j "
                + "JOIN inventory_mutation_participants p ON p.operation_id = j.operation_id "
                + "AND p.participant_index = 0 "
                + "WHERE p.owner_root_id = ? AND j.state = 'INTENT' AND j.operation_type = '" + OPERATION_TYPE + "' "
                + "ORDER BY j.created_at, j.operation_id";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(find)) {
            ps.setString(1, profile.value().toString());
            List<InventoryMutationOperationId> open = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add(InventoryMutationOperationId.of(rs.getString(1)));
                }
            }
            return List.copyOf(open);
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to find the open cargo moves of " + profile, e);
        }
    }

    @Override
    public Optional<PlayerSide> playerSide(InventoryMutationOperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection()) {
            List<Recorded> sides = participants(conn, operationId);
            if (sides.isEmpty()) {
                return Optional.empty();
            }
            Recorded mine = sides.get(0);
            return Optional.of(new PlayerSide(
                    ProfileId.of(UUID.fromString(mine.owner())),
                    mine.before(),
                    mine.after(),
                    mine.applyState(),
                    mine.beforeInventory()));
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to read cargo move " + operationId, e);
        }
    }

    @Override
    public Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql.selectJournalRead())) {
            ps.setString(1, operationId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(InventoryMutationJournalState.valueOf(rs.getString("state")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to read cargo move " + operationId, e);
        }
    }

    @Override
    public InventoryMutationJournalOutcome settle(
            InventoryMutationOperationId operationId,
            ServerNodeId node,
            TradeJournalPort.Holder player,
            byte @Nullable [] restore,
            long restoreOver,
            InventoryMutationJournalState settledAs) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(player, "player");
        if (settledAs != InventoryMutationJournalState.ABORTED
                && settledAs != InventoryMutationJournalState.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("A move settles as ABORTED or RECOVERY_REQUIRED, not " + settledAs);
        }
        return transaction.run("settleCargoMove", conn -> {
            Optional<String> state = rows.lockedState(conn, operationId);
            if (state.isEmpty()) {
                return InventoryMutationJournalOutcome.rejected("JOURNAL_NOT_FOUND");
            }
            if (settledAs.name().equals(state.get())) {
                return InventoryMutationJournalOutcome.success();
            }
            if (!"INTENT".equals(state.get())) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }
            List<Recorded> sides = participants(conn, operationId);
            if (sides.isEmpty()
                    || !sides.get(0).owner().equals(player.profile().value().toString())) {
                return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
            }
            Optional<String> refusal = refusal(conn, player, node);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            if (settledAs == InventoryMutationJournalState.ABORTED) {
                // Recovery decided from what it read before this transaction; it acts only while that holds.
                String holds = rows.durableFingerprint(conn, player.profile());
                Recorded mine = sides.get(0);
                if (!holds.equals(restore != null ? mine.after() : mine.before())) {
                    return InventoryMutationJournalOutcome.rejected("SIDE_MOVED");
                }
                if (restore != null && !writeInventory(conn, player, restore, restoreOver)) {
                    return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                }
                rows.setEveryParticipant(conn, operationId, ParticipantApplyState.REVERTED);
            }
            setState(conn, operationId, settledAs);
            return InventoryMutationJournalOutcome.success();
        });
    }

    /** A participant as the journal holds it. */
    @SuppressWarnings("ArrayRecordComponent")
    private record Recorded(
            String owner, String before, String after, ParticipantApplyState applyState, byte[] beforeInventory) {}

    private Optional<String> refusal(Connection conn, TradeJournalPort.Holder player, ServerNodeId node)
            throws SQLException {
        return authority.refusal(conn, player.player(), player.profile(), node, player.sessionEpoch(), true);
    }

    private boolean writeInventory(Connection conn, TradeJournalPort.Holder player, byte[] inventory, long over)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql.updateInventoryOcc())) {
            ps.setBytes(1, inventory);
            ps.setString(2, player.profile().value().toString());
            ps.setLong(3, over);
            if (ps.executeUpdate() != 1) {
                return false;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.updateSessionLastDurableVersion())) {
            ps.setLong(1, over + 1);
            ps.setString(2, player.player().value().toString());
            ps.executeUpdate();
        }
        return true;
    }

    private List<Recorded> participants(Connection conn, InventoryMutationOperationId operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(selectParticipants)) {
            ps.setString(1, operationId.value().toString());
            List<Recorded> sides = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sides.add(new Recorded(
                            rs.getString("owner_root_id"),
                            rs.getString("before_fingerprint"),
                            rs.getString("after_fingerprint"),
                            ParticipantApplyState.valueOf(rs.getString("durable_apply_state")),
                            before(rs.getString("mutation_delta_payload"))));
                }
            }
            return List.copyOf(sides);
        }
    }

    static byte[] before(@Nullable String payload) {
        Matcher kept = BEFORE.matcher(payload == null ? "" : payload);
        return kept.find() ? Base64.getDecoder().decode(kept.group(1)) : new byte[0];
    }

    private void setState(Connection conn, InventoryMutationOperationId operationId, InventoryMutationJournalState to)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql.updateJournalState())) {
            ps.setString(1, to.name());
            ps.setString(2, operationId.value().toString());
            ps.executeUpdate();
        }
    }
}
