package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The missions are {@code menus/island-mission-list.conf}: one tile for each, drawn from the progress the
 * window read off the player's thread, and a click hands items in as the window built in code did.
 */
class TheMissionsAreDrawnFromTheirFileTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final MissionDefinition DIAMONDS = new MissionDefinition(
            MissionId.of("collect-diamonds"),
            MissionBranch.MINING,
            "Collect diamonds",
            "Hand in diamonds.",
            MissionTriggerType.ITEM_SUBMIT,
            Material.DIAMOND.name(),
            10,
            new MissionReward(10L, 0L, 0L, List.of()));

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final IslandMissionService missions = mock(IslandMissionService.class);
    private final IslandStoragePort storage = mock(IslandStoragePort.class);
    private final PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
    private PlayerMock player;
    private IslandMissionsMenu menu;

    @BeforeEach
    void setUp() {
        player = createPlayer("Okur");
        player.setLocale(java.util.Locale.forLanguageTag("tr"));
        menu = new IslandMissionsMenu(missions, storage, sessions, new InlineSchedulerPort(), Messages.bundled(), null);
    }

    @Test
    @DisplayName(
            "A mission is a row that lists only the rewards it pays, and a click line only where a click hands items in")
    void aMissionIsARow() {
        MenuRow open = menu.rows(player, Map.of(), List.of(DIAMONDS)).get(0);

        assertThat(open.words())
                .containsEntry("material", "CHEST")
                .containsEntry("facts", "branch repeat progress crystals")
                .containsEntry("repeat", "Hiç, bir kez biter")
                .containsEntry("status", "0 / 10");
        assertThat(IslandMissionsMenu.factsOf(DIAMONDS, true)).endsWith("-action");
    }

    @Test
    @DisplayName("Every fact a mission tile can list has words in the catalogue")
    void everyFactHasWords() {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
        MissionDefinition paysEverything = new MissionDefinition(
                MissionId.of("all"),
                MissionBranch.FARMING,
                "All",
                "All.",
                MissionTriggerType.ITEM_SUBMIT,
                "WHEAT",
                1,
                new MissionReward(1L, 1L, 1L, List.of()));

        assertThat(provider.getKeys("en"))
                .containsAll(EveryMenuWordIsTranslatableTest.tileKeys(
                        "tile:0 @menu.missions.tile " + IslandMissionsMenu.factsOf(paysEverything, false)));
    }

    @Test
    @DisplayName("The shipped template draws a mission in the reader's words, leaving off a reward it does not pay")
    void theTemplateDrawsAMission() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        MenuContext drawn = MenuContext.of(player, null, 0)
                .withEntry(menu.rows(player, Map.of(), List.of(DIAMONDS)).get(0));

        assertThat(ShippedTemplates.lore(
                        ShippedTemplates.renderer(engine, Messages.bundled()),
                        ShippedTemplates.template("island-mission-list.conf", "missions"),
                        drawn))
                .contains("◆ Collect diamonds")
                .contains("İlerleme 0 / 10")
                .contains("Kristal +10")
                .contains("teslim et")
                .doesNotContain("Para")
                .doesNotContain("%entry_")
                .doesNotContain("<entry_");
    }

    @Test
    @DisplayName("A click on an open mission hands the held items in for the island the window was opened for")
    void aClickHandsItemsIn() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        menu.useMenuEngine(engine);
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        when(missions.submitManualItems(any(), any(), any(), anyLong(), any())).thenReturn(Optional.empty());
        MenuContext drawn = MenuContext.of(
                        player,
                        null,
                        0,
                        Map.of(
                                "island",
                                ISLAND.value().toString(),
                                "profile",
                                PROFILE.value().toString()))
                .withEntry(menu.rows(player, Map.of(), List.of(DIAMONDS)).get(0));

        ShippedTemplates.click(engine, "skyblock:mission-submit", drawn, player, ClickKind.LEFT, "");

        verify(missions).submitManualItems(eq(ISLAND), eq(PROFILE), any(), anyLong(), any());
    }

    @Test
    @DisplayName("Opening the missions hands them to the file, and the window built in code is not drawn")
    void openingUsesTheFile() {
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(missions.offeredMissions(any(), any(), any())).thenReturn(List.of(DIAMONDS));
        when(missions.currentProgress(eq(ISLAND), eq(PROFILE), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of());
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-mission-list"), anyMap(), anyMap()))
                .thenReturn(true);
        menu.useMenuEngine(engine);

        menu.open(player);

        verify(engine)
                .open(
                        eq(player),
                        eq("island-mission-list"),
                        eq(Map.of(
                                "island",
                                ISLAND.value().toString(),
                                "profile",
                                PROFILE.value().toString())),
                        eq(Map.of("skyblock:mission-list", menu.rows(player, Map.of(), List.of(DIAMONDS)))));
        org.bukkit.inventory.@org.jspecify.annotations.Nullable Inventory top =
                player.getOpenInventory().getTopInventory();
        assertThat(top == null || !(top.getHolder() instanceof com.uxplima.uxmlib.gui.Gui))
                .describedAs("no window built in code is open")
                .isTrue();
    }

    @Test
    @DisplayName("The daily, the weekly and the challenges each open with only the missions of their kind, as they "
            + "stand now")
    void eachKindOpensItsOwn() {
        MissionDefinition daily = new MissionDefinition(
                MissionId.of("daily"),
                MissionBranch.MINING,
                "Daily",
                "Every day.",
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                10,
                MissionReward.empty(),
                com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(missions.offeredMissions(any(), any(), any())).thenReturn(List.of(DIAMONDS, daily));
        Map<MissionId, com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> now = Map.of(
                daily.id(),
                new com.uxplima.uxmskyblock.core.domain.mission.MissionProgress(
                        daily.id(), 4L, false, null, java.time.Instant.now()));
        when(missions.currentProgress(eq(ISLAND), eq(PROFILE), org.mockito.ArgumentMatchers.any()))
                .thenReturn(now);
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-mission-list"), anyMap(), anyMap()))
                .thenReturn(true);
        menu.useMenuEngine(engine);

        menu.open(player, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY);
        menu.open(player, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE);
        menu.open(player, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.WEEKLY);

        verify(engine)
                .open(
                        eq(player),
                        eq("island-mission-list"),
                        anyMap(),
                        eq(Map.of("skyblock:mission-list", menu.rows(player, now, List.of(daily)))));
        verify(engine)
                .open(
                        eq(player),
                        eq("island-mission-list"),
                        anyMap(),
                        eq(Map.of("skyblock:mission-list", menu.rows(player, now, List.of(DIAMONDS)))));
        verify(engine)
                .open(eq(player), eq("island-mission-list"), anyMap(), eq(Map.of("skyblock:mission-list", List.of())));
        assertThat(menu.rows(player, now, List.of(daily)).get(0).words())
                .containsEntry("status", "4 / 10")
                .containsEntry("repeat", "Her gün");
    }

    @Test
    @DisplayName("Every mission opens in one order, the daily ones first, then by branch and id, whatever the file "
            + "handed back")
    void theMissionsKeepAnOrder() {
        MissionDefinition weekly = new MissionDefinition(
                MissionId.of("w"),
                MissionBranch.FARMING,
                "W",
                "W",
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                10,
                MissionReward.empty(),
                com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.WEEKLY);
        MissionDefinition dailyMining = new MissionDefinition(
                MissionId.of("b"),
                MissionBranch.MINING,
                "B",
                "B",
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                10,
                MissionReward.empty(),
                com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY);
        MissionDefinition dailyFarming = new MissionDefinition(
                MissionId.of("z"),
                MissionBranch.FARMING,
                "Z",
                "Z",
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                10,
                MissionReward.empty(),
                com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(missions.offeredMissions(any(), any(), any()))
                .thenReturn(List.of(DIAMONDS, weekly, dailyMining, dailyFarming));
        when(missions.currentProgress(eq(ISLAND), eq(PROFILE), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of());
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-mission-list"), anyMap(), anyMap()))
                .thenReturn(true);
        menu.useMenuEngine(engine);

        menu.open(player);

        verify(engine)
                .open(
                        eq(player),
                        eq("island-mission-list"),
                        anyMap(),
                        eq(Map.of(
                                "skyblock:mission-list",
                                menu.rows(player, Map.of(), List.of(dailyFarming, dailyMining, weekly, DIAMONDS)))));
    }
}
