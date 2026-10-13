package com.uxplima.uxmskyblock.persistence.boxed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with the Boxed boxes an ender pearl moved. */
final class BoxOffsetsStorageScenario {

    private BoxOffsetsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        SqlBoxedIslandsAdapter boxed = new SqlBoxedIslandsAdapter(database.dataSource());
        SqlBoxOffsetsAdapter offsets = new SqlBoxOffsetsAdapter(database.dataSource());
        for (IslandId each : new IslandId[] {island, other}) {
            BoxedLandStorageScenario.seedIsland(database, each);
            boxed.add(each);
        }

        assertThat(offsets.findAll()).describedAs("no box moved").isEmpty();
        offsets.move(island, new BoxOffset(12, -30));
        offsets.move(other, new BoxOffset(-4, 0));
        offsets.move(island, new BoxOffset(40, 7));
        assertThat(offsets.findAll())
                .hasSize(2)
                .containsEntry(island, new BoxOffset(40, 7))
                .containsEntry(other, new BoxOffset(-4, 0));
        assertThatThrownBy(() -> offsets.move(IslandId.of(UUID.randomUUID()), new BoxOffset(1, 1)))
                .describedAs("only a Boxed island has a box to move")
                .isInstanceOf(IllegalStateException.class);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, island.value().toString());
            ps.executeUpdate();
        }
        assertThat(offsets.findAll())
                .describedAs("an island gone takes its box")
                .containsOnlyKeys(other);
    }
}
