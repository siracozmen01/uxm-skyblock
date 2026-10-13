package com.uxplima.uxmskyblock.bukkit.parkour;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Puts a ghost in the world as an item display only its runner sees, never saved with the world. It moves itself on
 * its own entity scheduler, each move drawn over a few ticks so the ghost glides.
 */
public final class ServerGhostStage implements ParkourGhosts.Stage {

    /** How many ticks a move is drawn over. */
    private static final int GLIDE_TICKS = 2;

    private final Plugin plugin;
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ServerGhostStage.class.getName());

    private final ParkourConfiguration.Ghost look;
    private volatile boolean warned;

    public ServerGhostStage(Plugin plugin, ParkourConfiguration.Ghost look) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.look = Objects.requireNonNull(look, "look must not be null");
    }

    @Override
    public Optional<ParkourGhosts.Shown> show(Player runner, Location at, PlayerUuid whose, Runnable tick) {
        if (at.getWorld() == null || !Bukkit.isOwnedByCurrentRegion(at)) {
            return Optional.empty();
        }
        ItemDisplay ghost = at.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            display.setItemStack(item(whose));
            display.setBillboard(Display.Billboard.FIXED);
            display.setGlowing(look.glow());
            display.setTeleportDuration(GLIDE_TICKS);
            display.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(), new Vector3f(look.scale()), new AxisAngle4f()));
        });
        runner.showEntity(plugin, ghost);
        ghost.getScheduler().runAtFixedRate(plugin, task -> tick.run(), null, 1, 1);
        return Optional.of(new ParkourGhosts.Shown() {
            @Override
            public void moveTo(Location to) {
                var unused = ghost.teleportAsync(to);
            }

            @Override
            public void remove() {
                ghost.getScheduler().run(plugin, task -> ghost.remove(), null);
            }
        });
    }

    private ItemStack item(PlayerUuid whose) {
        Material written = look.item();
        Material drawn = written;
        if (!written.isItem()) {
            if (!warned) {
                warned = true;
                LOGGER.warning(
                        () -> "modules/parkour.conf ghost item: " + written + " is no item. A player head is drawn.");
            }
            drawn = Material.PLAYER_HEAD;
        }
        ItemStack item = new ItemStack(drawn);
        if (drawn == Material.PLAYER_HEAD && item.getItemMeta() instanceof SkullMeta head) {
            head.setOwningPlayer(Bukkit.getOfflinePlayer(whose.value()));
            item.setItemMeta(head);
        }
        return item;
    }
}
