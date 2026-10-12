package com.uxplima.uxmskyblock.bukkit.bank;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalGate;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit event listener enforcing runtime bankruptcy protections (Section 2.39):
 * <ul>
 *   <li>Suppresses mob spawners on locked islands.</li>
 *   <li>Suppresses natural crop growth on locked islands.</li>
 *   <li>Restricts visitors from entering locked islands.</li>
 *   <li>Restricts member block and item interactions while locked, preserving bank deposit remediation.</li>
 *   <li>Warns members upon login of pending grace expiration or active lockout asynchronously.</li>
 * </ul>
 */
public final class IslandBankruptcyListener implements Listener, ArrivalGate {

    private final IslandBankruptcyService bankruptcyService;
    private final Function<Location, Optional<Island>> islandLookup;
    private final @Nullable IslandStoragePort islandStoragePort;
    private final @Nullable SchedulerPort schedulerPort;
    private final Function<UUID, Optional<ProfileId>> activeProfileProvider;
    private final Clock clock;
    private final Messages messages;

    public IslandBankruptcyListener(
            IslandBankruptcyService bankruptcyService,
            IslandProtectionListener protectionListener,
            @Nullable IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable SchedulerPort schedulerPort,
            Messages messages) {
        this(
                bankruptcyService,
                Objects.requireNonNull(protectionListener, "protectionListener must not be null")::findIslandAt,
                islandStoragePort,
                schedulerPort,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty(),
                Clock.systemUTC(),
                messages);
    }

    public IslandBankruptcyListener(
            IslandBankruptcyService bankruptcyService,
            IslandProtectionListener protectionListener,
            @Nullable IslandStoragePort islandStoragePort) {
        this(bankruptcyService, protectionListener, islandStoragePort, null, null, Messages.bundled());
    }

    public IslandBankruptcyListener(
            IslandBankruptcyService bankruptcyService,
            Function<Location, Optional<Island>> islandLookup,
            @Nullable IslandStoragePort islandStoragePort,
            Clock clock) {
        this(
                bankruptcyService,
                islandLookup,
                islandStoragePort,
                null,
                uuid -> Optional.empty(),
                clock,
                Messages.bundled());
    }

    public IslandBankruptcyListener(
            IslandBankruptcyService bankruptcyService,
            Function<Location, Optional<Island>> islandLookup,
            @Nullable IslandStoragePort islandStoragePort,
            @Nullable SchedulerPort schedulerPort,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            Clock clock,
            Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.bankruptcyService = Objects.requireNonNull(bankruptcyService, "bankruptcyService must not be null");
        this.islandLookup = Objects.requireNonNull(islandLookup, "islandLookup must not be null");
        this.islandStoragePort = islandStoragePort;
        this.schedulerPort = schedulerPort;
        this.activeProfileProvider =
                Objects.requireNonNull(activeProfileProvider, "activeProfileProvider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        Location loc = event.getSpawner() != null ? event.getSpawner().getLocation() : event.getLocation();
        if (loc == null || loc.getWorld() == null) {
            loc = event.getLocation();
        }
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        islandLookup.apply(loc).ifPresent(island -> {
            if (bankruptcyService.isIslandLocked(island.id(), Instant.now(clock))) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (event.getBlock() == null) {
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        islandLookup.apply(loc).ifPresent(island -> {
            if (bankruptcyService.isIslandLocked(island.id(), Instant.now(clock))) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (hasBypassPermission(player)) {
            return;
        }

        if (event.getBlock() == null) {
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        islandLookup.apply(loc).ifPresent(island -> {
            if (bankruptcyService.isIslandLocked(island.id(), Instant.now(clock))) {
                event.setCancelled(true);
                player.sendMessage(messages.render(player, "bank.locked_actions"));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (hasBypassPermission(player)) {
            return;
        }

        if (event.getBlock() == null) {
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        islandLookup.apply(loc).ifPresent(island -> {
            if (bankruptcyService.isIslandLocked(island.id(), Instant.now(clock))) {
                event.setCancelled(true);
                player.sendMessage(messages.render(player, "bank.locked_actions"));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (hasBypassPermission(player)) {
            return;
        }

        Location loc = event.getClickedBlock().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        islandLookup.apply(loc).ifPresent(island -> {
            if (bankruptcyService.isIslandLocked(island.id(), Instant.now(clock))) {
                event.setCancelled(true);
                player.sendMessage(messages.render(player, "bank.locked_interactions"));
            }
        });
    }

    /**
     * A visitor arriving on a bankrupt island is turned away, however they came. Folia announces no
     * teleport, so this answers the arrival watch rather than a teleport event.
     */
    @Override
    public boolean refuses(Arrival arrival) {
        Location to = arrival.to();
        Player player = arrival.player();
        if (to.getWorld() == null || hasBypassPermission(player)) {
            return false;
        }
        Optional<Island> island = islandLookup.apply(to);
        if (island.isPresent()
                && bankruptcyService.isIslandLocked(island.get().id(), Instant.now(clock))
                && !isMember(island.get(), player)) {
            player.sendMessage(messages.render(player, "bank.locked_visitors"));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null
                || to.getWorld() == null
                || (from != null && from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }

        Player player = event.getPlayer();
        if (hasBypassPermission(player)) {
            return;
        }

        islandLookup.apply(to).ifPresent(island -> {
            if (bankruptcyService.isIslandLocked(island.id(), Instant.now(clock)) && !isMember(island, player)) {
                event.setCancelled(true);
                player.sendMessage(messages.render(player, "bank.locked_visitors"));
            }
        });
    }

    /**
     * Warns a member who logs in to an island in debt.
     *
     * <p>Called once the player's session is made. It ran on the join event, when the player has no
     * profile yet, so the warning raced the session and was mostly never shown.
     */
    public void onSessionActive(Player player) {
        if (islandStoragePort == null) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        Runnable checkTask = () -> {
            Optional<ProfileId> optProfile = activeProfileProvider.apply(playerUuid);
            if (optProfile.isEmpty()) {
                return;
            }
            ProfileId profileId = optProfile.get();

            islandStoragePort.findIslandIdByProfileId(profileId).ifPresent(islandId -> {
                Instant now = Instant.now(clock);
                IslandBankruptcyRecord record = bankruptcyService.getBankruptcyRecord(islandId, now);

                Runnable messageTask = () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (record.status() == BankruptcyStatus.GRACE) {
                        messages.send(
                                player,
                                "bank.grace_warning",
                                Placeholder.unparsed(
                                        "debt",
                                        com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(record.debtMinorUnits())));
                    } else if (record.status() == BankruptcyStatus.LOCKED) {
                        messages.send(
                                player,
                                "bank.locked_warning",
                                Placeholder.unparsed(
                                        "debt",
                                        com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(record.debtMinorUnits())));
                    }
                };

                if (schedulerPort != null) {
                    schedulerPort.onEntity(new PlayerUuid(playerUuid), messageTask);
                } else {
                    messageTask.run();
                }
            });
        };

        if (schedulerPort != null) {
            schedulerPort.async(checkTask);
        } else {
            checkTask.run();
        }
    }

    private boolean isMember(Island island, Player player) {
        if (island.ownerPlayerUuid().value().equals(player.getUniqueId())) {
            return true;
        }
        return island.members().values().stream()
                .anyMatch(m -> m.playerUuid().value().equals(player.getUniqueId()));
    }

    private boolean hasBypassPermission(Player player) {
        return player.isOp() || player.hasPermission("uxmskyblock.admin.bypass");
    }
}
