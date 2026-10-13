package com.uxplima.uxmskyblock.bukkit.acid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
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
 * The acid sea dissolves an item that lies in it long enough and burns a creature in it at each check, on an AcidIsland
 * island and nowhere else, each on its switch, sparing the creatures the operator names.
 */
class TheSeaDissolvesItemsAndBurnsCreaturesTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final long SECOND = 1_000_000_000L;

    private final Map<IslandId, Integer> stored = new HashMap<>();
    private final AtomicLong now = new AtomicLong(1_000 * SECOND);
    private final List<String> fired = new ArrayList<>();
    private Predicate<Entity> owned = entity -> true;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private AcidIslandService service;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpTheSea() {
        world = server.addSimpleWorld("skyblock");
        Island acid = island(0);
        Island plain = island(1000);
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(acid, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        service = new AcidIslandService(new AcidIslandsPort() {
            @Override
            public Map<IslandId, Integer> findAll() {
                return Map.copyOf(stored);
            }

            @Override
            public java.util.OptionalInt find(IslandId islandId) {
                Integer level = stored.get(islandId);
                return level == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(level);
            }

            @Override
            public void add(IslandId islandId, int seaLevel) {
                stored.putIfAbsent(islandId, seaLevel);
            }
        });
        service.add(acid.id(), Y);
        player = createPlayer("Swimmer");
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(new Location(world, 8.5, Y + 3, 8.5));
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
    }

    @Test
    @DisplayName("An item that lay in the sea as long as the operator wrote dissolves, and one on dry land stays")
    void anItemDissolves() {
        AcidSeaLife life = life(AcidIslandConfiguration.SeaLife.SHIPPED);
        Item sinking = itemIn(6, Material.WATER, new ItemStack(Material.OAK_LOG, 3));
        Item dry = itemIn(10, Material.AIR, new ItemStack(Material.DIRT));

        life.around(player);
        now.addAndGet(9 * SECOND);
        life.around(player);
        assertThat(sinking.isValid()).describedAs("nine seconds in the sea").isTrue();

        now.addAndGet(SECOND);
        life.around(player);
        assertThat(sinking.isValid()).isFalse();
        assertThat(dry.isValid()).isTrue();
        assertThat(fired).containsExactly("dissolved 3 oak log");
        assertThat(life.remembered()).isZero();
    }

    @Test
    @DisplayName("An item taken out of the sea starts again, and one in the sea of another mode stays")
    void theTimeStartsAgain() {
        AcidSeaLife life = life(AcidIslandConfiguration.SeaLife.SHIPPED);
        Item item = itemIn(6, Material.WATER, new ItemStack(Material.DIRT));
        Item elsewhere = itemIn(1006, Material.WATER, new ItemStack(Material.DIRT));
        PlayerMock far = createPlayer("Far");
        far.teleport(new Location(world, 1008.5, Y + 3, 8.5));

        life.around(player);
        now.addAndGet(6 * SECOND);
        world.getBlockAt(6, Y, 8).setType(Material.AIR);
        life.around(player);
        world.getBlockAt(6, Y, 8).setType(Material.WATER);
        life.around(player);
        now.addAndGet(6 * SECOND);
        life.around(player);
        life.around(far);
        assertThat(item.isValid())
                .describedAs("six seconds since it went back in")
                .isTrue();

        now.addAndGet(60 * SECOND);
        life.around(far);
        assertThat(elsewhere.isValid()).isTrue();
    }

    @Test
    @DisplayName("A creature in the sea burns once a check, however many players are near, and the spared do not")
    void aCreatureBurns() {
        AcidSeaLife life = life(new AcidIslandConfiguration.SeaLife(
                32, true, Duration.ofSeconds(10), true, 3.0, List.of("minecraft:Husk")));
        Zombie zombie = creatureIn(6, Material.WATER, Zombie.class);
        Zombie ashore = creatureIn(10, Material.AIR, Zombie.class);
        var husk = creatureIn(4, Material.WATER, org.bukkit.entity.Husk.class);
        PlayerMock second = createPlayer("Second");
        second.teleport(new Location(world, 8.5, Y + 3, 8.5));
        double full = zombie.getHealth();
        double huskFull = husk.getHealth();

        life.around(player);
        life.around(second);
        assertThat(zombie.getHealth()).isEqualTo(full - 3.0);
        assertThat(ashore.getHealth()).isEqualTo(full);
        assertThat(husk.getHealth()).describedAs("spared").isEqualTo(huskFull);

        now.addAndGet(SECOND);
        life.around(second);
        assertThat(zombie.getHealth()).describedAs("the next check").isEqualTo(full - 6.0);
    }

    @Test
    @DisplayName("Each switch turns its part off, and nothing a thread does not own is touched")
    void switchesAndOwners() {
        Item item = itemIn(6, Material.WATER, new ItemStack(Material.DIRT));
        Zombie zombie = creatureIn(4, Material.WATER, Zombie.class);
        double full = zombie.getHealth();

        AcidSeaLife creaturesOnly =
                life(new AcidIslandConfiguration.SeaLife(32, false, Duration.ZERO, true, 2.0, List.of()));
        creaturesOnly.around(player);
        assertThat(item.isValid()).isTrue();
        assertThat(zombie.getHealth()).isEqualTo(full - 2.0);

        AcidSeaLife itemsOnly =
                life(new AcidIslandConfiguration.SeaLife(32, true, Duration.ZERO, false, 2.0, List.of()));
        itemsOnly.around(player);
        assertThat(zombie.getHealth()).isEqualTo(full - 2.0);
        assertThat(item.isValid()).isFalse();

        assertThat(life(AcidIslandConfiguration.SeaLife.NONE).on()).isFalse();
        assertThat(life(new AcidIslandConfiguration.SeaLife(0, true, Duration.ZERO, true, 2, List.of()))
                        .on())
                .isFalse();

        Item another = itemIn(6, Material.WATER, new ItemStack(Material.DIRT));
        owned = entity -> !(entity instanceof Item);
        life(new AcidIslandConfiguration.SeaLife(32, true, Duration.ZERO, true, 2.0, List.of()))
                .around(player);
        assertThat(another.isValid()).describedAs("owned by another region").isTrue();
    }

    @Test
    @DisplayName("What no check has seen for a while is forgotten")
    void theGoneAreForgotten() {
        AcidSeaLife life = life(AcidIslandConfiguration.SeaLife.SHIPPED);
        itemIn(6, Material.WATER, new ItemStack(Material.DIRT));
        creatureIn(4, Material.WATER, Zombie.class);
        life.around(player);
        assertThat(life.remembered()).isEqualTo(2);

        now.addAndGet(19 * SECOND);
        life.forgetTheGone();
        assertThat(life.remembered()).isEqualTo(2);
        now.addAndGet(2 * SECOND);
        life.forgetTheGone();
        assertThat(life.remembered()).isZero();
    }

    @Test
    @DisplayName("sea-life is read from the file, and one that cannot be is the shipped")
    void read() throws Exception {
        assertThat(load("sea-life { reach = 16, items { dissolve = false, after = \"30s\" },"
                        + " creatures { burn = false, damage = 1.5, spared = [\"squid\"] } }"))
                .isEqualTo(new AcidIslandConfiguration.SeaLife(
                        16, false, Duration.ofSeconds(30), false, 1.5, List.of("squid")));
        assertThat(load("enabled = true")).isEqualTo(AcidIslandConfiguration.SeaLife.SHIPPED);
        assertThat(load("sea-life { reach = 65 }")).isEqualTo(AcidIslandConfiguration.SeaLife.SHIPPED);
        assertThat(load("sea-life { creatures { damage = -1 } }")).isEqualTo(AcidIslandConfiguration.SeaLife.SHIPPED);
        assertThat(AcidIslandConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString(new String(
                                        TheSeaDissolvesItemsAndBurnsCreaturesTest.class
                                                .getResourceAsStream("/modules/acidisland.conf")
                                                .readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .seaLife())
                .describedAs("the shipped file")
                .isEqualTo(AcidIslandConfiguration.SeaLife.SHIPPED);
    }

    private static AcidIslandConfiguration.SeaLife load(String written) throws Exception {
        return AcidIslandConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .seaLife();
    }

    private AcidSeaLife life(AcidIslandConfiguration.SeaLife seaLife) {
        AcidIslandConfiguration config = new AcidIslandConfiguration(
                true,
                AcidIslandConfiguration.Sea.SHIPPED,
                new AcidRules(2.0, 1.0, true, Duration.ofSeconds(1)),
                List.of(),
                List.of(),
                new AcidIslandConfiguration.Purification(true, true),
                seaLife,
                AcidIslandConfiguration.Armour.NONE);
        return new AcidSeaLife(
                service,
                islands,
                config,
                new InteractionEffects(Map.of(
                        "acid-item-dissolved", ActionList.parse(List.of("[console] probe dissolved %amount% %item%")))),
                new InteractionEffectPlayer(),
                now::get,
                entity -> owned.test(entity));
    }

    private Item itemIn(int x, Material fill, ItemStack stack) {
        world.getBlockAt(x, Y, 8).setType(fill);
        return world.dropItem(new Location(world, x + 0.5, Y + 0.2, 8.5), stack);
    }

    private <T extends Entity> T creatureIn(int x, Material fill, Class<T> type) {
        world.getBlockAt(x, Y, 8).setType(fill);
        world.getBlockAt(x, Y + 1, 8).setType(fill);
        return world.spawn(new Location(world, x + 0.5, Y, 8.5), type);
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
