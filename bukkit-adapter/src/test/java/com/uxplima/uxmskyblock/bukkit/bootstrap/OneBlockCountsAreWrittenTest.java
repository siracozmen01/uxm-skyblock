package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A OneBlock island's counted breaks reach the database on the operator's interval and when the
 * server stops, and not only when an island is forgotten.
 */
class OneBlockCountsAreWrittenTest {

    @Test
    @DisplayName("The count is written every save interval and once more when the server stops")
    void writtenOnScheduleAndAtStop() throws Exception {
        Path dir = Files.createTempDirectory("oneblock");
        try (PersistenceBootstrap persistence = PersistenceBootstrap.createSqlite(dir.resolve("sky.db"))) {
            IslandId island = island(dir.resolve("sky.db"));
            List<Runnable> scheduled = new ArrayList<>();
            List<Duration> periods = new ArrayList<>();
            SchedulerPort scheduler = mock(SchedulerPort.class);
            when(scheduler.repeatAsync(any(), any(), any())).thenAnswer(call -> {
                scheduled.add(call.getArgument(0, Runnable.class));
                periods.add(call.getArgument(2, Duration.class));
                return (AutoCloseable) () -> {};
            });
            OneBlockConfiguration config = OneBlockConfiguration.load(
                    HoconConfigurationLoader.builder().buildAndLoadString("save-interval = \"45s\""));
            ConfigurationWiring configuration = mock(ConfigurationWiring.class);
            when(configuration.oneBlockConfig()).thenReturn(config);
            when(configuration.messages()).thenReturn(com.uxplima.uxmskyblock.bukkit.i18n.Messages.bundled());
            when(configuration.effectsConfig())
                    .thenReturn(com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects.none());
            OneBlockWiring wiring = new OneBlockWiring(
                    configuration,
                    persistence,
                    scheduler,
                    mock(com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener.class),
                    uuid -> java.util.Optional.empty(),
                    (where, at, line) -> new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPauses.Shown() {
                        @Override
                        public void say(
                                java.util.function.Function<
                                                org.bukkit.entity.Player, net.kyori.adventure.text.Component>
                                        again) {}

                        @Override
                        public void remove() {}
                    });
            wiring.service().start(island, 0, 100, 0);

            wiring.service().onBreak(island);
            scheduled.getFirst().run();
            wiring.service().onBreak(island);
            wiring.close();

            assertThat(periods).containsExactly(Duration.ofSeconds(45));
            assertThat(persistence
                            .oneBlockProgressPort()
                            .find(island)
                            .orElseThrow()
                            .blocksBroken())
                    .isEqualTo(2);
        }
        String bootstrap = Files.readString(
                Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/SkyblockBootstrap.java"));
        assertThat(bootstrap.indexOf("gameplayWiring.oneBlockWiring().close();"))
                .describedAs("the stop writes the counts before the database closes")
                .isPositive()
                .isLessThan(bootstrap.indexOf("persistenceWiring.close();"));
    }

    @Test
    @DisplayName("No interval, an unreadable one and one that is not positive keep the default")
    void anythingElseKeepsTheDefault() throws Exception {
        for (String written : new String[] {"", "save-interval = \"soon\"", "save-interval = \"0s\""}) {
            assertThat(OneBlockConfiguration.load(
                                    HoconConfigurationLoader.builder().buildAndLoadString(written + "\nenabled = true"))
                            .saveInterval())
                    .describedAs(written)
                    .isEqualTo(OneBlockConfiguration.DEFAULT_SAVE_INTERVAL);
        }
    }

    private static IslandId island(Path file) throws Exception {
        IslandId id = IslandId.of(UUID.randomUUID());
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, lifecycle, economic_state,"
                                + " administrative_state, level_score, net_worth_minor_units, version)"
                                + " VALUES (?, ?, ?, 'ACTIVE', 'NORMAL', 'NORMAL', 0, 0, 1)")) {
            ps.setString(1, id.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
        return id;
    }
}
