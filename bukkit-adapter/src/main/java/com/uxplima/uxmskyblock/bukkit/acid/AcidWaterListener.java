package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * A bottle of water drunk on an AcidIsland island burns unless it was made clean, and a bottle filled
 * from a cauldron there comes out clean, because a cauldron holds the rain. A cauldron a bucket of sea
 * water was poured into holds the sea instead, until it is emptied.
 *
 * <p>Both handlers run on the thread that owns the player and the block, so nothing here is scheduled.
 */
public final class AcidWaterListener implements Listener {

    private final AcidHazard hazard;
    private final Messages messages;

    public AcidWaterListener(AcidHazard hazard, Messages messages) {
        this.hazard = Objects.requireNonNull(hazard, "hazard must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (AcidWater.isClean(event.getItem()) && hazard.onAcidIsland(player.getLocation())) {
            double healed = hazard.drankClean(player);
            if (healed > 0) {
                messages.send(
                        player,
                        "acid.drank_clean",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                                "healed", AcidHazard.healedText(healed)));
            }
            return;
        }
        if (!AcidWater.isAcid(event.getItem()) || !hazard.onAcidIsland(player.getLocation())) {
            return;
        }
        hazard.drank(player);
        messages.send(player, "acid.drank");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        Block cauldron = event.getBlock();
        if (!hazard.onAcidIsland(cauldron.getLocation())) {
            return;
        }
        CauldronLevelChangeEvent.ChangeReason reason = event.getReason();
        if (reason == CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY
                && event.getEntity() instanceof Player player) {
            // What is poured in decides what the cauldron holds: the sea, unless the bucket was made clean.
            PlayerInventory inventory = player.getInventory();
            ItemStack bucket = inventory.getItemInMainHand().getType() == Material.WATER_BUCKET
                    ? inventory.getItemInMainHand()
                    : inventory.getItemInOffHand();
            AcidWater.markCauldron(cauldron, !AcidWater.isCleanBucket(bucket));
            return;
        }
        if (reason == CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL
                && event.getEntity() instanceof Player player
                && !AcidWater.isAcidCauldron(cauldron)
                && fillClean(event, player)) {
            return;
        }
        if (event.getNewState().getType() == Material.CAULDRON) {
            // Emptied: whatever it held is gone, and the rain that fills it next is clean.
            AcidWater.markCauldron(cauldron, false);
        }
    }

    /** Fills the bottle in the server's place, with a clean one. False when there is no bottle in hand. */
    private boolean fillClean(CauldronLevelChangeEvent event, Player player) {
        PlayerInventory inventory = player.getInventory();
        EquipmentSlot hand = inventory.getItemInMainHand().getType() == Material.GLASS_BOTTLE
                ? EquipmentSlot.HAND
                : inventory.getItemInOffHand().getType() == Material.GLASS_BOTTLE ? EquipmentSlot.OFF_HAND : null;
        if (hand == null) {
            return false;
        }
        // The server would hand over a plain bottle, which is acid here. The same fill is done by hand
        // instead, with a clean bottle.
        event.setCancelled(true);
        event.getNewState().update(true, false);
        ItemStack bottles = inventory.getItem(hand);
        if (bottles.getAmount() <= 1) {
            inventory.setItem(hand, AcidWater.cleanBottle());
        } else {
            bottles.setAmount(bottles.getAmount() - 1);
            inventory.setItem(hand, bottles);
            for (ItemStack left : inventory.addItem(AcidWater.cleanBottle()).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
        }
        hazard.filledClean(player);
        return true;
    }
}
