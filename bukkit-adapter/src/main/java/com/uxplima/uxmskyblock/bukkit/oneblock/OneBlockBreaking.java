package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Map;

import org.bukkit.World;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.mission.ModeMissions;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;

/**
 * What one counted break of the block does: the next block laid, a creature or a chest set on it, a phase begun or finished, and the island told. Split out of {@link OneBlockListener}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class OneBlockBreaking {

    private final OneBlockListener host;

    OneBlockBreaking(OneBlockListener host) {
        this.host = host;
    }

    /**
     * What a counted break does: the block comes back as the draw or a moment, a phase that begins does so with its
     * lists, and a phase that waits begins with bedrock.
     */
    void counted(Island owner, Player player, World world, int x, int y, int z, OneBlockService.Broken next) {
        var members = host.contributions;
        if (members != null) {
            members.count(
                    owner.id(), new com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid(player.getUniqueId()));
        }
        OneBlockPauses waits = host.pauses;
        OneBlockDraws ahead = host.draws;
        OneBlockProgressBars shown = host.bars;
        if (shown != null) {
            host.scheduler.onEntity(player.getUniqueId(), () -> shown.show(player, next.position()));
        }
        // A hostile creature drawn ahead is told now, so the breaker is ready when it comes.
        for (OneBlockService.Coming coming : next.coming()) {
            if (!ahead.hostile(coming.creature())) {
                continue;
            }
            net.kyori.adventure.text.Component creature = ahead.creatureName(coming.creature(), player);
            host.messages.send(
                    player,
                    "oneblock.creature_coming",
                    Placeholder.component("creature", creature),
                    Placeholder.unparsed("breaks", Long.toString(coming.inBreaks())));
            host.effectPlayer.fire(
                    host.effects,
                    "oneblock-creature-coming",
                    player,
                    Map.of(
                            "creature",
                            ahead.plainCreatureName(coming.creature()),
                            "breaks",
                            Long.toString(coming.inBreaks())));
        }
        // Set once the break has finished: set now, the break would clear it again.
        OneBlockDraws drawn = host.draws;
        OneBlockMoments happens = host.moments;
        IslandId islandId = owner.id();
        String phase = next.position().phase().key();
        java.time.Duration pause = waits != null && next.phaseBegan() ? waits.pauseOf(phase) : java.time.Duration.ZERO;
        OneBlockBiomes painted = host.biomes;
        if (painted != null && next.phaseBegan()) {
            painted.began(owner, world, x, z, phase);
        }
        String momentKey = "moment:" + next.position().intoPhase();
        // A moment the island was paid in this pass, before a crash or an operator set its count back, is not had.
        // A phase that waits begins with bedrock, so nothing is laid at its first break but that.
        java.util.Optional<com.uxplima.uxmskyblock.bukkit.config.OneBlockMoment> moment =
                happens == null || !pause.isZero()
                        ? java.util.Optional.empty()
                        : happens.at(phase, next.position().intoPhase())
                                .filter(m -> host.service.claim(islandId, phase, momentKey, next.nextCycle()));
        host.scheduler.onRegion(world.getName(), x >> 4, z >> 4, () -> {
            OneBlockBlockLines over = host.blockLines;
            if (waits != null && !pause.isZero()) {
                // The countdown is the line while the phase waits.
                if (over != null) {
                    over.take(islandId);
                }
                waits.begin(islandId, phase, pause, world, x, y, z);
                OneBlockListener.putBackOnTop(world, x, y, z);
                return;
            }
            if (over != null) {
                over.show(islandId, world, x, y, z, next.position(), player);
            }
            String rarity = OneBlockListener.comeBack(
                    drawn,
                    world,
                    x,
                    y,
                    z,
                    next,
                    player,
                    moment.map(m -> m.block()).orElse(null));
            if (rarity != null) {
                host.scheduler.onEntity(
                        player.getUniqueId(), () -> host.effectPlayer.fire(host.effects, rarity, player));
            }
            if (happens != null) {
                moment.ifPresent(m -> happens.inTheWorld(drawn, m, world, x, y, z, player));
            }
        });
        if (happens != null) {
            moment.ifPresent(m -> host.paid(
                    islandId,
                    phase,
                    momentKey,
                    next.nextCycle(),
                    player,
                    () -> happens.forTheBreaker(
                            host.effectPlayer,
                            host.effects,
                            m,
                            player,
                            Map.of(
                                    "phase",
                                    phase,
                                    "break",
                                    Long.toString(next.position().intoPhase()),
                                    "cycle",
                                    Long.toString(next.nextCycle() + 1)))));
        }
        // A lucky break, as often as the file of the phase the broken block belonged to says: the phase it finished,
        // when it finished one, and that phase's last block.
        OneBlockPhaseLists lists = host.phaseLists;
        String of = next.finished()
                .map(com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase::key)
                .orElse(phase);
        if (lists != null && lists.lucky(of, host.luck.getAsDouble())) {
            Map<String, String> lucky = Map.of(
                    "phase",
                    of,
                    "break",
                    Long.toString(next.finished()
                            .map(com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase::blocks)
                            .orElse(next.position().intoPhase())),
                    "cycle",
                    // The round the broken block was broken in.
                    Long.toString(next.cycle() + 1));
            if (!lists.run(
                    host.effectPlayer,
                    of,
                    com.uxplima.uxmskyblock.bukkit.config.OneBlockLists.LUCKY,
                    owner,
                    player,
                    lucky)) {
                host.effectPlayer.fire(host.effects, "oneblock-lucky-break", player, lucky);
            }
        }
        ModeMissions counts = host.missions;
        // The block broken belonged to the phase it finished, when it finished one.
        counts.counted(
                player,
                MissionTriggerType.ONEBLOCK_BREAK,
                next.finished().orElse(next.position().phase()).key(),
                1L);
        if (next.phaseBegan()) {
            counts.counted(
                    player,
                    MissionTriggerType.ONEBLOCK_PHASE,
                    next.position().phase().key(),
                    1L);
            OneBlockPhaseLists written = host.phaseLists;
            // Which pass through the phases, from 1, as the panel and the placeholders count it.
            String cycle = Long.toString(next.cycle() + 1);
            next.finished().ifPresent(finished -> {
                Map<String, String> says = Map.of("phase", finished.key(), "cycle", cycle);
                // The phase's own lists, or the shipped one when it wrote none.
                host.pay(islandId, finished.key(), "finished", next.cycle(), player, () -> {
                    if (written == null
                            || !written.run(host.effectPlayer, finished.key(), "finished", owner, player, says)) {
                        host.effectPlayer.fire(host.effects, "oneblock-phase-finished", player, says);
                    }
                });
                if (written != null && next.cycle() == 0) {
                    host.pay(
                            islandId,
                            finished.key(),
                            "first-finished",
                            0,
                            player,
                            () -> written.run(
                                    host.effectPlayer, finished.key(), "first-finished", owner, player, says));
                }
            });
            host.messages.send(
                    player, "oneblock.phase_began", Placeholder.unparsed("phase", host.phaseTitle(player, next)));
            Map<String, String> began = Map.of(
                    "phase",
                    next.position().phase().key(),
                    "blocks",
                    Long.toString(next.position().phaseLength()),
                    "cycle",
                    Long.toString(next.nextCycle() + 1));
            host.pay(islandId, phase, "began", next.nextCycle(), player, () -> {
                if (written == null || !written.run(host.effectPlayer, phase, "began", owner, player, began)) {
                    host.effectPlayer.fire(host.effects, "oneblock-phase-began", player, began);
                }
            });
        }
    }
}
