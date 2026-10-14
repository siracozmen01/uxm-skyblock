package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * On a plot nothing hurts but the void, nobody goes hungry, no item lies on the ground and no ender
 * chest opens; on an island of another mode all of it happens as it always does.
 */
@SuppressWarnings({"deprecation", "removal"})
class NothingHappensToAPlayerOnAPlotTest extends MockBukkitHarness {

    private static final int PLOT = 8;
    private static final int ELSEWHERE = 1008;

    private final Set<IslandId> plots = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private BrixService service;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpPlot() {
        world = server.addSimpleWorld("plots");
        player = createPlayer("Builder");
        Island plot = island(0);
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(plot, "plots");
        islands.cacheIsland(island(1000), "plots");
        service = new BrixService(new BrixPlotsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(plots);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return plots.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                plots.add(islandId);
            }
        });
        service.start(plot.id());
    }

    @Test
    @DisplayName("Nothing but the void hurts a player on a plot, and elsewhere everything does")
    void nothingHurts() {
        BrixRules rules = rules(BrixConfiguration.Rules.SHIPPED);
        at(PLOT);
        assertThat(hurt(rules, DamageCause.FALL)).isTrue();
        assertThat(hurt(rules, DamageCause.ENTITY_ATTACK)).isTrue();
        assertThat(hurt(rules, DamageCause.VOID)).isFalse();

        at(ELSEWHERE);
        assertThat(hurt(rules, DamageCause.FALL)).isFalse();

        at(PLOT);
        assertThat(hurt(rules(new BrixConfiguration.Rules(false, true)), DamageCause.FALL))
                .describedAs("the operator lets damage through")
                .isFalse();
    }

    @Test
    @DisplayName("Nobody goes hungry on a plot, eating still fills, and elsewhere hunger is as always")
    void nobodyGoesHungry() {
        BrixRules rules = rules(BrixConfiguration.Rules.SHIPPED);
        player.setFoodLevel(15);
        at(PLOT);
        assertThat(fed(rules, 14)).isTrue();
        assertThat(fed(rules, 18)).isFalse();

        at(ELSEWHERE);
        assertThat(fed(rules, 14)).isFalse();

        at(PLOT);
        assertThat(fed(rules(new BrixConfiguration.Rules(true, false)), 14)).isFalse();
    }

    @Test
    @DisplayName("No item is thrown or lies on the ground on a plot, whatever the file says")
    void noItemLiesOnAPlot() {
        BrixRules rules = rules(new BrixConfiguration.Rules(false, false));
        at(PLOT);
        PlayerDropItemEvent thrown = new PlayerDropItemEvent(player, mock(Item.class));
        rules.onThrow(thrown);
        assertThat(thrown.isCancelled()).isTrue();
        assertThat(spawned(rules, PLOT)).isTrue();

        at(ELSEWHERE);
        PlayerDropItemEvent thrownElsewhere = new PlayerDropItemEvent(player, mock(Item.class));
        rules.onThrow(thrownElsewhere);
        assertThat(thrownElsewhere.isCancelled()).isFalse();
        assertThat(spawned(rules, ELSEWHERE)).isFalse();
    }

    @Test
    @DisplayName("An ender chest does not open on a plot, and opens elsewhere")
    void noEnderChest() {
        BrixRules rules = rules(BrixConfiguration.Rules.SHIPPED);
        at(PLOT);
        assertThat(opened(rules, player.getEnderChest())).isFalse();
        assertThat(opened(rules, server.createInventory(null, 27))).isTrue();

        at(ELSEWHERE);
        assertThat(opened(rules, player.getEnderChest())).isTrue();
    }

    @Test
    @DisplayName("No creature comes of itself onto a plot; one a player brings comes, and elsewhere all come")
    void noCreatureComesOfItself() {
        BrixRules rules = rules(BrixConfiguration.Rules.SHIPPED);
        for (CreatureSpawnEvent.SpawnReason unbidden : List.of(
                CreatureSpawnEvent.SpawnReason.NATURAL,
                CreatureSpawnEvent.SpawnReason.JOCKEY,
                CreatureSpawnEvent.SpawnReason.MOUNT,
                CreatureSpawnEvent.SpawnReason.PATROL,
                CreatureSpawnEvent.SpawnReason.RAID,
                CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
                CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
                CreatureSpawnEvent.SpawnReason.TRAP)) {
            assertThat(came(rules, PLOT, unbidden))
                    .describedAs("%s on the plot", unbidden)
                    .isFalse();
            assertThat(came(rules, ELSEWHERE, unbidden))
                    .describedAs("%s elsewhere", unbidden)
                    .isTrue();
        }
        for (CreatureSpawnEvent.SpawnReason brought : List.of(
                CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
                CreatureSpawnEvent.SpawnReason.SPAWNER,
                CreatureSpawnEvent.SpawnReason.COMMAND,
                CreatureSpawnEvent.SpawnReason.BREEDING,
                CreatureSpawnEvent.SpawnReason.CUSTOM)) {
            assertThat(came(rules, PLOT, brought))
                    .describedAs("%s on the plot", brought)
                    .isTrue();
        }
        // The plot's bounds reach 40 from its centre at 8; its ground is laid to 60. The grid's point nearest a place
        // past the bounds names the plot it is the ground of.
        BrixRules grid = new BrixRules(service, islands, BrixConfiguration.Rules.SHIPPED, () -> 1000, 60);
        assertThat(came(rules, 60, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("past the bounds, with no ground known")
                .isTrue();
        assertThat(came(grid, 60, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("on the ground past the bounds")
                .isFalse();
        assertThat(came(grid, 68, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("its last block")
                .isFalse();
        assertThat(came(grid, 69, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("past the ground")
                .isTrue();
        assertThat(came(grid, 60, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)).isTrue();
        assertThat(came(grid, -50, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("past the bounds on the other side: the nearest point is the plot's")
                .isFalse();
        assertThat(came(grid, PLOT, 68, CreatureSpawnEvent.SpawnReason.NATURAL)).isFalse();
        assertThat(came(grid, PLOT, -50, CreatureSpawnEvent.SpawnReason.NATURAL))
                .isFalse();
        assertThat(came(grid, PLOT, 69, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("past the ground along the other axis")
                .isTrue();
        assertThat(came(grid, ELSEWHERE + 45, CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("the ground of an island that is no plot")
                .isTrue();
        assertThat(came(
                        new BrixRules(service, islands, new BrixConfiguration.Rules(true, true, false), () -> 1000, 60),
                        60,
                        CreatureSpawnEvent.SpawnReason.NATURAL))
                .isTrue();
        assertThat(came(
                        rules(new BrixConfiguration.Rules(true, true, false)),
                        PLOT,
                        CreatureSpawnEvent.SpawnReason.NATURAL))
                .describedAs("written off")
                .isTrue();
    }

    @Test
    @DisplayName("The rules are read from the file and both are on unless written off")
    void theRulesAreRead() throws Exception {
        assertThat(rulesWritten("")).isEqualTo(BrixConfiguration.Rules.SHIPPED);
        assertThat(rulesWritten("rules { no-damage = false }")).isEqualTo(new BrixConfiguration.Rules(false, true));
        assertThat(rulesWritten("rules { no-hunger = false }")).isEqualTo(new BrixConfiguration.Rules(true, false));
        assertThat(rulesWritten("rules { no-spawning = false }"))
                .isEqualTo(new BrixConfiguration.Rules(true, true, false));
        assertThat(BrixConfiguration.Rules.SHIPPED.noSpawning()).isTrue();
    }

    private BrixRules rules(BrixConfiguration.Rules written) {
        return new BrixRules(service, islands, written);
    }

    private boolean hurt(BrixRules rules, DamageCause cause) {
        EntityDamageEvent event = new EntityDamageEvent(player, cause, 4.0);
        rules.onDamage(event);
        return event.isCancelled();
    }

    private boolean fed(BrixRules rules, int level) {
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, level);
        rules.onHunger(event);
        return event.isCancelled();
    }

    private boolean came(BrixRules rules, int x, CreatureSpawnEvent.SpawnReason reason) {
        return came(rules, x, 8, reason);
    }

    private boolean came(BrixRules rules, int x, int z, CreatureSpawnEvent.SpawnReason reason) {
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getLocation()).thenReturn(new Location(world, x + 0.5, 101, z + 0.5));
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, reason);
        rules.onSpawn(event);
        return !event.isCancelled();
    }

    private boolean spawned(BrixRules rules, int x) {
        Item item = mock(Item.class);
        when(item.getLocation()).thenReturn(new Location(world, x + 0.5, 101, 8.5));
        ItemSpawnEvent event = new ItemSpawnEvent(item);
        rules.onItem(event);
        return event.isCancelled();
    }

    private boolean opened(BrixRules rules, Inventory inventory) {
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTopInventory()).thenReturn(inventory);
        InventoryOpenEvent event = new InventoryOpenEvent(view);
        rules.onEnderChest(event);
        return !event.isCancelled();
    }

    private static BrixConfiguration.Rules rulesWritten(String hocon) throws Exception {
        return BrixConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon))
                .rules();
    }

    private void at(int x) {
        player.teleport(new Location(world, x + 0.5, 101, 8.5));
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
