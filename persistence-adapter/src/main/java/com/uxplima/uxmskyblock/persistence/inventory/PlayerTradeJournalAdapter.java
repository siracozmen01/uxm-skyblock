package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * The trade journal on the same two tables as every other journaled inventory mutation.
 *
 * <p>A trade is one {@code inventory_mutation_journals} row with one participant per player, each
 * fenced by its own {@code player_sessions} row. The inventory a side held before the trade is kept in
 * the participant's delta payload, so a side can be put back after a crash.
 */
public final class PlayerTradeJournalAdapter implements TradeJournalPort {

    /** The rows a trade's journal reads and writes inside the connection that moves it: the trade's state locked, every side set, a side's inventory written and fingerprinted, and whether another side still holds its half. */
    private final TradeJournalRows rows = new TradeJournalRows(this);

    private static final Pattern BEFORE = Pattern.compile("\"before\"\\s*:\\s*\"([A-Za-z0-9+/=]*)\"");

    private final Database database;
    final InventoryMutationJournalSql sql;
    final JournalTransaction transaction;
    private final SessionAuthorityGate authority;
    private final String insertJournal;
    private final String insertParticipant;
    private final String selectParticipants;
    final String updateParticipantsState;
    final String selectDurableInventory;

    public PlayerTradeJournalAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        Dialect dialect = database.dialect();
        this.sql = InventoryMutationJournalSql.forDialect(dialect);
        this.transaction = new JournalTransaction(database);
        this.authority = new SessionAuthorityGate(sql.selectSessionAuthority());
        // Postgres types a json column strictly and refuses a bare string bind.
        String json = dialect == Dialect.POSTGRES ? "CAST(? AS json)" : "?";
        this.insertJournal = "INSERT INTO inventory_mutation_journals "
                + "(operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at) "
                + "VALUES (?, '" + OPERATION_TYPE + "', 'INTENT', ?, " + json
                + ", ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        this.insertParticipant = "INSERT INTO inventory_mutation_participants "
                + "(operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                + "expected_version, authority_type, authority_id, authority_epoch, "
                + "before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at) "
                + "VALUES (?, ?, 'PLAYER_INVENTORY', 'PROFILE', ?, ?, 'PLAYER_SESSION', ?, ?, ?, ?, 'PENDING', "
                + json + ", CURRENT_TIMESTAMP)";
        String participants = "SELECT participant_index, owner_root_id, expected_version, before_fingerprint, "
                + "after_fingerprint, durable_apply_state, mutation_delta_payload "
                + "FROM inventory_mutation_participants WHERE operation_id = ? ORDER BY participant_index";
        this.selectParticipants = dialect == Dialect.SQLITE ? participants : participants + " FOR UPDATE";
        String durable = "SELECT inventory_nbt FROM profile_inventories WHERE profile_id = ?";
        this.selectDurableInventory = dialect == Dialect.SQLITE ? durable : durable + " FOR UPDATE";
        this.updateParticipantsState = "UPDATE inventory_mutation_participants "
                + "SET durable_apply_state = ?, updated_at = CURRENT_TIMESTAMP WHERE operation_id = ?";
    }

    @Override
    public InventoryMutationJournalOutcome recordIntent(
            InventoryMutationOperationId operationId,
            ServerNodeId node,
            List<Side> sides,
            String payload,
            Duration expiry) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expiry, "expiry");
        if (Objects.requireNonNull(sides, "sides").size() < 2) {
            throw new IllegalArgumentException("A trade has at least two sides");
        }
        return transaction.run("recordTradeIntent", conn -> {
            if (rows.lockedState(conn, operationId).isPresent()) {
                return InventoryMutationJournalOutcome.conflict("OPERATION_CONFLICT");
            }
            for (Side side : inLockOrder(sides, Side::holder)) {
                Optional<String> refusal = refusal(conn, side.holder(), node, true);
                if (refusal.isPresent()) {
                    return InventoryMutationJournalOutcome.rejected(refusal.get());
                }
                try (PreparedStatement ps = conn.prepareStatement(sql.selectInventoryVersion())) {
                    ps.setString(1, side.holder().profile().value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return InventoryMutationJournalOutcome.rejected("PROFILE_INVENTORY_NOT_FOUND");
                        }
                        if (rs.getLong(1) != side.expectedVersion()) {
                            return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                        }
                    }
                }
                // The side as the trade found it is written down with the intent, one version on. A
                // checkpoint may be a minute old; from here on durable storage holds the trade's before,
                // so recovery and the abort read the trade, not the minute. The version moves so that
                // nothing written at the version the session knew, a player's last write as they leave
                // mid-trade, lands over the trade: only the trade's own commit writes this side now.
                if (!rows.writeInventory(conn, side.holder(), side.beforeInventory(), side.expectedVersion())) {
                    return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insertJournal)) {
                ps.setString(1, operationId.value().toString());
                ps.setInt(2, sides.size());
                ps.setString(3, payload);
                ps.setTimestamp(4, new Timestamp(System.currentTimeMillis() + expiry.toMillis()));
                ps.executeUpdate();
            }
            for (int index = 0; index < sides.size(); index++) {
                Side side = sides.get(index);
                try (PreparedStatement ps = conn.prepareStatement(insertParticipant)) {
                    ps.setString(1, operationId.value().toString());
                    ps.setInt(2, index);
                    ps.setString(3, side.holder().profile().value().toString());
                    ps.setLong(4, side.expectedVersion());
                    ps.setString(5, side.holder().player().value().toString());
                    ps.setLong(6, side.holder().sessionEpoch());
                    ps.setString(7, side.beforeFingerprint());
                    ps.setString(8, side.afterFingerprint());
                    ps.setString(
                            9, "{\"before\":\"" + Base64.getEncoder().encodeToString(side.beforeInventory()) + "\"}");
                    ps.executeUpdate();
                }
            }
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index) {
        Objects.requireNonNull(operationId, "operationId");
        return transaction.run("markTradeSideApplied", conn -> {
            if (!rows.lockedState(conn, operationId).map("INTENT"::equals).orElse(false)) {
                return InventoryMutationJournalOutcome.rejected("TRADE_NOT_OPEN");
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
            InventoryMutationOperationId operationId, ServerNodeId node, List<Outcome> sides) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(sides, "sides");
        return transaction.run("commitTrade", conn -> {
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
            List<Participant> recorded = participants(conn, operationId);
            if (recorded.size() != sides.size()) {
                return InventoryMutationJournalOutcome.rejected("PARTICIPANT_MISMATCH");
            }
            if (recorded.stream().anyMatch(side -> side.applyState() == ParticipantApplyState.REVERTED)) {
                // Recovery put a side back already: committing over it would give that side's half twice.
                return InventoryMutationJournalOutcome.rejected("SIDE_PUT_BACK");
            }
            for (Outcome side : inLockOrder(sides, Outcome::holder)) {
                Optional<String> refusal = refusal(conn, side.holder(), node, true);
                if (refusal.isPresent()) {
                    return InventoryMutationJournalOutcome.rejected(refusal.get());
                }
            }
            for (int index = 0; index < sides.size(); index++) {
                Outcome side = sides.get(index);
                Participant participant = recorded.get(index);
                // The intent moved every side one version on; the commit writes over that one.
                if (!participant.profile().equals(side.holder().profile())
                        || participant.expectedVersion() + 1 != side.expectedVersion()) {
                    return InventoryMutationJournalOutcome.rejected("PARTICIPANT_MISMATCH");
                }
                if (!rows.writeInventory(conn, side.holder(), side.inventory(), side.expectedVersion())) {
                    return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                }
            }
            rows.setEveryParticipant(conn, operationId, ParticipantApplyState.APPLIED);
            setState(conn, operationId, InventoryMutationJournalState.COMMITTED);
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public InventoryMutationJournalOutcome abort(
            InventoryMutationOperationId operationId, ServerNodeId node, List<Holder> holders) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(holders, "holders");
        return transaction.run("abortTrade", conn -> {
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
            for (Holder holder : inLockOrder(holders, holder -> holder)) {
                Optional<String> refusal = refusal(conn, holder, node, false);
                if (refusal.isPresent()) {
                    return InventoryMutationJournalOutcome.rejected(refusal.get());
                }
            }
            // An abort says no side was given anything. A side whose durable inventory holds anything but
            // what the trade found, a player who left mid-trade with their half, is not aborted over: it
            // stays open for recovery to put back.
            for (Participant side : participants(conn, operationId)) {
                if (side.applyState() != ParticipantApplyState.REVERTED
                        && !rows.durableFingerprint(conn, side.profile()).equals(side.beforeFingerprint())) {
                    return InventoryMutationJournalOutcome.rejected("SIDE_WRITTEN");
                }
            }
            rows.setEveryParticipant(conn, operationId, ParticipantApplyState.REVERTED);
            setState(conn, operationId, InventoryMutationJournalState.ABORTED);
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public List<InventoryMutationOperationId> findOpenTrades(ProfileId profile) {
        Objects.requireNonNull(profile, "profile");
        String find = "SELECT j.operation_id FROM inventory_mutation_journals j "
                + "JOIN inventory_mutation_participants p ON p.operation_id = j.operation_id "
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
            throw new InventoryPersistenceException("Failed to find the open trades of " + profile, e);
        }
    }

    @Override
    public List<Participant> participants(InventoryMutationOperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection()) {
            return participants(conn, operationId);
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to read the sides of trade " + operationId, e);
        }
    }

    @Override
    public Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection()) {
            return readState(conn, operationId, sql.selectJournalRead()).map(InventoryMutationJournalState::valueOf);
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to read trade " + operationId, e);
        }
    }

    @Override
    public InventoryMutationJournalOutcome settleSide(
            InventoryMutationOperationId operationId,
            int index,
            ServerNodeId node,
            Holder holder,
            byte @Nullable [] restore,
            long restoreOver,
            boolean closeTrade) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(holder, "holder");
        return transaction.run("settleTradeSide", conn -> {
            Optional<String> state = rows.lockedState(conn, operationId);
            if (state.isEmpty()) {
                return InventoryMutationJournalOutcome.rejected("JOURNAL_NOT_FOUND");
            }
            if (!"INTENT".equals(state.get())) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }
            List<Participant> recorded = participants(conn, operationId);
            if (index < 0
                    || index >= recorded.size()
                    || !recorded.get(index).profile().equals(holder.profile())) {
                return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
            }
            Optional<String> refusal = refusal(conn, holder, node, true);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            // Recovery decided from what it read before this transaction. What it decided is checked
            // again here, under the locks, against what the inventories hold now: two players' recoveries
            // running at once must not each act on a picture the other just changed.
            Participant mine = recorded.get(index);
            boolean putBack = mine.applyState() == ParticipantApplyState.REVERTED;
            String holds = rows.durableFingerprint(conn, mine.profile());
            if (restore != null && (putBack || !holds.equals(mine.afterFingerprint()))) {
                return InventoryMutationJournalOutcome.rejected("SIDE_MOVED");
            }
            if (restore == null && !putBack && !holds.equals(mine.beforeFingerprint())) {
                return InventoryMutationJournalOutcome.rejected("SIDE_MOVED");
            }
            if (closeTrade && rows.anotherHoldsItsHalf(conn, recorded, index)) {
                return InventoryMutationJournalOutcome.rejected("ANOTHER_SIDE_HOLDS_ITS_HALF");
            }
            if (restore != null && !rows.writeInventory(conn, holder, restore, restoreOver)) {
                return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.updateParticipantState())) {
                ps.setString(1, ParticipantApplyState.REVERTED.name());
                ps.setString(2, operationId.value().toString());
                ps.setInt(3, index);
                ps.executeUpdate();
            }
            if (closeTrade) {
                rows.setEveryParticipant(conn, operationId, ParticipantApplyState.REVERTED);
                setState(conn, operationId, InventoryMutationJournalState.ABORTED);
            }
            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public InventoryMutationJournalOutcome settleTrade(
            InventoryMutationOperationId operationId,
            ServerNodeId node,
            Holder holder,
            InventoryMutationJournalState settledAs) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(holder, "holder");
        if (settledAs != InventoryMutationJournalState.COMMITTED
                && settledAs != InventoryMutationJournalState.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("A trade settles as COMMITTED or RECOVERY_REQUIRED, not " + settledAs);
        }
        return transaction.run("settleTrade", conn -> {
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
            List<Participant> recorded = participants(conn, operationId);
            if (recorded.stream().noneMatch(side -> side.profile().equals(holder.profile()))) {
                return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
            }
            Optional<String> refusal = refusal(conn, holder, node, true);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            if (settledAs == InventoryMutationJournalState.COMMITTED) {
                // A trade is committed as it stands only while every side still holds its outcome.
                for (Participant side : recorded) {
                    if (side.applyState() == ParticipantApplyState.REVERTED
                            || !rows.durableFingerprint(conn, side.profile()).equals(side.afterFingerprint())) {
                        return InventoryMutationJournalOutcome.rejected("NOT_EVERY_SIDE_HOLDS_IT");
                    }
                }
            }
            if (settledAs == InventoryMutationJournalState.COMMITTED) {
                rows.setEveryParticipant(conn, operationId, ParticipantApplyState.APPLIED);
            }
            setState(conn, operationId, settledAs);
            return InventoryMutationJournalOutcome.success();
        });
    }

    /**
     * {@code items} in the order their sessions are locked: by player id read as an unsigned 128-bit
     * number, so two trades over the same players lock them the same way and never wait on each other.
     */
    private static <T> List<T> inLockOrder(List<T> items, java.util.function.Function<T, Holder> holder) {
        Comparator<UUID> unsigned = (left, right) -> {
            int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
            return high != 0
                    ? high
                    : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
        };
        List<T> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparing(item -> holder.apply(item).player().value(), unsigned));
        return ordered;
    }

    private Optional<String> refusal(Connection conn, Holder holder, ServerNodeId node, boolean requireActive)
            throws SQLException {
        return authority.refusal(conn, holder.player(), holder.profile(), node, holder.sessionEpoch(), requireActive);
    }

    static Optional<String> readState(Connection conn, InventoryMutationOperationId operationId, String select)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, operationId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("state")) : Optional.empty();
            }
        }
    }

    private List<Participant> participants(Connection conn, InventoryMutationOperationId operationId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(selectParticipants)) {
            ps.setString(1, operationId.value().toString());
            List<Participant> sides = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sides.add(new Participant(
                            rs.getInt("participant_index"),
                            ProfileId.of(UUID.fromString(rs.getString("owner_root_id"))),
                            rs.getLong("expected_version"),
                            rs.getString("before_fingerprint"),
                            rs.getString("after_fingerprint"),
                            ParticipantApplyState.valueOf(rs.getString("durable_apply_state")),
                            before(rs.getString("mutation_delta_payload"))));
                }
            }
            return List.copyOf(sides);
        }
    }

    private static byte[] before(@Nullable String payload) {
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
