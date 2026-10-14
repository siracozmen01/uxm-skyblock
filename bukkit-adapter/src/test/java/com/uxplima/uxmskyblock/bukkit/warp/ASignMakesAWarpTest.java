package com.uxplima.uxmskyblock.bukkit.warp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.SignWarpsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** SuperiorSkyblock2 and BentoBox make a warp of a sign headed [warp]; here only /is setwarp did. */
class ASignMakesAWarpTest extends MockBukkitHarness {

    private final IslandProtectionListener islands = mock(IslandProtectionListener.class);
    private final IslandWarpService warps = mock(IslandWarpService.class);
    private PlayerMock player;
    private ProfileId profile;
    private Island island;
    private Block block;
    private SignWarps signs;

    @BeforeEach
    void setUp() {
        World world = server.addSimpleWorld("isles");
        player = createPlayer("Builder");
        player.teleport(new Location(world, 1, 64, 1));
        profile = new ProfileId(UUID.randomUUID());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 32),
                new PlayerUuid(player.getUniqueId()),
                profile,
                Instant.now());
        block = world.getBlockAt(3, 64, 4);
        block.setType(Material.OAK_SIGN);
        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.of(island));
        when(islands.activeProfile(new PlayerUuid(player.getUniqueId()))).thenReturn(Optional.of(profile));
        signs = new SignWarps(
                () -> new SignWarpsConfiguration(true, "[warp]"),
                islands,
                warps,
                new InlineSchedulerPort(),
                Messages.bundled());
    }

    private SignChangeEvent write(String first, String second) {
        List<Component> lines = new ArrayList<>(
                List.of(Component.text(first), Component.text(second), Component.empty(), Component.empty()));
        SignChangeEvent event = new SignChangeEvent(block, player, lines, Side.FRONT);
        signs.onWrite(event);
        return event;
    }

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    private String said() {
        StringBuilder all = new StringBuilder();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            all.append(plain(next)).append('\n');
        }
        return all.toString();
    }

    @Test
    @DisplayName("A member's sign headed [warp] makes a warp of its name at the sign, marked, and says so")
    void aWarpIsMade() {
        SignChangeEvent event = write("[WARP]", " Farm ");

        verify(warps)
                .createWarp(
                        eq(island),
                        eq(profile),
                        eq(WarpName.of("Farm")),
                        eq(new WarpLocation(
                                "isles", 3.5, 64, 4.5, player.getLocation().getYaw(), 0f)),
                        eq(WarpCategory.GENERAL),
                        eq(WarpCategory.GENERAL.defaultIconMaterial()));
        assertThat(plain(Objects.requireNonNull(event.line(0)))).isEqualTo("[Island Warp]");
        assertThat(plain(Objects.requireNonNull(event.line(1)))).isEqualTo("Farm");
        assertThat(((Sign) block.getState())
                        .getPersistentDataContainer()
                        .get(SignWarps.MARK, PersistentDataType.STRING))
                .isEqualTo("Farm");
        assertThat(said()).contains("Farm").contains("Break it");
    }

    @Test
    @DisplayName("A sign with another header, or with no name, makes nothing")
    void nothingIsMade() {
        assertThat(plain(Objects.requireNonNull(write("[shop]", "Farm").line(0))))
                .isEqualTo("[shop]");
        write("[warp]", "  ");
        assertThat(said()).contains("second line");
        verify(warps, never()).createWarp(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Off any island, or before the session is known, the sign is refused without a warp")
    void refusedBeforeTheService() {
        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.empty());
        write("[warp]", "Farm");
        assertThat(said()).contains("island");
        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.of(island));
        when(islands.activeProfile(any())).thenReturn(Optional.empty());
        write("[warp]", "Farm");
        verify(warps, never()).createWarp(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A warp the service refuses leaves the sign reading as refused and unmarked, and says why")
    void aRefusedWarp() {
        doThrow(new WarpLimitExceededException(island.id(), 2, 2))
                .when(warps)
                .createWarp(any(), any(), any(), any(), any(), any());
        write("[warp]", "Farm");
        Sign sign = (Sign) block.getState();
        assertThat(plain(sign.getSide(Side.FRONT).line(0))).isEqualTo("[Refused]");
        assertThat(sign.getPersistentDataContainer().has(SignWarps.MARK)).isFalse();
        assertThat(said()).contains("2");

        doThrow(new SecurityException("no")).when(warps).createWarp(any(), any(), any(), any(), any(), any());
        write("[warp]", "Farm");
        assertThat(said()).contains("cannot do that");
    }

    @Test
    @DisplayName("Breaking a warp sign deletes its warp; breaking any other sign deletes nothing")
    void breakingDeletes() {
        signs.onBreak(new BlockBreakEvent(block, player));
        verify(warps, never()).deleteWarp(any(), any(), any());

        write("[warp]", "Farm");
        said();
        signs.onBreak(new BlockBreakEvent(block, player));
        verify(warps).deleteWarp(island, profile, WarpName.of("Farm"));
        assertThat(said()).contains("Farm");

        doThrow(new SecurityException("no")).when(warps).deleteWarp(any(), any(), any());
        signs.onBreak(new BlockBreakEvent(block, player));
        assertThat(said()).contains("cannot do that");
    }
}
