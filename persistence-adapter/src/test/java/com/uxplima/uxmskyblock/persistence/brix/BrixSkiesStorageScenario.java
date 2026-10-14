package com.uxplima.uxmskyblock.persistence.brix;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must keep of the time and weather a Brix plot's team set. */
final class BrixSkiesStorageScenario {

    private BrixSkiesStorageScenario() {}

    static void run(Database database) throws SQLException {
        SqlBrixPlotsAdapter plots = new SqlBrixPlotsAdapter(database.dataSource());
        SqlBrixSkiesAdapter skies = new SqlBrixSkiesAdapter(database.dataSource());
        IslandId plot = island(database);
        IslandId other = island(database);
        plots.add(plot);
        plots.add(other);
        assertThat(skies.findAll()).isEmpty();

        skies.save(plot, new PlotSky(18_000, PlotSky.Weather.RAIN));
        skies.save(other, new PlotSky(PlotSky.WORLD_TIME, PlotSky.Weather.CLEAR));
        assertThat(skies.findAll())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        plot, new PlotSky(18_000, PlotSky.Weather.RAIN),
                        other, new PlotSky(PlotSky.WORLD_TIME, PlotSky.Weather.CLEAR)));

        skies.save(plot, new PlotSky(6000, PlotSky.Weather.WORLD));
        assertThat(skies.findAll())
                .describedAs("set again, kept once")
                .containsEntry(plot, new PlotSky(6000, PlotSky.Weather.WORLD));
        skies.save(other, PlotSky.WORLD);
        assertThat(skies.findAll()).describedAs("the world's own keeps nothing").containsOnlyKeys(plot);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM brix_plots WHERE island_id = ?")) {
            ps.setString(1, plot.value().toString());
            ps.executeUpdate();
        }
        assertThat(skies.findAll()).describedAs("the sky goes with the plot").isEmpty();
    }

    private static IslandId island(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, custom_name,"
                                + " created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', NULL, CURRENT_TIMESTAMP,"
                                + " CURRENT_TIMESTAMP)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
        return island;
    }
}
