package com.uxplima.uxmskyblock.bukkit.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockFormEvent;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OreGeneratorListenerTest extends MockBukkitHarness {

    private static final UpgradeId ORE_UPGRADE_ID = UpgradeId.of("ore_generator");

    private World world;
    private Island sampleIsland;
    private IslandId islandId;
    private SpatialIslandIndex spatialIndex;
    private IslandUpgradeService mockUpgradeService;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        islandId = new IslandId(UUID.randomUUID());
        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());

        spatialIndex = new SpatialIslandIndex();
        spatialIndex.indexIsland(sampleIsland, world.getName());

        mockUpgradeService = mock(IslandUpgradeService.class);
    }

    @Test
    @DisplayName("does not alter formed block when generators config is disabled")
    void doesNotAlterWhenDisabled() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(false, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("does not alter non-generator materials such as obsidian or dirt")
    void doesNotAlterOtherMaterials() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.OBSIDIAN);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.OBSIDIAN);
    }

    @Test
    @DisplayName("does not alter block outside island boundaries")
    void doesNotAlterOutsideIsland() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(5000, 64, 5000);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("does not alter block when island tier is 0")
    void doesNotAlterWhenTierZero() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.COAL_ORE, 1.0)));
        when(mockUpgradeService.getCachedTier(islandId, ORE_UPGRADE_ID)).thenReturn(0);
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("transforms cobblestone to ore according to tier rates")
    void transformsCobblestoneToOre() {
        GeneratorsConfiguration config =
                new GeneratorsConfiguration(true, Map.of(1, Map.of(Material.DIAMOND_ORE, 1.0)));
        when(mockUpgradeService.getCachedTier(islandId, ORE_UPGRADE_ID)).thenReturn(1);
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);

        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);

        assertThat(event.getNewState().getType()).isEqualTo(Material.DIAMOND_ORE);
    }

    @Test
    @DisplayName("An ore generator booster makes each ore likelier and the stone the generator forms no likelier")
    void aBoosterMakesOresLikelier() {
        java.util.LinkedHashMap<Material, Double> rates = new java.util.LinkedHashMap<>();
        rates.put(Material.COBBLESTONE, 0.7);
        rates.put(Material.COAL_ORE, 0.2);
        rates.put(Material.IRON_ORE, 0.1);
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, rates));

        assertThat(config.roll(1, 0.6)).isEqualTo(Material.COBBLESTONE);
        assertThat(config.roll(1, 0.6, 2.0))
                .describedAs("0.78 of 1.3 falls past the stone")
                .isEqualTo(Material.COAL_ORE);
        assertThat(config.roll(1, 0.5, 2.0))
                .describedAs("0.65 of 1.3 is still stone")
                .isEqualTo(Material.COBBLESTONE);
        assertThat(config.roll(1, 0.95, 2.0)).isEqualTo(Material.IRON_ORE);
        assertThat(config.roll(1, 0.82, 0.5))
                .describedAs("nothing makes ores rarer: 0.82 of 1.0 is coal, as unboosted")
                .isEqualTo(Material.COAL_ORE);

        when(mockUpgradeService.isCached(islandId)).thenReturn(true);
        when(mockUpgradeService.getCachedTier(islandId, ORE_UPGRADE_ID)).thenReturn(1);
        OreGeneratorListener listener = new OreGeneratorListener(
                mockUpgradeService,
                new GeneratorsConfiguration(
                        true, Map.of(1, Map.of(Material.COBBLESTONE, 1.0, Material.DIAMOND_ORE, 0.0001))),
                spatialIndex);
        listener.useBoosters((island, kind) -> island.equals(islandId)
                        && kind == com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory.ORE_GENERATOR
                ? 1_000_000.0
                : 1.0);
        Block block = world.getBlockAt(0, 64, 0);
        BlockState newState = block.getState();
        newState.setType(Material.COBBLESTONE);
        BlockFormEvent event = new BlockFormEvent(block, newState);
        listener.onBlockForm(event);
        assertThat(event.getNewState().getType())
                .describedAs("a booster a million strong turns up the ore nearly always")
                .isEqualTo(Material.DIAMOND_ORE);
    }

    @Test
    @DisplayName("A generator in the Nether turns up its tier's Nether rates, and one in the overworld its own")
    void theNetherTurnsUpItsOwn() {
        org.bukkit.World nether = server.createWorld(
                new org.bukkit.WorldCreator("skyblock_nether").environment(org.bukkit.World.Environment.NETHER));
        assertThat(nether.getEnvironment()).isEqualTo(org.bukkit.World.Environment.NETHER);
        spatialIndex.indexIsland(sampleIsland, nether.getName());
        GeneratorsConfiguration config = new GeneratorsConfiguration(
                true,
                Map.of(1, Map.of(Material.DIAMOND_ORE, 1.0)),
                Map.of(org.bukkit.World.Environment.NETHER, Map.of(1, Map.of(Material.NETHER_QUARTZ_ORE, 1.0))));
        when(mockUpgradeService.getCachedTier(islandId, ORE_UPGRADE_ID)).thenReturn(1);
        OreGeneratorListener listener = new OreGeneratorListener(mockUpgradeService, config, spatialIndex);

        BlockState there = nether.getBlockAt(0, 64, 0).getState();
        there.setType(Material.COBBLESTONE);
        BlockFormEvent inTheNether = new BlockFormEvent(nether.getBlockAt(0, 64, 0), there);
        listener.onBlockForm(inTheNether);
        BlockState here = world.getBlockAt(0, 64, 0).getState();
        here.setType(Material.COBBLESTONE);
        BlockFormEvent inTheOverworld = new BlockFormEvent(world.getBlockAt(0, 64, 0), here);
        listener.onBlockForm(inTheOverworld);

        assertThat(inTheNether.getNewState().getType()).isEqualTo(Material.NETHER_QUARTZ_ORE);
        assertThat(inTheOverworld.getNewState().getType()).isEqualTo(Material.DIAMOND_ORE);
    }
}
