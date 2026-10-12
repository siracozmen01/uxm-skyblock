package com.uxplima.uxmskyblock.bukkit.state;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/** A player whose session holds their state, crossing between groups of worlds through an in-memory store. */
public final class CarriedStateFixture {

    private CarriedStateFixture() {}

    /** {@link PlayerStates} for {@code player} alone, their session started where they stand. */
    public static PlayerStates started(
            Player player, List<String> presetWorlds, PlayerStates.Advancements advancements) {
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        ActiveSession session = new ActiveSession(PlayerUuid.of(player.getUniqueId()), profile, 1, 1);
        session.holdUntil(System.nanoTime() + Duration.ofHours(1).toNanos());
        session.enterPlay();
        CarriedStatesInMemory store = new CarriedStatesInMemory();
        store.inventories.put(profile, ProfileInventoryRecord.createDefault(profile, new byte[0], new byte[0]));
        PlayerStates states = new PlayerStates(
                () -> CarriedStateConfiguration.SHIPPED,
                () -> presetWorlds,
                new InventoryCodec() {
                    @Override
                    public byte[] write(ItemStack[] items) {
                        return new byte[0];
                    }

                    @Override
                    public ItemStack[] read(byte[] bytes) {
                        return new ItemStack[0];
                    }
                },
                advancements,
                store,
                new PlayerStates.Sessions() {
                    @Override
                    public @Nullable ActiveSession session(UUID who) {
                        return who.equals(player.getUniqueId()) ? session : null;
                    }

                    @Override
                    public boolean inPlay(UUID who) {
                        return who.equals(player.getUniqueId()) && session.inPlay(System.nanoTime());
                    }

                    @Override
                    public ServerNodeId node() {
                        return ServerNodeId.of("lab");
                    }
                },
                DIRECT);
        states.sessionActive(player);
        return states;
    }

    private static final SchedulerPort DIRECT = new SchedulerPort() {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    };
}
