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
        java.util.Optional<com.uxplima.uxmskyblock.bukkit.config.OneBlockMoment> moment = happens == null
                ? java.util.Optional.empty()
                : happens.at(next.position().phase().key(), next.position().intoPhase());
        scheduler.onRegion(world.getName(), x >> 4, z >> 4, () -> {
            comeBack(
                    drawn,
                    world,
                    x,
                    y,
                    z,
                    next,
                    player,
                    moment.map(m -> m.block()).orElse(null));
            if (happens != null) {
                moment.ifPresent(m -> happens.inTheWorld(drawn, m, world, x, y, z, player));
            }
        });
        if (happens != null) {
            moment.ifPresent(m -> happens.forTheBreaker(
                    effectPlayer,
                    effects,
                    m,
                    player,
                    Map.of(
                            "phase",
                            next.position().phase().key(),
                            "break",
                            Long.toString(next.position().intoPhase()))));
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
            next.finished()
                    .ifPresent(finished -> effectPlayer.fire(
                            effects, "oneblock-phase-finished", player, Map.of("phase", finished.key())));
            messages.send(player, "oneblock.phase_began", Placeholder.unparsed("phase", title(player, next)));
            effectPlayer.fire(
                    effects,
                    "oneblock-phase-began",
                    player,
                    Map.of(
                            "phase",
                            next.position().phase().key(),
                            "blocks",
                            Long.toString(next.position().phaseLength())));
        }
    }

    private static boolean isTheBlock(OneBlockProgressPort.OneBlockIsland island, Block block) {
        return block.getX() == island.x() && block.getY() == island.y() && block.getZ() == island.z();
    }

    /**
     * How far below the block's top a player may have dropped while it was gone and still be put back.
     * The block is gone for a tick; a player falls well under a block in that time.
     */
    static final double CAUGHT_WITHIN = 3.0;

    private static void comeBack(
            OneBlockDraws draws,
            World world,
            int x,
            int y,
            int z,
            OneBlockService.Broken next,
            Player breaker,
            @org.jspecify.annotations.Nullable String instead) {
        // A moment's block stands in for the one drawn.
        lay(draws, world, x, y, z, instead != null ? instead : next.nextBlock());
        next.creature()
                .ifPresent(creature -> draws.spawn(new Location(world, x + 0.5, y + 1, z + 0.5), creature, breaker));
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
