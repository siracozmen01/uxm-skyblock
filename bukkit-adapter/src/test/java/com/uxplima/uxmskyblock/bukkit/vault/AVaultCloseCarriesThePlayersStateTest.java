package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

/**
 * Closing a vault window hands the player's state to the commit, so the two are written together.
 *
 * <p>The window passed nothing for the player, and the page was written alone: what the player had
 * put in the vault stayed in their stored inventory until the next checkpoint, a minute later.
 */
class AVaultCloseCarriesThePlayersStateTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-x");

    private ServerMock server;
    private IslandVaultService vaultService;
    private PlayerSessionCoordinator sessions;
    private IslandVaultWindow window;
    private final ProfileId profile = new ProfileId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        vaultService = mock(IslandVaultService.class);
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.nodeId()).thenReturn(NODE);
        window = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                inlineScheduler(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                sessions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName(
            "The close carries what the player holds, at the session's epoch and version, and moves the version on")
    void theCloseCarriesTheState() {
        PlayerMock player = server.addPlayer();
        ActiveSession session = new ActiveSession(new PlayerUuid(player.getUniqueId()), profile, 4L, 9L);
        when(sessions.getActiveSession(player.getUniqueId())).thenReturn(session);
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 3));

        window.commit(player, holder(profile), new ItemStack[] {null});

        PlayerStateWrite written = captured();
        assertThat(written.node()).isEqualTo(NODE);
        assertThat(written.sessionEpoch()).isEqualTo(4L);
        assertThat(written.expectedVersion()).isEqualTo(9L);
        assertThat(Arrays.stream(BukkitInventorySerializer.deserializeItemStacks(
                                written.state().inventoryNbt()))
                        .filter(Objects::nonNull)
                        .filter(item -> !item.getType().isAir()))
                .containsExactly(new ItemStack(Material.EMERALD, 3));
        assertThat(session.lastDurableVersion())
                .describedAs("the next checkpoint builds on it")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("A checkpoint that lands between the close and the save moves the version the save writes over")
    void aCheckpointBetweenTheCloseAndTheSaveIsBuiltOn() {
        java.util.List<Runnable> later = new java.util.ArrayList<>();
        SchedulerPort deferred = inlineScheduler();
        doAnswer(invocation -> later.add(invocation.getArgument(0, Runnable.class)))
                .when(deferred)
                .async(any(Runnable.class));
        IslandVaultWindow deferring = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                deferred,
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                sessions);
        PlayerMock player = server.addPlayer();
        ActiveSession session = new ActiveSession(new PlayerUuid(player.getUniqueId()), profile, 4L, 9L);
        when(sessions.getActiveSession(player.getUniqueId())).thenReturn(session);

        deferring.commit(player, holder(profile), new ItemStack[] {null});
        // The checkpoint taken while the window was open lands before the save does.
        session.setLastDurableVersion(10L);
        later.forEach(Runnable::run);

        PlayerStateWrite written = captured();
        assertThat(written.expectedVersion()).isEqualTo(10L);
        assertThat(written.state().version()).isEqualTo(10L);
        assertThat(session.lastDurableVersion()).isEqualTo(11L);
    }

    @Test
    @DisplayName("Until the close's write lands, or its refusal is settled, nothing else moves the player's state")
    void aCloseHoldsOtherWritesUntilItLands() {
        java.util.List<Runnable> later = new java.util.ArrayList<>();
        SchedulerPort deferred = inlineScheduler();
        doAnswer(invocation -> later.add(invocation.getArgument(0, Runnable.class)))
                .when(deferred)
                .async(any(Runnable.class));
        IslandVaultWindow deferring = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                deferred,
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                sessions);
        PlayerMock player = server.addPlayer();
        ActiveSession session = new ActiveSession(new PlayerUuid(player.getUniqueId()), profile, 4L, 9L);
        when(sessions.getActiveSession(player.getUniqueId())).thenReturn(session);

        deferring.commit(player, holder(profile), new ItemStack[] {null});
        assertThat(session.selfWriteOutstanding()).isTrue();
        later.forEach(Runnable::run);
        assertThat(session.selfWriteOutstanding()).isFalse();

        later.clear();
        org.mockito.Mockito.doThrow(new IllegalStateException("refused"))
                .when(vaultService)
                .commitVaultPage(any(), any(), any(), any(), any());
        deferring.commit(player, holder(profile), new ItemStack[] {null});
        assertThat(session.selfWriteOutstanding()).isTrue();
        later.forEach(Runnable::run);
        assertThat(session.selfWriteOutstanding())
                .describedAs("a refused write is settled on the player, and then holds nothing back")
                .isFalse();
    }

    @Test
    @DisplayName("A window opened for another profile than the one in play carries nothing for the player")
    void anotherProfileCarriesNothing() {
        PlayerMock player = server.addPlayer();
        when(sessions.getActiveSession(player.getUniqueId()))
                .thenReturn(new ActiveSession(new PlayerUuid(player.getUniqueId()), profile, 4L, 9L));

        window.commit(player, holder(new ProfileId(UUID.randomUUID())), new ItemStack[] {null});

        assertThat(capturedOrNull()).isNull();
    }

    private static IslandVaultWindow.VaultHolder holder(ProfileId owner) {
        return new IslandVaultWindow.VaultHolder(
                IslandId.of(UUID.randomUUID()),
                1,
                owner,
                UUID.randomUUID().toString(),
                List.of(new ItemStack(Material.AIR)),
                true,
                true);
    }

    private PlayerStateWrite captured() {
        return Objects.requireNonNull(capturedOrNull());
    }

    private @org.jspecify.annotations.Nullable PlayerStateWrite capturedOrNull() {
        ArgumentCaptor<PlayerStateWrite> captor = ArgumentCaptor.forClass(PlayerStateWrite.class);
        verify(vaultService).commitVaultPage(any(), any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }
}
