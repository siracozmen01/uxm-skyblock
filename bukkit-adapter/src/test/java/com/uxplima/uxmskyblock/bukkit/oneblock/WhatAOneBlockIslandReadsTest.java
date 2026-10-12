package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A OneBlock island's players can read where it stands: in the panel {@code /is oneblock} opens, in
 * chat when the panel's file is gone, and in {@code %skyblock_oneblock_<name>%} placeholders.
 */
class WhatAOneBlockIslandReadsTest extends MockBukkitHarness {

    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new ConcurrentHashMap<>();
    private final IslandStoragePort islands = mock(IslandStoragePort.class);
    private final IslandId islandId = IslandId.of(UUID.randomUUID());

    @SuppressWarnings("NullAway.Init")
    private OneBlockService service;

    @SuppressWarnings("NullAway.Init")
    private OneBlockPanel panel;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private ProfileId profile;

    @BeforeEach
    void setUpPanel() {
        player = createPlayer("Miner");
        profile = new ProfileId(player.getUniqueId());
        service = new OneBlockService(memory(), phases(OneBlockPhases.AfterTheLast.STAY), new SplittableRandom(1));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        panel = new OneBlockPanel(service, islands, scheduler, Messages.bundled(), uuid -> Optional.of(profile));
    }

    @Test
    @DisplayName("The values say which phase, how far into it and how many blocks in all")
    void theValuesSayWhereTheIslandStands() {
        Map<String, String> values = panel.values(player, 4);

        assertThat(values)
                .containsEntry("phase", "Underground")
                .containsEntry("phase_id", "underground")
                .containsEntry("phase_number", "2")
                .containsEntry("phase_count", "2")
                .containsEntry("blocks_broken", "4")
                .containsEntry("phase_blocks", "1")
                .containsEntry("phase_length", "5")
                .containsEntry("phase_left", "4")
                .containsEntry("phase_percent", "20");
    }

    @Test
    @DisplayName("An island that stays in the last phase has none of it left, and is all the way through")
    void pastTheLastPhaseNothingIsLeft() {
        Map<String, String> values = panel.values(player, 20);

        assertThat(values)
                .containsEntry("phase_number", "2")
                .containsEntry("phase_blocks", "17")
                .containsEntry("phase_left", "0")
                .containsEntry("phase_percent", "100");
    }

    @Test
    @DisplayName("A placeholder reads memory only, and says nothing about an island that is no OneBlock island")
    void placeholdersReadMemory() {
        assertThat(panel.placeholder(player, islandId.value(), "is_oneblock")).isEqualTo("false");
        assertThat(panel.placeholder(player, islandId.value(), "phase")).isEmpty();

        service.start(islandId, 0, 100, 0);
        service.onBreak(islandId);
        service.onBreak(islandId);

        assertThat(panel.placeholder(player, islandId.value(), "is_oneblock")).isEqualTo("true");
        assertThat(panel.placeholder(player, islandId.value(), "phase")).isEqualTo("Plains");
        assertThat(panel.placeholder(player, islandId.value(), "phase_left")).isEqualTo("1");
        assertThat(panel.placeholder(player, islandId.value(), "blocks_broken")).isEqualTo("2");
        assertThat(panel.placeholder(player, islandId.value(), "no_such_value")).isNull();
    }

    @Test
    @DisplayName("The placeholder expansion answers oneblock_ names for the player's island")
    void theExpansionAnswers() {
        SkyblockPlaceholderExpansion expansion = new SkyblockPlaceholderExpansion(
                islands,
                mock(IslandBankPort.class),
                mock(IslandUpgradeStoragePort.class),
                mock(IslandLeaderboardPort.class),
                mock(SchedulerPort.class),
                uuid -> Optional.of(profile));
        expansion.cacheData(
                player.getUniqueId(),
                new SkyblockPlaceholderExpansion.CachedPlayerIsland(
                        true, islandId.value(), "owner", 1, 0, 0, Map.of(), System.currentTimeMillis()));
        service.start(islandId, 0, 100, 0);

        assertThat(expansion.onRequest(player, "oneblock_phase_number"))
                .describedAs("nothing answers before OneBlock is wired")
                .isNull();

        expansion.useOneBlock(panel);

        assertThat(expansion.onRequest(player, "oneblock_phase_number")).isEqualTo("1");
        assertThat(expansion.onRequest(player, "ONEBLOCK_IS_ONEBLOCK")).isEqualTo("true");
    }

    @Test
    @DisplayName("Without its menu file the panel says the standing in chat, and a plain island is told so")
    void thePanelFallsBackToChat() {
        when(islands.findIslandIdByProfileId(profile)).thenReturn(Optional.of(islandId));

        panel.open(player);
        assertThat(said()).singleElement().asString().endsWith("Your island is not a OneBlock island.");

        service.start(islandId, 0, 100, 0);
        for (int i = 0; i < 4; i++) {
            service.onBreak(islandId);
        }
        panel.open(player);

        assertThat(said())
                .singleElement()
                .asString()
                .contains("Underground")
                .contains("(2/2)")
                .contains("1 of 5");
    }

    @Test
    @DisplayName("The shipped menu file and its words name only values the panel gives")
    void theMenuNamesOnlyWhatThePanelGives() throws Exception {
        java.util.Set<String> given = panel.values(player, 0).keySet();
        List<String> named = new ArrayList<>();
        collect(Pattern.compile("%argument_([a-z_]+)%"), resource("menus/island-oneblock.conf"), named);
        for (String language : List.of("messages/messages_en.conf", "messages/messages_tr.conf")) {
            String text = resource(language);
            String block = text.substring(text.indexOf("    oneblock {\n        title"));
            block = block.substring(0, block.indexOf("\n    }"));
            collect(Pattern.compile("<argument_([a-z_]+)>"), block, named);
            assertThat(block).describedAs("%s draws the phase", language).contains("<argument_phase>");
        }

        assertThat(named).isNotEmpty();
        assertThat(given).containsAll(named);
        assertThat(resource("menus/island-oneblock.conf")).contains("@menu.oneblock.title");
    }

    private static void collect(Pattern pattern, String text, List<String> into) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private List<String> said() {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OneBlockPhases phases(OneBlockPhases.AfterTheLast after) {
        return new OneBlockPhases(
                List.of(
                        new OneBlockPhase(
                                "plains", 3, new WeightedPool(Map.of("GRASS_BLOCK", 1.0)), WeightedPool.empty(), 0),
                        new OneBlockPhase(
                                "underground", 5, new WeightedPool(Map.of("STONE", 1.0)), WeightedPool.empty(), 0)),
                after);
    }

    private OneBlockProgressPort memory() {
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId island, int x, int y, int z) {
                stored.put(island, new OneBlockIsland(island, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId island) {
                return Optional.ofNullable(stored.get(island));
            }

            @Override
            public void addBreaks(IslandId island, long breaks) {
                stored.computeIfPresent(
                        island,
                        (id, held) ->
                                new OneBlockIsland(id, held.x(), held.y(), held.z(), held.blocksBroken() + breaks));
            }

            @Override
            public void setBreaks(IslandId island, long count) {
                stored.computeIfPresent(
                        island, (id, held) -> new OneBlockIsland(id, held.x(), held.y(), held.z(), count));
            }
        };
    }
}
