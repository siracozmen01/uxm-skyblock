package com.uxplima.uxmskyblock.bukkit.mission;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandMissionListenerTest {

    private IslandMissionService missionService;
    private IslandStoragePort islandStoragePort;
    private PlayerSessionCoordinator sessionCoordinator;
    private ImmediateScheduler scheduler;
    private IslandMissionListener listener;

    private Player player;
    private UUID playerUuid;
    private ProfileId profileId;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        missionService = mock(IslandMissionService.class);
        islandStoragePort = mock(IslandStoragePort.class);
        sessionCoordinator = mock(PlayerSessionCoordinator.class);
        scheduler = new ImmediateScheduler();

        listener = new IslandMissionListener(
                missionService, islandStoragePort, sessionCoordinator, scheduler, Messages.bundled());

        playerUuid = UUID.randomUUID();
        profileId = new ProfileId(UUID.randomUUID());
        islandId = new IslandId(UUID.randomUUID());

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnline()).thenReturn(true);
        when(sessionCoordinator.activeProfile(playerUuid)).thenReturn(Optional.of(profileId));
    }

    @Test
    @DisplayName("dispatchTrigger caches island lookup and avoids redundant DB queries on hot path")
    void cachesIslandLookupOnHotPath() {
        when(islandStoragePort.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.COBBLESTONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        // First event: cache miss, triggers async lookup
        listener.onBlockBreak(event);
        verify(islandStoragePort, times(1)).findIslandIdByProfileId(profileId);
        verify(missionService, times(1))
                .handleTrigger(any(), any(), any(MissionTriggerType.class), any(), any(Long.class), any());

        // Second event: cache hit, zero DB queries!
        listener.onBlockBreak(event);
        verify(islandStoragePort, times(1)).findIslandIdByProfileId(profileId); // Still 1!
        verify(missionService, times(2))
                .handleTrigger(any(), any(), any(MissionTriggerType.class), any(), any(Long.class), any());
    }

    @Test
    @DisplayName("setProfileIsland prewarms cache so hot path never queries island storage")
    void prewarmedCacheNeverQueriesStorage() {
        listener.setProfileIsland(profileId, islandId);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBlockBreak(event);

        verify(islandStoragePort, never()).findIslandIdByProfileId(any());
        verify(missionService, times(1))
                .handleTrigger(any(), any(), any(MissionTriggerType.class), any(), any(Long.class), any());
    }

    @Test
    @DisplayName("invalidateProfile evicts cached mapping and queries storage on next event")
    void invalidationClearsCache() {
        listener.setProfileIsland(profileId, islandId);
        listener.invalidateProfile(profileId);

        when(islandStoragePort.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.OAK_LOG);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBlockBreak(event);

        verify(islandStoragePort, times(1)).findIslandIdByProfileId(profileId);
    }

    @Test
    @DisplayName("A mode's own event is counted as any other trigger, for the player's island")
    void aModesEventIsCounted() {
        listener.setProfileIsland(profileId, islandId);

        listener.counted(player, MissionTriggerType.ONEBLOCK_PHASE, "underground", 1L);

        verify(missionService)
                .handleTrigger(
                        org.mockito.ArgumentMatchers.eq(islandId),
                        org.mockito.ArgumentMatchers.eq(profileId),
                        org.mockito.ArgumentMatchers.eq(MissionTriggerType.ONEBLOCK_PHASE),
                        org.mockito.ArgumentMatchers.eq("underground"),
                        org.mockito.ArgumentMatchers.eq(1L),
                        any());
    }

    private static class ImmediateScheduler implements SchedulerPort {
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
    }
}
