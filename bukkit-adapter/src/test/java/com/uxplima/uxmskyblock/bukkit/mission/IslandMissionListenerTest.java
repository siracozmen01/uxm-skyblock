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

    @Test
    @DisplayName("A level reached moves every member's missions, and a member on this server hears of one finished")
    void aLevelReachedMovesEveryMember() {
        ProfileId otherProfile = new ProfileId(UUID.randomUUID());
        UUID otherUuid = UUID.randomUUID();
        com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition levelTen =
                new com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition(
                        com.uxplima.uxmskyblock.core.domain.mission.MissionId.of("level_ten"),
                        com.uxplima.uxmskyblock.core.domain.mission.MissionBranch.ECONOMY,
                        "Level ten",
                        "",
                        MissionTriggerType.LEVEL_REACH,
                        "",
                        10L,
                        com.uxplima.uxmskyblock.core.domain.mission.MissionReward.empty());
        com.uxplima.uxmskyblock.core.domain.mission.MissionProgress done =
                new com.uxplima.uxmskyblock.core.domain.mission.MissionProgress(
                        levelTen.id(), 10L, true, java.time.Instant.now(), java.time.Instant.now());
        when(missionService.findMission(levelTen.id())).thenReturn(Optional.of(levelTen));
        when(missionService.handleReach(
                        org.mockito.ArgumentMatchers.eq(islandId),
                        org.mockito.ArgumentMatchers.eq(profileId),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        any()))
                .thenReturn(java.util.List.of(
                        done,
                        new com.uxplima.uxmskyblock.core.domain.mission.MissionProgress(
                                levelTen.id(), 4L, false, null, java.time.Instant.now())));
        when(missionService.handleReach(
                        org.mockito.ArgumentMatchers.eq(islandId),
                        org.mockito.ArgumentMatchers.eq(otherProfile),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        any()))
                .thenReturn(java.util.List.of(done));
        listener.useMembers(
                island -> island.equals(islandId)
                        ? java.util.List.of(
                                new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                        new PlayerUuid(playerUuid),
                                        profileId,
                                        com.uxplima.uxmskyblock.core.domain.island.IslandRole.OWNER,
                                        java.time.Instant.now()),
                                new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                        new PlayerUuid(otherUuid),
                                        otherProfile,
                                        com.uxplima.uxmskyblock.core.domain.island.IslandRole.OWNER,
                                        java.time.Instant.now()))
                        : java.util.List.of(),
                uuid -> uuid.equals(playerUuid) ? player : null);

        listener.reached(islandId, MissionTriggerType.LEVEL_REACH, 12L);

        verify(missionService)
                .handleReach(
                        org.mockito.ArgumentMatchers.eq(islandId),
                        org.mockito.ArgumentMatchers.eq(profileId),
                        org.mockito.ArgumentMatchers.eq(MissionTriggerType.LEVEL_REACH),
                        org.mockito.ArgumentMatchers.eq(12L),
                        any());
        verify(missionService)
                .handleReach(
                        org.mockito.ArgumentMatchers.eq(islandId),
                        org.mockito.ArgumentMatchers.eq(otherProfile),
                        org.mockito.ArgumentMatchers.eq(MissionTriggerType.LEVEL_REACH),
                        org.mockito.ArgumentMatchers.eq(12L),
                        any());
        verify(player, times(1)).sendMessage(any(net.kyori.adventure.text.Component.class));
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
