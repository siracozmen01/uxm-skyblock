package com.uxplima.uxmskyblock.bukkit.islandeffect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An island's effect upgrades put their potion effects on its players, the way SuperiorSkyblock2's island effects
 * and IridiumSkyblock's potion enhancements do. This plugin had none.
 */
class AnIslandPutsItsEffectsOnItsPlayersTest extends MockBukkitHarness {

    private static final UpgradeId EFFECTS = UpgradeId.of("island_effects");
    private static final UpgradeId BEACON = UpgradeId.of("beacon");

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock member;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock visitor;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    private final Map<UpgradeId, Integer> bought = new HashMap<>();
    private final Map<UpgradeId, UpgradeDefinition> definitions = new HashMap<>();

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        member = createPlayer("Member");
        visitor = createPlayer("Visitor");
        PlayerMock owner = createPlayer("Owner");
        island = Island.create(
                        IslandId.of(UUID.randomUUID()),
                        IslandBounds.fromCenterAndRadius(0, 0, 32),
                        new PlayerUuid(owner.getUniqueId()),
                        new ProfileId(owner.getUniqueId()),
                        Instant.now())
                .addMember(new IslandMember(
                        new PlayerUuid(member.getUniqueId()),
                        new ProfileId(member.getUniqueId()),
                        IslandRole.MEMBER,
                        Instant.now()));
        member.setLocation(new Location(world, 5, 100, 5));
        visitor.setLocation(new Location(world, 5, 100, 5));
        definitions.put(
                EFFECTS,
                new UpgradeDefinition(
                        EFFECTS,
                        "Effects",
                        List.of(
                                new UpgradeTier(1, 1L, "PRIMARY", Map.of("haste", 1.0, "luck", 0.0)),
                                new UpgradeTier(
                                        2, 2L, "PRIMARY", Map.of("haste", 1.0, "speed", 2.0, "no_such", 1.0)))));
        definitions.put(
                BEACON,
                new UpgradeDefinition(
                        BEACON, "Beacon", List.of(new UpgradeTier(1, 1L, "PRIMARY", Map.of("haste", 3.0)))));
    }

    private IslandEffects effects(IslandEffectsConfiguration config, SchedulerPort scheduler) {
        return new IslandEffects(
                config,
                at -> world.equals(at.getWorld()) && island.bounds().contains(at.getBlockX(), at.getBlockZ())
                        ? Optional.of(island)
                        : Optional.empty(),
                uuid -> Optional.of(new ProfileId(uuid)),
                () -> definitions,
                (id, upgrade) -> bought.getOrDefault(upgrade, 0),
                scheduler,
                () -> List.of(member, visitor));
    }

    private IslandEffects effects(IslandEffectsConfiguration config) {
        return effects(config, mock(SchedulerPort.class));
    }

    private static IslandEffectsConfiguration shipped() {
        return new IslandEffectsConfiguration(true, 3, false, List.of(EFFECTS, BEACON));
    }

    @Test
    @DisplayName(
            "A member wears the effects of the tier bought, a little longer than the beat, and an unknown one is passed over")
    void aMemberWearsTheTier() {
        assertThat(effects(shipped()).wear(member))
                .describedAs("nothing bought")
                .isEmpty();
        bought.put(EFFECTS, -1);
        bought.put(BEACON, 1);
        assertThat(effects(shipped()).wear(member))
                .describedAs("an upgrade not read yet gives nothing this beat, the other still does")
                .containsOnly(Map.entry(PotionEffectType.HASTE, 3));
        bought.remove(BEACON);
        UpgradeId free = UpgradeId.of("free_haste");
        definitions.put(
                free,
                new UpgradeDefinition(free, "Free", List.of(new UpgradeTier(1, 0L, "PRIMARY", Map.of("haste", 1.0)))));
        bought.put(free, -1);
        assertThat(effects(new IslandEffectsConfiguration(true, 3, false, List.of(free)))
                        .wear(member))
                .describedAs("a free first tier is not worn before the island is read")
                .isEmpty();
        bought.put(free, 0);
        assertThat(effects(new IslandEffectsConfiguration(true, 3, false, List.of(free)))
                        .wear(member))
                .describedAs("read, the free tier is held")
                .containsKey(PotionEffectType.HASTE);

        bought.put(EFFECTS, 2);
        Map<PotionEffectType, Integer> worn = effects(shipped()).wear(member);

        assertThat(worn).containsOnly(Map.entry(PotionEffectType.HASTE, 1), Map.entry(PotionEffectType.SPEED, 2));
        PotionEffect speed = member.getPotionEffect(PotionEffectType.SPEED);
        assertThat(speed).isNotNull();
        assertThat(speed.getAmplifier()).isEqualTo(1);
        assertThat(speed.getDuration()).isEqualTo(3 * 20 + IslandEffects.OVERLAP_TICKS);
        assertThat(speed.hasParticles()).isFalse();
    }

    @Test
    @DisplayName("Where two upgrades name one effect the higher level is worn")
    void theHigherLevelWins() {
        bought.put(EFFECTS, 1);
        bought.put(BEACON, 1);

        assertThat(effects(shipped()).wear(member)).containsEntry(PotionEffectType.HASTE, 3);
        assertThat(effects(new IslandEffectsConfiguration(true, 3, false, List.of(BEACON, EFFECTS)))
                        .wear(member))
                .describedAs("whichever the file names first")
                .containsEntry(PotionEffectType.HASTE, 3)
                .describedAs("a level below one is no effect")
                .doesNotContainKey(PotionEffectType.LUCK);
    }

    @Test
    @DisplayName("A visitor wears nothing unless the file says visitors do, and off the island nobody does")
    void visitorsAndElsewhere() {
        bought.put(EFFECTS, 1);
        assertThat(effects(shipped()).wear(visitor)).isEmpty();
        assertThat(effects(new IslandEffectsConfiguration(true, 3, true, List.of(EFFECTS)))
                        .wear(visitor))
                .containsKey(PotionEffectType.HASTE);

        member.setLocation(new Location(world, 500, 100, 500));
        assertThat(effects(shipped()).wear(member)).isEmpty();
        member.setLocation(new Location(world, 5, 100, 5));
        assertThat(effects(new IslandEffectsConfiguration(true, 3, false, List.of(BEACON)))
                        .wear(member))
                .describedAs("an upgrade the file does not name gives nothing")
                .isEmpty();
        assertThat(effects(new IslandEffectsConfiguration(false, 3, false, List.of(EFFECTS)))
                        .wear(member))
                .describedAs("turned off")
                .isEmpty();
    }

    @Test
    @DisplayName("The beat runs on the file's period and puts the effects on each player on their own thread")
    void theBeat() throws Exception {
        bought.put(EFFECTS, 1);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        List<Runnable> beats = new ArrayList<>();
        List<Duration> periods = new ArrayList<>();
        when(scheduler.repeatGlobal(any(Runnable.class), any(Duration.class), any(Duration.class)))
                .thenAnswer(call -> {
                    beats.add(call.getArgument(0));
                    periods.add(call.getArgument(2));
                    return (AutoCloseable) () -> {};
                });
        List<PlayerUuid> threads = new ArrayList<>();
        doAnswer(call -> {
                    threads.add(call.getArgument(0));
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));

        effects(new IslandEffectsConfiguration(true, 5, false, List.of(EFFECTS)), scheduler)
                .start()
                .close();
        beats.getFirst().run();

        assertThat(periods).containsExactly(Duration.ofSeconds(5));
        assertThat(threads)
                .containsExactly(new PlayerUuid(member.getUniqueId()), new PlayerUuid(visitor.getUniqueId()));
        assertThat(member.hasPotionEffect(PotionEffectType.HASTE)).isTrue();

        effects(new IslandEffectsConfiguration(false, 5, false, List.of(EFFECTS)), scheduler)
                .start()
                .close();
        assertThat(beats).describedAs("turned off, no beat").hasSize(1);
    }
}
