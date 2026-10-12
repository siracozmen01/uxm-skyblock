package com.uxplima.uxmskyblock.bukkit.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.bukkit.config.PresetPreview;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player looks at a kind of island from above, as a spectator, for a set time, and is put back where they stood,
 * as they were. They cannot leave the showroom while they look, and a quit during a look is undone when they return.
 */
class APlayerLooksAtAKindBeforeChoosingItTest extends MockBukkitHarness {

    private static final StarterPreset CLASSIC = preset("classic");
    private static final StarterPreset DESERT = preset("desert");

    private final AtomicReference<PresetPreview> settings = new AtomicReference<>(PresetPreview.SHIPPED);
    private final List<String> laid = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private boolean holdTicks;
    private Location spawnPoint;
    private Instant now = Instant.parse("2026-10-12T12:00:00Z");
    private World skyblock;
    private PresetPreviews previews;
    private PlayerMock ada;
    private SchedulerPort scheduler;
    private Clock clock;
    private Location stood;

    private static StarterPreset preset(String id) {
        return new StarterPreset(
                id,
                id,
                id,
                "schematics/" + id + ".schem",
                IslandBiome.PLAINS,
                GameModeType.SKYBLOCK,
                List.of(StarterPreset.PLATFORM));
    }

    @BeforeEach
    void setUp() {
        skyblock = server.addSimpleWorld("skyblock");
        World spawn = server.addSimpleWorld("spawn");
        spawnPoint = new Location(spawn, 0.5, 64, 0.5);
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    if (holdTicks) {
                        nextTick.add(call.getArgument(1, Runnable.class));
                    } else {
                        call.getArgument(1, Runnable.class).run();
                    }
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(call -> later.add(call.getArgument(1, Runnable.class)))
                .when(scheduler)
                .asyncAfter(any(Duration.class), any(Runnable.class));
        clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        previews = new PresetPreviews(
                settings::get,
                () -> List.of(DESERT, CLASSIC),
                server::getWorld,
                () -> "skyblock",
                () -> spawnPoint,
                (preset, world, x, y, z) -> {
                    laid.add(preset.id() + " " + x + " " + y + " " + z);
                    world.getBlockAt(x, y, z).setType(Material.GRASS_BLOCK);
                    return CompletableFuture.completedFuture(null);
                },
                scheduler,
                Messages.bundled(),
                clock);
        ada = createMovingPlayer("Ada");
        stood = new Location(spawn, 10.5, 70, -3.5, 45f, 5f);
        ada.teleport(stood);
        ada.setGameMode(GameMode.SURVIVAL);
    }

    private String said() {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = ada.nextComponentMessage()) != null) {
            all.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(line))
                    .append('\n');
        }
        return all.toString();
    }

    @Test
    @DisplayName(
            "A look lays the kind's showroom once, puts the player over it as a spectator, and back after its time")
    void aLook() {
        assertThat(previews.show(ada, CLASSIC)).isTrue();

        assertThat(laid)
                .describedAs("classic is first by id, so its showroom is the first")
                .containsExactly("classic -1000000 100 -1000000");
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SPECTATOR);
        assertThat(ada.getLocation().getWorld()).isEqualTo(skyblock);
        assertThat(ada.getLocation().getX()).isEqualTo(-1_000_000 + 0.5);
        assertThat(ada.getLocation().getY()).isEqualTo(100 + PresetPreviews.ABOVE);
        assertThat(ada.getLocation().getZ()).isEqualTo(-1_000_000 + 0.5 - PresetPreviews.BACK);
        assertThat(previews.looking(ada)).isTrue();
        assertThat(said()).contains("classic").contains("20s");

        later.removeFirst().run();

        assertThat(previews.looking(ada)).isFalse();
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(ada.getLocation()).isEqualTo(stood);
        assertThat(ada.getPersistentDataContainer().has(PresetPreviews.RETURN, PersistentDataType.STRING))
                .isFalse();

        now = now.plusSeconds(31);
        PlayerMock bo = createMovingPlayer("Bo");
        bo.teleport(stood);
        previews.show(bo, CLASSIC);
        previews.show(ada, DESERT);
        assertThat(laid)
                .describedAs("classic stands already; desert stands a spacing further along")
                .containsExactly("classic -1000000 100 -1000000", "desert -998976 100 -1000000");
    }

    @Test
    @DisplayName("A second look waits out the cooldown, and none is had while looks are off")
    void cooldownAndOff() {
        previews.show(ada, CLASSIC);
        later.removeFirst().run();
        said();

        assertThat(previews.show(ada, CLASSIC)).isFalse();
        assertThat(said()).contains("look again");
        now = now.plusSeconds(31);
        settings.set(new PresetPreview(
                false,
                Duration.ofSeconds(20),
                48,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                "",
                -1_000_000,
                -1_000_000,
                100,
                1024));
        assertThat(previews.show(ada, CLASSIC)).isFalse();
        assertThat(said()).contains("turned off");
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("No jump to another player during a look; a teleport that goes through ends it, one refused does not")
    void teleports() {
        org.bukkit.plugin.Plugin plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(previews, plugin);
        server.getPluginManager().registerEvents(new RefusesCommands(), plugin);
        previews.show(ada, CLASSIC);
        Location over = ada.getLocation();

        PlayerTeleportEvent spectate =
                new PlayerTeleportEvent(ada, over, stood, PlayerTeleportEvent.TeleportCause.SPECTATE);
        server.getPluginManager().callEvent(spectate);
        assertThat(spectate.isCancelled()).isTrue();
        assertThat(previews.looking(ada)).isTrue();

        PlayerTeleportEvent refused =
                new PlayerTeleportEvent(ada, over, stood, PlayerTeleportEvent.TeleportCause.COMMAND);
        server.getPluginManager().callEvent(refused);
        assertThat(refused.isCancelled()).isTrue();
        assertThat(previews.looking(ada))
                .describedAs("refused by another plugin: the player is still a spectator at the showroom")
                .isTrue();
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SPECTATOR);

        PlayerTeleportEvent home = new PlayerTeleportEvent(ada, over, stood, PlayerTeleportEvent.TeleportCause.PLUGIN);
        server.getPluginManager().callEvent(home);
        assertThat(home.isCancelled()).isFalse();
        assertThat(previews.looking(ada)).isFalse();
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
    }

    /** Refuses a command's teleport, as a plugin that holds a player in a fight would. */
    public static final class RefusesCommands implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
        public void onTeleport(PlayerTeleportEvent event) {
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
                event.setCancelled(true);
            }
        }
    }

    @Test
    @DisplayName("A player hurt a moment ago, falling or burning cannot look, and can once calm")
    void notInAFight() {
        org.bukkit.event.entity.EntityDamageEvent hit = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        org.mockito.Mockito.when(hit.getEntity()).thenReturn(ada);
        previews.onHurt(hit);

        assertThat(previews.show(ada, CLASSIC)).isFalse();
        assertThat(said()).contains("Not now");
        now = now.plusSeconds(11);
        ada.setFallDistance(3f);
        assertThat(previews.show(ada, CLASSIC)).isFalse();
        ada.setFallDistance(0f);
        ada.setFireTicks(40);
        assertThat(previews.show(ada, CLASSIC)).isFalse();
        ada.setFireTicks(0);
        assertThat(previews.show(ada, CLASSIC)).isTrue();
    }

    @Test
    @DisplayName("The end of a look cut short does not end the next one")
    void anOldEndLeavesTheNextLook() {
        previews.show(ada, CLASSIC);
        Runnable firstEnd = later.removeFirst();
        previews.onTeleport(
                new PlayerTeleportEvent(ada, ada.getLocation(), stood, PlayerTeleportEvent.TeleportCause.COMMAND));
        ada.teleport(stood);
        now = now.plusSeconds(31);
        previews.show(ada, DESERT);

        firstEnd.run();
        assertThat(previews.looking(ada)).isTrue();
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SPECTATOR);

        later.removeFirst().run();
        assertThat(previews.looking(ada)).isFalse();
    }

    @Test
    @DisplayName("A spectator who drifts past the radius, or out of the world, is put back over the showroom")
    void drifting() {
        previews.show(ada, CLASSIC);
        Location over = ada.getLocation().clone();

        Location near = over.clone().add(10, -5, 30);
        previews.onMove(new PlayerMoveEvent(ada, over, near));
        ada.teleport(near);
        assertThat(ada.getLocation()).isEqualTo(near);

        Location far = over.clone().add(60, 0, 0);
        previews.onMove(new PlayerMoveEvent(ada, near, far));
        assertThat(ada.getLocation()).isEqualTo(over);

        ada.teleport(near);
        Location deep = over.clone().add(0, -80, PresetPreviews.BACK);
        previews.onMove(new PlayerMoveEvent(ada, near, deep));
        assertThat(ada.getLocation()).isEqualTo(over);
    }

    @Test
    @DisplayName("A quit during a look plays as before, and the next join puts the player back where they stood")
    void aQuitIsUndone() {
        previews.show(ada, CLASSIC);

        previews.onQuit(new PlayerQuitEvent(ada, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(previews.looking(ada)).isFalse();

        ada.setGameMode(GameMode.SPECTATOR);
        holdTicks = true;
        previews.onJoin(new PlayerJoinEvent(ada, Component.empty()));
        assertThat(ada.getGameMode())
                .describedAs("held as a looker until the move lands")
                .isEqualTo(GameMode.SPECTATOR);
        assertThat(previews.looking(ada)).isTrue();
        assertThat(ada.getLocation())
                .describedAs("no move inside the join: on Folia it takes the region down")
                .isNotEqualTo(stood);
        assertThat(ada.getPersistentDataContainer().has(PresetPreviews.RETURN, PersistentDataType.STRING))
                .describedAs("kept until the move is made")
                .isTrue();
        holdTicks = false;
        List<Runnable> due = new ArrayList<>(nextTick);
        nextTick.clear();
        due.forEach(Runnable::run);
        assertThat(ada.getLocation()).isEqualTo(stood);
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(previews.looking(ada)).isFalse();
        assertThat(ada.getPersistentDataContainer().has(PresetPreviews.RETURN, PersistentDataType.STRING))
                .isFalse();
        later.forEach(Runnable::run);
        assertThat(ada.getLocation())
                .describedAs("the old look's end does nothing")
                .isEqualTo(stood);
    }

    @Test
    @DisplayName("A move back that fails tries the spawn, and one that fails there too keeps the player held and"
            + " tries again")
    void aMoveThatFails() {
        Refusing bo = new Refusing(server);
        server.addPlayer(bo);
        bo.teleport(stood);
        bo.setGameMode(GameMode.SURVIVAL);
        previews.show(bo, CLASSIC);
        Location over = bo.getLocation();

        bo.refuse = place -> place.getWorld().getName().equals("spawn") && place.getX() == stood.getX();
        later.removeFirst().run();
        assertThat(bo.getLocation())
                .describedAs("refused its own place, sent to spawn")
                .isEqualTo(spawnPoint);
        assertThat(bo.getGameMode()).isEqualTo(GameMode.SURVIVAL);

        now = now.plusSeconds(31);
        bo.teleport(stood);
        previews.show(bo, CLASSIC);
        bo.refuse = place -> true;
        later.removeFirst().run();
        assertThat(bo.getLocation()).isEqualTo(over);
        assertThat(bo.getGameMode())
                .describedAs("never in their own mode at the showroom")
                .isEqualTo(GameMode.SPECTATOR);
        assertThat(previews.looking(bo)).isTrue();
        assertThat(bo.getPersistentDataContainer().has(PresetPreviews.RETURN, PersistentDataType.STRING))
                .isTrue();

        bo.refuse = place -> false;
        later.removeFirst().run();
        assertThat(bo.getLocation()).describedAs("tried again, and it landed").isEqualTo(stood);
        assertThat(bo.getGameMode()).isEqualTo(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("Hits long over are let go as many players are hurt, and a fresh one is kept")
    void manyHurt() {
        for (int each = 0; each < PresetPreviews.HURT_KEPT; each++) {
            org.bukkit.entity.Player other = mock(org.bukkit.entity.Player.class);
            org.mockito.Mockito.when(other.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
            previews.onHurt(hit(other));
        }
        now = now.plusSeconds(60);
        // The one past the count lets the old hits go, and must keep its own.
        previews.onHurt(hit(ada));

        assertThat(previews.show(ada, CLASSIC)).describedAs("hurt a moment ago").isFalse();
    }

    private static org.bukkit.event.entity.EntityDamageEvent hit(org.bukkit.entity.Entity entity) {
        org.bukkit.event.entity.EntityDamageEvent hit = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        org.mockito.Mockito.when(hit.getEntity()).thenReturn(entity);
        return hit;
    }

    @Test
    @DisplayName("A player gliding or riding is not let look, as a look would take them out of the fall or the ride")
    void notOnTheWing() {
        Refusing bo = new Refusing(server);
        server.addPlayer(bo);
        bo.teleport(stood);
        bo.setGameMode(GameMode.SURVIVAL);

        bo.gliding = true;
        assertThat(previews.show(bo, CLASSIC)).isFalse();
        bo.gliding = false;
        bo.riding = true;
        assertThat(previews.show(bo, CLASSIC)).isFalse();
        bo.riding = false;
        assertThat(previews.show(bo, CLASSIC)).isTrue();
        assertThat(bo.getGameMode()).isEqualTo(GameMode.SPECTATOR);
    }

    /**
     * A player whose asynchronous move lands at once, or is refused where {@link #refuse} says, and who glides or
     * rides when told to.
     */
    private static final class Refusing extends PlayerMock {
        java.util.function.Predicate<Location> refuse = place -> false;
        boolean gliding;
        boolean riding;

        Refusing(org.mockbukkit.mockbukkit.ServerMock server) {
            super(
                    server,
                    "Bo",
                    java.util.UUID.nameUUIDFromBytes("Bo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public boolean isGliding() {
            return gliding;
        }

        @Override
        public boolean isInsideVehicle() {
            return riding;
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(
                Location location,
                PlayerTeleportEvent.TeleportCause cause,
                io.papermc.paper.entity.TeleportFlag... flags) {
            if (refuse.test(location)) {
                return CompletableFuture.completedFuture(false);
            }
            setLocation(location);
            return CompletableFuture.completedFuture(true);
        }
    }

    @Test
    @DisplayName("Calm is asked again when the look begins, and a hit is remembered past a quit")
    void calmWhenItBegins() {
        CompletableFuture<Void> laying = new CompletableFuture<>();
        PresetPreviews slow = new PresetPreviews(
                settings::get,
                () -> List.of(CLASSIC),
                server::getWorld,
                () -> "skyblock",
                () -> spawnPoint,
                (preset, world, x, y, z) -> laying,
                scheduler,
                Messages.bundled(),
                clock);
        org.bukkit.event.entity.EntityDamageEvent hit = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        org.mockito.Mockito.when(hit.getEntity()).thenReturn(ada);

        assertThat(slow.show(ada, CLASSIC)).isTrue();
        slow.onHurt(hit);
        laying.complete(null);
        assertThat(slow.looking(ada))
                .describedAs("hurt while the showroom was laid")
                .isFalse();
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);

        slow.onQuit(new PlayerQuitEvent(ada, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        now = now.plusSeconds(31);
        said();
        assertThat(slow.show(ada, CLASSIC))
                .describedAs("a hit 31 seconds ago is long calm")
                .isTrue();
        slow.onHurt(hit);
        slow.onQuit(new PlayerQuitEvent(ada, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        now = now.plusSeconds(31);
        slow.onHurt(hit);
        slow.onQuit(new PlayerQuitEvent(ada, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(slow.show(ada, CLASSIC))
                .describedAs("hurt, left and came back")
                .isFalse();
    }

    @Test
    @DisplayName("A player whose place to return to is gone joins at the server's spawn, never at the showroom")
    void noPlaceLeft() {
        ada.getPersistentDataContainer()
                .set(PresetPreviews.RETURN, PersistentDataType.STRING, "gone;1;2;3;0;0;CREATIVE");
        ada.setGameMode(GameMode.SPECTATOR);

        previews.onJoin(new PlayerJoinEvent(ada, Component.empty()));

        assertThat(ada.getGameMode()).isEqualTo(GameMode.CREATIVE);
        assertThat(ada.getLocation()).isEqualTo(spawnPoint);
    }

    @Test
    @DisplayName("What a look writes on a player reads back, and a crash's leftovers give the mode back at least")
    void written() {
        assertThat(PresetPreviews.read(PresetPreviews.written(stood, GameMode.ADVENTURE), server::getWorld))
                .hasValue(new PresetPreviews.Returned(stood, GameMode.ADVENTURE));
        assertThat(PresetPreviews.read("gone;1;2;3;0;0;CREATIVE", server::getWorld))
                .hasValue(new PresetPreviews.Returned(null, GameMode.CREATIVE));
        assertThat(PresetPreviews.read("spawn;1;2;3;0;0;FLYING", server::getWorld))
                .hasValueSatisfying(back -> assertThat(back.mode()).isEqualTo(GameMode.SURVIVAL));
        assertThat(PresetPreviews.read("nonsense", server::getWorld))
                .hasValue(new PresetPreviews.Returned(null, GameMode.SURVIVAL));
    }
}
