package com.uxplima.uxmskyblock.bukkit.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Ageable;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The crop growth and spawner rates upgrades were sold and nothing read them, and of the boosters only mob
 * experience did anything. Each rate an island pays for now acts, the upgrade's and the booster's together.
 */
class TheRatesAnIslandPaysForActTest extends MockBukkitHarness {

    private static final UpgradeId SLOW = UpgradeId.of("slow");

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    private final Map<UpgradeId, Integer> bought = new HashMap<>();
    private final Map<BoosterCategory, Double> boosted = new HashMap<>();
    private final List<String> regions = new ArrayList<>();
    private double roll = 0.99;

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 32),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    private static UpgradeDefinition rated(UpgradeId id, double... rates) {
        List<UpgradeTier> tiers = new ArrayList<>();
        for (int i = 0; i < rates.length; i++) {
            tiers.add(new UpgradeTier(i + 1, 10L, "PRIMARY", Map.of("rate_multiplier", rates[i])));
        }
        return new UpgradeDefinition(id, id.key(), tiers);
    }

    private IslandRates rates() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        org.mockito.Mockito.doAnswer(call -> {
                    regions.add(call.getArgument(0) + " " + call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        return new IslandRates(
                at -> world.equals(at.getWorld()) && island.bounds().contains(at.getBlockX(), at.getBlockZ())
                        ? Optional.of(island)
                        : Optional.empty(),
                (id, upgrade) -> bought.getOrDefault(upgrade, 0),
                () -> Map.of(
                        UpgradeId.CROP_GROWTH,
                        rated(UpgradeId.CROP_GROWTH, 1.5, 2.0),
                        UpgradeId.SPAWNER_RATES,
                        rated(UpgradeId.SPAWNER_RATES, 1.25),
                        IslandRates.MOB_DROPS,
                        rated(IslandRates.MOB_DROPS, 2.0),
                        SLOW,
                        rated(SLOW, 0.5)),
                (id, kind) -> boosted.getOrDefault(kind, 1.0),
                scheduler,
                () -> roll);
    }

    @Test
    @DisplayName("A rate gives its whole part always and one more by chance for the rest")
    void theWholeAndTheRest() {
        assertThat(IslandRates.times(4, 1.5, 0.99)).isEqualTo(6);
        assertThat(IslandRates.times(1, 1.5, 0.49)).isEqualTo(2);
        assertThat(IslandRates.times(1, 1.5, 0.5)).isEqualTo(1);
        assertThat(IslandRates.times(3, 1.0, 0.0)).isEqualTo(3);
    }

    @Test
    @DisplayName("The rate is the upgrade's tier times the booster running, and plain off the island or unread")
    void theRateIsTheUpgradeTimesTheBooster() {
        Location here = new Location(world, 5, 64, 5);
        assertThat(rates().rateAt(here, UpgradeId.CROP_GROWTH, BoosterCategory.CROP_GROWTH))
                .isEqualTo(1.0);

        bought.put(UpgradeId.CROP_GROWTH, 2);
        boosted.put(BoosterCategory.CROP_GROWTH, 1.5);
        assertThat(rates().rateAt(here, UpgradeId.CROP_GROWTH, BoosterCategory.CROP_GROWTH))
                .isEqualTo(3.0);
        assertThat(rates().rateAt(here, UpgradeId.CROP_GROWTH, null))
                .describedAs("no booster kind")
                .isEqualTo(2.0);
        assertThat(rates().rateAt(
                                new Location(world, 900, 64, 900), UpgradeId.CROP_GROWTH, BoosterCategory.CROP_GROWTH))
                .isEqualTo(1.0);

        bought.put(UpgradeId.CROP_GROWTH, -1);
        assertThat(rates().rateAt(here, UpgradeId.CROP_GROWTH, BoosterCategory.CROP_GROWTH))
                .describedAs("unread, the booster alone")
                .isEqualTo(1.5);
        bought.put(SLOW, 1);
        assertThat(rates().rateAt(here, SLOW, null))
                .describedAs("a tier below one slows nothing")
                .isEqualTo(1.0);
        boosted.put(BoosterCategory.CROP_GROWTH, 0.5);
        assertThat(rates().rateAt(here, UpgradeId.CROP_GROWTH, BoosterCategory.CROP_GROWTH))
                .describedAs("nothing slows the island")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("A crop that grows a stage grows the rate's worth, no further than it can")
    void cropsGrowFaster() {
        bought.put(UpgradeId.CROP_GROWTH, 2);
        boosted.put(BoosterCategory.CROP_GROWTH, 1.5);
        Ageable crop = mock(Ageable.class);
        Ageable faster = mock(Ageable.class);
        when(crop.getAge()).thenReturn(6);
        when(crop.getMaximumAge()).thenReturn(7);
        when(crop.clone()).thenReturn(faster);
        BlockState grown = mock(BlockState.class);
        when(grown.getBlockData()).thenReturn(crop);
        Block block = world.getBlockAt(5, 64, 5);

        rates().onGrow(new BlockGrowEvent(block, grown));
        verify(faster).setAge(7);
        verify(grown).setBlockData(faster);

        BlockState far = mock(BlockState.class);
        when(far.getBlockData()).thenReturn(crop);
        rates().onGrow(new BlockGrowEvent(world.getBlockAt(900, 64, 900), far));
        verify(far, never()).setBlockData(any());
    }

    @Test
    @DisplayName("A crop at one and a half grows an extra stage half the time")
    void aCropByChance() {
        bought.put(UpgradeId.CROP_GROWTH, 1);
        Ageable crop = mock(Ageable.class);
        Ageable faster = mock(Ageable.class);
        when(crop.getAge()).thenReturn(1);
        when(crop.getMaximumAge()).thenReturn(7);
        when(crop.clone()).thenReturn(faster);
        BlockState grown = mock(BlockState.class);
        when(grown.getBlockData()).thenReturn(crop);
        Block block = world.getBlockAt(5, 64, 5);

        roll = 0.6;
        rates().onGrow(new BlockGrowEvent(block, grown));
        verify(grown, never()).setBlockData(any());
        roll = 0.4;
        rates().onGrow(new BlockGrowEvent(block, grown));
        verify(faster).setAge(2);
    }

    @Test
    @DisplayName("A spawner that spawns waits its next delay cut by the rate, on its own region")
    void spawnersRunFaster() {
        bought.put(UpgradeId.SPAWNER_RATES, 1);
        boosted.put(BoosterCategory.SPAWNER_RATE, 2.0);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(40);
        when(block.getZ()).thenReturn(-20);
        when(block.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        when(spawner.getBlock()).thenReturn(block);
        when(spawner.getDelay()).thenReturn(400);
        when(block.getState()).thenReturn(spawner);

        rates().onSpawn(new SpawnerSpawnEvent(mock(org.bukkit.entity.Entity.class), spawner));

        assertThat(regions).containsExactly("skyblock 2,-2");
        verify(spawner).setDelay(160);
        verify(spawner).update();

        CreatureSpawner plain = mock(CreatureSpawner.class);
        Block elsewhere = mock(Block.class);
        when(elsewhere.getLocation()).thenReturn(new Location(world, 900, 64, 900));
        when(plain.getBlock()).thenReturn(elsewhere);
        rates().onSpawn(new SpawnerSpawnEvent(mock(org.bukkit.entity.Entity.class), plain));
        assertThat(regions).describedAs("a spawner of no island is left be").hasSize(1);

        CreatureSpawner short_ = mock(CreatureSpawner.class);
        Block quick = mock(Block.class);
        when(quick.getState()).thenReturn(short_);
        when(short_.getDelay()).thenReturn(1);
        IslandRates.shorten(quick, 3.0);
        verify(short_).setDelay(1);
    }

    @Test
    @DisplayName("A mob killed on the island drops the rate's worth of each stack, split into full stacks")
    void mobsDropMore() {
        bought.put(IslandRates.MOB_DROPS, 1);
        LivingEntity zombie = mock(LivingEntity.class);
        when(zombie.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        List<ItemStack> drops = new ArrayList<>(
                List.of(new ItemStack(Material.ROTTEN_FLESH, 2), new ItemStack(Material.COBBLESTONE, 40)));

        rates().onDeath(new EntityDeathEvent(
                zombie, DamageSource.builder(DamageType.GENERIC).build(), drops));

        assertThat(drops)
                .extracting(stack -> stack.getType() + " " + stack.getAmount())
                .containsExactly("ROTTEN_FLESH 4", "COBBLESTONE 64", "COBBLESTONE 16");

        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        List<ItemStack> theirs = new ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 3)));
        rates().onDeath(new EntityDeathEvent(
                player, DamageSource.builder(DamageType.GENERIC).build(), theirs));
        assertThat(theirs).describedAs("a player's own things are not doubled").hasSize(1);
        assertThat(theirs.getFirst().getAmount()).isEqualTo(3);

        bought.put(IslandRates.MOB_DROPS, 0);
        List<ItemStack> plain = new ArrayList<>(List.of(new ItemStack(Material.BONE, 2)));
        rates().onDeath(new EntityDeathEvent(
                zombie, DamageSource.builder(DamageType.GENERIC).build(), plain));
        assertThat(plain.getFirst().getAmount()).isEqualTo(2);
    }
}
