package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Optional;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.application.worth.RecalculationGate;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;

/**
 * {@code /is level} and its recalculation: the island's level said, and counted again block by block when asked. Split out of {@link IslandProgressionCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class LevelVerbs {

    private final IslandProgressionCommands host;

    LevelVerbs(IslandProgressionCommands host) {
        this.host = host;
    }

    int executeLevel(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            host.send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandWorthService worthService = host.worthServiceProvider.get();
        if (worthService == null) {
            host.send(player, "level.engine_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        host.schedulerPort.async(() -> {
            // Which island the caller belongs to is a row in a table. Reading it here, before the
            // scheduler, put a query on the thread Brigadier runs a command on, which is the thread
            // running the game for everybody in the region.
            Optional<IslandId> optIsland = host.islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                host.send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            long bankBalance =
                    host.islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
            // The finished missions really are counted. This used to pass a hardcoded zero, so
            // levels.quest-weight was a number an operator could set and never see applied: a player
            // who finished every mission scored the same as one who finished none.
            IslandScoreBreakdown score =
                    worthService.calculateScore(islandId, host.completedMissions(islandId, profileId), bankBalance);
            host.schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                host.send(player, "level.header");
                host.send(
                        player,
                        "level.calculated",
                        IslandProgressionCommands.number("island_level", score.calculatedLevel()));
                host.send(player, "level.total_score", IslandProgressionCommands.number("score", score.totalScore()));
                host.send(player, "level.block_score", IslandProgressionCommands.number("score", score.blockScore()));
                host.send(
                        player, "level.spawner_score", IslandProgressionCommands.number("score", score.spawnerScore()));
                host.send(player, "level.bank_score", IslandProgressionCommands.number("score", score.bankScore()));
                host.send(
                        player,
                        "level.worth",
                        Placeholder.unparsed(
                                "worth", IslandProgressionCommands.money(score.dampedEconomicWorthMinorUnits())));
                host.send(player, "level.recalculate_hint");
            });
        });
        return Cmd.OK;
    }

    int executeLevelRecalculate(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            host.send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandWorthService worthService = host.worthServiceProvider.get();
        if (worthService == null) {
            host.send(player, "level.engine_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        RecalculationGate gate = host.recalculationGate;

        host.schedulerPort.async(() -> {
            Optional<IslandId> optIsland = host.islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                host.send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> optLoc =
                    host.islandLocationService.findLocation(islandId);
            if (optLoc.isEmpty()) {
                host.schedulerPort.onEntity(
                        new PlayerUuid(player.getUniqueId()), () -> host.send(player, "level.details_missing"));
                return;
            }
            com.uxplima.uxmskyblock.core.domain.island.IslandLocation loc = optLoc.get();
            long bankBalance =
                    host.islandBankService.getBalanceMinorUnits(profileId).orElse(0L);

            // The same hardcoded zero that once sat in executeLevel sat here too, and this is the
            // worse half: the recalculation is what rescans the island and publishes the new level,
            // so levels.quest-weight was dropped from the number a player is finally given.
            int completed = host.completedMissions(islandId, profileId);

            switch (gate.tryEnter(islandId)) {
                case RecalculationGate.Admission.AlreadyRunning running -> {
                    host.send(player, "level.recalculation_running");
                    return;
                }
                case RecalculationGate.Admission.CoolingDown wait -> {
                    host.send(
                            player,
                            "level.recalculation_cooldown",
                            Placeholder.unparsed(
                                    "remaining",
                                    Long.toString(Math.max(1L, wait.remaining().toSeconds()))));
                    return;
                }
                case RecalculationGate.Admission.Admitted admitted -> host.send(player, "level.recalculating");
            }

            try {
                worthService.triggerAsyncRecalculation(
                        islandId, loc.worldName(), loc.bounds(), completed, bankBalance, score -> {
                            gate.leave(islandId);
                            host.schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                                host.send(player, "level.recalculated");
                                host.send(
                                        player,
                                        "level.new_level",
                                        IslandProgressionCommands.number("island_level", score.calculatedLevel()),
                                        IslandProgressionCommands.number("score", score.totalScore()));
                                host.send(
                                        player,
                                        "level.worth",
                                        Placeholder.unparsed(
                                                "worth",
                                                IslandProgressionCommands.money(
                                                        score.dampedEconomicWorthMinorUnits())));
                            });
                        });
            } catch (RuntimeException failed) {
                // A rescan that never started must not hold the island's place for ever.
                gate.leave(islandId);
                throw failed;
            }
        });
        return Cmd.OK;
    }
    /**
     * The level this island has reached, or every level at once when there is nothing to measure.
     *
     * <p>A node with the worth engine switched off has no levels, so it has no levels to require:
     * every biome the operator offers is open there. Returning the highest possible number says
     * that without a second branch at the caller.
     */
    int levelOf(IslandId islandId, ProfileId profileId) {
        IslandWorthService worthService = host.worthServiceProvider.get();
        if (worthService == null) {
            return Integer.MAX_VALUE;
        }
        long bankBalance =
                host.islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
        return (int) Math.min(
                Integer.MAX_VALUE,
                worthService
                        .calculateScore(islandId, host.completedMissions(islandId, profileId), bankBalance)
                        .calculatedLevel());
    }
}
