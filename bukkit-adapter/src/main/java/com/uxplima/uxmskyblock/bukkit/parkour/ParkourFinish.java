package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * A run over the finish plate: the time kept, the best times and the rewards the runner has not yet claimed. Split out of {@link ParkourRuns}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ParkourFinish {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ParkourFinish.class.getName());

    private final ParkourRuns host;

    ParkourFinish(ParkourRuns host) {
        this.host = host;
    }

    void finish(Player runner, IslandId course, Location plate, boolean team) {
        ParkourRuns.Run run = host.runs.get(runner.getUniqueId());
        if (run == null || !run.course().equals(course)) {
            return;
        }
        host.runs.remove(runner.getUniqueId());
        Duration time = Duration.between(run.started(), host.clock.instant());
        PlayerUuid who = PlayerUuid.of(runner.getUniqueId());
        ParkourGhosts racing = host.ghosts;
        com.uxplima.uxmskyblock.core.application.parkour.GhostPath path =
                racing == null ? null : racing.finished(runner, time, plate);
        ParkourSplits taking = host.splits;
        Optional<Map<com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort.Checkpoint, Long>> taken =
                taking == null ? Optional.empty() : taking.finished(runner.getUniqueId());
        host.effectPlayer.fire(host.effects, "parkour-finished", runner, ParkourRuns.said(time));
        host.scheduler.async(() -> {
            ParkourService.Finish finish;
            Optional<ParkourPort.Best> before;
            Optional<ParkourPort.Best> after;
            try {
                before = host.service.leader(course);
                finish = host.service.finish(course, who, time);
                after = host.service.leader(course);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "A run on course " + course + " could not be counted.");
                host.scheduler.onEntity(
                        who, () -> host.messages.send(runner, "parkour.finished", ParkourRuns.timed("time", time)));
                return;
            }
            boolean best =
                    finish instanceof ParkourService.Finish.First || finish instanceof ParkourService.Finish.Beaten;
            if (taking != null && best && taken.isPresent()) {
                taking.keep(course, who, taken.get());
            }
            if (racing != null && path != null && best) {
                racing.keep(course, who, time, path);
            }
            // A record is a time below the course's best before it, that still leads once it is counted: of two
            // finishes at once, the slower is no record.
            boolean courseRecord =
                    after.filter(leader -> leader.runner().equals(who) && leader.millis() == time.toMillis())
                                    .isPresent()
                            && before.map(was -> time.toMillis() < was.millis()).orElse(true);
            String recordWas = before.map(was -> ParkourRuns.format(Duration.ofMillis(was.millis())))
                    .orElse("");
            Optional<ParkourSeasonService.Counted> season = host.counted(course, who, time);
            boolean seasonRecord =
                    season.filter(ParkourSeasonService.Counted::record).isPresent();
            String seasonWas = season.flatMap(ParkourSeasonService.Counted::was)
                    .map(was -> ParkourRuns.format(Duration.ofMillis(was)))
                    .orElse("");
            List<String> lists = team ? List.of() : claimed(course, who, finish, courseRecord, seasonRecord, time);
            host.scheduler.onEntity(who, () -> {
                host.told(runner, finish);
                if (courseRecord) {
                    host.messages.send(runner, "parkour.course_record", ParkourRuns.timed("time", time));
                }
                if (seasonRecord) {
                    host.messages.send(
                            runner,
                            "parkour.season.record",
                            ParkourRuns.timed("time", time),
                            Placeholder.unparsed("season", season.get().season().name()));
                }
                host.paid(runner, finish, lists, recordWas, seasonWas);
            });
        });
    }
    /**
     * The lists this finish earns and has claimed: a first finish once for good, a new best and a course record once
     * in their period, none for a run quicker than the shortest. Off the main thread.
     */
    List<String> claimed(
            IslandId course,
            PlayerUuid who,
            ParkourService.Finish finish,
            boolean courseRecord,
            boolean seasonRecord,
            Duration time) {
        com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort paidIn = host.ledger;
        ParkourConfiguration.Rewards written = host.terms;
        if (paidIn == null || time.compareTo(written.shortestRun()) < 0) {
            return List.of();
        }
        long now = host.clock.millis();
        long again = now - written.againAfter().toMillis();
        List<String> lists = new ArrayList<>();
        if (finish instanceof ParkourService.Finish.First) {
            ParkourRuns.claim(paidIn, course, who, ParkourRuns.FIRST_FINISH, now, Long.MIN_VALUE, lists);
        }
        if (finish instanceof ParkourService.Finish.Beaten) {
            ParkourRuns.claim(paidIn, course, who, ParkourRuns.PERSONAL_BEST, now, again, lists);
        }
        if (courseRecord) {
            ParkourRuns.claim(paidIn, course, who, ParkourRuns.COURSE_RECORD, now, again, lists);
        }
        if (seasonRecord) {
            ParkourRuns.claim(paidIn, course, who, ParkourRuns.SEASON_RECORD, now, again, lists);
        }
        return List.copyOf(lists);
    }
}
