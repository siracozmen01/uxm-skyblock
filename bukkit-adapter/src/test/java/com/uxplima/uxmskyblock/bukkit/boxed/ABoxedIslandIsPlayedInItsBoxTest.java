package com.uxplima.uxmskyblock.bukkit.boxed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A Boxed island is played inside its box: an advancement one of its players makes grows the box once,
 * nobody builds or walks where no box reaches in a Boxed world, and each player is shown their box as
 * a world border of their own.
 */
class ABoxedIslandIsPlayedInItsBoxTest extends MockBukkitHarness {

    private static final String BYPASS = "server.boxed.bypass";

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final ProfileId profile = ProfileId.of(UUID.randomUUID());
    private final Map<IslandId, Long> stored = new HashMap<>();
    private final Set<String> earned = new HashSet<>();
    private final List<IslandId> changed = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World boxedWorld;

    @SuppressWarnings("NullAway.Init")
    private World skyblock;

    @SuppressWarnings("NullAway.Init")
    private BoxedService service;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private BoxedListener listener;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpBox() {
        boxedWorld = server.addSimpleWorld("boxed");
        skyblock = server.addSimpleWorld("skyblock");
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(
                Island.create(
                        islandId,
                        IslandBounds.fromCenterAndRadius(0, 0, 5),
                        PlayerUuid.of(UUID.randomUUID()),
                        profile,
                        Instant.now()),
                "boxed");
        service = new BoxedService(
                new BoxedIslandsPort() {
                    @Override
                    public Map<IslandId, Long> findAll() {
                        return Map.copyOf(stored);
                    }

                    @Override
                    public OptionalLong find(IslandId id) {
                        Long blocks = stored.get(id);
                        return blocks == null ? OptionalLong.empty() : OptionalLong.of(blocks);
                    }

                    @Override
                    public void add(IslandId id) {
                        stored.putIfAbsent(id, 0L);
                    }

                    @Override
                    public boolean earn(IslandId id, String advancement, int blocks) {
                        if (!earned.add(id + advancement)) {
                            return false;
                        }
                        stored.merge(id, (long) blocks, Long::sum);
                        return true;
                    }
                },
                new BoxRules(5, 2, 50, Map.of(), List.of("minecraft:recipes/")));
        scheduler = mock(SchedulerPort.class);
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
        listener = new BoxedListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                InteractionEffects.none(),
                new InteractionEffectPlayer(),
                uuid -> Optional.of(profile),
                id -> id.equals(profile) ? Optional.of(islandId) : Optional.empty(),
                changed::add,
                Set.of("boxed"),
                BYPASS);
    }

    @Test
    @DisplayName("Making the island Boxed records it and moves its edge to the starting box")
    void theStartRecordsTheIsland() throws Exception {
        StarterSchematicEngine engine = new StarterSchematicEngine();
        engine.actions().register(new BoxedStart(service, scheduler, changed::add));

        engine.build(new IslandStart(boxedWorld, islandId, 0, 70, 0, preset()), List.of(BoxedStart.ACTION))
                .join();

        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(changed).containsExactly(islandId);
    }

    @Test
    @DisplayName("An advancement grows the box once for the island, and a recipe grows nothing")
    void anAdvancementGrowsTheBox() {
        service.start(islandId);
        PlayerMock player = createPlayer("Explorer");

        listener.onAdvancement(done(player, "story/mine_stone"));
        listener.onAdvancement(done(createPlayer("Friend"), "story/mine_stone"));
        listener.onAdvancement(done(player, "recipes/misc/bread"));

        assertThat(service.radius(islandId)).hasValue(7);
        assertThat(changed).containsExactly(islandId);
        assertThat(said(player))
                .anySatisfy(line -> assertThat(line).contains("2").contains("15×15"));
    }

    @Test
    @DisplayName("An advancement handed back as a player crosses between groups of worlds grows nothing")
    void anAdvancementHandedBackGrowsNothing() {
        service.start(islandId);
        PlayerMock player = createPlayer("Traveller");
        server.addSimpleWorld("elsewhere");
        com.uxplima.uxmskyblock.bukkit.state.PlayerStates states =
                new com.uxplima.uxmskyblock.bukkit.state.PlayerStates(
                        () -> com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration.SHIPPED,
                        () -> java.util.List.of(player.getWorld().getName(), "elsewhere"),
                        new com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec() {
                            @Override
                            public byte[] write(org.bukkit.inventory.ItemStack[] items) {
                                return new byte[0];
                            }

                            @Override
                            public org.bukkit.inventory.ItemStack[] read(byte[] bytes) {
                                return new org.bukkit.inventory.ItemStack[0];
                            }
                        },
                        new com.uxplima.uxmskyblock.bukkit.state.PlayerStates.Advancements() {
                            @Override
                            public java.util.Set<String> earned(Player who) {
                                return java.util.Set.of();
                            }

                            @Override
                            public void earn(Player who, java.util.Set<String> criteria) {
                                listener.onAdvancement(done(who, "story/mine_stone"));
                            }
                        });
        states.arrived(player);

        player.teleport(new org.bukkit.Location(server.getWorld("elsewhere"), 0, 64, 0));
        assertThat(states.arrived(player)).isTrue();

        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(changed).isEmpty();
    }

    @Test
    @DisplayName("In a Boxed world nobody breaks or walks where no box reaches, and elsewhere nothing changes")
    void outsideTheBoxIsClosed() {
        service.start(islandId);
        PlayerMock player = createPlayer("Explorer");

        assertThat(breakAt(player, boxedWorld, 3, 3).isCancelled()).isFalse();
        assertThat(breakAt(player, boxedWorld, 9, 3).isCancelled()).isTrue();
        assertThat(breakAt(player, skyblock, 9, 3).isCancelled())
                .describedAs("another world is not Boxed")
                .isFalse();

        PlayerMoveEvent out = new PlayerMoveEvent(
                player, new Location(boxedWorld, 5.5, 70, 0.5), new Location(boxedWorld, 6.5, 70, 0.5));
        listener.onMove(out);
        assertThat(out.isCancelled()).isTrue();
        PlayerMoveEvent alreadyOut = new PlayerMoveEvent(
                player, new Location(boxedWorld, 20.5, 70, 0.5), new Location(boxedWorld, 21.5, 70, 0.5));
        listener.onMove(alreadyOut);
        assertThat(alreadyOut.isCancelled())
                .describedAs("a player already outside is not held where they stand")
                .isFalse();

        player.addAttachment(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), BYPASS, true);
        assertThat(breakAt(player, boxedWorld, 9, 3).isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A player on a Boxed island is shown the box as a border of their own, and it follows the box")
    void theBorderShowsTheBox() {
        service.start(islandId);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(boxedWorld);
        when(player.getLocation()).thenReturn(new Location(boxedWorld, 1.5, 70, 1.5));
        WorldBorder border = mock(WorldBorder.class);
        BoxedBorders borders = new BoxedBorders(service, islands, scheduler, Duration.ofSeconds(1), () -> border);

        borders.show(player);
        borders.show(player);

        verify(player, org.mockito.Mockito.times(1)).setWorldBorder(border);
        verify(border).setSize(11.0);
        verify(border).setCenter(0.5, 0.5);
        service.earn(islandId, "minecraft:story/mine_stone");
        islands.cacheIsland(
                Island.create(
                        islandId,
                        IslandBounds.fromCenterAndRadius(0, 0, 7),
                        PlayerUuid.of(UUID.randomUUID()),
                        profile,
                        Instant.now()),
                "boxed");
        borders.show(player);
        verify(border).setSize(15.0);

        when(player.getLocation()).thenReturn(new Location(skyblock, 1.5, 70, 1.5));
        when(player.getWorld()).thenReturn(skyblock);
        borders.show(player);
        verify(player).setWorldBorder(null);
    }

    @Test
    @DisplayName("The preset plays Boxed in a world of its own, and the shipped file reads as shipped")
    void thePresetAndTheFile() throws Exception {
        StarterPreset preset = preset();

        assertThat(preset.mode()).isEqualTo(GameModeType.BOXED);
        assertThat(preset.start()).containsExactly(BoxedStart.ACTION);
        assertThat(preset.worldOr("skyblock")).isEqualTo("boxed");
        assertThat(BoxedConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/boxed.conf"))))
                .isEqualTo(BoxedConfiguration.defaultConfiguration());
        BoxedConfiguration written = BoxedConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("box { start-radius = 3 }\nadvancements { \"minecraft:story/mine_diamond\" = 9 }"));
        assertThat(written.rules().startRadius()).isEqualTo(3);
        assertThat(written.rules().blocksFor("minecraft:story/mine_diamond")).isEqualTo(9);
        assertThat(BoxedConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("box { start-radius = 0 }"))
                        .rules())
                .isEqualTo(BoxRules.shipped());
    }

    private BlockBreakEvent breakAt(PlayerMock player, World world, int x, int z) {
        Block block = world.getBlockAt(x, 70, z);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBreak(event);
        return event;
    }

    private static PlayerAdvancementDoneEvent done(Player player, String key) {
        Advancement advancement = mock(Advancement.class);
        when(advancement.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return new PlayerAdvancementDoneEvent(player, advancement, Component.empty());
    }

    private static List<String> said(PlayerMock player) {
        List<String> said = new ArrayList<>();
        for (Component next = player.nextComponentMessage(); next != null; next = player.nextComponentMessage()) {
            said.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return said;
    }

    private StarterPreset preset() throws Exception {
        return PresetConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                .catalogue()
                .findById("boxed")
                .orElseThrow();
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
