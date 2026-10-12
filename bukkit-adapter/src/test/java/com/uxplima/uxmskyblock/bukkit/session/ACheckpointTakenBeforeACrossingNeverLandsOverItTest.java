package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.CarriedParts;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The ambient checkpoint writes over the version its snapshot was taken at.
 *
 * <p>It wrote over whatever version the session had reached by the time the write ran. A crossing between groups of
 * worlds that landed between the snapshot and the write moved that version on, so the snapshot of what the player
 * held before the crossing was written over what they picked up, and what they had put down was held twice.
 */
class ACheckpointTakenBeforeACrossingNeverLandsOverItTest extends MockBukkitHarness {

    private final AtomicReference<Runnable> between = new AtomicReference<>(() -> {});
    private final AtomicInteger writes = new AtomicInteger();
    private SessionBench bench;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server, this::counting, CrossingFirst::new);
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName("A crossing that lands between the checkpoint's snapshot and its write is not written over")
    void theCrossingStays() {
        PlayerMock player = bench.inPlay(createPlayer("Crosser"));
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        ProfileId profile = new ProfileId(player.getUniqueId());
        ActiveSession session = Objects.requireNonNull(bench.coordinator.getActiveSession(player.getUniqueId()));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        CarriedStatePort carried = bench.persistence.carriedStatePort();
        carried.carryFirst(profile, "world");
        between.set(() -> {
            CarriedStatePort.Crossed crossed = carried.cross(new CarriedStatePort.Crossing(
                    uuid,
                    profile,
                    ServerNodeId.of("bench-node"),
                    session.sessionEpoch(),
                    "world",
                    "boxed",
                    bench.stored(profile),
                    List.of(),
                    CarriedParts.ALL));
            // As the crossing leaves the session once it has landed on the player.
            session.setLastDurableVersion(
                    ((CarriedStatePort.Crossed.Done) crossed).carried().version());
        });

        bench.coordinator.checkpointPlayer(uuid);

        bench.until(() -> assertThat(writes).hasValue(1));
        assertThat(SessionBench.items(bench.stored(profile)))
                .describedAs("the diamonds held before the crossing stay put down in the group they were left in")
                .isEmpty();
        assertThat(carried.carriedGroup(profile)).hasValue("boxed");
    }

    private ProfileInventoryCheckpointPort counting(ProfileInventoryCheckpointPort real) {
        return (ProfileInventoryCheckpointPort) Proxy.newProxyInstance(
                ProfileInventoryCheckpointPort.class.getClassLoader(),
                new Class<?>[] {ProfileInventoryCheckpointPort.class},
                (proxy, method, args) -> {
                    boolean checkpoint = method.getName().equals("checkpointInventory");
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    } finally {
                        if (checkpoint) {
                            writes.incrementAndGet();
                        }
                    }
                });
    }

    /**
     * Runs the crossing, when one is waiting, on the async thread just before the next task there: the
     * checkpoint's write, its snapshot already taken.
     */
    private final class CrossingFirst implements SchedulerPort {

        private final SchedulerPort real;

        CrossingFirst(SchedulerPort real) {
            this.real = real;
        }

        @Override
        public void async(Runnable task) {
            Runnable crossing = between.getAndSet(() -> {});
            real.async(() -> {
                crossing.run();
                task.run();
            });
        }

        @Override
        public void onGlobal(Runnable task) {
            real.onGlobal(task);
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            real.onRegion(worldName, chunkX, chunkZ, task);
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            real.onEntity(playerUuid, task);
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task, Runnable retired) {
            real.onEntity(playerUuid, task, retired);
        }

        @Override
        public boolean onGlobalThread() {
            return real.onGlobalThread();
        }

        @Override
        public boolean ownsEntity(PlayerUuid playerUuid) {
            return real.ownsEntity(playerUuid);
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            real.asyncAfter(delay, task);
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            real.laterGlobal(delay, task);
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return real.repeatGlobal(task, initialDelay, period);
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return real.repeatAsync(task, initialDelay, period);
        }
    }
}
