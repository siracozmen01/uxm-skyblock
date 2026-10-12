package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A manual submission takes the items on the entity thread and writes the progress off it. If the
 * write does nothing the player has paid for nothing, and nothing used to give the items back.
 */
class MissionSubmissionReturnsItemsTest extends MockBukkitHarness {

    private IslandMissionService missionService;
    private IslandMissionsMenu menu;
    private PlayerMock player;
    private IslandId islandId;
    private ProfileId profileId;
    private MissionDefinition definition;

    @BeforeEach
    void setUp() {
        missionService = mock(IslandMissionService.class);
        IslandStoragePort storagePort = mock(IslandStoragePort.class);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        menu = new IslandMissionsMenu(
                missionService, storagePort, sessions, new DirectScheduler(), Messages.bundled(), null);

        player = createPlayer("Submitter");
        islandId = IslandId.of(UUID.randomUUID());
        profileId = new ProfileId(UUID.randomUUID());

        definition = new MissionDefinition(
                MissionId.of("collect-diamonds"),
                MissionBranch.MINING,
                "Collect diamonds",
                "Hand in diamonds.",
                MissionTriggerType.ITEM_SUBMIT,
                Material.DIAMOND.name(),
                10,
                MissionReward.empty());
        when(missionService.offeredMissions(any(), any(), any())).thenReturn(List.of(definition));
        when(missionService.findAllProgress(eq(islandId), eq(profileId))).thenReturn(java.util.Map.of());
    }

    @Test
    @DisplayName("Items come back when the submission is not credited")
    void itemsComeBackWhenNotCredited() {
        when(missionService.submitManualItems(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.empty());
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, 0L);

        assertThat(countOf(Material.DIAMOND)).isEqualTo(4);
    }

    @Test
    @DisplayName("Items come back when the write throws rather than vanishing with the error")
    void itemsComeBackWhenTheWriteThrows() {
        when(missionService.submitManualItems(any(), any(), any(), anyLong(), any()))
                .thenThrow(new IllegalStateException("database is gone"));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, 0L);

        assertThat(countOf(Material.DIAMOND)).isEqualTo(4);
    }

    @Test
    @DisplayName("Only what the mission took stays taken, and the rest comes back")
    void onlyWhatWasCreditedStaysTaken() {
        // The window was drawn when the mission still wanted ten, so ten are taken. Another window
        // handed in eight in between, so the mission credits two. The other two are the player's.
        when(missionService.submitManualItems(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.of(
                        new IslandMissionService.MissionSubmission(MissionProgress.initial(definition.id()), 2L)));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, 0L);

        assertThat(countOf(Material.DIAMOND))
                .describedAs("diamonds the mission did not take")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("Handing in to a mission that wants nothing more takes nothing")
    void aMissionThatWantsNothingTakesNothing() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, definition.requiredAmount());

        assertThat(countOf(Material.DIAMOND)).isEqualTo(4);
    }

    @Test
    @DisplayName("Items stay taken when the submission is credited")
    void itemsStayTakenWhenCredited() {
        when(missionService.submitManualItems(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.of(
                        new IslandMissionService.MissionSubmission(MissionProgress.initial(definition.id()), 4L)));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, 0L);

        assertThat(countOf(Material.DIAMOND)).isZero();
    }

    @Test
    @DisplayName("A renamed diamond is not handed in as a diamond")
    void aRenamedDiamondIsNotHandedIn() {
        when(missionService.submitManualItems(any(), any(), any(), anyLong(), any()))
                .thenAnswer(call -> Optional.of(new IslandMissionService.MissionSubmission(
                        MissionProgress.initial(definition.id()), call.<Long>getArgument(3))));
        player.getInventory().addItem(named(Material.DIAMOND, "Heirloom"));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, 0L);

        verify(missionService).submitManualItems(any(), any(), any(), eq(3L), any());
        assertThat(player.getInventory().getContents())
                .describedAs("the renamed diamond the mission never priced")
                .anySatisfy(stack -> assertThat(stack).isEqualTo(named(Material.DIAMOND, "Heirloom")));
        assertThat(countOf(Material.DIAMOND)).isEqualTo(1);
    }

    @Test
    @DisplayName("A player holding only a renamed diamond has nothing to hand in")
    void onlyARenamedDiamondIsNothingToHandIn() {
        player.getInventory().addItem(named(Material.DIAMOND, "Heirloom"));

        menu.handleManualItemSubmission(player, islandId, profileId, definition, 0L);

        verify(missionService, never()).submitManualItems(any(), any(), any(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND)).isEqualTo(1);
    }

    private static ItemStack named(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        stack.setItemMeta(meta);
        return stack;
    }

    private int countOf(Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static final class DirectScheduler implements SchedulerPort {
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
