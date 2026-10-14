package com.uxplima.uxmskyblock.bukkit.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntBiFunction;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * The rates an island pays for, at work: crops grow faster, spawners run faster and mobs drop more.
 *
 * <p>An island's rate is its upgrade's {@code rate_multiplier} on the tier it holds, times the booster of the same
 * kind it has running. The crop growth and spawner rates upgrades were sold for a long time and nothing read them;
 * the boosters of the same names likewise. Each is read from memory: an island not read yet runs at the plain rate
 * until it is.
 */
public final class IslandRates implements Listener {

    /** Mobs killed on the island drop more. */
    public static final UpgradeId MOB_DROPS = UpgradeId.of("mob_drops");

    /** The property of a rate upgrade's tier that holds its rate. */
    static final String RATE = "rate_multiplier";

    private final Function<Location, Optional<Island>> islandAt;
    private final ToIntBiFunction<IslandId, UpgradeId> bought;
    private final Supplier<Map<UpgradeId, UpgradeDefinition>> definitions;
    private final ToDoubleBiFunction<IslandId, BoosterCategory> boosters;
    private final SchedulerPort scheduler;
    private final DoubleSupplier random;

    /**
     * @param bought the tiers of an upgrade an island bought, below zero while not known
     * @param boosters the multiplier of the booster of a kind an island has running, one for none
     * @param random a number from zero to one, for the part of a rate a whole step cannot give
     */
    public IslandRates(
            Function<Location, Optional<Island>> islandAt,
            ToIntBiFunction<IslandId, UpgradeId> bought,
            Supplier<Map<UpgradeId, UpgradeDefinition>> definitions,
            ToDoubleBiFunction<IslandId, BoosterCategory> boosters,
            SchedulerPort scheduler,
            DoubleSupplier random) {
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt must not be null");
        this.bought = Objects.requireNonNull(bought, "bought must not be null");
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.boosters = Objects.requireNonNull(boosters, "boosters must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** The rate of {@code upgrade} and {@code booster} together on the island at {@code at}, one for none. */
    double rateAt(Location at, UpgradeId upgrade, @Nullable BoosterCategory booster) {
        Optional<Island> island = islandAt.apply(at);
        if (island.isEmpty()) {
            return 1.0;
        }
        IslandId id = island.get().id();
        double rate = upgradeRate(id, upgrade);
        if (booster != null) {
            rate *= Math.max(1.0, boosters.applyAsDouble(id, booster));
        }
        return rate;
    }

    private double upgradeRate(IslandId island, UpgradeId upgrade) {
        UpgradeDefinition definition = definitions.get().get(upgrade);
        if (definition == null) {
            return 1.0;
        }
        // An island not read yet holds no tier it bought, and still the free one every island starts on.
        return definition
                .getTier(definition.effectiveTier(bought.applyAsInt(island, upgrade)))
                .map(tier -> tier.properties().getOrDefault(RATE, 1.0))
                .map(rate -> Math.max(1.0, rate))
                .orElse(1.0);
    }

    /** How many a rate makes of {@code count}: the whole part always, and one more by chance for the rest. */
    static int times(int count, double rate, double roll) {
        double made = count * rate;
        int whole = (int) Math.floor(made);
        return whole + (roll < made - whole ? 1 : 0);
    }

    /** A crop that grows a stage grows the rate's worth of stages, as far as it can grow. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        BlockState grown = event.getNewState();
        if (!(grown.getBlockData() instanceof Ageable crop)) {
            return;
        }
        double rate = rateAt(event.getBlock().getLocation(), UpgradeId.CROP_GROWTH, BoosterCategory.CROP_GROWTH);
        int stages = times(1, rate, random.getAsDouble()) - 1;
        if (stages <= 0) {
            return;
        }
        BlockData faster = crop.clone();
        ((Ageable) faster).setAge(Math.min(crop.getMaximumAge(), crop.getAge() + stages));
        grown.setBlockData(faster);
    }

    /** A spawner that spawns waits its next delay cut by the rate. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(SpawnerSpawnEvent event) {
        CreatureSpawner spawner = event.getSpawner();
        if (spawner == null) {
            return;
        }
        Block block = spawner.getBlock();
        double rate = rateAt(block.getLocation(), UpgradeId.SPAWNER_RATES, BoosterCategory.SPAWNER_RATE);
        if (rate <= 1.0) {
            return;
        }
        // The game draws the next delay once the spawn is done, so it is cut after, on the spawner's own region.
        scheduler.onRegion(
                block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4, () -> shorten(block, rate));
    }

    /** Cuts the delay a spawner is waiting by {@code rate}. */
    static void shorten(Block block, double rate) {
        if (block.getState() instanceof CreatureSpawner spawner) {
            spawner.setDelay(Math.max(1, (int) (spawner.getDelay() / rate)));
            spawner.update();
        }
    }

    /** A mob killed on the island drops the rate's worth of each stack it drops. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        double rate = rateAt(event.getEntity().getLocation(), MOB_DROPS, null);
        if (rate <= 1.0) {
            return;
        }
        List<ItemStack> more = new ArrayList<>();
        for (ItemStack drop : event.getDrops()) {
            int count = times(drop.getAmount(), rate, random.getAsDouble());
            int max = Math.max(1, drop.getMaxStackSize());
            while (count > 0) {
                ItemStack stack = drop.clone();
                stack.setAmount(Math.min(max, count));
                more.add(stack);
                count -= stack.getAmount();
            }
        }
        event.getDrops().clear();
        event.getDrops().addAll(more);
    }
}
