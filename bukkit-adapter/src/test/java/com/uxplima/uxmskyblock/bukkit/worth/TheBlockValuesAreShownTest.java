package com.uxplima.uxmskyblock.bukkit.worth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** SuperiorSkyblock2 and IridiumSkyblock list what each block is worth; here levels.conf was read by nobody but the scan. */
class TheBlockValuesAreShownTest extends MockBukkitHarness {

    private LevelConfiguration levels;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        player = createPlayer("Builder");
        levels = new LevelConfiguration(
                100,
                10_000,
                25,
                50,
                0.5,
                Map.of(
                        "minecraft:diamond_block", 900L,
                        "minecraft:gold_block", 450L,
                        "minecraft:iron_block", 450L,
                        "minecraft:dirt", 0L,
                        "minecraft:not_a_block", 5L),
                Map.of(
                        "minecraft:diamond_block", 90_000L,
                        "minecraft:iron_block", 9_001L,
                        "minecraft:gold_block", 9_000L,
                        "minecraft:emerald_block", 3L),
                Map.of());
    }

    private BlockValues values() {
        return new BlockValues(() -> levels, minor -> "$" + minor, Messages.bundled());
    }

    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(next)).append('\n');
        }
        return all.toString();
    }

    @Test
    @DisplayName("Each block worth points or money is a row, the most points first, then the most money, then by name")
    void theRows() {
        List<MenuRow> rows = values().rows();
        assertThat(rows)
                .extracting(MenuRow::handle)
                .containsExactly(
                        "minecraft:diamond_block",
                        "minecraft:iron_block",
                        "minecraft:gold_block",
                        "minecraft:not_a_block",
                        "minecraft:emerald_block");
        Map<String, String> diamond = rows.get(0).words();
        assertThat(diamond)
                .containsEntry("name", "<lang:block.minecraft.diamond_block>")
                .containsEntry("material", "DIAMOND_BLOCK")
                .containsEntry("worth", "$45000")
                .containsEntry("points", "900")
                .containsEntry("level", "9.00");
        assertThat(rows.get(1).words()).containsEntry("worth", "$4501").containsEntry("level", "4.50");
        assertThat(rows.get(3).words())
                .describedAs("a key the game has no block for is drawn as paper")
                .containsEntry("material", "PAPER")
                .containsEntry("worth", "$0");
        assertThat(rows.get(4).words()).containsEntry("points", "0").containsEntry("level", "0.00");
    }

    @Test
    @DisplayName("The window opens from its file with the rows; with none to show, or no window, it says so")
    void theWindow() {
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq(BlockValues.FILE), anyMap(), anyMap())).thenReturn(true);
        BlockValues shown = values();
        shown.useMenuEngine(engine);
        verify(engine).handedList(BlockValues.BLOCKS);
        shown.open(player);
        verify(engine)
                .open(eq(player), eq(BlockValues.FILE), eq(Map.of()), eq(Map.of(BlockValues.BLOCKS, shown.rows())));
        assertThat(said(player)).isEmpty();

        when(engine.open(any(), any(), anyMap(), anyMap())).thenReturn(false);
        shown.open(player);
        assertThat(said(player)).contains("block-values.conf");

        levels = new LevelConfiguration(100, 10_000, 25, 50, 0.5, Map.of("minecraft:dirt", 0L), Map.of(), Map.of());
        shown.open(player);
        assertThat(said(player)).contains("No block is worth anything");
    }

    @Test
    @DisplayName("/is values opens the window on the player's thread, and says so when island worth is off")
    void theCommand() throws Exception {
        com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener protection =
                mock(com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener.class);
        when(protection.spatialIndex()).thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex());
        com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator sessions =
                mock(com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator.class);
        com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree tree =
                new com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree(
                        mock(com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase.class),
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandLocationService.class),
                        mock(com.uxplima.uxmskyblock.core.application.flag.IslandFlagService.class),
                        mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankService.class),
                        mock(com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort.class),
                        mock(com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService.class),
                        mock(com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort.class),
                        new com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog(),
                        mock(com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine.class),
                        protection,
                        sessions,
                        new InlineSchedulerPort(),
                        Messages.bundled(),
                        com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration.defaults(),
                        com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-1"),
                        "world");
        CommandDispatcher<CommandSourceStack> players = new CommandDispatcher<>();
        players.register(tree.buildRoot());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        players.execute("island values", source);
        assertThat(said(player)).contains("turned off");

        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(any(), any(), anyMap(), anyMap())).thenReturn(true);
        BlockValues shown = values();
        shown.useMenuEngine(engine);
        tree.useBlockValues(shown);
        players.execute("island values", source);
        verify(engine).open(eq(player), eq(BlockValues.FILE), anyMap(), anyMap());
    }
}
