package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * What a ChunkBlock island's players see and do: the panel behind the chunks command, the unlock of the
 * chunk a player faces, and the {@code %skyblock_chunkblock_<name>%} placeholders.
 *
 * <p>The panel is {@code menus/island-chunks.conf}, so the operator decides what it shows. Every value
 * the file may name is listed in {@link #values}.
 */
public final class ChunkBlockPanel {

    /** The menu file the panel is, under {@code menus/}. */
    public static final String MENU = "island-chunks";

    private final ChunkBlockService service;
    private final IslandStoragePort islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final BiFunction<IslandId, ProfileId, Long> levelOf;
    private @Nullable SkyblockMenuEngine engine;
    private @Nullable ChunkPurchase purchase;

    /**
     * @param levelOf the island's level as the level command works it out, read off the main thread
     */
    public ChunkBlockPanel(
            ChunkBlockService service,
            IslandStoragePort islands,
            SchedulerPort scheduler,
            Messages messages,
            Function<UUID, Optional<ProfileId>> activeProfile,
            BiFunction<IslandId, ProfileId, Long> levelOf) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.levelOf = Objects.requireNonNull(levelOf, "levelOf must not be null");
    }

    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
    }

    /** Pays for chunks by their grades, rather than by the level ladder alone. */
    public void usePurchase(@Nullable ChunkPurchase purchase) {
        this.purchase = purchase;
    }

    public @Nullable ChunkPurchase purchase() {
        return purchase;
    }

    /** Shows how far the player's island has opened, or says why there is nothing to show. */
    public void open(Player player) {
        withIsland(player, (islandId, profile) -> {
            Optional<ChunkTerritory> territory = service.territory(islandId);
            if (territory.isEmpty()) {
                tell(player, "chunkblock.not_chunkblock");
                return;
            }
            long level = levelOf.apply(islandId, profile);
            Map<String, String> values = values(territory.get(), level);
            scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> show(player, values));
        });
    }

    /** Pays toward the chunk beside the player's, in the direction the player faces, and opens it once paid. */
    public void unlockFaced(Player player) {
        Location at = player.getLocation();
        if (at == null) {
            return;
        }
        ChunkPos faced = faced(at);
        ChunkPurchase grades = this.purchase;
        if (grades != null) {
            // Hands in what the player carries toward the chunk's grade, and opens it once its price is met.
            grades.payAll(player, faced, () -> {});
            return;
        }
        withIsland(player, (islandId, profile) -> {
            Optional<Island> island = islands.findIslandById(islandId);
            if (island.isEmpty()) {
                tell(player, "error.no_island");
                return;
            }
            long level = levelOf.apply(islandId, profile);
            ChunkBlockService.UnlockResult result =
                    service.unlock(islandId, faced, island.get().bounds(), level);
            TagResolver next = Placeholder.unparsed("island_level", Long.toString(result.nextRequirement()));
            switch (result.outcome()) {
                case UNLOCKED -> tell(player, "chunkblock.unlocked", next);
                case ALREADY_OPEN -> tell(player, "chunkblock.already_open");
                case NOT_BESIDE_TERRITORY -> tell(player, "chunkblock.not_beside");
                case OUTSIDE_ISLAND -> tell(player, "chunkblock.outside_island");
                case LEVEL_TOO_LOW ->
                    tell(
                            player,
                            "chunkblock.level_too_low",
                            next,
                            Placeholder.unparsed("current", Long.toString(level)));
                case NOT_CHUNKBLOCK -> tell(player, "chunkblock.not_chunkblock");
                case TAKEN -> tell(player, "chunkblock.taken");
            }
        });
    }

    /**
     * The chunk beside the one at {@code at}, in the direction a player there faces. A yaw of 0 faces
     * south, toward growing z, and the rest follow clockwise.
     */
    static ChunkPos faced(Location at) {
        ChunkPos here = ChunkPos.ofBlock(at.getBlockX(), at.getBlockZ());
        int quarter = Math.floorMod(Math.round(at.getYaw() / 90.0f), 4);
        return switch (quarter) {
            case 0 -> new ChunkPos(here.x(), here.z() + 1);
            case 1 -> new ChunkPos(here.x() - 1, here.z());
            case 2 -> new ChunkPos(here.x(), here.z() - 1);
            default -> new ChunkPos(here.x() + 1, here.z());
        };
    }

    /**
     * Everything the panel and the placeholders may name.
     *
     * <ul>
     *   <li>{@code open_chunks}: how many chunks the island has open, the first among them
     *   <li>{@code next_level}: the level the next chunk needs
     *   <li>{@code island_level}: the island's level
     *   <li>{@code levels_to_go}: how many levels until the next chunk, 0 when it can open now
     * </ul>
     */
    public Map<String, String> values(ChunkTerritory territory, long level) {
        long next = territory.nextRequirement(service.rules());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("open_chunks", Integer.toString(territory.size()));
        values.put("next_level", Long.toString(next));
        values.put("island_level", Long.toString(level));
        values.put("levels_to_go", Long.toString(Math.max(0, next - level)));
        return values;
    }

    /**
     * One {@code chunkblock_<name>} placeholder, from memory only, for the island a player belongs to.
     * {@code is_chunkblock} says whether it is a ChunkBlock island; {@code open_chunks} and
     * {@code next_level} are empty for one that is not.
     *
     * @return the value, or null for a name that is not a ChunkBlock placeholder
     */
    public @Nullable String placeholder(UUID islandId, String name) {
        Objects.requireNonNull(name, "name must not be null");
        Optional<ChunkTerritory> held = service.inMemory(IslandId.of(islandId));
        return switch (name) {
            case "is_chunkblock" -> Boolean.toString(held.isPresent());
            case "open_chunks" -> held.map(t -> Integer.toString(t.size())).orElse("");
            case "next_level" ->
                held.map(t -> Long.toString(t.nextRequirement(service.rules()))).orElse("");
            default -> null;
        };
    }

    void show(Player player, Map<String, String> values) {
        SkyblockMenuEngine menus = this.engine;
        if (menus != null && menus.open(player, MENU, values)) {
            return;
        }
        TagResolver.Builder tags = TagResolver.builder();
        values.forEach((name, value) -> tags.resolver(Placeholder.unparsed(name, value)));
        messages.send(player, "chunkblock.standing", tags.build());
    }

    private void withIsland(Player player, IslandTask task) {
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        scheduler.async(() -> {
            Optional<IslandId> islandId = islands.findIslandIdByProfileId(profile.get());
            if (islandId.isEmpty()) {
                tell(player, "error.no_island");
                return;
            }
            task.run(islandId.get(), profile.get());
        });
    }

    private void tell(Player player, String key, TagResolver... resolvers) {
        scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> messages.send(player, key, resolvers));
    }

    @FunctionalInterface
    private interface IslandTask {
        void run(IslandId islandId, ProfileId profile);
    }
}
