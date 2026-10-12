package com.uxplima.uxmskyblock.bukkit.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import org.jspecify.annotations.Nullable;

/**
 * Keeps what a player carries apart for each group of worlds, and swaps it when they cross from one group to
 * another.
 *
 * <p>Everything is written on the player, in their own saved data: the group whose things they carry now, and what
 * they put down in each group they left. A server that crashes rolls the player and what they put down back
 * together, so nothing is lost and nothing is had twice.
 *
 * <p>Every call is made on the player's own thread.
 */
public final class PlayerStates {

    /** The group whose things the player carries now. */
    static final NamespacedKey GROUP = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:state_group"));

    private static final Logger LOGGER = Logger.getLogger(PlayerStates.class.getName());
    private static final int VERSION = 1;
    private static final Set<UUID> SWAPPING = ConcurrentHashMap.newKeySet();

    /** A player's advancements, read and set as {@code advancement|criterion} lines, the recipe ones left out. */
    public interface Advancements {
        Set<String> earned(Player player);

        void earn(Player player, Set<String> criteria);
    }

    private final Supplier<CarriedStateConfiguration> config;
    private final Supplier<List<String>> presetWorlds;
    private final InventoryCodec codec;
    private final Advancements advancements;

    /** @param presetWorlds the worlds the presets make islands in, each a group when the file names none */
    public PlayerStates(
            Supplier<CarriedStateConfiguration> config,
            Supplier<List<String>> presetWorlds,
            InventoryCodec codec,
            Advancements advancements) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.presetWorlds = Objects.requireNonNull(presetWorlds, "presetWorlds must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.advancements = Objects.requireNonNull(advancements, "advancements must not be null");
    }

    /**
     * Whether the player's things are being swapped right now. An advancement awarded back in a swap is one they
     * had already, and nothing that rewards earning one should answer it.
     */
    public static boolean isSwapping(Player player) {
        return SWAPPING.contains(player.getUniqueId());
    }

    /** The group whose things the player carries, or nothing before they first stood in a world a group names. */
    public static Optional<String> carried(Player player) {
        return Optional.ofNullable(player.getPersistentDataContainer().get(GROUP, PersistentDataType.STRING));
    }

    /**
     * The player stands in a world they may not have carried their things to: a world they just changed to, or the
     * one they joined in. Swaps what they carry when the world is in another group. Returns whether it swapped.
     */
    public boolean arrived(Player player) {
        CarriedStateConfiguration settings = config.get();
        if (!settings.enabled()) {
            return false;
        }
        Optional<String> to = settings.groupOf(player.getWorld().getName(), presetWorlds.get());
        if (to.isEmpty()) {
            // A world no group names keeps what the player brought.
            return false;
        }
        PersistentDataContainer data = player.getPersistentDataContainer();
        String from = data.get(GROUP, PersistentDataType.STRING);
        if (from == null) {
            // What a player carries the first time is the group's they first stand in.
            data.set(GROUP, PersistentDataType.STRING, to.get());
            return false;
        }
        if (from.equals(to.get())) {
            return false;
        }
        swap(player, from, to.get(), settings.keeps());
        return true;
    }

    private void swap(Player player, String from, String to, CarriedStateConfiguration.Keeps keeps) {
        // A creative place's seal is lifted first, so what was made there never reaches another group.
        SealedInventory.lift(player, codec);
        player.closeInventory();
        SWAPPING.add(player.getUniqueId());
        try {
            PersistentDataContainer data = player.getPersistentDataContainer();
            byte[] incoming = data.get(slot(to), PersistentDataType.BYTE_ARRAY);
            Snapshot coming = incoming == null ? Snapshot.fresh() : Snapshot.read(incoming);
            // What the player puts down is written before anything they carry changes.
            Snapshot leaving = take(player);
            data.set(slot(from), PersistentDataType.BYTE_ARRAY, leaving.write());
            data.set(GROUP, PersistentDataType.STRING, to);
            // Advancements come back first, into empty hands. An advancement's own reward, experience or loot, is
            // paid each time it is given back, and everything set below overwrites what it paid, so crossing
            // between groups is no way to earn it again.
            player.getInventory().clear();
            if (keeps.advancements()) {
                advancements.earn(player, coming.advancements());
            }
            give(player, coming, leaving, keeps);
            data.remove(slot(to));
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.SEVERE,
                    e,
                    () -> "What " + player.getName() + " carries could not be swapped from " + from + " to " + to
                            + "; they keep what they have.");
        } finally {
            SWAPPING.remove(player.getUniqueId());
        }
    }

    /** Where the things a player put down in {@code group} are kept on them. */
    static NamespacedKey slot(String group) {
        String safe = group.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:state_of_" + safe));
    }

    /** Everything the player carries now. */
    private Snapshot take(Player player) {
        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(SealedInventory.effectLine(effect));
        }
        return new Snapshot(
                codec.write(player.getInventory().getContents()),
                codec.write(player.getEnderChest().getContents()),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                effects,
                advancements.earned(player));
    }

    /**
     * Hands the player what {@code coming} holds for each part the group keeps apart, and what they carried,
     * {@code leaving}, for every other part, so nothing paid in the swap stays.
     */
    private void give(Player player, Snapshot coming, Snapshot leaving, CarriedStateConfiguration.Keeps keeps) {
        Snapshot items = keeps.inventory() ? coming : leaving;
        player.getInventory().clear();
        player.getInventory()
                .setContents(contents(items.inventory(), player.getInventory().getSize()));
        Snapshot chest = keeps.enderChest() ? coming : leaving;
        player.getEnderChest().clear();
        player.getEnderChest()
                .setContents(contents(chest.enderChest(), player.getEnderChest().getSize()));
        Snapshot experience = keeps.experience() ? coming : leaving;
        player.setLevel(experience.level());
        player.setExp(experience.exp());
        player.setTotalExperience(experience.totalExperience());
        Snapshot body = keeps.health() ? coming : leaving;
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double most = max == null ? 20.0 : max.getValue();
        player.setHealth(body.health() <= 0 ? most : Math.min(body.health(), most));
        Snapshot stomach = keeps.hunger() ? coming : leaving;
        player.setFoodLevel(stomach.food());
        player.setSaturation(stomach.saturation());
        player.setExhaustion(stomach.exhaustion());
        Snapshot effects = keeps.effects() ? coming : leaving;
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (String written : effects.effects()) {
            PotionEffect effect = SealedInventory.effectWritten(written);
            if (effect != null) {
                player.addPotionEffect(effect);
            }
        }
    }

    /**
     * Whether the player stands in a world of another group than the one whose things they carry: arrived, and not
     * swapped yet. Nothing they carry may be put down there before the swap.
     */
    public boolean pending(Player player) {
        CarriedStateConfiguration settings = config.get();
        if (!settings.enabled()) {
            return false;
        }
        Optional<String> here = settings.groupOf(player.getWorld().getName(), presetWorlds.get());
        Optional<String> carried = carried(player);
        return here.isPresent() && carried.isPresent() && !here.get().equals(carried.get());
    }

    private ItemStack[] contents(byte @Nullable [] written, int size) {
        if (written == null || written.length == 0) {
            return new ItemStack[size];
        }
        return codec.read(written);
    }

    /** What a player carries, as it is put down in a group and picked up again. */
    static final class Snapshot {

        private final byte @Nullable [] inventory;
        private final byte @Nullable [] enderChest;
        private final int level;
        private final float exp;
        private final int totalExperience;
        private final double health;
        private final int food;
        private final float saturation;
        private final float exhaustion;
        private final List<String> effects;
        private final Set<String> advancements;

        Snapshot(
                byte @Nullable [] inventory,
                byte @Nullable [] enderChest,
                int level,
                float exp,
                int totalExperience,
                double health,
                int food,
                float saturation,
                float exhaustion,
                List<String> effects,
                Set<String> advancements) {
            this.inventory = inventory;
            this.enderChest = enderChest;
            this.level = level;
            this.exp = exp;
            this.totalExperience = totalExperience;
            this.health = health;
            this.food = food;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.effects = List.copyOf(effects);
            this.advancements = Set.copyOf(advancements);
        }

        byte @Nullable [] inventory() {
            return inventory;
        }

        byte @Nullable [] enderChest() {
            return enderChest;
        }

        int level() {
            return level;
        }

        float exp() {
            return exp;
        }

        int totalExperience() {
            return totalExperience;
        }

        double health() {
            return health;
        }

        int food() {
            return food;
        }

        float saturation() {
            return saturation;
        }

        float exhaustion() {
            return exhaustion;
        }

        List<String> effects() {
            return effects;
        }

        Set<String> advancements() {
            return advancements;
        }

        /** What a player carries in a group they never stood in: nothing, full health and a full stomach. */
        static Snapshot fresh() {
            return new Snapshot(null, null, 0, 0, 0, 0, 20, 5, 0, List.of(), Set.of());
        }

        byte[] write() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION);
                writeBytes(out, inventory);
                writeBytes(out, enderChest);
                out.writeInt(level);
                out.writeFloat(exp);
                out.writeInt(totalExperience);
                out.writeDouble(health);
                out.writeInt(food);
                out.writeFloat(saturation);
                out.writeFloat(exhaustion);
                writeLines(out, effects);
                writeLines(out, List.copyOf(advancements));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        }

        static Snapshot read(byte[] written) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(written))) {
                int version = in.readInt();
                if (version != VERSION) {
                    throw new IllegalStateException("a kept state of version " + version + " is not one this reads");
                }
                return new Snapshot(
                        readBytes(in),
                        readBytes(in),
                        in.readInt(),
                        in.readFloat(),
                        in.readInt(),
                        in.readDouble(),
                        in.readInt(),
                        in.readFloat(),
                        in.readFloat(),
                        readLines(in),
                        new LinkedHashSet<>(readLines(in)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void writeBytes(DataOutputStream out, byte @Nullable [] value) throws IOException {
            if (value == null) {
                out.writeInt(-1);
                return;
            }
            out.writeInt(value.length);
            out.write(value);
        }

        private static byte @Nullable [] readBytes(DataInputStream in) throws IOException {
            int length = in.readInt();
            if (length < 0) {
                return null;
            }
            byte[] value = new byte[length];
            in.readFully(value);
            return value;
        }

        private static void writeLines(DataOutputStream out, List<String> lines) throws IOException {
            out.writeInt(lines.size());
            for (String line : lines) {
                out.writeUTF(line);
            }
        }

        private static List<String> readLines(DataInputStream in) throws IOException {
            int count = in.readInt();
            List<String> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(in.readUTF());
            }
            return lines;
        }
    }
}
