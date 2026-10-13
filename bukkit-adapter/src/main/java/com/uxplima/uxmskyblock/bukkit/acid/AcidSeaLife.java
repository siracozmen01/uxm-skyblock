package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;

/**
 * What lies and swims in the acid sea around a player: an item dissolves once it has lain in the sea a while, and a
 * creature burns at each check, as {@code sea-life} of {@code modules/acidisland.conf} lets them.
 *
 * <p>An item that sinks onto a vent and lies there a while is an offering, when the vent takes items of its kind and
 * the player whose check finds it is the one who threw it: as many as the offering takes are taken, and a roll of its
 * loot table goes to them. An item nobody threw, as a creature drops dying in the sea or a dispenser throws, and an
 * item another player threw, is no offering for anyone else; it dissolves as any other. So no player is answered for
 * another's offering, and no vent pays out for drops nobody gave.
 *
 * <p>Each player's check looks around them on their own thread, and touches only what that thread owns, so on Folia
 * nothing is read or changed from a region it does not belong to. Two players near one creature burn it once a check,
 * and an item's time in the sea is kept by its id, whoever's check sees it.
 */
public final class AcidSeaLife {

    /** Who threw an item, if anybody did. */
    @FunctionalInterface
    public interface Throwers {
        @org.jspecify.annotations.Nullable UUID of(Item item);
    }

    private final AcidIslandConfiguration.SeaLife life;
    private final long checkNanos;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final LongSupplier clock;
    private final Predicate<Entity> owned;
    private final Set<String> spared = new HashSet<>();
    private final Map<UUID, Long> inTheSeaSince = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBurnt = new ConcurrentHashMap<>();
    private final AcidIslandConfiguration.Offerings offerings;
    private final Messages messages;
    private final VentLoot loot;
    private final Throwers throwers;
    private final AcidWaters waters;

    /**
     * @param clock the time, in nanoseconds
     * @param owned whether the running thread owns an entity
     */
    public AcidSeaLife(
            AcidIslandService service,
            IslandProtectionListener islands,
            AcidIslandConfiguration config,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            LongSupplier clock,
            Predicate<Entity> owned,
            Messages messages,
            VentLoot loot,
            Throwers throwers) {
        this.offerings = config.offerings();
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.loot = Objects.requireNonNull(loot, "loot must not be null");
        this.throwers = Objects.requireNonNull(throwers, "throwers must not be null");
        this.waters = new AcidWaters(service, islands, config.between());
        this.life = config.seaLife();
        this.checkNanos = config.rules().checkEvery().toNanos();
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.owned = Objects.requireNonNull(owned, "owned must not be null");
        for (String type : life.spared()) {
            spared.add(type.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
        }
    }

    /** Whether there is anything to look for. */
    public boolean on() {
        return life.reach() > 0
                && (life.itemsDissolve()
                        || life.creaturesBurn()
                        || !offerings.list().isEmpty());
    }

    /** Looks at what lies and swims in the sea around {@code player}, on the player's own thread. */
    public void around(Player player) {
        if (!on() || !player.isValid()) {
            return;
        }
        long now = clock.getAsLong();
        double reach = life.reach();
        for (Entity entity : player.getNearbyEntities(reach, reach, reach)) {
            if (!owned.test(entity) || !entity.isValid()) {
                continue;
            }
            if (entity instanceof Item item
                    && (life.itemsDissolve() || !offerings.list().isEmpty())) {
                item(player, item, now);
            } else if (entity instanceof Mob mob && life.creaturesBurn()) {
                creature(mob, now);
            }
        }
    }

    private void item(Player player, Item item, long now) {
        UUID id = item.getUniqueId();
        if (!inTheAcidSea(item.getLocation())) {
            inTheSeaSince.remove(id);
            lastSeen.remove(id);
            return;
        }
        lastSeen.put(id, now);
        long since = inTheSeaSince.computeIfAbsent(id, ignored -> now);
        if (offered(player, item, since, now)
                || !life.itemsDissolve()
                || now - since < life.itemsAfter().toNanos()) {
            return;
        }
        inTheSeaSince.remove(id);
        lastSeen.remove(id);
        String name = item.getItemStack().getType().getKey().getKey().replace('_', ' ');
        String amount = Integer.toString(item.getItemStack().getAmount());
        item.remove();
        effectPlayer.fire(effects, "acid-item-dissolved", player, Map.of("item", name, "amount", amount));
    }

    /**
     * Takes an offering off an item lying on a vent, once it has lain in the sea as long as offerings wait, and gives
     * back a roll of its loot table. Whether the item was an offering the vent takes, and so is not to dissolve.
     */
    private boolean offered(Player player, Item item, long since, long now) {
        Location at = item.getLocation();
        if (at.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType() != AcidSeaStart.VENT) {
            return false;
        }
        if (!player.getUniqueId().equals(throwers.of(item))) {
            // Only its thrower's own check answers an offering.
            return false;
        }
        ItemStack stack = item.getItemStack();
        String key = stack.getType().getKey().toString();
        java.util.Optional<AcidIslandConfiguration.Offering> offering = offerings.answering(key);
        if (offering.isEmpty() || stack.getAmount() < offering.get().takes()) {
            return false;
        }
        long waited = now - since;
        if (waited < offerings.after().toNanos()) {
            return true;
        }
        int takes = offering.get().takes();
        if (stack.getAmount() == takes) {
            item.remove();
            inTheSeaSince.remove(item.getUniqueId());
            lastSeen.remove(item.getUniqueId());
        } else {
            stack.setAmount(stack.getAmount() - takes);
            item.setItemStack(stack);
            inTheSeaSince.put(item.getUniqueId(), now);
        }
        for (ItemStack left : player.getInventory()
                .addItem(loot.roll(offering.get().loot(), at, player).toArray(ItemStack[]::new))
                .values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
        String name = stack.getType().getKey().getKey().replace('_', ' ');
        String amount = Integer.toString(takes);
        effectPlayer.fire(effects, "acid-vent-offering", player, Map.of("item", name, "amount", amount));
        messages.send(
                player,
                "acid.offering",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("item", name),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("amount", amount));
        return true;
    }

    private void creature(Mob mob, long now) {
        if (spared.contains(mob.getType().getKey().getKey()) || mob.isDead()) {
            return;
        }
        if (!inTheAcidSea(mob.getLocation()) && !inTheAcidSea(mob.getEyeLocation())) {
            return;
        }
        UUID id = mob.getUniqueId();
        Long before = lastBurnt.get(id);
        if (before != null && now - before < checkNanos / 2) {
            return;
        }
        lastBurnt.put(id, now);
        mob.damage(life.creatureDamage());
    }

    /**
     * Forgets the items and creatures no check has seen for a while: picked up, gone out of the sea far from anyone,
     * or unloaded. Called once a beat, from any thread.
     */
    public void forgetTheGone() {
        long now = clock.getAsLong();
        long stale = Math.max(checkNanos, 1) * 10 + life.itemsAfter().toNanos();
        lastSeen.entrySet().removeIf(seen -> {
            if (now - seen.getValue() > stale) {
                inTheSeaSince.remove(seen.getKey());
                return true;
            }
            return false;
        });
        inTheSeaSince.keySet().removeIf(id -> !lastSeen.containsKey(id));
        lastBurnt.values().removeIf(at -> now - at > stale);
    }

    /** How many items and creatures are kept in mind. */
    int remembered() {
        return inTheSeaSince.size() + lastBurnt.size();
    }

    private boolean inTheAcidSea(Location at) {
        if (!isWater(at.getBlock())) {
            return false;
        }
        return waters.acid(at);
    }

    private static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }
}
