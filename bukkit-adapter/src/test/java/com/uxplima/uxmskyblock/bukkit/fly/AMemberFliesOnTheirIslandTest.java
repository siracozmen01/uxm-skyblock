package com.uxplima.uxmskyblock.bukkit.fly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause;
import com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is fly} flies a member on their own island, and only there.
 *
 * <p>SuperiorSkyblock2 and IridiumSkyblock each let a team fly at home; this plugin had no flight at all.
 */
@SuppressWarnings({"deprecation", "removal"})
class AMemberFliesOnTheirIslandTest extends MockBukkitHarness {

    private static final UpgradeId WINGS = UpgradeId.of("island_fly");

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock owner;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock member;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    private final Map<UUID, Integer> tiers = new HashMap<>();
    private final List<String> fired = new ArrayList<>();
    private int lookups;
    private Instant now = Instant.parse("2026-10-14T12:00:00Z");

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        owner = createPlayer("Owner");
        member = createPlayer("Member");
        island = Island.create(
                        IslandId.of(UUID.randomUUID()),
                        IslandBounds.fromCenterAndRadius(0, 0, 32),
                        new PlayerUuid(owner.getUniqueId()),
                        new ProfileId(owner.getUniqueId()),
                        Instant.now())
                .addMember(new IslandMember(
                        new PlayerUuid(member.getUniqueId()),
                        new ProfileId(member.getUniqueId()),
                        IslandRole.MEMBER,
                        Instant.now()));
        owner.setLocation(new Location(world, 5, 100, 5));
        member.setLocation(new Location(world, 5, 100, 5));
    }

    private IslandFly fly(IslandFlyConfiguration config) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new IslandFly(
                config,
                at -> {
                    lookups++;
                    return world.equals(at.getWorld()) && island.bounds().contains(at.getBlockX(), at.getBlockZ())
                            ? Optional.of(island)
                            : Optional.empty();
                },
                uuid -> Optional.of(new ProfileId(uuid)),
                (id, upgrade) -> upgrade.equals(WINGS) ? tiers.getOrDefault(id.value(), 0) : 0,
                Messages.bundled(),
                (interaction, player) -> fired.add(interaction + " " + player.getName()),
                clock);
    }

    private static IslandFlyConfiguration shipped() {
        return new IslandFlyConfiguration(true, null, "", true);
    }

    private static String said(PlayerMock player) {
        return PlainTextComponentSerializer.plainText()
                .serialize(Objects.requireNonNull(player.nextComponentMessage()));
    }

    private static EntityDamageEvent fall(PlayerMock player) {
        return new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 4.0);
    }

    @Test
    @DisplayName("A member flies on their island by /is fly, and comes down by it again")
    void aMemberFliesAndComesDown() {
        IslandFly fly = fly(shipped());

        fly.toggle(member);
        assertThat(member.getAllowFlight()).isTrue();
        assertThat(member.isFlying()).isTrue();
        assertThat(fly.flies(member)).isTrue();
        assertThat(said(member)).contains("You fly");

        fly.toggle(member);
        assertThat(member.getAllowFlight()).isFalse();
        assertThat(fly.flies(member)).isFalse();
        assertThat(said(member)).contains("You came down");
        assertThat(fired).containsExactly("island-fly-on Member", "island-fly-off Member");
    }

    @Test
    @DisplayName(
            "A visitor, off the island, a role without it, a missing node, an unbought upgrade and the switch refuse")
    void eachRuleRefuses() {
        PlayerMock visitor = createPlayer("Visitor");
        visitor.setLocation(new Location(world, 5, 100, 5));
        fly(shipped()).toggle(visitor);
        assertThat(visitor.getAllowFlight()).isFalse();
        assertThat(said(visitor)).contains("only on an island you belong to");

        member.setLocation(new Location(world, 500, 100, 500));
        fly(shipped()).toggle(member);
        assertThat(said(member)).describedAs("off the island").contains("only on an island you belong to");
        member.setLocation(new Location(world, 5, 100, 5));

        island = island.addMember(new IslandMember(
                new PlayerUuid(member.getUniqueId()),
                new ProfileId(member.getUniqueId()),
                new IslandRole("GROUNDED", 400, "Grounded", EnumSet.of(IslandPermission.BLOCK_BREAK), false),
                Instant.now()));
        fly(shipped()).toggle(member);
        assertThat(member.getAllowFlight()).isFalse();
        assertThat(said(member)).contains("Your role on this island may not fly");

        IslandFly noded = fly(new IslandFlyConfiguration(true, null, "skyblock.wings", true));
        noded.toggle(owner);
        assertThat(owner.getAllowFlight()).isFalse();
        assertThat(said(owner)).contains("You may not fly on islands");
        owner.addAttachment(MockBukkit.createMockPlugin(), "skyblock.wings", true);
        noded.toggle(owner);
        assertThat(owner.getAllowFlight()).describedAs("holding the node").isTrue();
        noded.toggle(owner);
        owner.nextComponentMessage();
        owner.nextComponentMessage();

        IslandFly bought = fly(new IslandFlyConfiguration(true, WINGS, "", true));
        bought.toggle(owner);
        assertThat(owner.getAllowFlight()).isFalse();
        assertThat(said(owner)).contains("has not bought flight");
        tiers.put(island.id().value(), 1);
        bought.toggle(owner);
        assertThat(owner.getAllowFlight()).describedAs("a tier bought").isTrue();

        PlayerMock other = createPlayer("Other");
        other.setLocation(new Location(world, 5, 100, 5));
        fly(new IslandFlyConfiguration(false, null, "", true)).toggle(other);
        assertThat(said(other)).contains("turned off");
    }

    @Test
    @DisplayName("The owner flies whatever their stored role says, since a role kept before the permission lacks it")
    void theOwnerAlwaysMay() {
        island = island.addMember(new IslandMember(
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(owner.getUniqueId()),
                new IslandRole("OWNER", 1000, "Owner", EnumSet.of(IslandPermission.BLOCK_BREAK), true),
                Instant.now()));

        fly(shipped()).toggle(owner);

        assertThat(owner.getAllowFlight()).isTrue();
    }

    @Test
    @DisplayName("Flying off the island sets the player down and spares the fall that follows, once and for a while")
    void leavingSetsThemDown() {
        IslandFly fly = fly(shipped());
        fly.toggle(member);
        member.nextComponentMessage();

        fly.onMove(new PlayerMoveEvent(member, new Location(world, 5, 100, 5), new Location(world, 6, 100, 5)));
        assertThat(fly.flies(member)).describedAs("still on the island").isTrue();
        int read = lookups;
        fly.onMove(
                new PlayerMoveEvent(member, new Location(world, 6.1, 100, 5.1), new Location(world, 6.7, 100.4, 5.6)));
        assertThat(lookups)
                .describedAs("a move inside one block reads no island")
                .isEqualTo(read);

        fly.onMove(new PlayerMoveEvent(member, new Location(world, 32, 100, 5), new Location(world, 40, 100, 5)));
        assertThat(fly.flies(member)).isFalse();
        assertThat(member.getAllowFlight()).isFalse();
        assertThat(said(member)).contains("you were set down");
        assertThat(fired).endsWith("island-fly-lost Member");

        EntityDamageEvent fall = fall(member);
        fly.onFall(fall);
        assertThat(fall.isCancelled()).isTrue();
        EntityDamageEvent again = fall(member);
        fly.onFall(again);
        assertThat(again.isCancelled()).describedAs("spared once").isFalse();

        fly.toggle(member);
        assertThat(fly.flies(member)).isTrue();
        fly.arrived(new Arrival(member, null, new Location(world, 900, 100, 900), ArrivalCause.TELEPORT, false));
        assertThat(fly.flies(member))
                .describedAs("a teleport away sets them down too")
                .isFalse();
        now = now.plus(IslandFly.SPARED_FOR).plusSeconds(1);
        EntityDamageEvent late = fall(member);
        fly.onFall(late);
        assertThat(late.isCancelled()).describedAs("past the while").isFalse();
    }

    @Test
    @DisplayName("Wings given before a stop with no quit are taken back on joining, and others' are left alone")
    void wingsLeftByAStopAreTakenBack() {
        IslandFly before = fly(shipped());
        before.toggle(member);
        assertThat(member.getPersistentDataContainer().has(IslandFly.MARK)).isTrue();

        IslandFly after = fly(shipped());
        after.onJoin(new org.bukkit.event.player.PlayerJoinEvent(member, Component.empty()));
        assertThat(member.getAllowFlight())
                .describedAs("the server stopped with them aloft")
                .isFalse();
        assertThat(member.getPersistentDataContainer().has(IslandFly.MARK)).isFalse();

        owner.setAllowFlight(true);
        after.onJoin(new org.bukkit.event.player.PlayerJoinEvent(owner, Component.empty()));
        assertThat(owner.getAllowFlight())
                .describedAs("wings given by another, unmarked")
                .isTrue();

        after.toggle(member);
        after.onJoin(new org.bukkit.event.player.PlayerJoinEvent(member, Component.empty()));
        assertThat(member.getAllowFlight())
                .describedAs("flying by this one now")
                .isTrue();
        after.toggle(member);
        assertThat(member.getPersistentDataContainer().has(IslandFly.MARK))
                .describedAs("coming down takes the mark off")
                .isFalse();
    }

    @Test
    @DisplayName("An island whose upgrades are still read asks to try again, and keeps a flier up meanwhile")
    void anUnreadIsland() {
        tiers.put(island.id().value(), -1);
        IslandFly bought = fly(new IslandFlyConfiguration(true, WINGS, "", true));
        bought.toggle(owner);
        assertThat(owner.getAllowFlight()).isFalse();
        assertThat(said(owner)).contains("still being read");

        tiers.put(island.id().value(), 1);
        bought.toggle(owner);
        assertThat(bought.flies(owner)).isTrue();
        tiers.put(island.id().value(), -1);
        bought.onMove(new PlayerMoveEvent(owner, new Location(world, 5, 100, 5), new Location(world, 7, 100, 5)));
        assertThat(bought.flies(owner))
                .describedAs("a purchase forgot the tiers for a moment")
                .isTrue();
        tiers.put(island.id().value(), 0);
        bought.onMove(new PlayerMoveEvent(owner, new Location(world, 7, 100, 5), new Location(world, 9, 100, 5)));
        assertThat(bought.flies(owner)).describedAs("known, and none bought").isFalse();
    }

    @Test
    @DisplayName("With the fall not spared, a player set down for leaving takes it")
    void aFallNotSpared() {
        IslandFly fly = fly(new IslandFlyConfiguration(true, null, "", false));
        fly.toggle(member);
        fly.onMove(new PlayerMoveEvent(member, new Location(world, 32, 100, 5), new Location(world, 40, 100, 5)));

        EntityDamageEvent fall = fall(member);
        fly.onFall(fall);
        assertThat(fall.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Wings from elsewhere are left alone: a flier already is told so, and creative keeps its own")
    void othersWingsAreLeftAlone() {
        IslandFly fly = fly(shipped());
        member.setAllowFlight(true);
        fly.toggle(member);
        assertThat(fly.flies(member)).isFalse();
        assertThat(said(member)).contains("You can fly already");
        member.setAllowFlight(false);

        owner.setGameMode(GameMode.CREATIVE);
        owner.setAllowFlight(false);
        fly.toggle(owner);
        assertThat(fly.flies(owner)).isTrue();
        fly.onQuit(new PlayerQuitEvent(owner, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(owner.getAllowFlight())
                .describedAs("creative keeps its own wings")
                .isTrue();
        assertThat(fly.flies(owner)).isFalse();

        fly.toggle(member);
        fly.onQuit(new PlayerQuitEvent(member, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(member.getAllowFlight())
                .describedAs("a leaving survival flier is set down")
                .isFalse();
    }

    @Test
    @DisplayName("/is fly reaches the fly on the player's own thread, and says so when the server runs none")
    void theCommandFlies() throws Exception {
        com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler =
                org.mockito.Mockito.mock(com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort.class);
        List<String> threads = new ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
                    threads.add(call.getArgument(0).toString());
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(
                        org.mockito.ArgumentMatchers.any(PlayerUuid.class),
                        org.mockito.ArgumentMatchers.any(Runnable.class));
        com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree tree =
                new com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree(
                        org.mockito.Mockito.mock(
                                com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase.class),
                        org.mockito.Mockito.mock(
                                com.uxplima.uxmskyblock.core.application.island.IslandLocationService.class),
                        org.mockito.Mockito.mock(com.uxplima.uxmskyblock.core.application.flag.IslandFlagService.class),
                        org.mockito.Mockito.mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankService.class),
                        org.mockito.Mockito.mock(
                                com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort.class),
                        org.mockito.Mockito.mock(
                                com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService.class),
                        org.mockito.Mockito.mock(
                                com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort.class),
                        new com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog(),
                        org.mockito.Mockito.mock(com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine.class),
                        protectionListenerWithIndex(),
                        org.mockito.Mockito.mock(com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator.class),
                        scheduler,
                        Messages.bundled(),
                        com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration.defaults(),
                        com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-1"),
                        "world");
        com.mojang.brigadier.CommandDispatcher<io.papermc.paper.command.brigadier.CommandSourceStack> players =
                new com.mojang.brigadier.CommandDispatcher<>();
        players.register(tree.buildRoot());
        io.papermc.paper.command.brigadier.CommandSourceStack source =
                org.mockito.Mockito.mock(io.papermc.paper.command.brigadier.CommandSourceStack.class);
        org.mockito.Mockito.when(source.getSender()).thenReturn(member);

        players.execute("island fly", source);
        assertThat(said(member)).describedAs("no fly on this server").contains("turned off");

        threads.clear();
        tree.useIslandFly(fly(shipped()));
        players.execute("island fly", source);
        assertThat(member.getAllowFlight()).isTrue();
        assertThat(threads).containsExactly(new PlayerUuid(member.getUniqueId()).toString());
    }

    private static com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener protectionListenerWithIndex() {
        com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener listener =
                org.mockito.Mockito.mock(com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener.class);
        org.mockito.Mockito.when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex());
        return listener;
    }
}
