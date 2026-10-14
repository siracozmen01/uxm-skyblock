package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;

/**
 * {@code /is vault log}: the last moves in and out of the island vault, each with who made it and when. Split out of {@link IslandVaultWindow}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class VaultLog {

    private final IslandVaultWindow host;

    VaultLog(IslandVaultWindow host) {
        this.host = host;
    }

    /**
     * Sends the island's management what the vault has seen.
     *
     * <p>The audit entries were written and no one could read them: the service call that returns
     * them had no caller anywhere. A record nobody can look at is not a record.
     *
     * <p>The bar is the island's management permission, not the one that opens the vault. Every
     * member can open the chest; who took what out of it is the owner's question.
     */
    public void showLog(Player player, int limit) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.messages.send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();

        host.schedulerPort.async(() -> {
            Optional<Island> optIsland = host.islandStoragePort
                    .findIslandIdByProfileId(profileId)
                    .flatMap(host.islandStoragePort::findIslandById);
            if (optIsland.isEmpty()) {
                host.schedulerPort.onEntity(playerUuid, () -> host.messages.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!host.roleOf(island, profileId).hasPermission(IslandPermission.SETTINGS_MODIFY)) {
                host.schedulerPort.onEntity(playerUuid, () -> host.messages.send(player, "vault.log_no_permission"));
                return;
            }

            List<VaultAuditLogEntry> entries = host.vaultService.getRecentAuditLogs(island.id(), limit);
            host.schedulerPort.onEntity(playerUuid, () -> sendLog(player, island, entries));
        });
    }

    void sendLog(Player player, Island island, List<VaultAuditLogEntry> entries) {
        if (entries.isEmpty()) {
            host.messages.send(player, "vault.log_empty");
            return;
        }
        host.messages.send(player, "vault.log_header", Placeholder.unparsed("count", Integer.toString(entries.size())));
        for (VaultAuditLogEntry entry : entries) {
            host.messages.send(
                    player,
                    "vault.log_entry",
                    Placeholder.unparsed("actor", IslandVaultWindow.nameOf(island, entry.actorProfileId())),
                    Placeholder.unparsed("action", entry.actionType().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("quantity", Integer.toString(entry.quantity())),
                    Placeholder.unparsed("item", entry.itemSummary()),
                    Placeholder.unparsed("page", Integer.toString(entry.page())),
                    Placeholder.unparsed("slot", Integer.toString(entry.slot())),
                    Placeholder.unparsed("when", IslandVaultWindow.WHEN.format(entry.createdAt())));
        }
    }
}
