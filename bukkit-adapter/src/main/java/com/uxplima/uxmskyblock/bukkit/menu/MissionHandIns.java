package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Instant;
import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.ItemNames;
import com.uxplima.uxmskyblock.bukkit.inventory.TradableStacks;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;

/**
 * Items handed in to a mission from the missions window: what the player carries counted, taken, and credited to the mission. Split out of {@link IslandMissionsMenu}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class MissionHandIns {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(MissionHandIns.class.getName());

    private final IslandMissionsMenu host;

    MissionHandIns(IslandMissionsMenu host) {
        this.host = host;
    }

    /** Package private so the guard against losing a player's items can drive it directly. */
    void handleManualItemSubmission(
            Player player, IslandId islandId, ProfileId profileId, MissionDefinition def, long alreadyCounted) {
        String filter = def.targetFilter();
        Material requiredMat = Material.matchMaterial(filter);
        if (requiredMat == null) {
            host.messages.send(player, "menu.missions.invalid_requirement", Placeholder.unparsed("filter", filter));
            return;
        }

        int count = TradableStacks.countOf(player, requiredMat);

        if (count <= 0) {
            host.messages.send(
                    player, "menu.missions.nothing_to_submit", ItemNames.placeholder("item", requiredMat.name()));
            return;
        }

        // How many the mission still wanted when this window was drawn. Reading it again here would
        // be a query on the thread that owns the player, and the answer is already on the tile the
        // player just clicked. What the service actually credits is what it reports back, and
        // anything it did not take is handed straight back below.
        long needed = def.requiredAmount() - alreadyCounted;
        int toTake = (int) Math.min(count, needed);

        if (toTake <= 0) {
            return;
        }

        TradableStacks.take(player, requiredMat, toTake);

        final int taken = toTake;
        host.schedulerPort.async(() -> {
            long credited;
            try {
                credited = host.missionService
                        .submitManualItems(islandId, profileId, def.id(), taken, Instant.now())
                        .map(IslandMissionService.MissionSubmission::credited)
                        .orElse(0L);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.SEVERE,
                        "Crediting a manual mission submission failed, returning the items to the player",
                        e);
                credited = 0L;
            }

            long creditedFinal = credited;
            host.schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                // Whatever the mission did not take goes back. A window drawn a moment ago can say
                // the mission wants ten when another window has already handed in eight, and a
                // player who loses the other two to a counter that stopped rising has been robbed
                // by their own second window.
                int surplus = (int) (taken - creditedFinal);
                if (surplus > 0) {
                    host.returnItems(player, requiredMat, surplus);
                }
                if (creditedFinal <= 0) {
                    host.messages.send(
                            player, "menu.missions.submit_failed", ItemNames.placeholder("item", requiredMat.name()));
                    return;
                }
                host.messages.send(
                        player,
                        "menu.missions.submitted",
                        Placeholder.unparsed("amount", Long.toString(creditedFinal)),
                        ItemNames.placeholder("item", requiredMat.name()));
                host.open(player);
            });
        });
    }
}
