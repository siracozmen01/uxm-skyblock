package com.uxplima.uxmskyblock.bukkit.creative;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * What a player brings to a creative place, kept aside while they are there and given back when they
 * leave: their items, their experience, their effects and their game mode.
 *
 * <p>Everything kept is written on the player, so it is saved with them. A server that stops while a
 * player is sealed still has it when they come back, and they get it back the moment they are
 * somewhere else. Whatever they held in the place is gone when they leave it: nothing made in creative
 * reaches the rest of the server.
 *
 * <p>A seal says which place put it on, and only that place takes it off: a player on a Parkour course is
 * not given back what the course keeps because the Brix beat saw them off every plot.
 *
 * <p>Every call is made on the player's own thread.
 */
public final class SealedInventory {

    static final NamespacedKey ITEMS = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:sealed_items"));
    static final NamespacedKey MODE = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:sealed_mode"));
    static final NamespacedKey LEVEL = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:sealed_level"));
    static final NamespacedKey EXP = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:sealed_exp"));
    static final NamespacedKey OWNER = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:sealed_by"));
    static final NamespacedKey EFFECTS = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:sealed_effects"));

    private static final Logger LOGGER = Logger.getLogger(SealedInventory.class.getName());

    private final InventoryCodec codec;
    private final String owner;

    /** @param owner the place this seal belongs to, which alone takes it off */
    public SealedInventory(InventoryCodec codec, String owner) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
    }

    /**
     * Gives the player back what any place kept aside, whichever place it was. Used where the player leaves every
     * place at once, such as for another world, before anything they hold now is taken anywhere.
     */
    public static boolean lift(Player player, InventoryCodec codec) {
        String by = player.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        return by != null && new SealedInventory(codec, by).unseal(player);
    }

    /** Whether the player has something kept aside. */
    public boolean isSealed(Player player) {
        return holds(player);
    }

    /**
     * Whether the player has something kept aside, so that what they hold now was made in a creative
     * place and must not be sold, stored or carried out of it.
     */
    public static boolean holds(Player player) {
        return player.getPersistentDataContainer().has(ITEMS, PersistentDataType.BYTE_ARRAY);
    }

    /**
     * Keeps the player's items, experience, effects and mode aside and leaves them empty handed. A player
     * already sealed keeps what was kept first. Returns whether anything was kept now.
     */
    public boolean seal(Player player) {
        if (isSealed(player)) {
            return false;
        }
        // A window left open would keep taking items across the seal: a vault page opened off the plot.
        player.closeInventory();
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(OWNER, PersistentDataType.STRING, owner);
        data.set(MODE, PersistentDataType.STRING, player.getGameMode().name());
        data.set(LEVEL, PersistentDataType.INTEGER, player.getLevel());
        data.set(EXP, PersistentDataType.FLOAT, player.getExp());
        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(effectLine(effect));
        }
        data.set(EFFECTS, PersistentDataType.LIST.strings(), effects);
        // The items last: their mark is what says the player is sealed.
        data.set(
                ITEMS,
                PersistentDataType.BYTE_ARRAY,
                codec.write(player.getInventory().getContents()));
        player.getInventory().clear();
        return true;
    }

    /**
     * Gives the player back what this place kept aside and drops whatever they held since. A seal another
     * place put on is left alone. Returns whether anything was given back.
     */
    public boolean unseal(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        byte[] items = data.get(ITEMS, PersistentDataType.BYTE_ARRAY);
        if (items == null || !owner.equals(data.get(OWNER, PersistentDataType.STRING))) {
            return false;
        }
        // A chest on the plot left open would hand its items to the inventory given back.
        player.closeInventory();
        // The mode first, so the items kept do not arrive in a creative inventory.
        String mode = data.get(MODE, PersistentDataType.STRING);
        player.setGameMode(modeNamed(mode));
        player.getInventory().clear();
        try {
            player.getInventory().setContents(codec.read(items));
        } catch (RuntimeException e) {
            // Left on the player: an item this server cannot read is not thrown away.
            LOGGER.log(Level.SEVERE, e, () -> "The items kept for " + player.getName() + " could not be read.");
            return false;
        }
        Integer level = data.get(LEVEL, PersistentDataType.INTEGER);
        Float exp = data.get(EXP, PersistentDataType.FLOAT);
        player.setLevel(level == null ? 0 : level);
        player.setExp(exp == null ? 0 : exp);
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (String written : data.getOrDefault(EFFECTS, PersistentDataType.LIST.strings(), List.of())) {
            PotionEffect effect = effectWritten(written);
            if (effect != null) {
                player.addPotionEffect(effect);
            }
        }
        data.remove(ITEMS);
        data.remove(OWNER);
        data.remove(MODE);
        data.remove(LEVEL);
        data.remove(EXP);
        data.remove(EFFECTS);
        return true;
    }

    private static GameMode modeNamed(@org.jspecify.annotations.Nullable String written) {
        if (written != null) {
            try {
                return GameMode.valueOf(written);
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "A kept game mode reads " + written + ", which is none. Survival is given back.");
            }
        }
        return GameMode.SURVIVAL;
    }

    /** An effect as a seal writes it: its type, amplifier, duration, and whether it is ambient, shows particles and an icon. */
    public static String effectLine(PotionEffect effect) {
        return effect.getType().getKey().asString() + " " + effect.getAmplifier() + " " + effect.getDuration() + " "
                + effect.isAmbient() + " " + effect.hasParticles() + " " + effect.hasIcon();
    }

    /** The effect a line {@link #effectLine} wrote, or nothing for a line it cannot read. */
    public static @org.jspecify.annotations.Nullable PotionEffect effectWritten(String written) {
        String[] parts = written.split(" ", -1);
        if (parts.length != 6) {
            return null;
        }
        PotionEffectType type = com.uxplima.uxmskyblock.bukkit.effect.PotionEffectLines.named(parts[0]);
        if (type == null) {
            return null;
        }
        try {
            return new PotionEffect(
                    type,
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[1]),
                    Boolean.parseBoolean(parts[3]),
                    Boolean.parseBoolean(parts[4]),
                    Boolean.parseBoolean(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
