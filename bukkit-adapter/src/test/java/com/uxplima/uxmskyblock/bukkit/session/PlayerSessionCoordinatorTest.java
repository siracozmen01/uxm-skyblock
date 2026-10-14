package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlayerSessionCoordinatorTest extends MockBukkitHarness {

    private Path tempDir;
    private PersistenceBootstrap persistenceBootstrap;
    private FoliaSchedulerAdapter scheduler;
    private IslandProtectionListener protectionListener;
    private SwitchProfileUseCase switchProfileUseCase;
    private PlayerSessionCoordinator coordinator;
    private final ServerNodeId nodeId = new ServerNodeId("test-node-1");

    @BeforeEach
    void setUpCoordinator() throws IOException {
        tempDir = Files.createTempDirectory("session_test_");
        Path dbPath = tempDir.resolve("test.db");
        persistenceBootstrap = PersistenceBootstrap.createSqlite(dbPath);

        // create a dummy plugin
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        scheduler = new FoliaSchedulerAdapter(plugin);
        protectionListener =
                new IslandProtectionListener(persistenceBootstrap.islandStoragePort(), new IslandAccessService());
        switchProfileUseCase = new SwitchProfileUseCase(
                persistenceBootstrap.profileSwitchPort(), persistenceBootstrap.inventoryPort());

        coordinator = new PlayerSessionCoordinator(
                nodeId,
                persistenceBootstrap.sessionAuthorityPort(),
                persistenceBootstrap.inventoryPort(),
                persistenceBootstrap.handoffFinalizationPort(),
                switchProfileUseCase,
                new InventoryJournalRecovery(
                        persistenceBootstrap.mutationJournalPort(), persistenceBootstrap.inventoryPort()),
                scheduler,
                protectionListener,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Messages.bundled());
    }

    @AfterEach
    void tearDownCoordinator() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
        if (persistenceBootstrap != null) {
            persistenceBootstrap.close();
        }
    }

    @Test
    @DisplayName("handlePlayerJoin bootstraps session authority and loads inventory")
    void playerJoinBootstrapsSession() {
        PlayerMock player = createPlayer("JoinPlayer");
        coordinator.handlePlayerJoin(player);

        eventually(() -> {
            ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
            assertThat(session).isNotNull();
            assertThat(Objects.requireNonNull(session).sessionEpoch()).isEqualTo(1L);
            assertThat(session.lastDurableVersion()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    @DisplayName("A join settles the inventory operation a crashed node left open")
    void aJoinSettlesWhatACrashLeftOpen() throws Exception {
        PlayerMock player = createPlayer("CrashPlayer");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profile = new ProfileId(player.getUniqueId());
        ServerNodeId deadNode = new ServerNodeId("dead-node");
        var sessions = persistenceBootstrap.sessionAuthorityPort();
        var journal = persistenceBootstrap.mutationJournalPort();
        long deadEpoch =
                ((SessionAuthorityOutcome.Success) sessions.ensureSession(playerUuid, profile, deadNode)).epoch();
        String durable = InventoryFingerprint.of(persistenceBootstrap
                .inventoryPort()
                .loadInventory(profile)
                .orElseThrow()
                .inventoryNbt());
        InventoryMutationOperationId delivery = InventoryMutationOperationId.random();
        assertThat(journal.recordIntent(
                                playerUuid,
                                profile,
                                deadNode,
                                deadEpoch,
                                persistenceBootstrap
                                        .inventoryPort()
                                        .loadInventory(profile)
                                        .orElseThrow()
                                        .version(),
                                delivery,
                                "REWARD_DELIVERY",
                                durable,
                                InventoryFingerprint.of(new byte[] {1, 2, 3}),
                                "{}",
                                Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        // The dead node stops renewing, and its lease runs out.
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"));
                var ps = conn.prepareStatement("UPDATE player_sessions SET lease_expires_at = DATETIME('now', '-30"
                        + " seconds') WHERE player_uuid = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        coordinator.handlePlayerJoin(player);

        eventually(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());
        assertThat(journal.loadJournal(delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.ABORTED);
        assertThat(journal.findOpenIntents(profile)).isEmpty();
    }

    @Test
    @DisplayName("Whatever waits for a session is called once the session is made, with the player")
    void aSessionHookIsCalledOnceTheSessionIsMade() {
        PlayerMock player = createPlayer("HookPlayer");
        java.util.List<org.bukkit.entity.Player> called = new java.util.concurrent.CopyOnWriteArrayList<>();
        coordinator.whenSessionActive(called::add);

        coordinator.handlePlayerJoin(player);

        eventuallyTick(() -> assertThat(called).containsExactly(player));
        assertThat(coordinator.activeProfile(player.getUniqueId()))
                .describedAs("the profile a hook would ask for")
                .isPresent();
    }

    @Test
    @DisplayName("A profile switch tells the hooks the old profile left and the new one arrived")
    void aSwitchIsALeaveAndAnArrival() {
        PlayerMock player = createPlayer("SwitchHookPlayer");
        java.util.List<ProfileId> left = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<ProfileId> arrived = new java.util.concurrent.CopyOnWriteArrayList<>();
        coordinator.whenProfileLeft((who, profile) -> left.add(profile));
        coordinator.whenSessionActive(
                who -> arrived.add(coordinator.activeProfile(who.getUniqueId()).orElseThrow()));
        coordinator.handlePlayerJoin(player);
        eventuallyTick(() -> assertThat(arrived).hasSize(1));
        ProfileId first = arrived.get(0);
        ProfileId second = new ProfileId(UUID.randomUUID());
        persistenceBootstrap.registerProfile(new PlayerUuid(player.getUniqueId()), second);
        persistenceBootstrap
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(second, new byte[0], new byte[0]));

        coordinator.switchProfile(player, second);

        eventuallyTick(() -> assertThat(arrived).containsExactly(first, second));
        assertThat(left).containsExactly(first);
    }

    @Test
    @DisplayName("checkpointPlayer saves updated player inventory snapshot")
    void checkpointSavesInventory() {
        PlayerMock player = createPlayer("CheckpointPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        // A checkpoint is skipped while another write of the player is landing, and the next one writes after it.
        // The join writes the player once; under a loaded build that write can still be landing here, so the
        // checkpoint is taken once it is done.
        eventuallyTick(() -> assertThat(Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()))
                        .writes()
                        .isLocked())
                .isFalse());
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 10));
        coordinator.checkpointPlayer(new PlayerUuid(player.getUniqueId()));

        eventuallyTick(() -> {
            ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
            assertThat(session).isNotNull();
            Optional<ProfileInventoryRecord> inv = persistenceBootstrap
                    .inventoryPort()
                    .loadInventory(Objects.requireNonNull(session).activeProfileId());
            assertThat(inv).isPresent();
            byte[] nbt = inv.get().inventoryNbt();
            assertThat(nbt).isNotEmpty();
            ItemStack[] items = BukkitInventorySerializer.deserializeItemStacks(nbt);
            assertThat(items).isNotEmpty();
            assertThat(items[0]).isEqualTo(new ItemStack(Material.DIAMOND, 10));
        });
    }

    @Test
    @DisplayName("handlePlayerQuit finalizes handoff and flushes durable inventory")
    void playerQuitDrainsAndFlushes() {
        PlayerMock player = createPlayer("QuitPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 3));
        coordinator.handlePlayerQuit(player);

        assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();

        eventuallyTick(() -> {
            ProfileId profileId = new ProfileId(player.getUniqueId());
            Optional<ProfileInventoryRecord> inv =
                    persistenceBootstrap.inventoryPort().loadInventory(profileId);
            assertThat(inv).isPresent();
            byte[] nbt = inv.get().inventoryNbt();
            assertThat(nbt).isNotEmpty();
            ItemStack[] items = BukkitInventorySerializer.deserializeItemStacks(nbt);
            assertThat(items).isNotEmpty();
            assertThat(items[0]).isEqualTo(new ItemStack(Material.GOLD_BLOCK, 3));
        });
    }

    @Test
    @DisplayName("A switch to another player's profile is refused, and told from the catalogue")
    void aRefusedSwitchIsToldPlainly() {
        PlayerMock player = createPlayer("RefusedSwitchPlayer");
        coordinator.handlePlayerJoin(player);
        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());
        while (player.nextMessage() != null) {
            // What joining said is not what this test is about.
        }

        // Another player's first profile carries that player's uuid, which anyone can read.
        PlayerMock victim = createPlayer("Victim");
        coordinator.handlePlayerJoin(victim);
        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(victim.getUniqueId())).isNotNull());

        coordinator.switchProfile(player, new ProfileId(victim.getUniqueId()));

        java.util.List<String> said = new java.util.ArrayList<>();
        eventuallyTick(() -> {
            for (String next = player.nextMessage(); next != null; next = player.nextMessage()) {
                said.add(next);
            }
            assertThat(said).anyMatch(line -> line.contains("could not be switched"));
        });
        assertThat(said)
                .describedAs("the use case's sentence stays in the log")
                .noneMatch(line -> line.contains("TARGET_PROFILE") || line.contains("Failed"));
        assertThat(Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()))
                        .activeProfileId())
                .describedAs("the player is still on their own profile")
                .isEqualTo(new ProfileId(player.getUniqueId()));
    }

    @Test
    @DisplayName("switchProfile transitions player items between profiles")
    void switchProfileTransfersInventory() {
        PlayerMock player = createPlayer("SwitchPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
        assertThat(session).isNotNull();
        ProfileId profileA = Objects.requireNonNull(session).activeProfileId();
        ProfileId profileB = new ProfileId(UUID.randomUUID());
        assertThat(profileA).isNotEqualTo(profileB);

        // Pre-seed profile B in DB
        persistenceBootstrap.registerProfile(new PlayerUuid(player.getUniqueId()), profileB);

        ItemStack[] bItems = new ItemStack[41];
        bItems[0] = new ItemStack(Material.EMERALD, 64);
        byte[] bNbt = BukkitInventorySerializer.serializeItemStacks(bItems);
        persistenceBootstrap
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(profileB, bNbt, new byte[0]));

        // Give player netherite on profile A
        player.getInventory().setItem(0, new ItemStack(Material.NETHERITE_INGOT, 5));

        coordinator.switchProfile(player, profileB);

        eventuallyTick(() -> {
            ActiveSession currentSession = coordinator.getActiveSession(player.getUniqueId());
            assertThat(currentSession).isNotNull();
            assertThat(Objects.requireNonNull(currentSession).activeProfileId()).isEqualTo(profileB);
            assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.EMERALD, 64));
        });
    }

    @Test
    @DisplayName("activeProfile resolves canonical session profile and updates on switch")
    void activeProfileResolvesCanonical() {
        PlayerMock player = createPlayer("ActiveProfilePlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        ProfileId initial = coordinator.activeProfile(player.getUniqueId()).orElseThrow();
        assertThat(initial).isEqualTo(new ProfileId(player.getUniqueId()));

        ProfileId profileB = new ProfileId(UUID.randomUUID());
        persistenceBootstrap.registerProfile(new PlayerUuid(player.getUniqueId()), profileB);
        persistenceBootstrap
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(profileB, new byte[0], new byte[0]));

        coordinator.switchProfile(player, profileB);

        eventuallyTick(() -> {
            ProfileId active = coordinator.activeProfile(player.getUniqueId()).orElseThrow();
            assertThat(active).isEqualTo(profileB);
        });
    }

    @Test
    @DisplayName("selfFencePlayer marks session fenced, removes it, and kicks player")
    void selfFencePlayerFencesAndKicks() {
        PlayerMock player = createPlayer("FencedPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        coordinator.selfFencePlayer(new PlayerUuid(player.getUniqueId()), "Lease expired test");

        assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();
        assertThat(coordinator.activeProfile(player.getUniqueId())).isEmpty();

        eventuallyTick(() -> assertThat(player.isOnline()).isFalse());
    }

    @Test
    @DisplayName("handlePlayerQuit releases session to OFFLINE state in persistence")
    void playerQuitReleasesToOffline() {
        PlayerMock player = createPlayer("OfflinePlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        coordinator.handlePlayerQuit(player);

        eventuallyTick(() -> {
            var sessionOpt =
                    persistenceBootstrap.sessionAuthorityPort().findSession(new PlayerUuid(player.getUniqueId()));
            assertThat(sessionOpt).isPresent();
            assertThat(sessionOpt.get().state())
                    .isEqualTo(com.uxplima.uxmskyblock.core.domain.session.SessionState.OFFLINE);
        });
    }

    private void eventuallyTick(Runnable assertion) {
        long start = System.currentTimeMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() - start < 5000) {
            try {
                if (server != null && server.getScheduler() != null) {
                    server.getScheduler().performOneTick();
                }
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }
}
