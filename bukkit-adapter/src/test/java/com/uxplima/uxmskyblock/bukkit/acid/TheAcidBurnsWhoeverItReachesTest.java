package com.uxplima.uxmskyblock.bukkit.acid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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

/**
 * The acid sea burns whoever swims in it and the acid rain whoever it falls on, on an AcidIsland island
 * and nowhere else.
 *
 * <p>The sea is kept off by an effect the operator names, and the rain by a helmet or a roof. The sea
 * gives its effects as it burns. A player in creative is not touched.
 */
class TheAcidBurnsWhoeverItReachesTest extends MockBukkitHarness {

    private static final int Y = 100;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private AcidHazard hazard;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    private final Map<IslandId, Integer> stored = new HashMap<>();

    @BeforeEach
    void setUpIslands() {
        world = server.addSimpleWorld("skyblock");
        world.setStorm(false);
        Island acid = island(0);
        Island plain = island(1000);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(acid, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        AcidIslandService service = new AcidIslandService(new AcidIslandsPort() {
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
        AcidIslandConfiguration config = new AcidIslandConfiguration(
                true,
                new AcidIslandConfiguration.Sea(
                        2, 4, 64, "SAND", new AcidIslandConfiguration.Vents(0.01, 3, List.of("nausea:0:6")), 0),
                new AcidRules(2.0, 1.0, true, Duration.ofSeconds(1)),
                List.of("water_breathing", "no_such_effect"),
                List.of("poison:1:4", "nausea", "no_such_effect:0:3"),
                new AcidIslandConfiguration.Purification(true, true),
                AcidIslandConfiguration.SeaLife.NONE);
        hazard = new AcidHazard(
                service,
                islands,
                mock(SchedulerPort.class),
                config,
                InteractionEffects.none(),
                new InteractionEffectPlayer());
        player = createPlayer("Swimmer");
        player.setGameMode(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("The sea burns a swimmer on an AcidIsland island and poisons them as the operator wrote")
    void theSeaBurns() {
        standIn(8, Material.WATER);

        assertThat(hazard.check(player)).isEqualTo(2.0);
        assertThat(player.getHealth()).isEqualTo(18.0);
        PotionEffect poison = player.getPotionEffect(PotionEffectType.POISON);
        assertThat(poison).isNotNull();
        assertThat(poison.getAmplifier()).isEqualTo(1);
        assertThat(poison.getDuration()).isEqualTo(80);
    }

    @Test
    @DisplayName("An effect the operator names keeps the sea off")
    void aNamedEffectProtects() {
        standIn(8, Material.WATER);
        player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 200, 0));

        assertThat(hazard.check(player)).isZero();
        assertThat(player.getPotionEffect(PotionEffectType.POISON)).isNull();
    }

    @Test
    @DisplayName("Water on an island of another mode, and dry land on the acid one, burn nobody")
    void onlyTheAcidSeaBurns() {
        standIn(1008, Material.WATER);
        assertThat(hazard.check(player)).isZero();

        standIn(8, Material.AIR);
        assertThat(hazard.check(player)).isZero();
        assertThat(player.getHealth()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("The rain burns under an open sky, and a helmet or a roof keeps it off")
    void theRainBurnsUnderTheSky() {
        standIn(8, Material.AIR);
        world.setStorm(true);

        assertThat(hazard.check(player)).isEqualTo(1.0);
        assertThat(player.getPotionEffect(PotionEffectType.POISON))
                .describedAs("the rain gives the sea's effects to nobody")
                .isNull();

        player.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
        assertThat(hazard.check(player)).isZero();

        player.getInventory().setHelmet(null);
        world.getBlockAt(8, Y + 10, 8).setType(Material.STONE);
        assertThat(hazard.check(player)).isZero();
    }

    @Test
    @DisplayName("A vent's fumes reach a swimmer within its reach, and one further off breathes clean")
    void theVentsFume() {
        int floor = Y - 4;
        world.getBlockAt(11, floor, 8).setType(AcidSeaStart.VENT);
        standIn(8, Material.WATER);
        player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 200, 0));

        hazard.check(player);
        assertThat(player.getPotionEffect(PotionEffectType.NAUSEA))
                .describedAs("no effect keeps the fumes off")
                .isNotNull();

        player.removePotionEffect(PotionEffectType.NAUSEA);
        standIn(4, Material.WATER);
        hazard.check(player);
        assertThat(player.getPotionEffect(PotionEffectType.NAUSEA)).isNull();

        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.teleport(new Location(world, 8.5, Y + 5, 8.5));
        hazard.check(player);
        assertThat(player.getPotionEffect(PotionEffectType.NAUSEA))
                .describedAs("high above the sea the fumes are gone")
                .isNull();
    }

    @Test
    @DisplayName("A player in creative swims in the acid untouched")
    void creativeIsUntouched() {
        standIn(8, Material.WATER);
        world.setStorm(true);
        player.setGameMode(GameMode.CREATIVE);

        assertThat(hazard.check(player)).isZero();
    }

    @Test
    @DisplayName("An effect line that cannot be read is dropped, and the lines beside it still count")
    void aBadLineIsDropped() {
        assertThat(AcidHazard.effectWritten("poison:0:3")).isNotNull();
        assertThat(AcidHazard.effectWritten("nausea")).isNull();
        assertThat(AcidHazard.effectWritten("poison:x:3")).isNull();
        assertThat(AcidHazard.effectWritten("poison:0:0")).isNull();
        assertThat(AcidHazard.effectWritten("no_such_effect:0:3")).isNull();
    }

    private void standIn(int x, Material fill) {
        world.getBlockAt(x, Y, 8).setType(fill);
        world.getBlockAt(x, Y + 1, 8).setType(fill);
        player.teleport(new Location(world, x + 0.5, Y, 8.5));
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
