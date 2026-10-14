package com.uxplima.uxmskyblock.bukkit.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** SuperiorSkyblock2 lets an operator keep commands from visitors; here a visitor could run any. */
class AVisitorIsRefusedTheListedCommandsTest extends MockBukkitHarness {

    private final IslandProtectionListener islands = mock(IslandProtectionListener.class);
    private PlayerMock player;
    private ProfileId profile;
    private Island island;
    private VisitorCommandGuard guard;

    @BeforeEach
    void setUp() {
        World world = server.addSimpleWorld("isles");
        player = createPlayer("Guest");
        player.teleport(new Location(world, 1, 64, 1));
        profile = new ProfileId(UUID.randomUUID());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 32),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.of(island));
        when(islands.activeProfile(new PlayerUuid(player.getUniqueId()))).thenReturn(Optional.of(profile));
        guard = new VisitorCommandGuard(
                () -> new VisitorsConfiguration(List.of("sethome")), islands, Messages.bundled());
    }

    private boolean refused(String line) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, line);
        guard.onCommand(event);
        return event.isCancelled();
    }

    @Test
    @DisplayName("A visitor is refused a listed command and told so; any other command runs")
    void aVisitorIsRefused() {
        assertThat(refused("/sethome base")).isTrue();
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage())))
                .contains("/sethome");
        assertThat(refused("/home base")).isFalse();
    }

    @Test
    @DisplayName("A member, staff who bypass, and a player off any island run it")
    void othersRunIt() {
        Island theirs = island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                new PlayerUuid(player.getUniqueId()),
                profile,
                com.uxplima.uxmskyblock.core.domain.island.IslandRole.MEMBER,
                Instant.now()));
        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.of(theirs));
        assertThat(refused("/sethome")).isFalse();

        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.of(island));
        player.addAttachment(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), "uxmskyblock.admin.bypass", true);
        assertThat(refused("/sethome")).isFalse();
    }

    @Test
    @DisplayName("A player whose session is not known yet is a visitor, and off any island nobody is")
    void unknownAndWild() {
        when(islands.activeProfile(any())).thenReturn(Optional.empty());
        assertThat(refused("/sethome")).isTrue();
        when(islands.findIslandAt(any(Location.class))).thenReturn(Optional.empty());
        assertThat(refused("/sethome")).isFalse();
    }
}
