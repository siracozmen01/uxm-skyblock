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
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
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
    private final List<String> fired = new ArrayList<>();
    private final List<String> counted = new ArrayList<>();

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

                    @Override
                    public void grant(IslandId id, long blocks) {
                        stored.merge(id, blocks, Long::sum);
                    }
                },
                new BoxRules(
                        5,
                        2,
                        50,
                        Map.of(),
                        List.of("minecraft:recipes/"),
                        com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(2)));
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
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        listener = new BoxedListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(java.util.Map.of(
                        "boxed-grew",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe grew %blocks% %size%")),
                        "boxed-moved",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe moved %x% %z%")))),
                new InteractionEffectPlayer(),
                uuid -> Optional.of(profile),
                id -> id.equals(profile) ? Optional.of(islandId) : Optional.empty(),
                changed::add,
                Set.of("boxed"),
                BYPASS);
    }

    @Test
    @DisplayName(
            "Making the island Boxed records it, lays its land, and only then moves its edge and lets it be played")
    void theStartRecordsTheIsland() throws Exception {
        StarterSchematicEngine engine = new StarterSchematicEngine();
        List<String> order = new java.util.ArrayList<>();
        java.util.concurrent.CompletableFuture<Void> laid = new java.util.concurrent.CompletableFuture<>();
        engine.actions()
                .register(new BoxedStart(
                        service,
                        scheduler,
                        id -> {
                            changed.add(id);
                            order.add("edge");
                        },
                        id -> {
                            order.add("land at " + service.radius(id).orElse(-1));
                            return laid;
                        }));

        java.util.concurrent.CompletableFuture<Void> built =
                engine.build(new IslandStart(boxedWorld, islandId, 0, 70, 0, preset()), List.of(BoxedStart.ACTION));

        assertThat(built).describedAs("the land is not laid yet").isNotDone();
        assertThat(order).containsExactly("land at 5");
        laid.completeExceptionally(new IllegalStateException("a land that could not be laid"));
        built.join();
        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(changed).containsExactly(islandId);
        assertThat(order)
                .describedAs("a land that failed still lets the island be played")
                .containsExactly("land at 5", "edge");
    }

    @Test
    @DisplayName("An island that could not be made Boxed is let be played, with no land laid and no edge moved")
    void aStartThatFails() throws Exception {
        BoxedIslandsPort broken = mock(BoxedIslandsPort.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("the database is down"))
                .when(broken)
                .add(islandId);
        List<IslandId> laid = new ArrayList<>();
        BoxedStart start = new BoxedStart(
                new BoxedService(
                        broken,
                        new BoxRules(
                                5,
                                2,
                                50,
                                Map.of(),
                                List.of(),
                                com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(2))),
                scheduler,
                changed::add,
                id -> {
                    laid.add(id);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });

        assertThat(start.applyThen(new IslandStart(boxedWorld, islandId, 0, 70, 0, preset())))
                .isDone();
        assertThat(laid).isEmpty();
        assertThat(changed).isEmpty();
    }

    @Test
    @DisplayName("An advancement grows the box once for the island, and a recipe grows nothing")
    void anAdvancementGrowsTheBox() {
        listener.useMissions((who, trigger, target, amount) -> counted.add(trigger + " " + target + " " + amount));
        service.start(islandId);
        PlayerMock player = createPlayer("Explorer");

        listener.onAdvancement(done(player, "story/mine_stone"));
        listener.onAdvancement(done(createPlayer("Friend"), "story/mine_stone"));
        listener.onAdvancement(done(player, "recipes/misc/bread"));

        assertThat(service.radius(islandId)).hasValue(7);
        assertThat(changed).containsExactly(islandId);
        assertThat(said(player))
                .anySatisfy(line -> assertThat(line).contains("2").contains("15×15"));
        assertThat(fired).describedAs("how far it grew and how wide it is now").containsExactly("grew 2 15");
        assertThat(counted)
                .describedAs("how far it grew counts for the missions")
                .containsExactly("BOXED_GROW * 2");
    }

    @Test
    @DisplayName("A visitor on another island earns nothing there, unless the operator lets visitors earn")
    void aVisitorEarnsNothing() {
        service.start(islandId);
        IslandId other = IslandId.of(UUID.randomUUID());
        islands.cacheIsland(
                Island.create(
                        other,
                        IslandBounds.fromCenterAndRadius(1000, 0, 5),
                        PlayerUuid.of(UUID.randomUUID()),
                        ProfileId.of(UUID.randomUUID()),
                        Instant.now()),
                "boxed");
        PlayerMock player = createPlayer("Visitor");
        player.teleport(new Location(boxedWorld, 1000.5, 70, 0.5));

        listener.onAdvancement(done(player, "story/mine_stone"));

        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(changed).isEmpty();
        assertThat(said(player)).singleElement().asString().contains("another island");

        player.teleport(new Location(boxedWorld, 1.5, 70, 1.5));
        listener.onAdvancement(done(player, "story/mine_stone"));
        assertThat(service.radius(islandId)).describedAs("on their own island").hasValue(7);

        player.teleport(new Location(skyblock, 500.5, 70, 500.5));
        listener.onAdvancement(done(player, "story/upgrade_tools"));
        assertThat(service.radius(islandId)).describedAs("on no island at all").hasValue(9);

        player.teleport(new Location(boxedWorld, 1000.5, 70, 0.5));
        listener.letVisitorsEarn(true);
        listener.onAdvancement(done(player, "story/smelt_iron"));
        assertThat(service.radius(islandId)).hasValue(11);
        assertThat(said(player)).noneSatisfy(line -> assertThat(line).contains("another island"));
    }

    @Test
    @DisplayName(
            "A pearl thrown from inside the box by its owner moves the box to where it lands, as far as a box reaches")
    void aPearlMovesTheBox() {
        service.start(islandId);
        PlayerMock owner = createPlayer("Owner");

        // The owner's role as stored before the box could be moved, which does not name it.
        islands.cacheIsland(
                islands.findIslandAt(new Location(boxedWorld, 0, 70, 0))
                        .orElseThrow()
                        .addMember(new IslandMember(
                                PlayerUuid.of(owner.getUniqueId()),
                                profile,
                                new IslandRole(
                                        "OWNER",
                                        1000,
                                        "Owner",
                                        java.util.EnumSet.of(IslandPermission.BLOCK_BREAK),
                                        true),
                                Instant.now())),
                "boxed");
        List<String> spawns = new ArrayList<>();
        listener.useSpawnMover((id, box, x, y, z) -> spawns.add(id.equals(islandId) + " " + box + " " + x + " " + z));
        boolean tooFar = pearl(owner, 1, 1, 48, 0);
        assertThat(tooFar).isTrue();
        assertThat(said(owner)).singleElement().asString().contains("cannot reach that far");
        assertThat(service.offset(islandId)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset.NONE);

        boolean moved = pearl(owner, 2, -1, 20, -7);
        assertThat(moved).isFalse();
        assertThat(service.offset(islandId)).isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(20, -7));
        assertThat(changed).containsExactly(islandId);
        assertThat(said(owner)).singleElement().asString().contains("moved to where your pearl landed");
        assertThat(spawns)
                .describedAs("the spawn is asked to follow into the moved box, to where the pearl landed")
                .containsExactly("true " + IslandBounds.fromCenterAndRadius(20, -7, 5) + " 20.5 -6.5");
        assertThat(fired)
                .describedAs("the effect list, with how far the box lies")
                .containsExactly("moved 20 -7");
        assertThat(service.reach(islandId)).hasValue(25);
        // What the border service does once told the box changed: the island's square reaches round the moved box.
        islands.cacheIsland(
                Island.create(
                        islandId,
                        IslandBounds.fromCenterAndRadius(0, 0, 25),
                        PlayerUuid.of(UUID.randomUUID()),
                        profile,
                        Instant.now()),
                "boxed");

        assertThat(listener.outsideEveryBox(owner, new Location(boxedWorld, 1, 70, 1)))
                .describedAs("where the box stood")
                .isTrue();
        assertThat(listener.outsideEveryBox(owner, new Location(boxedWorld, 24, 70, -3)))
                .isFalse();
        assertThat(BoxedListener.boxOf(
                        service,
                        islands.findIslandAt(new Location(boxedWorld, 0, 70, 0)).orElseThrow()))
                .hasValue(IslandBounds.fromCenterAndRadius(20, -7, 5));

        boolean fromOutside = pearl(owner, 1, 1, 22, -7);
        assertThat(fromOutside).describedAs("thrown from outside the box now").isFalse();
        assertThat(service.offset(islandId)).isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(20, -7));
    }

    @Test
    @DisplayName("The border is drawn round the box where a pearl moved it")
    void theBorderFollowsAMovedBox() {
        service.start(islandId);
        service.move(islandId, new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(3, -4));
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(boxedWorld);
        when(player.getLocation()).thenReturn(new Location(boxedWorld, 1.5, 70, 1.5));
        WorldBorder border = mock(WorldBorder.class);

        new BoxedBorders(service, islands, scheduler, Duration.ofSeconds(1), () -> border).show(player);

        verify(border).setCenter(3.5, -3.5);
        verify(border).setSize(11.0);
    }

    @Test
    @DisplayName(
            "A pearl of a player whose role may not move the box moves nothing, and never carries them out of every box")
    void aPearlWithoutTheRole() {
        IslandId other = IslandId.of(UUID.randomUUID());
        islands.cacheIsland(
                Island.create(
                        other,
                        IslandBounds.fromCenterAndRadius(1000, 0, 5),
                        PlayerUuid.of(UUID.randomUUID()),
                        ProfileId.of(UUID.randomUUID()),
                        Instant.now()),
                "boxed");
        service.start(other);
        PlayerMock guest = createPlayer("Guest");

        assertThat(pearl(guest, 1000, 0, 1003, 2)).describedAs("inside the box").isFalse();
        assertThat(pearl(guest, 1000, 0, 1030, 0))
                .describedAs("out of every box")
                .isTrue();
        assertThat(said(guest)).singleElement().asString().contains("outside your box");
        assertThat(service.offset(other)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset.NONE);
        // A role of the operator's that may change the biome, but not move the box.
        IslandRole gardener =
                new IslandRole("GARDENER", 300, "Gardener", java.util.EnumSet.of(IslandPermission.BIOME_CHANGE), false);
        islands.cacheIsland(
                Island.create(
                                other,
                                IslandBounds.fromCenterAndRadius(1000, 0, 5),
                                PlayerUuid.of(UUID.randomUUID()),
                                ProfileId.of(UUID.randomUUID()),
                                Instant.now())
                        .addMember(
                                new IslandMember(PlayerUuid.of(guest.getUniqueId()), profile, gardener, Instant.now())),
                "boxed");
        assertThat(pearl(guest, 1000, 0, 1003, 2)).isFalse();
        assertThat(service.offset(other)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset.NONE);
        assertThat(listener.refuses(new com.uxplima.uxmskyblock.bukkit.arrival.Arrival(
                        guest,
                        new Location(boxedWorld, 1000, 70, 0),
                        new Location(boxedWorld, 1030, 70, 0),
                        com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.TELEPORT,
                        false)))
                .describedAs("only a pearl is judged here")
                .isFalse();
        assertThat(listener.refuses(new com.uxplima.uxmskyblock.bukkit.arrival.Arrival(
                        guest,
                        null,
                        new Location(boxedWorld, 1030, 70, 0),
                        com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.PEARL,
                        false)))
                .describedAs("a pearl with nowhere it came from")
                .isFalse();
    }

    @Test
    @DisplayName("An advancement is read with how deep in its tab it lies and whether the screen shows it")
    void theFactsOfAnAdvancement() {
        Advancement root = mock(Advancement.class);
        Advancement middle = mock(Advancement.class);
        Advancement deep = mock(Advancement.class);
        when(middle.getParent()).thenReturn(root);
        when(deep.getParent()).thenReturn(middle);
        when(deep.getKey()).thenReturn(NamespacedKey.minecraft("story/deep"));
        when(root.getKey()).thenReturn(NamespacedKey.minecraft("story/root"));
        when(root.getDisplay()).thenReturn(mock(io.papermc.paper.advancement.AdvancementDisplay.class));

        assertThat(BoxedListener.facts(deep))
                .isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                        "minecraft:story/deep", 2, false));
        assertThat(BoxedListener.facts(root))
                .isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                        "minecraft:story/root", 0, true));
    }

    @Test
    @DisplayName("An advancement handed back as a player crosses between groups of worlds grows nothing")
    void anAdvancementHandedBackGrowsNothing() {
        service.start(islandId);
        PlayerMock player = createPlayer("Traveller");
        server.addSimpleWorld("elsewhere");
        com.uxplima.uxmskyblock.bukkit.state.PlayerStates states =
                com.uxplima.uxmskyblock.bukkit.state.CarriedStateFixture.started(
                        player,
                        java.util.List.of(player.getWorld().getName(), "elsewhere"),
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
        List<IslandId> seen = new java.util.ArrayList<>();
        borders.whenShown(seen::add);

        borders.show(player);
        borders.show(player);
        assertThat(seen).describedAs("told once, as the box is shown anew").containsExactly(islandId);

        verify(player, org.mockito.Mockito.times(1)).setWorldBorder(border);
        verify(border).setSize(11.0);
        verify(border).setCenter(0.5, 0.5);
        service.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts("minecraft:story/mine_stone", 1, true));
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
        assertThat(seen).describedAs("and again as it grows").containsExactly(islandId, islandId);

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
        assertThat(written.rules()
                        .blocksFor(new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:story/mine_diamond", 1, true)))
                .isEqualTo(9);
        assertThat(BoxedConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("box { start-radius = 0 }"))
                        .rules())
                .isEqualTo(BoxRules.shipped());
        BoxedConfiguration scored = BoxedConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("scoring { by = flat, root = 1, recipes = 2, hidden = 3 }\nvisitors-earn = true"));
        assertThat(scored.rules().scoring())
                .isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth(false, 1, 2, 3));
        assertThat(scored.visitorsEarn()).isTrue();
        assertThat(BoxedConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("scoring { root = 4 }"))
                        .rules()
                        .scoring())
                .isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth(true, 4, 0, 0));
        for (String wrong : List.of("scoring { by = steep }", "scoring { hidden = -1 }")) {
            assertThat(BoxedConfiguration.load(
                                    HoconConfigurationLoader.builder().buildAndLoadString(wrong))
                            .rules())
                    .describedAs(wrong)
                    .isEqualTo(BoxRules.shipped());
        }
        assertThat(BoxedConfiguration.defaultConfiguration().visitorsEarn()).isFalse();
    }

    private BlockBreakEvent breakAt(PlayerMock player, World world, int x, int z) {
        Block block = world.getBlockAt(x, 70, z);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBreak(event);
        return event;
    }

    /** Whether the box refuses a pearl from one place to another, as the arrival watch asks it. */
    private boolean pearl(Player player, int fromX, int fromZ, int toX, int toZ) {
        return listener.refuses(new com.uxplima.uxmskyblock.bukkit.arrival.Arrival(
                player,
                new Location(boxedWorld, fromX + 0.5, 70, fromZ + 0.5),
                new Location(boxedWorld, toX + 0.5, 70, toZ + 0.5),
                com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.PEARL,
                false));
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
