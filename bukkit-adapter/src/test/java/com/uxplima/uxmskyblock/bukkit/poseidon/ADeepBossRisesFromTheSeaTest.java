package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.loot.LootFiller;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonIslandsPort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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
 * Now and then the deep boss rises beside a swimmer on a Poseidon island: named, with the health written, one at a
 * time and none again for a while. Its slayer is given the loot table besides its own drops, and if nobody slays it,
 * it sinks back. The island hears each of these.
 */
class ADeepBossRisesFromTheSeaTest extends MockBukkitHarness {

    private static final int Y = 100;

    private static final PoseidonConfiguration.Boss BOSS = new PoseidonConfiguration.Boss(
            true, "zombie", 60, Duration.ofMinutes(1), 0.1, Duration.ofHours(2), Duration.ofMinutes(10), "deep");

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-14T12:00:00Z"));
    private final Set<IslandId> stored = new HashSet<>();
    private final Deque<Double> rolls = new ArrayDeque<>();
    private final List<String> heard = new ArrayList<>();
    private final List<Runnable> sinking = new ArrayList<>();
    private final List<Duration> sinkAfter = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island poseidon;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private PoseidonService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock diver;

    @BeforeEach
    void setUpTheSea() {
        world = server.addSimpleWorld("skyblock");
        poseidon = island(0);
        Island plain = island(1000);
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(poseidon, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        service = new PoseidonService(new PoseidonIslandsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stored);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stored.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stored.add(islandId);
            }
        });
        service.start(poseidon.id());
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        server.getCommandMap().register("probe", new Command("probe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                heard.add(String.join(" ", args));
                return true;
            }
        });
        diver = createPlayer("Diver");
        diver.setGameMode(GameMode.SURVIVAL);
        swimAt(8);
    }

    private DeepBoss boss(PoseidonConfiguration.Boss config) {
        InteractionEffectPlayer effects = new InteractionEffectPlayer();
        return new DeepBoss(
                config,
                service,
                islands,
                scheduler,
                Messages.bundled(),
                () -> new InteractionEffects(java.util.Map.of(
                        "poseidon-boss-rises",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe rises %player_name%")),
                        "poseidon-boss-slain",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe slain %player_name%")),
                        "poseidon-boss-sinks",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe sinks %player_name%")))),
                effects,
                () -> LootTables.of(
                        LootTables.table("deep", hocon("rolls = 1\nentries = [ { item { material = TRIDENT } } ]"))),
                new LootFiller(effects, InteractionEffects::none, (key, random, at) -> List.of()),
                (risen, after, task) -> {
                    sinkAfter.add(after);
                    sinking.add(task);
                },
                clock,
                new Random() {
                    @Override
                    public double nextDouble() {
                        return rolls.isEmpty() ? 0.99 : rolls.pop();
                    }
                });
    }

    @Test
    @DisplayName(
            "A swimmer on a Poseidon island raises it when the chance falls: named, at its health, the island told")
    void itRises() {
        DeepBoss deep = boss(BOSS);
        rolls.push(0.2);
        assertThat(deep.look(diver)).describedAs("the chance not met").isEmpty();

        rolls.push(0.05);
        Optional<LivingEntity> risen = deep.look(diver);
        assertThat(risen).isPresent();
        LivingEntity boss = risen.get();
        assertThat(boss.getType()).isEqualTo(org.bukkit.entity.EntityType.ZOMBIE);
        assertThat(boss.getHealth()).isEqualTo(60.0);
        assertThat(Objects.requireNonNull(boss.getAttribute(Attribute.MAX_HEALTH))
                        .getBaseValue())
                .isEqualTo(60.0);
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(Objects.requireNonNull(boss.customName())))
                .isEqualTo("The Deep Elder");
        assertThat(boss.isPersistent())
                .describedAs("a restart leaves none behind")
                .isFalse();
        assertThat(boss.getLocation().getBlockY())
                .describedAs("from the water below")
                .isEqualTo(Y - 2);
        assertThat(heard).containsExactly("rises Diver");
        assertThat(sinkAfter).containsExactly(Duration.ofMinutes(10));

        rolls.push(0.0);
        assertThat(deep.look(diver)).describedAs("one at a time").isEmpty();
    }

    @Test
    @DisplayName("Nobody raises it out of the water, off a Poseidon island, in creative, nor while it is off")
    void nobodyRaisesIt() {
        DeepBoss deep = boss(BOSS);
        rolls.push(0.0);
        world.getBlockAt(8, Y, 8).setType(Material.AIR);
        world.getBlockAt(8, Y + 1, 8).setType(Material.AIR);
        assertThat(deep.look(diver)).describedAs("dry").isEmpty();

        diver.teleport(new Location(world, 1008.5, Y, 8.5));
        world.getBlockAt(1008, Y, 8).setType(Material.WATER);
        rolls.push(0.0);
        assertThat(deep.look(diver)).describedAs("another mode's island").isEmpty();

        swimAt(8);
        diver.setGameMode(GameMode.CREATIVE);
        rolls.push(0.0);
        assertThat(deep.look(diver)).isEmpty();
        diver.setGameMode(GameMode.SURVIVAL);

        DeepBoss off = boss(new PoseidonConfiguration.Boss(
                false, "zombie", 60, Duration.ofMinutes(1), 1, Duration.ZERO, Duration.ofMinutes(1), ""));
        rolls.push(0.0);
        assertThat(off.look(diver)).isEmpty();
        assertThat(heard).isEmpty();

        DeepBoss unknown = boss(new PoseidonConfiguration.Boss(
                true, "no_such_beast", 60, Duration.ofMinutes(1), 1, Duration.ZERO, Duration.ofMinutes(1), ""));
        rolls.push(0.0);
        assertThat(unknown.look(diver))
                .describedAs("a creature the server has not")
                .isEmpty();
        DeepBoss dead = boss(new PoseidonConfiguration.Boss(
                true, "item", 60, Duration.ofMinutes(1), 1, Duration.ZERO, Duration.ofMinutes(1), ""));
        rolls.push(0.0);
        assertThat(dead.look(diver)).describedAs("no living creature").isEmpty();
        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("Only the players on the island hear it")
    void onlyTheIslandHears() {
        PlayerMock away = createPlayer("Away");
        away.teleport(new Location(world, 1008.5, Y, 8.5));
        rolls.push(0.0);
        assertThat(boss(BOSS).look(diver)).isPresent();
        assertThat(heard).containsExactly("rises Diver");
    }

    @Test
    @DisplayName("Nobody slays it: it sinks back after a while, the island is told, and none rises again for a while")
    void itSinks() {
        DeepBoss deep = boss(BOSS);
        rolls.push(0.0);
        LivingEntity boss = deep.look(diver).orElseThrow();
        heard.clear();

        sinking.getFirst().run();
        assertThat(boss.isValid()).isFalse();
        assertThat(heard).containsExactly("sinks Diver");

        clock.advance(Duration.ofHours(2).minusSeconds(1));
        rolls.push(0.0);
        assertThat(deep.look(diver)).describedAs("within every of the last").isEmpty();
        clock.advance(Duration.ofSeconds(1));
        rolls.push(0.0);
        LivingEntity next = deep.look(diver).orElseThrow();

        // A boss the server took away with its chunk is not there to sink: the island is free again, quietly.
        next.remove();
        heard.clear();
        sinking.getLast().run();
        assertThat(heard).isEmpty();
        clock.advance(Duration.ofHours(2));
        rolls.push(0.0);
        assertThat(deep.look(diver)).describedAs("the island is free again").isPresent();
    }

    @Test
    @DisplayName("Its slayer is given the loot table besides its drops; the island is told; a slain one does not sink")
    void itIsSlain() {
        DeepBoss deep = boss(BOSS);
        rolls.push(0.0);
        LivingEntity boss = deep.look(diver).orElseThrow();
        heard.clear();
        boss.setKiller(diver);
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH)));

        deep.onDeath(new EntityDeathEvent(
                boss, DamageSource.builder(DamageType.PLAYER_ATTACK).build(), drops));

        assertThat(drops).extracting(ItemStack::getType).containsExactly(Material.ROTTEN_FLESH, Material.TRIDENT);
        assertThat(heard).containsExactly("slain Diver");
        sinking.getFirst().run();
        assertThat(heard).describedAs("a slain one does not sink").containsExactly("slain Diver");

        rolls.push(0.0);
        assertThat(deep.look(diver)).describedAs("none again for a while").isEmpty();

        LivingEntity stranger =
                (LivingEntity) world.spawnEntity(new Location(world, 8, Y, 8), org.bukkit.entity.EntityType.ZOMBIE);
        stranger.setKiller(diver);
        List<ItemStack> theirs = new ArrayList<>();
        deep.onDeath(new EntityDeathEvent(
                stranger, DamageSource.builder(DamageType.GENERIC).build(), theirs));
        assertThat(theirs)
                .describedAs("an unmarked creature is none of its business")
                .isEmpty();
        assertThat(heard).hasSize(1);
        assertThat(DeepBoss.carried(
                        new ItemStack[] {null, new ItemStack(Material.AIR), new ItemStack(Material.TRIDENT)}))
                .extracting(ItemStack::getType)
                .containsExactly(Material.TRIDENT);
    }

    @Test
    @DisplayName("Slain by nobody, or with a table loot/ has not, it gives no table")
    void noTable() {
        DeepBoss deep = boss(BOSS);
        rolls.push(0.0);
        LivingEntity boss = deep.look(diver).orElseThrow();
        List<ItemStack> drops = new ArrayList<>();
        deep.onDeath(new EntityDeathEvent(
                boss, DamageSource.builder(DamageType.GENERIC).build(), drops));
        assertThat(drops).isEmpty();

        clock.advance(Duration.ofHours(3));
        DeepBoss missing = boss(new PoseidonConfiguration.Boss(
                true, "zombie", 60, Duration.ofMinutes(1), 1, Duration.ZERO, Duration.ofMinutes(1), "gone"));
        rolls.push(0.0);
        LivingEntity other = missing.look(diver).orElseThrow();
        other.setKiller(diver);
        List<ItemStack> none = new ArrayList<>();
        missing.onDeath(new EntityDeathEvent(
                other, DamageSource.builder(DamageType.PLAYER_ATTACK).build(), none));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("The shipped boss, its block read from the file, a bad one the shipped, and the shipped loot table")
    void theShippedFiles() throws Exception {
        assertThat(PoseidonConfiguration.load(hocon(resource("modules/poseidon.conf")))
                        .boss())
                .isEqualTo(PoseidonConfiguration.Boss.SHIPPED);
        assertThat(PoseidonConfiguration.load(hocon("boss { enabled = false, entity = \" Drowned \", health = 40,"
                                + " check-every = 30s, chance = 1, every = 0s, lasts = 2m, loot = \"\" }"))
                        .boss())
                .isEqualTo(new PoseidonConfiguration.Boss(
                        false, "drowned", 40, Duration.ofSeconds(30), 1, Duration.ZERO, Duration.ofMinutes(2), ""));
        for (String bad : List.of(
                "boss { health = 0 }", "boss { chance = 1.5 }", "boss { chance = -0.1 }", "boss { lasts = 0s }")) {
            assertThat(PoseidonConfiguration.load(hocon(bad)).boss())
                    .describedAs(bad)
                    .isEqualTo(PoseidonConfiguration.Boss.SHIPPED);
        }
        assertThat(LootTables.table("deep_boss", hocon(resource("loot/deep_boss.conf")))
                        .id())
                .isEqualTo("deep_boss");
    }

    private void swimAt(int x) {
        world.getBlockAt(x, Y - 2, 8).setType(Material.WATER);
        world.getBlockAt(x, Y, 8).setType(Material.WATER);
        world.getBlockAt(x, Y + 1, 8).setType(Material.WATER);
        diver.teleport(new Location(world, x + 0.5, Y, 8.5));
    }

    private static org.spongepowered.configurate.ConfigurationNode hocon(String text) {
        try {
            return HoconConfigurationLoader.builder().buildAndLoadString(text);
        } catch (org.spongepowered.configurate.ConfigurateException e) {
            throw new IllegalStateException(e);
        }
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    /** A clock the test moves on by hand. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
