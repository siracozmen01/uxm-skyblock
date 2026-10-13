package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.jspecify.annotations.Nullable;

/**
 * What a OneBlock island's players can read about it: the panel behind {@code /is oneblock}, and the
 * {@code %skyblock_oneblock_<name>%} placeholders.
 *
 * <p>The panel is {@code menus/island-oneblock.conf}, so the operator decides what it shows and where.
 * A Bedrock player gets the same file as a native form, which the menu engine draws from it. Every
 * value the file may name is listed in {@link #values}.
 */
public final class OneBlockPanel {

    /** The menu file the panel is, under {@code menus/}. */
    public static final String MENU = "island-oneblock";

    private final OneBlockService service;
    private final IslandStoragePort islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private @Nullable SkyblockMenuEngine engine;
    private volatile java.util.function.@Nullable Supplier<com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration>
            config;

    public OneBlockPanel(
            OneBlockService service,
            IslandStoragePort islands,
            SchedulerPort scheduler,
            Messages messages,
            Function<UUID, Optional<ProfileId>> activeProfile) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
    }

    /** The operator's phase files, which the next moment is read from. Without them there is none to tell. */
    public void useConfiguration(
            java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration> config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** The engine that draws the operator's menu file. Without one the standing is said in chat. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
    }

    /** Shows the player where their island stands, or says why there is nothing to show. */
    public void open(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        scheduler.async(() -> {
            Optional<IslandId> islandId = islands.findIslandIdByProfileId(profile.get());
            if (islandId.isEmpty()) {
                scheduler.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            // Off the player's thread, so an island not yet in memory may be read here.
            Optional<OneBlockProgressPort.OneBlockIsland> oneBlock = service.island(islandId.get());
            OneBlockPhases way = service.phasesOf(islandId.get()).orElse(service.phases());
            scheduler.onEntity(playerUuid, () -> {
                if (oneBlock.isEmpty()) {
                    messages.send(player, "oneblock.not_oneblock");
                } else {
                    show(player, way, oneBlock.get().blocksBroken());
                }
            });
        });
    }

    /** Draws the panel on the player's own thread from a count already read. */
    void show(Player player, OneBlockPhases way, long broken) {
        Map<String, String> values = values(player, way, broken);
        SkyblockMenuEngine menus = this.engine;
        if (menus != null && menus.open(player, MENU, values)) {
            return;
        }
        TagResolver.Builder tags = TagResolver.builder();
        values.forEach((name, value) -> tags.resolver(Placeholder.unparsed(name, value)));
        messages.send(player, "oneblock.standing", tags.build());
    }

    /**
     * Everything the panel and the placeholders may name, for an island that has broken {@code broken}
     * blocks.
     *
     * <ul>
     *   <li>{@code phase}: the phase's title in the viewer's language
     *   <li>{@code phase_id}: the phase's name in {@code modules/oneblock.conf}
     *   <li>{@code phase_number} and {@code phase_count}: which phase, from one, and how many there are
     *   <li>{@code blocks_broken}: every block the island has broken
     *   <li>{@code phase_blocks}, {@code phase_length} and {@code phase_left}: breaks into the phase,
     *       how many it lasts and how many remain
     *   <li>{@code phase_percent}: how far through the phase, from 0 to 100
     *   <li>{@code next_phase} and {@code next_phase_id}: the phase that follows, along the island's way, its title
     *       and its name; empty after the last phase of an island that stays in it
     *   <li>{@code next_moment}: how many breaks until the next moment the phase file writes, empty when none is left
     *   <li>{@code cycle}: which pass through the phases the island is on, from 1
     * </ul>
     */
    public Map<String, String> values(@Nullable Audience viewer, long broken) {
        return values(viewer, service.phases(), broken);
    }

    /** The same, along an island's own way through the phases, which its picks set. */
    Map<String, String> values(@Nullable Audience viewer, OneBlockPhases phases, long broken) {
        OneBlockPhases.Position at = phases.positionAt(broken);
        String key = at.phase().key();
        long into = at.intoPhase();
        long length = at.phaseLength();
        Map<String, String> values = new LinkedHashMap<>();
        Audience audience = viewer == null ? Audience.empty() : viewer;
        values.put("phase", messages.named(audience, "oneblock.phases", key, key));
        values.put("phase_id", key);
        values.put("phase_number", Integer.toString(at.index() + 1));
        values.put("phase_count", Integer.toString(phases.phases().size()));
        values.put("blocks_broken", Long.toString(broken));
        values.put("phase_blocks", Long.toString(into));
        values.put("phase_length", Long.toString(length));
        // An island that stays in the last phase goes on past its length; nothing is left of it.
        values.put("phase_left", Long.toString(Math.max(0, length - into)));
        values.put("phase_percent", Long.toString(length <= 0 ? 100 : Math.min(100, into * 100 / length)));
        String next = nextPhase(phases, at);
        values.put("next_phase", next.isEmpty() ? "" : messages.named(audience, "oneblock.phases", next, next));
        values.put("next_phase_id", next);
        values.put("next_moment", nextMoment(key, into));
        values.put("cycle", Long.toString(phases.cycleOf(broken) + 1));
        return values;
    }

    /** The phase after the one at {@code at}: the next along the way, the first again past the last when it repeats. */
    private static String nextPhase(OneBlockPhases phases, OneBlockPhases.Position at) {
        int after = at.index() + 1;
        if (after < phases.phases().size()) {
            return phases.phases().get(after).key();
        }
        return phases.afterTheLast() == OneBlockPhases.AfterTheLast.REPEAT
                ? phases.phases().getFirst().key()
                : "";
    }

    /** How many breaks until the next moment of {@code phase} after {@code into} of its blocks, or empty. */
    private String nextMoment(String phase, long into) {
        var files = config;
        if (files == null) {
            return "";
        }
        return files.get().moments().getOrDefault(phase, Map.of()).keySet().stream()
                .filter(at -> at > into)
                .min(Long::compare)
                .map(at -> Long.toString(at - into))
                .orElse("");
    }

    /**
     * One {@code oneblock_<name>} placeholder for the island a player belongs to.
     *
     * <p>A scoreboard asks on the thread that draws it, so this reads memory only. {@code is_oneblock}
     * says whether the island is a OneBlock island; every other name is one of {@link #values}, and is
     * empty for an island that is not one.
     *
     * @param name what follows {@code oneblock_}
     * @return the value, or null for a name that is not a OneBlock placeholder
     */
    public @Nullable String placeholder(@Nullable OfflinePlayer player, UUID islandId, String name) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Optional<OneBlockProgressPort.OneBlockIsland> held = service.inMemory(IslandId.of(islandId));
        if (name.equals("is_oneblock")) {
            return Boolean.toString(held.isPresent());
        }
        Player online = player == null ? null : player.getPlayer();
        Map<String, String> values = values(
                online,
                service.phasesInMemory(IslandId.of(islandId)).orElse(service.phases()),
                held.map(OneBlockProgressPort.OneBlockIsland::blocksBroken).orElse(0L));
        if (!values.containsKey(name)) {
            return null;
        }
        return held.isPresent() ? values.get(name) : "";
    }
}
