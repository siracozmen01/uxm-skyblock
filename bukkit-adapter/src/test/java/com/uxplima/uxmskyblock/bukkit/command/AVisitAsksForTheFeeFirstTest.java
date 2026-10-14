package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.visitor.VisitFeeGate;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

/** {@code /is visit} to an island that charges shows the fee, takes it once confirmed, and only then moves anyone. */
class AVisitAsksForTheFeeFirstTest extends MockBukkitHarness {

    private static final String WORLD = "isles";
    private static final IslandId TARGET = IslandId.of(UUID.randomUUID());

    private final List<String> calls = new ArrayList<>();
    private PlayerMock player;
    private IslandLocationService locations;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private long fee;
    private boolean agrees;
    private boolean pays = true;
    private boolean moved;

    @BeforeEach
    void setUp() {
        server.addSimpleWorld(WORLD);
        player = createPlayer("Guest");
        ProfileId visitor = new ProfileId(UUID.randomUUID());
        locations = mock(IslandLocationService.class);
        when(locations.findIsland(TARGET))
                .thenReturn(Optional.of(Island.create(
                        TARGET,
                        IslandBounds.fromCenterAndRadius(0, 0, 50),
                        new PlayerUuid(UUID.randomUUID()),
                        new ProfileId(UUID.randomUUID()),
                        Instant.now())));
        when(locations.findLocation(TARGET))
                .thenReturn(Optional.of(new IslandLocation(
                        TARGET, WORLD, IslandBounds.fromCenterAndRadius(0, 0, 50), 8.5, 65, 8.5, 0, 0)));
        when(locations.findIslandId(visitor)).thenReturn(Optional.empty());
        IslandWarpService warps = mock(IslandWarpService.class);
        when(warps.isPlayerBanned(any(), any())).thenReturn(false);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(visitor));
        IslandNavigationCommands commands = new IslandNavigationCommands(
                locations,
                sessions,
                new InlineSchedulerPort(),
                WORLD,
                () -> null,
                () -> null,
                () -> warps,
                () -> null,
                Messages.bundled());
        commands.useVisitFees(() -> new VisitFeeGate() {
            @Override
            public long owed(IslandId island, Player visitor1, @Nullable ProfileId profile) {
                calls.add("owed");
                return fee;
            }

            @Override
            public boolean agreed(Player visitor1, IslandId island, long owed) {
                return agrees && owed == fee;
            }

            @Override
            public void ask(Player visitor1, IslandId island, long owed, String confirmLine) {
                calls.add("ask " + owed + " " + confirmLine);
            }

            @Override
            public void pay(Player visitor1, IslandId island, long owed, Runnable then) {
                calls.add("pay " + owed);
                if (pays) {
                    then.run();
                }
            }
        });
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildVisit());
    }

    private void run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        moved = false;
        try {
            dispatcher.execute(line, source);
        } catch (UnimplementedOperationException unimplemented) {
            assertThat(unimplemented.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().contains("teleport"));
            moved = true;
        }
    }

    @Test
    @DisplayName("An island that charges nothing takes the visitor at once")
    void free() throws Exception {
        run("visit " + TARGET.value());
        assertThat(moved).isTrue();
        assertThat(calls).containsExactly("owed");
    }

    @Test
    @DisplayName("An island that charges shows the fee and the line that confirms it, and moves nobody")
    void asked() throws Exception {
        fee = 500;
        run("visit " + TARGET.value());
        assertThat(moved).isFalse();
        assertThat(calls).containsExactly("owed", "ask 500 /visit " + TARGET.value() + " confirm");

        calls.clear();
        run("visit " + TARGET.value() + " confirm");
        assertThat(moved)
                .describedAs("a confirm with no fee shown, or a higher one")
                .isFalse();
        assertThat(calls).containsExactly("owed", "ask 500 /visit " + TARGET.value() + " confirm");
    }

    @Test
    @DisplayName("A confirmed fee is paid before the visitor is moved; an unpaid one moves nobody")
    void paid() throws Exception {
        fee = 500;
        agrees = true;
        run("visit " + TARGET.value() + " confirm");
        assertThat(calls).containsExactly("owed", "pay 500");
        assertThat(moved).isTrue();

        calls.clear();
        pays = false;
        run("visit " + TARGET.value() + " confirm");
        assertThat(calls).containsExactly("owed", "pay 500");
        assertThat(moved).isFalse();
    }

    @Test
    @DisplayName("An island with no place to be put down costs nothing")
    void noPlaceNoFee() throws Exception {
        fee = 500;
        agrees = true;
        when(locations.findLocation(TARGET)).thenReturn(Optional.empty());
        run("visit " + TARGET.value() + " confirm");
        assertThat(calls).containsExactly("owed");
        assertThat(moved).isFalse();
    }
}
