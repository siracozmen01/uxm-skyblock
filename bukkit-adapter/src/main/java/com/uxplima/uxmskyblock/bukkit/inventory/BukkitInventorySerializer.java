package com.uxplima.uxmskyblock.bukkit.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import org.jspecify.annotations.Nullable;

/**
 * Utility for serializing and deserializing Bukkit player inventory, equipment, ender chest,
 * potion effects, and player stats to/from {@link ProfileInventoryRecord}.
 */
@SuppressWarnings("deprecation")
public final class BukkitInventorySerializer {

    private BukkitInventorySerializer() {}

    /**
     * Serializes an array of ItemStacks to binary payload.
     */
    public static byte[] serializeItemStacks(ItemStack @Nullable [] items) {
        if (items == null || items.length == 0) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.length);
            for (ItemStack item : items) {
                boos.writeObject(item);
            }
            boos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize ItemStack array", e);
        }
    }

    /**
     * Deserializes binary payload into an array of ItemStacks.
     */
    public static ItemStack[] deserializeItemStacks(byte @Nullable [] data) {
        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int length = bois.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) bois.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize ItemStack array", e);
        }
    }

    /**
     * Serializes a collection of active potion effects to binary payload.
     */
    public static byte[] serializePotionEffects(Collection<PotionEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(effects.size());
            for (PotionEffect effect : effects) {
                boos.writeObject(effect);
            }
            boos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize potion effects", e);
        }
    }

    /**
     * Deserializes binary payload into a collection of potion effects.
     */
    @SuppressWarnings("unchecked")
    public static Collection<PotionEffect> deserializePotionEffects(byte @Nullable [] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int count = bois.readInt();
            List<PotionEffect> effects = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                effects.add((PotionEffect) bois.readObject());
            }
            return effects;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize potion effects", e);
        }
    }

    /**
     * Snapshots the live player's inventory, ender chest, and stats into a {@link ProfileInventoryRecord}.
     *
     * <p>A player sealed in a creative place is written as the seal keeps them: what they brought, their level,
     * effects, game mode and flight. What they hold in the place is never written, so a crash there gives them
     * back what they brought rather than what they made.
     *
     * <p>Must be invoked on the player's owning entity thread.
     */
    public static ProfileInventoryRecord snapshotPlayer(Player player, ProfileId profileId, long version) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profileId, "profileId");

        com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.Kept kept =
                com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.keptFor(player);
        byte[] invBytes = serializeItemStacks(
                kept != null ? kept.items() : player.getInventory().getContents());
        byte[] ecBytes = serializeItemStacks(player.getEnderChest().getContents());
        byte[] potionBytes = serializePotionEffects(kept != null ? kept.effects() : player.getActivePotionEffects());

        Location loc = player.getLocation();
        String worldName =
                (loc != null && loc.getWorld() != null) ? loc.getWorld().getName() : null;
        double locX = loc != null ? loc.getX() : 0.0;
        double locY = loc != null ? loc.getY() : 0.0;
        double locZ = loc != null ? loc.getZ() : 0.0;

        return new ProfileInventoryRecord(
                profileId,
                version,
                invBytes,
                ecBytes,
                kept != null ? experienceOf(kept.level(), kept.exp()) : experienceOf(player),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                potionBytes,
                worldName,
                locX,
                locY,
                locZ,
                (kept != null ? kept.mode() : player.getGameMode()).name(),
                kept != null ? kept.flight() : player.getAllowFlight());
    }

    /**
     * The experience points {@code player} holds, worked out from their level and the bar.
     *
     * <p>{@link Player#getTotalExperience()} is only the points picked up since the last death, and
     * levels given by a command never reach it: a player given thirty levels read as none, and came
     * back to none. The level and the bar are what the player sees, so the points are counted from
     * them, with the thresholds the game uses.
     */
    static int experienceOf(Player player) {
        return experienceOf(player.getLevel(), player.getExp());
    }

    /** The experience points a {@code level} and a bar at {@code exp} make. */
    static int experienceOf(int level, float exp) {
        int whole = Math.max(0, level);
        return pointsToReach(whole) + Math.round(exp * pointsInLevel(whole));
    }

    /**
     * Sets the level and the bar that {@code points} make, with the thresholds {@link #experienceOf}
     * counts by.
     *
     * <p>Giving the points one by one through the game rolls the bar over in floating point, and at an
     * exact threshold it can stop a hair short: twelve levels came back as eleven and a full bar.
     */
    static void applyExperience(Player player, int points) {
        int remaining = Math.max(0, points);
        int level = 0;
        while (pointsToReach(level + 1) <= remaining) {
            level++;
        }
        player.setLevel(level);
        player.setExp((float) (remaining - pointsToReach(level)) / pointsInLevel(level));
        player.setTotalExperience(remaining);
    }

    /** The points it takes to reach {@code level} from nothing. */
    static int pointsToReach(int level) {
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (5 * level * level - 81 * level + 720) / 2;
        }
        return (9 * level * level - 325 * level + 4440) / 2;
    }

    /** The points between {@code level} and the next. */
    private static int pointsInLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    /**
     * Applies the given {@link ProfileInventoryRecord} onto the live player.
     *
     * <p>Must be invoked on the player's owning entity thread.
     */
    public static void applyToPlayer(Player player, ProfileInventoryRecord record) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(record, "record");

        // Inventory
        ItemStack[] items = deserializeItemStacks(record.inventoryNbt());
        if (items.length > 0) {
            player.getInventory().setContents(items);
        } else {
            player.getInventory().clear();
        }

        // Ender Chest
        ItemStack[] ecItems = deserializeItemStacks(record.enderchestNbt());
        if (ecItems.length > 0) {
            player.getEnderChest().setContents(ecItems);
        } else {
            player.getEnderChest().clear();
        }

        // Experience
        applyExperience(player, record.experiencePoints());

        // Health
        double maxHealth = 20.0;
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealth = maxHealthAttr.getValue();
        }
        player.setHealth(Math.max(1.0, Math.min(record.health(), maxHealth)));

        // Food & Saturation
        player.setFoodLevel(record.foodLevel());
        player.setSaturation(record.saturation());

        // Potion Effects
        for (PotionEffect current : player.getActivePotionEffects()) {
            player.removePotionEffect(current.getType());
        }
        for (PotionEffect effect : deserializePotionEffects(record.activePotionEffectsNbt())) {
            player.addPotionEffect(effect);
        }

        // GameMode
        try {
            player.setGameMode(GameMode.valueOf(record.gamemode()));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Keep existing gamemode
        }

        // Flight
        player.setAllowFlight(record.flightAllowed());
    }

    /**
     * Resolves the saved logout location if available and valid in the given server.
     */
    public static Optional<Location> resolveLogoutLocation(ProfileInventoryRecord record, Server server) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(server, "server");

        if (record.logoutWorld() == null
                || record.logoutX() == null
                || record.logoutY() == null
                || record.logoutZ() == null) {
            return Optional.empty();
        }

        World world = server.getWorld(record.logoutWorld());
        if (world == null) {
            return Optional.empty();
        }

        return Optional.of(new Location(world, record.logoutX(), record.logoutY(), record.logoutZ()));
    }
}
