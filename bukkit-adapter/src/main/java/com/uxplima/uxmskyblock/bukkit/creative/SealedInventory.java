package com.uxplima.uxmskyblock.bukkit.creative;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import org.jspecify.annotations.Nullable;

/**
 * What a player brings to a creative place, kept aside while they are there and given back when they
 * leave: their items, their experience, their effects and their game mode.
 *
 * <p>What is kept is what the server writes as the player's own. Every snapshot the session takes of a
 * sealed player, the checkpoint, the last write at a quit or a stop, a profile switch and a crossing between
 * groups of worlds, holds what the seal keeps and never what was made in the place, so nothing made in
 * creative reaches the database. A server that stops while a player is sealed gives them back what they
 * brought from there when they come back, and the place seals them again if they stand in it. The seal
 * itself lives for the session only, and a player is sealed only while their session is in play: at the
 * join the session has not yet put their own state on them.
 *
 * <p>A seal says which place put it on, and only that place takes it off: a player on a Parkour course is
 * not given back what the course keeps because the Brix beat saw them off every plot.
 *
 * <p>Every call is made on the player's own thread.
 */
public final class SealedInventory {

    private static final Logger LOGGER = Logger.getLogger(SealedInventory.class.getName());
    private static final Map<UUID, Kept> KEPT = new ConcurrentHashMap<>();

    /**
     * What a seal keeps of a player: the items as the seal writes them and as they were, the game mode, the
     * level and bar, the effects and whether they could fly.
     */
    @SuppressWarnings("ArrayRecordComponent")
    public record Kept(
            String owner,
            byte[] written,
            ItemStack[] items,
            GameMode mode,
            int level,
            float exp,
            List<PotionEffect> effects,
            boolean flight) {

        public Kept {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(mode, "mode");
            written = written.clone();
            items = copy(items);
            effects = List.copyOf(effects);
        }

        @Override
        public byte[] written() {
            return written.clone();
        }

        @Override
        public ItemStack[] items() {
            return copy(items);
        }

        private static ItemStack[] copy(@Nullable ItemStack[] items) {
            ItemStack[] copy = new ItemStack[items.length];
            for (int slot = 0; slot < items.length; slot++) {
                ItemStack item = items[slot];
                copy[slot] = item == null ? null : item.clone();
            }
            return copy;
        }
    }

    private final InventoryCodec codec;
    private final String owner;
    private final Predicate<Player> inPlay;

    /** @param owner the place this seal belongs to, which alone takes it off */
    public SealedInventory(InventoryCodec codec, String owner) {
        this(codec, owner, player -> true);
    }

    /**
     * @param owner the place this seal belongs to, which alone takes it off
     * @param inPlay whether the player's session holds their own state, the only time they may be sealed
     */
    public SealedInventory(InventoryCodec codec, String owner, Predicate<Player> inPlay) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.inPlay = Objects.requireNonNull(inPlay, "inPlay must not be null");
    }

    /**
     * Gives the player back what any place kept aside, whichever place it was. Used where the player leaves every
     * place at once, such as for another world, before anything they hold now is taken anywhere.
     */
    public static boolean lift(Player player, InventoryCodec codec) {
        Kept kept = KEPT.get(player.getUniqueId());
        return kept != null && new SealedInventory(codec, kept.owner()).unseal(player);
    }

    /** What the seal on the player keeps, or nothing when they are not sealed. */
    public static @Nullable Kept keptFor(Player player) {
        return KEPT.get(player.getUniqueId());
    }

    /**
     * Lets go of a seal from before the player's session put their own state on them: what it kept is what the
     * session wrote and has just given back.
     */
    public static void forget(Player player) {
        KEPT.remove(player.getUniqueId());
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
        return KEPT.containsKey(player.getUniqueId());
    }

    /**
     * Keeps the player's items, experience, effects and mode aside and leaves them empty handed. A player
     * already sealed keeps what was kept first, and a player whose session does not hold their state yet is
     * not sealed. Returns whether anything was kept now.
     */
    public boolean seal(Player player) {
        if (isSealed(player) || !inPlay.test(player)) {
            return false;
        }
        // A window left open would keep taking items across the seal: a vault page opened off the plot.
        player.closeInventory();
        ItemStack[] items = player.getInventory().getContents();
        KEPT.put(
                player.getUniqueId(),
                new Kept(
                        owner,
                        codec.write(items),
                        items,
                        player.getGameMode(),
                        player.getLevel(),
                        player.getExp(),
                        List.copyOf(player.getActivePotionEffects()),
                        player.getAllowFlight()));
        player.getInventory().clear();
        return true;
    }

    /**
     * Gives the player back what this place kept aside and drops whatever they held since. A seal another
     * place put on is left alone. Returns whether anything was given back.
     */
    public boolean unseal(Player player) {
        Kept kept = KEPT.get(player.getUniqueId());
        if (kept == null || !owner.equals(kept.owner())) {
            return false;
        }
        // A chest on the plot left open would hand its items to the inventory given back.
        player.closeInventory();
        // The mode first, so the items kept do not arrive in a creative inventory.
        player.setGameMode(kept.mode());
        player.getInventory().clear();
        try {
            player.getInventory().setContents(codec.read(kept.written()));
        } catch (RuntimeException e) {
            // Left kept: an item this server cannot read is not thrown away.
            LOGGER.log(Level.SEVERE, e, () -> "The items kept for " + player.getName() + " could not be read.");
            return false;
        }
        player.setLevel(kept.level());
        player.setExp(kept.exp());
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : kept.effects()) {
            player.addPotionEffect(effect);
        }
        KEPT.remove(player.getUniqueId());
        return true;
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
