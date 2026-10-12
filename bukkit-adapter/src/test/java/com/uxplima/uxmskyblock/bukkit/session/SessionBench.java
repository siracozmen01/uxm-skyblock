package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A session coordinator on a file database whose ambient checkpoint runs once an hour, so anything
 * that reaches the database in a test was written by what the test did.
 */
final class SessionBench implements AutoCloseable {

    final PersistenceBootstrap persistence;
    final PlayerSessionCoordinator coordinator;
    private final ServerMock server;
    private final boolean ownsPersistence;

    SessionBench(ServerMock server) throws Exception {
        this(server, UnaryOperator.identity());
    }

    /** The same, with the checkpoint port the coordinator writes through passed through {@code checkpoints}. */
    SessionBench(ServerMock server, UnaryOperator<ProfileInventoryCheckpointPort> checkpoints) throws Exception {
        this(
                server,
                PersistenceBootstrap.createSqlite(
                        Files.createTempDirectory("bench_").resolve("bench.db")),
                checkpoints,
                true);
    }

    /** The same, with the scheduler the coordinator runs on passed through {@code schedulers} as well. */
    SessionBench(
            ServerMock server,
            UnaryOperator<ProfileInventoryCheckpointPort> checkpoints,
            UnaryOperator<com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort> schedulers)
            throws Exception {
        this(
                server,
                PersistenceBootstrap.createSqlite(
                        Files.createTempDirectory("bench_").resolve("bench.db")),
                checkpoints,
                schedulers,
                true);
    }

    /** A second server on the same database, as a restart after a crash finds it; it leaves the database open. */
    SessionBench(ServerMock server, PersistenceBootstrap shared) {
        this(server, shared, UnaryOperator.identity(), false);
    }

    private SessionBench(
            ServerMock server,
            PersistenceBootstrap persistence,
            UnaryOperator<ProfileInventoryCheckpointPort> checkpoints,
            boolean ownsPersistence) {
        this(server, persistence, checkpoints, UnaryOperator.identity(), ownsPersistence);
    }

    private SessionBench(
            ServerMock server,
            PersistenceBootstrap persistence,
            UnaryOperator<ProfileInventoryCheckpointPort> checkpoints,
            UnaryOperator<com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort> schedulers,
            boolean ownsPersistence) {
        this.server = server;
        this.persistence = persistence;
        this.ownsPersistence = ownsPersistence;
        coordinator = new PlayerSessionCoordinator(
                ServerNodeId.of("bench-node"),
                persistence.sessionAuthorityPort(),
                checkpoints.apply(persistence.inventoryPort()),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                schedulers.apply(new FoliaSchedulerAdapter(MockBukkit.createMockPlugin())),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled());
    }

    /** A player whose session is made and in their hands. */
    PlayerMock inPlay(PlayerMock player) {
        coordinator.handlePlayerJoin(player);
        until(() -> assertThat(coordinator.inPlay(player.getUniqueId())).isTrue());
        return player;
    }

    ProfileInventoryRecord stored(ProfileId profile) {
        return persistence.inventoryPort().loadInventory(profile).orElseThrow();
    }

    ProfileInventoryRecord stored(PlayerMock player) {
        return stored(new ProfileId(player.getUniqueId()));
    }

    static ItemStack[] items(ProfileInventoryRecord record) {
        return Arrays.stream(BukkitInventorySerializer.deserializeItemStacks(record.inventoryNbt()))
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .toArray(ItemStack[]::new);
    }

    /** Ticks the server until {@code assertion} holds, or fails with its last word after five seconds. */
    void until(Runnable assertion) {
        long start = System.currentTimeMillis();
        AssertionError last = new AssertionError("never checked");
        while (System.currentTimeMillis() - start < 5000) {
            server.getScheduler().performOneTick();
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.onSpinWait();
            }
        }
        throw last;
    }

    @Override
    public void close() {
        coordinator.shutdown();
        if (ownsPersistence) {
            persistence.close();
        }
    }
}
