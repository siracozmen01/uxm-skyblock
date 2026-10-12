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
    private Instant now = Instant.parse("2026-10-12T12:00:00Z");
    private World skyblock;
    private PresetPreviews previews;
    private PlayerMock ada;
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
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(call -> later.add(call.getArgument(1, Runnable.class)))
                .when(scheduler)
                .asyncAfter(any(Duration.class), any(Runnable.class));
        previews = new PresetPreviews(
                settings::get,
                () -> List.of(DESERT, CLASSIC),
                server::getWorld,
                () -> "skyblock",
                (preset, world, x, y, z) -> {
                    laid.add(preset.id() + " " + x + " " + y + " " + z);
                    world.getBlockAt(x, y, z).setType(Material.GRASS_BLOCK);
                    return CompletableFuture.completedFuture(null);
                },
                scheduler,
                Messages.bundled(),
                new Clock() {
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
                });
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
                false, Duration.ofSeconds(20), 48, Duration.ofSeconds(30), "", -1_000_000, -1_000_000, 100, 1024));
        assertThat(previews.show(ada, CLASSIC)).isFalse();
        assertThat(said()).contains("turned off");
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("No jump to another player during a look, and anybody else's teleport ends it where it lands")
    void teleports() {
        previews.show(ada, CLASSIC);
        Location over = ada.getLocation();

        PlayerTeleportEvent spectate =
                new PlayerTeleportEvent(ada, over, stood, PlayerTeleportEvent.TeleportCause.SPECTATE);
        previews.onTeleport(spectate);
        assertThat(spectate.isCancelled()).isTrue();
        assertThat(previews.looking(ada)).isTrue();

        PlayerTeleportEvent home = new PlayerTeleportEvent(ada, over, stood, PlayerTeleportEvent.TeleportCause.COMMAND);
        previews.onTeleport(home);
        assertThat(home.isCancelled()).isFalse();
        assertThat(previews.looking(ada)).isFalse();
        assertThat(ada.getGameMode()).isEqualTo(GameMode.SURVIVAL);
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

        previews.onJoin(new PlayerJoinEvent(ada, Component.empty()));
        assertThat(ada.getLocation()).isEqualTo(stood);
        assertThat(ada.getPersistentDataContainer().has(PresetPreviews.RETURN, PersistentDataType.STRING))
                .isFalse();
        later.forEach(Runnable::run);
        assertThat(ada.getLocation())
                .describedAs("the old look's end does nothing")
                .isEqualTo(stood);
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
