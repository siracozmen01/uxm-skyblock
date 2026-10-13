package com.uxplima.uxmskyblock.persistence.boxed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxSide;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with the side each Boxed box grows toward, and the growth each island bought. */
final class BoxSidesStorageScenario {

    private BoxSidesStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        SqlBoxedIslandsAdapter boxed = new SqlBoxedIslandsAdapter(database.dataSource());
        SqlBoxSidesAdapter sides = new SqlBoxSidesAdapter(database.dataSource());
        for (IslandId each : new IslandId[] {island, other}) {
            BoxedLandStorageScenario.seedIsland(database, each);
            boxed.add(each);
        }

        assertThat(sides.findAll()).describedAs("no side chosen").isEmpty();
        sides.choose(island, BoxSide.NORTH_EAST);
        sides.choose(other, BoxSide.WEST);
        sides.choose(island, BoxSide.SOUTH);
        assertThat(sides.findAll())
                .hasSize(2)
                .containsEntry(island, BoxSide.SOUTH)
                .containsEntry(other, BoxSide.WEST);
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("UPDATE boxed_sides SET side = 'up' WHERE island_id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(sides.findAll()).describedAs("a side no release knows").containsOnlyKeys(island);
        assertThatThrownBy(() -> sides.choose(IslandId.of(UUID.randomUUID()), BoxSide.EAST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(boxed.bought(island)).isZero();
        assertThat(boxed.earn(island, BoxedIslandsPort.BOUGHT + 1, 3)).isTrue();
        assertThat(boxed.earn(island, BoxedIslandsPort.BOUGHT + 2, 3)).isTrue();
        assertThat(boxed.earn(island, "minecraft:story/mine_stone", 1)).isTrue();
        boxed.grant(island, 4);
        assertThat(boxed.earn(other, BoxedIslandsPort.BOUGHT + 1, 3)).isTrue();
        assertThat(boxed.bought(island))
                .describedAs("its purchases, not its advancements")
                .isEqualTo(2);
        assertThat(boxed.find(island)).hasValue(11);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, island.value().toString());
            ps.executeUpdate();
        }
        assertThat(sides.findAll()).describedAs("an island gone takes its side").isEmpty();
        assertThat(boxed.bought(island)).isZero();
        assertThat(boxed.bought(other)).isEqualTo(1);
    }
}
