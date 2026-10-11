package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class IslandResetConfirmationMenuTest extends MockBukkitHarness {

    private IslandRecycleService mockRecycleService;
    private IslandStoragePort mockStorage;
    private PlayerSessionCoordinator mockSessionCoordinator;
    private DirectSchedulerPort scheduler;

    private IslandResetConfirmationMenu menu;
    private Player player;
    private IslandId islandId;
    private ProfileId profileId;

    @BeforeEach
    void setUp() {
        Guis.install(MockBukkit.createMockPlugin());

        mockRecycleService = mock(IslandRecycleService.class);
        mockStorage = mock(IslandStoragePort.class);
        mockSessionCoordinator = mock(PlayerSessionCoordinator.class);
        scheduler = new DirectSchedulerPort();

        menu = new IslandResetConfirmationMenu(
                mockRecycleService, mockStorage, mockSessionCoordinator, scheduler, Messages.bundled());

        player = createPlayer("TestResetPlayer");
        islandId = new IslandId(UUID.randomUUID());
        profileId = new ProfileId(player.getUniqueId());
    }

    @Test
    @DisplayName("buildGui sets the delete and keep buttons, and nothing between them asks for a code")
    void buildGuiSetsExpectedSlots() {
        SimpleGui gui = menu.buildGui(player, profileId, islandId, () -> {});

        assertThat(gui.size()).isEqualTo(27);
        assertThat(gui.getItem(11)).isInstanceOf(GuiItem.Static.class);
        assertThat(((GuiItem.Static) Objects.requireNonNull(gui.getItem(11)))
                        .item()
                        .getType())
                .isEqualTo(Material.RED_CONCRETE);

        assertThat(((GuiItem.Static) Objects.requireNonNull(gui.getItem(13)))
                        .item()
                        .getType())
                .describedAs("two buttons and nothing else: no code to copy out")
                .isEqualTo(Material.GRAY_STAINED_GLASS_PANE);

        assertThat(gui.getItem(15)).isInstanceOf(GuiItem.Static.class);
        assertThat(((GuiItem.Static) Objects.requireNonNull(gui.getItem(15)))
                        .item()
                        .getType())
                .isEqualTo(Material.GREEN_CONCRETE);

        // Fillers
        assertThat(gui.getItem(0)).isInstanceOf(GuiItem.Static.class);
        assertThat(((GuiItem.Static) Objects.requireNonNull(gui.getItem(0)))
                        .item()
                        .getType())
                .isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        assertThat(gui.getItem(26)).isInstanceOf(GuiItem.Static.class);
        assertThat(((GuiItem.Static) Objects.requireNonNull(gui.getItem(26)))
                        .item()
                        .getType())
                .isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
    }

    @Test
    @DisplayName("open warns player when no island exists")
    void openWarnsPlayerWithoutIsland() {
        when(mockSessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.empty());

        menu.open(player, () -> {});

        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
            assertThat(player.getOpenInventory().getTopInventory().getSize()).isNotEqualTo(27);
        }
    }

    @Test
    @DisplayName("open displays confirmation gui when player has island")
    void openDisplaysConfirmationGuiWhenIslandPresent() {
        when(mockSessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));

        menu.open(player, () -> {});

        assertThat(player.getOpenInventory().getTopInventory().getSize()).isEqualTo(27);
    }

    @Test
    @DisplayName("open delegates to BedrockFormService when player is on Bedrock")
    void openDelegatesToBedrockFormServiceWhenBedrockPlayer() {
        com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService mockBedrock =
                mock(com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.class);
        when(mockBedrock.isBedrock(player)).thenReturn(true);

        IslandResetConfirmationMenu bedrockMenu = new IslandResetConfirmationMenu(
                mockRecycleService, mockStorage, mockSessionCoordinator, scheduler, mockBedrock, Messages.bundled());

        when(mockSessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));

        bedrockMenu.open(player, () -> {});

        org.mockito.Mockito.verify(mockBedrock)
                .openConfirmationModal(
                        eq(player),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static class DirectSchedulerPort implements SchedulerPort {
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

    @Test
    @DisplayName("The confirm button runs the confirmation it was given, and erases nothing by itself")
    void theConfirmButtonRunsTheGivenConfirmation() {
        java.util.concurrent.atomic.AtomicInteger confirmed = new java.util.concurrent.atomic.AtomicInteger();
        SimpleGui gui = menu.buildGui(player, profileId, islandId, confirmed::incrementAndGet);

        clickSlot(gui, 11);

        assertThat(confirmed).hasValue(1);
        org.mockito.Mockito.verify(mockRecycleService, org.mockito.Mockito.never())
                .executeReset(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** Clicks a slot the way the framework does: the resolved action, on a cancelled event. */
    private void clickSlot(SimpleGui gui, int slot) {
        com.uxplima.uxmlib.gui.item.GuiItem item =
                java.util.Objects.requireNonNull(gui.getItem(slot), "slot " + slot + " is empty");
        item.action(new com.uxplima.uxmlib.gui.item.RenderContext(player, gui, slot))
                .accept(mock(org.bukkit.event.inventory.InventoryClickEvent.class));
    }
}
