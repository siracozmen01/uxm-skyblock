package com.uxplima.uxmskyblock.persistence.brix;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.brix.BrixSkiesPort;
import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The {@code brix_plot_skies} table. */
public final class SqlBrixSkiesAdapter implements BrixSkiesPort {

    private final DataSource dataSource;

    public SqlBrixSkiesAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<IslandId, PlotSky> findAll() {
        Map<IslandId, PlotSky> all = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT island_id, plot_time, plot_weather FROM brix_plot_skies");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.put(
                        IslandId.of(UUID.fromString(rs.getString(1))),
                        new PlotSky(
                                rs.getInt(2),
                                PlotSky.Weather.valueOf(rs.getString(3).toUpperCase(Locale.ROOT))));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the Brix plots' skies", e);
        }
    }

    @Override
    public void save(IslandId islandId, PlotSky sky) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(sky, "sky must not be null");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM brix_plot_skies WHERE island_id = ?")) {
                    ps.setString(1, islandId.value().toString());
                    ps.executeUpdate();
                }
                if (!sky.isWorld()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO brix_plot_skies (island_id, plot_time, plot_weather) VALUES (?, ?, ?)")) {
                        ps.setString(1, islandId.value().toString());
                        ps.setInt(2, sky.time());
                        ps.setString(3, sky.weather().name().toLowerCase(Locale.ROOT));
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not keep the sky of Brix plot " + islandId, e);
        }
    }
}
