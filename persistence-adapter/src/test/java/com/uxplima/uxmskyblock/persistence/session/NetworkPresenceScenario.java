package com.uxplima.uxmskyblock.persistence.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;

/** What every engine must count as the players on the network. */
final class NetworkPresenceScenario {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-b");

    private NetworkPresenceScenario() {}

    static void run(Database database) throws Exception {
        PlayerSessionAuthorityAdapter sessions = new PlayerSessionAuthorityAdapter(database);
        SqlNetworkPresenceAdapter presence = new SqlNetworkPresenceAdapter(database.dataSource());
        assertThat(presence.online()).describedAs("nobody yet").isZero();

        PlayerUuid onA = login(sessions, NODE_A);
        PlayerUuid onB = login(sessions, NODE_B);
        PlayerUuid leaving = login(sessions, NODE_A);
        PlayerUuid moving = login(sessions, NODE_B);
        PlayerUuid stopped = login(sessions, NODE_A);
        assertThat(presence.online()).describedAs("on either server").isEqualTo(5);

        // Leaving drains first, then lets go.
        assertThat(sessions.drain(leaving, NODE_A, epoch(sessions, leaving)).isSuccess())
                .isTrue();
        assertThat(sessions.releaseToOffline(leaving, NODE_A, epoch(sessions, leaving))
                        .isSuccess())
                .isTrue();
        assertThat(sessions.drain(moving, NODE_B, epoch(sessions, moving)).isSuccess())
                .isTrue();
        try (Connection conn = database.connection();
                // An hour ago by the database's own clock, which writes and reads every lease.
                PreparedStatement ps = conn.prepareStatement("UPDATE player_sessions SET lease_expires_at = "
                        + anHourAgo(database.dialect()) + " WHERE player_uuid = ?")) {
            ps.setString(1, stopped.value().toString());
            ps.executeUpdate();
        }

        assertThat(presence.online())
                .describedAs("one gone offline and one whose server stopped renewing are not on; one on its way is")
                .isEqualTo(3);
        assertThat(onA).isNotEqualTo(onB);
    }

    private static String anHourAgo(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> "DATETIME('now', '-1 hour')";
            case POSTGRES -> "CURRENT_TIMESTAMP - INTERVAL '1 hour'";
            default -> "CURRENT_TIMESTAMP - INTERVAL 1 HOUR";
        };
    }

    private static PlayerUuid login(PlayerSessionAuthorityAdapter sessions, ServerNodeId node) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        SessionAuthorityOutcome outcome = sessions.ensureSession(player, ProfileId.of(UUID.randomUUID()), node);
        assertThat(outcome.isSuccess()).isTrue();
        return player;
    }

    private static long epoch(PlayerSessionAuthorityAdapter sessions, PlayerUuid player) {
        return sessions.findSession(player).orElseThrow().sessionEpoch();
    }
}
