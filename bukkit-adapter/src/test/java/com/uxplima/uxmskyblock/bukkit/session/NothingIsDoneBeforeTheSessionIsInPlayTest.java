package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player does nothing with what they hold until their session's state is in their hands.
 *
 * <p>Nothing stopped a player between the join and the moment their durable state was put on them,
 * or after this server fenced itself off their session. What they held was about to be replaced or
 * could no longer be written, and an item dropped then stayed on the ground and came back with the
 * state as well.
 */
class NothingIsDoneBeforeTheSessionIsInPlayTest extends MockBukkitHarness {

    private PersistenceBootstrap persistence;
    private PlayerSessionCoordinator coordinator;
    private PlayerSessionListener listener;

    @BeforeEach
    void setUpCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("inplay_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("inplay.db"));
        coordinator = new PlayerSessionCoordinator(
                ServerNodeId.of("inplay-node"),
                persistence.sessionAuthorityPort(),
                persistence.inventoryPort(),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                new FoliaSchedulerAdapter(MockBukkit.createMockPlugin()),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled());
        listener = new PlayerSessionListener(coordinator);
    }

    @AfterEach
    void tearDownCoordinator() {
        coordinator.shutdown();
        persistence.close();
    }

    @Test
    @DisplayName("A drop and a break are refused before the join has given the player their state")
    void refusedBeforeTheJoin() {
        PlayerMock player = createPlayer("Early");

        assertThat(dropped(player)).isFalse();
        assertThat(broke(player)).isFalse();
    }

    @Test
    @DisplayName("A drop and a break go through once the session is in play")
    void allowedInPlay() {
        PlayerMock player = inPlay("Playing");

        assertThat(dropped(player)).isTrue();
        assertThat(broke(player)).isTrue();
    }

    @Test
    @DisplayName("A drop and a break are refused once this server has fenced the session")
    void refusedOnceFenced() {
        PlayerMock player = inPlay("Fenced");

        coordinator.selfFencePlayer(new PlayerUuid(player.getUniqueId()), "the database stopped answering");

        assertThat(dropped(player)).isFalse();
        assertThat(broke(player)).isFalse();
    }

    @Test
    @DisplayName("Every other way out is refused before the join, and goes through once the session is in play")
    void everyOtherWayOut() {
        PlayerMock early = createPlayer("Waiting");
        assertThat(otherWaysOut(early)).allMatch(org.bukkit.event.Cancellable::isCancelled);
        assertThat(diesKeeping(early)).isTrue();

        PlayerMock playing = inPlay("Free");
        assertThat(otherWaysOut(playing)).noneMatch(org.bukkit.event.Cancellable::isCancelled);
        assertThat(diesKeeping(playing)).isFalse();
    }

    @Test
    @DisplayName("A profile switch is refused while the session is out of play or a window's write is still to land")
    void aSwitchWaitsForPlay() {
        PlayerMock player = inPlay("Switcher");
        ActiveSession session = java.util.Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()));
        com.uxplima.uxmskyblock.core.domain.identity.ProfileId before = session.activeProfileId();
        com.uxplima.uxmskyblock.core.domain.identity.ProfileId other =
                com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(java.util.UUID.randomUUID());
        persistence.registerProfile(new PlayerUuid(player.getUniqueId()), other);
        persistence
                .inventoryPort()
                .initializeInventory(com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord.createDefault(
                        other, new byte[0], new byte[0]));

        session.leavePlay();
        coordinator.switchProfile(player, other);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(player.nextMessage()).contains("could not be switched");
        });
        session.enterPlay();
        session.selfWriteBegun();
        coordinator.switchProfile(player, other);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(player.nextMessage()).contains("could not be switched");
        });

        assertThat(session.activeProfileId()).isEqualTo(before);
        assertThat(session.inPlay(System.nanoTime())).isTrue();

        session.selfWriteLanded();
        coordinator.switchProfile(player, other);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(session.activeProfileId())
                    .describedAs("in play with nothing still to land, the same switch goes through")
                    .isEqualTo(other);
        });
    }

    /** Opening a window, placing an entity, using one, an armor stand, a bucket either way and a throw. */
    private List<org.bukkit.event.Cancellable> otherWaysOut(PlayerMock player) {
        org.bukkit.block.Block block = player.getLocation().getBlock();
        org.bukkit.entity.ArmorStand stand =
                player.getWorld().spawn(player.getLocation(), org.bukkit.entity.ArmorStand.class);
        org.bukkit.entity.Snowball snowball =
                player.getWorld().spawn(player.getLocation(), org.bukkit.entity.Snowball.class);
        snowball.setShooter(player);
        org.bukkit.event.inventory.InventoryOpenEvent open = new org.bukkit.event.inventory.InventoryOpenEvent(
                player.openInventory(server.createInventory(null, 9)));
        org.bukkit.event.entity.EntityPlaceEvent place = new org.bukkit.event.entity.EntityPlaceEvent(
                stand, player, block, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.event.player.PlayerInteractAtEntityEvent at =
                new org.bukkit.event.player.PlayerInteractAtEntityEvent(player, stand, new org.bukkit.util.Vector());
        org.bukkit.event.player.PlayerArmorStandManipulateEvent manipulate =
                new org.bukkit.event.player.PlayerArmorStandManipulateEvent(
                        player,
                        stand,
                        new ItemStack(Material.DIAMOND_HELMET),
                        new ItemStack(Material.AIR),
                        org.bukkit.inventory.EquipmentSlot.HEAD,
                        org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.event.player.PlayerBucketEmptyEvent empty = new org.bukkit.event.player.PlayerBucketEmptyEvent(
                player,
                block,
                block,
                org.bukkit.block.BlockFace.UP,
                Material.LAVA_BUCKET,
                new ItemStack(Material.LAVA_BUCKET),
                org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.event.player.PlayerBucketFillEvent fill = new org.bukkit.event.player.PlayerBucketFillEvent(
                player,
                block,
                block,
                org.bukkit.block.BlockFace.UP,
                Material.BUCKET,
                new ItemStack(Material.BUCKET),
                org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.event.entity.ProjectileLaunchEvent thrown =
                new org.bukkit.event.entity.ProjectileLaunchEvent(snowball);
        listener.onOpen(open);
        listener.onPlaceEntity(place);
        listener.onInteractAtEntity(at);
        listener.onArmorStand(manipulate);
        listener.onBucketEmpty(empty);
        listener.onBucketFill(fill);
        listener.onThrow(thrown);
        return List.of(open, place, at, manipulate, empty, fill, thrown);
    }

    /** Whether {@code player} dying now keeps what they hold, with nothing dropped. */
    private boolean diesKeeping(PlayerMock player) {
        List<ItemStack> drops = new java.util.ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 3)));
        org.bukkit.event.entity.PlayerDeathEvent death = new org.bukkit.event.entity.PlayerDeathEvent(
                player,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC)
                        .build(),
                drops,
                10,
                net.kyori.adventure.text.Component.text("died"),
                true);
        listener.onDeath(death);
        return death.getKeepInventory() && death.getDrops().isEmpty() && death.getDroppedExp() == 0;
    }

    private PlayerMock inPlay(String name) {
        PlayerMock player = createPlayer(name);
        coordinator.handlePlayerJoin(player);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.inPlay(player.getUniqueId())).isTrue();
        });
        return player;
    }

    /** Whether a drop by {@code player} went through. */
    private boolean dropped(PlayerMock player) {
        Item item = player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.DIAMOND));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        listener.onDrop(event);
        return !event.isCancelled();
    }

    /** Whether a block break by {@code player} went through. */
    private boolean broke(PlayerMock player) {
        BlockBreakEvent event = new BlockBreakEvent(player.getLocation().getBlock(), player);
        listener.onBreak(event);
        return !event.isCancelled();
    }
}
