package com.uxplima.uxmskyblock.bukkit.warp;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.warp.DuplicateWarpNameException;
import com.uxplima.uxmskyblock.core.domain.warp.InvalidWarpNameException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import com.uxplima.uxmskyblock.core.domain.warp.WarpOutsideIslandException;

/**
 * Makes a warp of a sign: a member who writes the header on its first line and a name on its second makes a warp of
 * that name where the sign stands, as {@code /is setwarp} would, with the same permission and the same limit. The
 * sign then reads as a warp sign and carries the warp's name, and breaking it deletes the warp.
 */
public final class SignWarps implements Listener {

    private static final Logger LOGGER = Logger.getLogger(SignWarps.class.getName());

    /** The mark on a warp sign, naming its warp. */
    static final NamespacedKey MARK = new NamespacedKey("uxmskyblock", "sign-warp");

    private final Supplier<SignWarpsConfiguration> configuration;
    private final IslandProtectionListener islands;
    private final IslandWarpService warps;
    private final SchedulerPort scheduler;
    private final Messages messages;

    public SignWarps(
            Supplier<SignWarpsConfiguration> configuration,
            IslandProtectionListener islands,
            IslandWarpService warps,
            SchedulerPort scheduler,
            Messages messages) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.warps = Objects.requireNonNull(warps, "warps must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWrite(SignChangeEvent event) {
        if (!configuration.get().marks(plain(event.line(0)))) {
            return;
        }
        Player player = event.getPlayer();
        String name = plain(event.line(1)).strip();
        if (name.isEmpty()) {
            messages.send(player, "warp.sign.needs_name");
            return;
        }
        Block block = event.getBlock();
        Optional<Island> island = islands.findIslandAt(block.getLocation());
        Optional<ProfileId> profile = islands.activeProfile(new PlayerUuid(player.getUniqueId()));
        if (island.isEmpty()) {
            messages.send(player, "warp.outside_island");
            return;
        }
        if (profile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        Location standing = player.getLocation();
        float yaw = standing == null ? 0f : standing.getYaw();
        WarpLocation where = new WarpLocation(
                block.getWorld().getName(), block.getX() + 0.5, block.getY(), block.getZ() + 0.5, yaw, 0f);
        event.line(0, messages.render(player, "warp.sign.header"));
        event.line(1, Component.text(name));
        org.bukkit.block.sign.Side side = event.getSide();
        scheduler.async(() -> make(player, block, side, island.get(), profile.get(), name, where));
    }

    private void make(
            Player player,
            Block block,
            org.bukkit.block.sign.Side side,
            Island island,
            ProfileId profile,
            String name,
            WarpLocation where) {
        String refusal;
        try {
            warps.createWarp(
                    island,
                    profile,
                    WarpName.of(name),
                    where,
                    WarpCategory.GENERAL,
                    WarpCategory.GENERAL.defaultIconMaterial());
            scheduler.onRegion(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4, () -> {
                if (block.getState() instanceof Sign sign) {
                    sign.getPersistentDataContainer().set(MARK, PersistentDataType.STRING, name);
                    sign.update(true, false);
                }
            });
            tell(player, "warp.sign.created", name);
            return;
        } catch (SecurityException denied) {
            refusal = "warp.no_permission";
        } catch (InvalidWarpNameException bad) {
            refusal = "warp.bad_name";
        } catch (WarpOutsideIslandException outside) {
            refusal = "warp.outside_island";
        } catch (DuplicateWarpNameException taken) {
            refusal = "warp.name_taken";
        } catch (WarpLimitExceededException full) {
            refusal = "warp.limit_reached";
            String said = refusal;
            scheduler.onEntity(
                    new PlayerUuid(player.getUniqueId()),
                    () -> messages.send(
                            player, said, Placeholder.unparsed("max", Integer.toString(full.maxAllowed()))));
            unmark(block, side);
            return;
        } catch (RuntimeException failed) {
            LOGGER.log(Level.WARNING, "A sign warp could not be saved", failed);
            refusal = "warp.failed";
        }
        tell(player, refusal, name);
        unmark(block, side);
    }

    /** A sign whose warp was refused reads as refused, so it is not taken for a warp. */
    private void unmark(Block block, org.bukkit.block.sign.Side side) {
        scheduler.onRegion(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4, () -> {
            if (block.getState() instanceof Sign sign) {
                sign.getSide(side)
                        .line(0, messages.render(net.kyori.adventure.audience.Audience.empty(), "warp.sign.refused"));
                sign.update(true, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign sign)) {
            return;
        }
        String name = sign.getPersistentDataContainer().get(MARK, PersistentDataType.STRING);
        if (name == null) {
            return;
        }
        Player player = event.getPlayer();
        Optional<Island> island = islands.findIslandAt(event.getBlock().getLocation());
        Optional<ProfileId> profile = islands.activeProfile(new PlayerUuid(player.getUniqueId()));
        if (island.isEmpty() || profile.isEmpty()) {
            return;
        }
        scheduler.async(() -> {
            try {
                warps.deleteWarp(island.get(), profile.get(), WarpName.of(name));
                tell(player, "warp.deleted", name);
            } catch (SecurityException denied) {
                tell(player, "warp.no_permission", name);
            } catch (RuntimeException gone) {
                // The warp was deleted another way first; the sign goes all the same.
            }
        });
    }

    private void tell(Player player, String key, String name) {
        scheduler.onEntity(
                new PlayerUuid(player.getUniqueId()),
                () -> messages.send(player, key, Placeholder.unparsed("name", name)));
    }

    private static String plain(@org.jspecify.annotations.Nullable Component line) {
        return line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line);
    }
}
