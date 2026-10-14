package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.loot.LootFiller;
import com.uxplima.uxmskyblock.bukkit.loot.LootTable;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * The deep boss of a Poseidon island: now and then, while a player swims there, a creature rises from the deep beside
 * them. Whoever slays it is given a loot table; if nobody does, it sinks back after a while. One island sees one at a
 * time, and none again until a while has passed.
 *
 * <p>The beat runs on the global thread and hands each player to their own thread, where the boss rises beside them.
 * The boss is marked with its island, so its death is known by the mark alone, and it is not kept when its chunk goes,
 * so a restart leaves none behind.
 */
public final class DeepBoss implements Listener {

    private static final Logger LOGGER = Logger.getLogger(DeepBoss.class.getName());

    /**
     * Runs {@code task} on the boss's own thread after {@code after}, or as soon as the boss is gone if it goes first:
     * a creature the server took away with its chunk is no longer there to sink.
     */
    public interface Later {
        void run(Entity boss, Duration after, Runnable task);
    }

    private final PoseidonConfiguration.Boss config;
    private final PoseidonService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Supplier<InteractionEffects> effects;
    private final InteractionEffectPlayer effectPlayer;
    private final Supplier<LootTables> tables;
    private final LootFiller filler;
    private final Later later;
    private final Clock clock;
    private final Random random;
    private final NamespacedKey mark = new NamespacedKey("uxmskyblock", "deep_boss");
    private final Map<IslandId, UUID> risen = new ConcurrentHashMap<>();
    private final Map<IslandId, Instant> ended = new ConcurrentHashMap<>();

    public DeepBoss(
            PoseidonConfiguration.Boss config,
            PoseidonService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages,
            Supplier<InteractionEffects> effects,
            InteractionEffectPlayer effectPlayer,
            Supplier<LootTables> tables,
            LootFiller filler,
            Later later,
            Clock clock,
            Random random) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.tables = Objects.requireNonNull(tables, "tables must not be null");
        this.filler = Objects.requireNonNull(filler, "filler must not be null");
        this.later = Objects.requireNonNull(later, "later must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** Starts the beat while the boss is enabled. Closing what it returns stops it. */
    public AutoCloseable start() {
        if (!config.enabled()) {
            return () -> {};
        }
        return scheduler.repeatGlobal(
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> look(player));
                    }
                },
                config.checkEvery(),
                config.checkEvery());
    }

    /**
     * One look at one player, on the player's own thread: a swimmer on a Poseidon island with no boss risen and none
     * lately rolls the chance. Answers the boss that rose.
     */
    public Optional<LivingEntity> look(Player player) {
        if (!config.enabled() || !player.isValid() || player.isDead()) {
            return Optional.empty();
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return Optional.empty();
        }
        Location at = player.getLocation();
        if (at == null) {
            return Optional.empty();
        }
        Optional<Island> island = islands.findIslandAt(at);
        if (island.isEmpty() || !service.isPoseidon(island.get().id()) || !inWater(player, at)) {
            return Optional.empty();
        }
        IslandId id = island.get().id();
        Instant last = ended.get(id);
        if (risen.containsKey(id)
                || (last != null && Duration.between(last, clock.instant()).compareTo(config.every()) < 0)
                || random.nextDouble() >= config.chance()) {
            return Optional.empty();
        }
        return rise(id, at);
    }

    /** Raises the boss beside {@code at}, on the thread that owns it. */
    Optional<LivingEntity> rise(IslandId island, Location at) {
        EntityType type = org.bukkit.Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(config.entity()));
        if (type == null
                || type.getEntityClass() == null
                || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            LOGGER.warning(() -> "modules/poseidon.conf boss names the creature '" + config.entity()
                    + "', which is no living creature. None rises.");
            return Optional.empty();
        }
        Location spot = at.clone();
        Location below = at.clone().add(0, -2, 0);
        if (below.getBlock().getType() == Material.WATER) {
            spot = below;
        }
        if (!(at.getWorld().spawnEntity(spot, type) instanceof LivingEntity boss)) {
            return Optional.empty();
        }
        boss.getPersistentDataContainer()
                .set(mark, PersistentDataType.STRING, island.value().toString());
        boss.setPersistent(false);
        boss.customName(messages.render(Bukkit.getConsoleSender(), "poseidon.boss.name"));
        boss.setCustomNameVisible(true);
        AttributeInstance most = boss.getAttribute(Attribute.MAX_HEALTH);
        if (most != null) {
            most.setBaseValue(config.health());
        }
        boss.setHealth(config.health());
        risen.put(island, boss.getUniqueId());
        fire(island, "poseidon-boss-rises");
        later.run(boss, config.lasts(), () -> {
            if (!risen.remove(island, boss.getUniqueId())) {
                return;
            }
            ended.put(island, clock.instant());
            if (boss.isValid()) {
                boss.remove();
                fire(island, "poseidon-boss-sinks");
            }
        });
        return Optional.of(boss);
    }

    /** A slain boss gives its slayer the loot table, besides its own drops, and every player on the island is told. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        String marked = event.getEntity().getPersistentDataContainer().get(mark, PersistentDataType.STRING);
        if (marked == null) {
            return;
        }
        IslandId island;
        try {
            island = IslandId.of(UUID.fromString(marked));
        } catch (IllegalArgumentException e) {
            return;
        }
        risen.remove(island, event.getEntity().getUniqueId());
        ended.put(island, clock.instant());
        Player slayer = event.getEntity().getKiller();
        Optional<LootTable> table =
                config.loot().isEmpty() ? Optional.empty() : tables.get().named(config.loot());
        if (slayer != null && table.isPresent()) {
            Inventory bag = Bukkit.createInventory(null, 54);
            filler.fill(bag, table.get(), random, event.getEntity().getLocation(), slayer);
            event.getDrops().addAll(carried(bag.getContents()));
        } else if (slayer != null && !config.loot().isEmpty()) {
            LOGGER.warning(() ->
                    "modules/poseidon.conf boss names the loot table '" + config.loot() + "', which loot/ has not.");
        }
        fire(island, "poseidon-boss-slain");
    }

    /** The stacks a filled bag holds, leaving its empty slots. */
    static java.util.List<ItemStack> carried(@Nullable ItemStack[] contents) {
        java.util.List<ItemStack> held = new java.util.ArrayList<>();
        for (@Nullable ItemStack stack : contents) {
            if (stack != null && !stack.getType().isAir()) {
                held.add(stack);
            }
        }
        return held;
    }

    /** Fires {@code effect} for every player standing on {@code island}, each on their own thread. */
    private void fire(IslandId island, String effect) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> {
                Location at = player.getLocation();
                if (at != null
                        && islands.findIslandAt(at)
                                .map(Island::id)
                                .filter(island::equals)
                                .isPresent()) {
                    effectPlayer.fire(effects.get(), effect, player);
                }
            });
        }
    }

    private static boolean inWater(Player player, Location at) {
        return at.getBlock().getType() == Material.WATER
                || player.getEyeLocation().getBlock().getType() == Material.WATER;
    }
}
