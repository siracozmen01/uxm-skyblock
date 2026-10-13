package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.effect.PotionEffectLines;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidExposure;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * The acid sea and the acid rain: each player standing on an AcidIsland island is checked on a fixed
 * beat, and whatever reaches them hurts.
 *
 * <p>The beat runs on the global thread and only hands each player to their own thread, where the
 * check reads the blocks around them. On Folia a player's blocks belong to the player's region, so
 * nothing about the player is read anywhere else. Every answer about the island comes from memory.
 */
public final class AcidHazard {

    private static final Logger LOGGER = Logger.getLogger(AcidHazard.class.getName());

    private final AcidIslandService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final AcidRules rules;
    private final List<PotionEffectType> protection;
    private final List<PotionEffect> seaEffects;
    private final List<PotionEffect> ventFumes;
    private final AcidIslandConfiguration.Sea sea;
    private final AcidIslandConfiguration.Armour armour;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private @Nullable AcidSeaLife seaLife;

    public AcidHazard(
            AcidIslandService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            AcidIslandConfiguration config,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.rules = config.rules();
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.protection = new ArrayList<>();
        for (String name : config.waterProtection()) {
            PotionEffectType type = effectNamed(name);
            if (type == null) {
                LOGGER.warning(() -> "modules/acidisland.conf water-protection: no effect is named " + name + ".");
            } else {
                protection.add(type);
            }
        }
        this.seaEffects = effectsWritten("hazard water-effects", config.waterEffects());
        this.ventFumes =
                effectsWritten("sea vents effects", config.sea().vents().effects());
        this.sea = config.sea();
        this.armour = config.armour();
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, rules.checkEvery(), rules.checkEvery());
    }

    /** Has each check also look at the items and creatures in the sea around the player. */
    public void alsoLookAt(AcidSeaLife life) {
        this.seaLife = Objects.requireNonNull(life, "life must not be null");
    }

    private void round() {
        AcidSeaLife life = seaLife;
        if (life != null) {
            life.forgetTheGone();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> {
                check(player);
                if (life != null) {
                    life.around(player);
                }
            });
        }
    }

    /** One check of one player, on the player's own thread. Returns the health it took. */
    public double check(Player player) {
        if (!player.isValid() || player.isDead()) {
            return 0;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return 0;
        }
        Location feet = player.getLocation();
        if (feet == null) {
            return 0;
        }
        Optional<Island> island = islands.findIslandAt(feet);
        if (island.isEmpty() || !service.isAcid(island.get().id())) {
            return 0;
        }
        breatheVents(player, feet, island.get());
        AcidExposure bare = exposureOf(player, feet);
        AcidExposure exposure = armoured(player, bare);
        if (exposure != bare && rules.damage(exposure) < rules.damage(bare)) {
            wear(player);
        }
        double taken = rules.damage(exposure);
        if (taken <= 0) {
            return 0;
        }
        player.damage(taken);
        if (rules.seaHurts(exposure)) {
            for (PotionEffect effect : seaEffects) {
                player.addPotionEffect(effect);
            }
            effectPlayer.fire(effects, "acid-sea-burns", player);
        } else {
            effectPlayer.fire(effects, "acid-rain-burns", player);
        }
        return taken;
    }

    /**
     * Gives the fumes of a sulfur vent to a player near one: in the sea or just above it, within the
     * vent's reach sideways. The blocks read are the player's neighbours, which their region owns.
     */
    private void breatheVents(Player player, Location feet, Island island) {
        int reach = sea.vents().reach();
        if (ventFumes.isEmpty() || sea.vents().chance() <= 0) {
            return;
        }
        OptionalInt surface = service.seaLevel(island.id());
        if (surface.isEmpty() || feet.getBlockY() > surface.getAsInt() + 1) {
            return;
        }
        int floor = surface.getAsInt() - sea.depth();
        World world = feet.getWorld();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (world.getBlockAt(feet.getBlockX() + dx, floor, feet.getBlockZ() + dz)
                                .getType()
                        == AcidSeaStart.VENT) {
                    for (PotionEffect effect : ventFumes) {
                        player.addPotionEffect(effect);
                    }
                    effectPlayer.fire(effects, "acid-vent-fumes", player);
                    return;
                }
            }
        }
    }

    /** Whether the spot is on an AcidIsland island. Memory only. */
    public boolean onAcidIsland(@Nullable Location at) {
        if (at == null) {
            return false;
        }
        Optional<Island> island = islands.findIslandAt(at);
        return island.isPresent() && service.isAcid(island.get().id());
    }

    /**
     * A player drank a bottle of acid water: it burns as the sea does and gives the sea's effects. No
     * effect keeps it off, because it is inside them. Returns the health it took.
     */
    public double drank(Player player) {
        double taken = rules.waterDamage();
        if (taken > 0) {
            player.damage(taken);
        }
        for (PotionEffect effect : seaEffects) {
            player.addPotionEffect(effect);
        }
        effectPlayer.fire(effects, "acid-water-drunk", player);
        return taken;
    }

    /** A player filled a clean bottle from a cauldron, which the plugin did in the server's place. */
    public void filledClean(Player player) {
        effectPlayer.fire(effects, "acid-clean-water-filled", player);
    }

    /** What reaches the player once a full set of the armour named keeps off what it may. */
    private AcidExposure armoured(Player player, AcidExposure bare) {
        if (!wearsAFullSet(player)) {
            return bare;
        }
        return new AcidExposure(
                bare.inWater(),
                bare.rainedOn() && !armour.rain(),
                bare.helmet(),
                bare.waterProtected() || armour.sea());
    }

    private boolean wearsAFullSet(Player player) {
        PlayerInventory worn = player.getInventory();
        String set = setOf(worn.getHelmet(), "_helmet");
        return set != null
                && armour.sets().contains(set)
                && set.equals(setOf(worn.getChestplate(), "_chestplate"))
                && set.equals(setOf(worn.getLeggings(), "_leggings"))
                && set.equals(setOf(worn.getBoots(), "_boots"));
    }

    /** {@code netherite} for a netherite helmet, or nothing for an empty slot or another piece. */
    private static @Nullable String setOf(@Nullable ItemStack piece, String suffix) {
        if (piece == null || piece.isEmpty()) {
            return null;
        }
        String name = piece.getType().getKey().getKey();
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : null;
    }

    /** Takes the wear off each piece of the set that kept the acid off; a piece worn through breaks. */
    private void wear(Player player) {
        if (armour.wear() <= 0) {
            // Nothing to take, and no reason to write the player's armour back each check.
            return;
        }
        PlayerInventory worn = player.getInventory();
        worn.setHelmet(worn(worn.getHelmet()));
        worn.setChestplate(worn(worn.getChestplate()));
        worn.setLeggings(worn(worn.getLeggings()));
        worn.setBoots(worn(worn.getBoots()));
    }

    private @Nullable ItemStack worn(@Nullable ItemStack piece) {
        if (piece == null
                || piece.getType().getMaxDurability() <= 0
                || !(piece.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta)
                || meta.isUnbreakable()) {
            return piece;
        }
        int damage = meta.getDamage() + armour.wear();
        if (damage >= piece.getType().getMaxDurability()) {
            return null;
        }
        meta.setDamage(damage);
        piece.setItemMeta(meta);
        return piece;
    }

    private AcidExposure exposureOf(Player player, Location feet) {
        Block feetBlock = feet.getBlock();
        Block eyeBlock = player.getEyeLocation().getBlock();
        boolean inWater = isWater(feetBlock) || isWater(eyeBlock);
        World world = player.getWorld();
        boolean rainedOn = world.hasStorm() && world.getHighestBlockYAt(feet) < eyeBlock.getY();
        ItemStack helmet = player.getInventory().getHelmet();
        boolean wearsHelmet = helmet != null && !helmet.isEmpty();
        boolean safe = false;
        for (PotionEffectType type : protection) {
            if (player.hasPotionEffect(type)) {
                safe = true;
                break;
            }
        }
        return new AcidExposure(inWater, rainedOn, wearsHelmet, safe);
    }

    private static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private static @Nullable PotionEffectType effectNamed(String name) {
        return PotionEffectLines.named(name);
    }

    private static List<PotionEffect> effectsWritten(String where, List<String> lines) {
        return PotionEffectLines.allWritten("modules/acidisland.conf " + where, lines);
    }

    /** An effect written {@code name:amplifier:seconds}, or null when it cannot be read. */
    static @Nullable PotionEffect effectWritten(String written) {
        return PotionEffectLines.written(written);
    }
}
