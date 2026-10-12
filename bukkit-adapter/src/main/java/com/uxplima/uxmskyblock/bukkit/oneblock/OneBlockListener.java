package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.mission.ModeMissions;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;

/**
 * Brings a OneBlock island's block back when it is broken, as whatever its phase draws.
 *
 * <p>The break itself is the server's: whoever broke the block gets what it drops, and a break the
 * island's protection refused never reaches here. Once it has gone through, the block is set again in
 * the region that owns it, after the break has finished, and a creature the phase draws stands on it.
 * The first break of a new phase tells the player which phase it is, and fires what the operator wrote
 * for {@code oneblock-phase-finished}, then {@code oneblock-phase-began}, in {@code modules/effects.conf}. Both
 * say {@code %phase%}, the phase's key; the one begun says {@code %blocks%}, how many breaks it lasts.
 */
public final class OneBlockListener implements Listener {

    private final OneBlockService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private volatile ModeMissions missions = ModeMissions.NONE;
    private volatile OneBlockDraws draws = OneBlockDraws.PLAIN;
    private volatile @org.jspecify.annotations.Nullable OneBlockMoments moments;
    private volatile @org.jspecify.annotations.Nullable OneBlockPhaseLists phaseLists;
    private volatile @org.jspecify.annotations.Nullable OneBlockPauses pauses;

    public OneBlockListener(
            OneBlockService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
    }

    /** Goes on with the waits read back before the world was loaded. */
    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        OneBlockPauses waits = pauses;
        if (waits != null) {
            waits.worldLoaded(event.getWorld());
        }
    }

    /** Tells it which phases wait once they begin, with their block bedrock and a countdown over it. */
    public void usePauses(OneBlockPauses pauses) {
        this.pauses = Objects.requireNonNull(pauses, "pauses must not be null");
    }

    /** Tells it what each phase runs when it begins, finishes and first finishes. */
    public void usePhaseLists(OneBlockPhaseLists phaseLists) {
        this.phaseLists = Objects.requireNonNull(phaseLists, "phaseLists must not be null");
    }

    /** Tells it what happens at the exact breaks the phase files write. */
    public void useMoments(OneBlockMoments moments) {
        this.moments = Objects.requireNonNull(moments, "moments must not be null");
    }

    /** Tells it how the operator's pool entries are put into the world: states, spawners and creatures. */
    public void useDraws(OneBlockDraws draws) {
        this.draws = Objects.requireNonNull(draws, "draws must not be null");
    }

    /** Tells it where a break of the block and a phase reached count, for the missions that ask for them. */
    public void useMissions(ModeMissions missions) {
        this.missions = Objects.requireNonNull(missions, "missions must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Optional<Island> island = islands.findIslandAt(block.getLocation());
        if (island.isEmpty()) {
            return;
        }
        Optional<OneBlockProgressPort.OneBlockIsland> oneBlock =
                service.island(island.get().id());
        if (oneBlock.isEmpty() || !isTheBlock(oneBlock.get(), block)) {
            return;
        }
        OneBlockPauses waits = pauses;
        if (waits != null && waits.holds(island.get().id())) {
            // The phase's wait is not over: its bedrock stays, whoever could break it.
            event.setCancelled(true);
            return;
        }
        Optional<OneBlockService.Broken> broken = service.onBreak(island.get().id());
        if (broken.isEmpty()) {
            return;
        }
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Player player = event.getPlayer();
        OneBlockService.Broken next = broken.get();
        // Set once the break has finished: set now, the break would clear it again.
        OneBlockDraws drawn = draws;
        OneBlockMoments happens = moments;
        IslandId islandId = island.get().id();
        String phase = next.position().phase().key();
        java.time.Duration pause = waits != null && next.phaseBegan() ? waits.pauseOf(phase) : java.time.Duration.ZERO;
        String momentKey = "moment:" + next.position().intoPhase();
        // A moment the island was paid in this pass, before a crash or an operator set its count back, is not had.
        // A phase that waits begins with bedrock, so nothing is laid at its first break but that.
        java.util.Optional<com.uxplima.uxmskyblock.bukkit.config.OneBlockMoment> moment =
                happens == null || !pause.isZero()
                        ? java.util.Optional.empty()
                        : happens.at(phase, next.position().intoPhase())
                                .filter(m -> service.claim(islandId, phase, momentKey, next.nextCycle()));
        scheduler.onRegion(world.getName(), x >> 4, z >> 4, () -> {
            if (waits != null && !pause.isZero()) {
                waits.begin(islandId, phase, pause, world, x, y, z);
                putBackOnTop(world, x, y, z);
                return;
            }
            String rarity = comeBack(
                    drawn,
                    world,
                    x,
                    y,
                    z,
                    next,
                    player,
                    moment.map(m -> m.block()).orElse(null));
            if (rarity != null) {
                scheduler.onEntity(player.getUniqueId(), () -> effectPlayer.fire(effects, rarity, player));
            }
            if (happens != null) {
                moment.ifPresent(m -> happens.inTheWorld(drawn, m, world, x, y, z, player));
            }
        });
        if (happens != null) {
            moment.ifPresent(m -> paid(
                    islandId,
                    phase,
                    momentKey,
                    next.nextCycle(),
                    player,
                    () -> happens.forTheBreaker(
                            effectPlayer,
                            effects,
                            m,
                            player,
                            Map.of(
                                    "phase",
                                    phase,
                                    "break",
                                    Long.toString(next.position().intoPhase())))));
        }
        ModeMissions counts = missions;
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
            OneBlockPhaseLists written = phaseLists;
            String cycle = Long.toString(next.cycle());
            next.finished().ifPresent(finished -> {
                Map<String, String> says = Map.of("phase", finished.key(), "cycle", cycle);
                // The phase's own lists, or the shipped one when it wrote none.
                pay(islandId, finished.key(), "finished", next.cycle(), player, () -> {
                    if (written == null
                            || !written.run(effectPlayer, finished.key(), "finished", island.get(), player, says)) {
                        effectPlayer.fire(effects, "oneblock-phase-finished", player, says);
                    }
                });
                if (written != null && next.cycle() == 0) {
                    pay(
                            islandId,
                            finished.key(),
                            "first-finished",
                            0,
                            player,
                            () -> written.run(
                                    effectPlayer, finished.key(), "first-finished", island.get(), player, says));
                }
            });
            messages.send(player, "oneblock.phase_began", Placeholder.unparsed("phase", title(player, next)));
            Map<String, String> began = Map.of(
                    "phase",
                    next.position().phase().key(),
                    "blocks",
                    Long.toString(next.position().phaseLength()));
            pay(islandId, phase, "began", next.nextCycle(), player, () -> {
                if (written == null || !written.run(effectPlayer, phase, "began", island.get(), player, began)) {
                    effectPlayer.fire(effects, "oneblock-phase-began", player, began);
                }
            });
        }
    }

    /** Pays a reward of a phase once in its pass: claimed here, then recorded and paid as {@link #paid} does. */
    private void pay(IslandId islandId, String phase, String reward, long cycle, Player player, Runnable payment) {
        if (service.claim(islandId, phase, reward, cycle)) {
            paid(islandId, phase, reward, cycle, player, payment);
        }
    }

    /**
     * Records a reward claimed off the region threads, then pays it on the player's own: a reward storage already
     * holds in that pass, or could not record, is not paid.
     */
    private void paid(IslandId islandId, String phase, String reward, long cycle, Player player, Runnable payment) {
        scheduler.async(() -> {
            if (service.record(islandId, phase, reward, cycle)) {
                scheduler.onEntity(player.getUniqueId(), payment);
            }
        });
    }

    private static boolean isTheBlock(OneBlockProgressPort.OneBlockIsland island, Block block) {
        return block.getX() == island.x() && block.getY() == island.y() && block.getZ() == island.z();
    }

    /**
     * How far below the block's top a player may have dropped while it was gone and still be put back.
     * The block is gone for a tick; a player falls well under a block in that time.
     */
    static final double CAUGHT_WITHIN = 3.0;

    /**
     * Lays what the break drew, or the moment's block in its place, and brings what appears on it. A container drawn
     * from the pool is given its loot table.
     *
     * @return the entry of modules/effects.conf the drawn entry's rarity fires for the breaker, or none
     */
    private static @org.jspecify.annotations.Nullable String comeBack(
            OneBlockDraws draws,
            World world,
            int x,
            int y,
            int z,
            OneBlockService.Broken next,
            Player breaker,
            @org.jspecify.annotations.Nullable String instead) {
        // A moment's block stands in for the one drawn, and fills from the moment's own table.
        lay(draws, world, x, y, z, instead != null ? instead : next.nextBlock());
        next.creature()
                .ifPresent(creature -> draws.spawn(new Location(world, x + 0.5, y + 1, z + 0.5), creature, breaker));
        return instead != null
                ? null
                : draws.fill(
                        world,
                        x,
                        y,
                        z,
                        next.nextBlock(),
                        next.position().phase().key());
    }

    /** Lays the block as {@code block} and puts whoever dropped while it was gone back on top. On the block's region. */
    static void lay(OneBlockDraws draws, World world, int x, int y, int z, String block) {
        draws.lay(world, x, y, z, block);
        putBackOnTop(world, x, y, z);
    }

    /**
     * Puts back on top of the block every player who stood on it and dropped while it was gone.
     *
     * <p>A player's client breaks the block before the server does and sees air at once, so the player
     * standing on it starts to fall, and the block comes back around their feet. From there the game
     * lets them fall through it into the void. Whoever is in the block's column, below its top and not
     * far under it, is set back on it where they stood, looking where they looked.
     */
    private static void putBackOnTop(World world, int x, int y, int z) {
        BoundingBox column = new BoundingBox(x, y - CAUGHT_WITHIN, z, x + 1, y + 1, z + 1);
        for (Entity entity : world.getNearbyEntities(column, Player.class::isInstance)) {
            Location at = entity.getLocation();
            if (at.getY() >= y + 1 || at.getY() < y - CAUGHT_WITHIN) {
                continue;
            }
            Location top = new Location(world, at.getX(), y + 1, at.getZ(), at.getYaw(), at.getPitch());
            entity.setFallDistance(0f);
            entity.setVelocity(new Vector(0, 0, 0));
            var unused = entity.teleportAsync(top);
        }
    }

    /** The phase's title in the player's language, or its name when the catalogue has none. */
    private String title(Player player, OneBlockService.Broken next) {
        String key = next.position().phase().key();
        return messages.named(player, "oneblock.phases", key, key);
    }
}
