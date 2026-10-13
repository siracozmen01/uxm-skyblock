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

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import io.papermc.paper.potion.PotionMix;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
 * Water taken on an AcidIsland island is acid until it is made clean, in a furnace, in a brewing stand
 * with coal, or by filling the bottle from a cauldron, which holds the rain.
 */
class AcidWaterIsMadeCleanTest extends MockBukkitHarness {

    private static final int Y = 100;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private AcidWaterListener listener;

    @SuppressWarnings("NullAway.Init")
    private java.util.function.Function<AcidIslandConfiguration.CleanWater, AcidWaterListener> healing;

    private final List<String> fired = new java.util.ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    private final Map<IslandId, Integer> stored = new HashMap<>();

    @BeforeEach
    void setUpIslands() {
        world = server.addSimpleWorld("skyblock");
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
                AcidIslandConfiguration.Sea.SHIPPED,
                new AcidRules(3.0, 1.0, true, Duration.ofSeconds(1)),
                List.of("water_breathing"),
                List.of("poison:0:3"),
                new AcidIslandConfiguration.Purification(true, true),
                AcidIslandConfiguration.SeaLife.NONE,
                AcidIslandConfiguration.Armour.NONE,
                AcidIslandConfiguration.CleanWater.NONE,
                AcidIslandConfiguration.Look.SHIPPED,
                AcidIslandConfiguration.Buried.NONE);
        AcidHazard hazard = new AcidHazard(
                service,
                islands,
                mock(SchedulerPort.class),
                config,
                InteractionEffects.none(),
                new InteractionEffectPlayer());
        listener = new AcidWaterListener(hazard, Messages.bundled());
        healing = clean -> new AcidWaterListener(
                new AcidHazard(
                        service,
                        islands,
                        mock(SchedulerPort.class),
                        new AcidIslandConfiguration(
                                true,
                                AcidIslandConfiguration.Sea.SHIPPED,
                                new AcidRules(3.0, 1.0, true, Duration.ofSeconds(1)),
                                List.of(),
                                List.of("poison:0:3"),
                                new AcidIslandConfiguration.Purification(true, true),
                                AcidIslandConfiguration.SeaLife.NONE,
                                AcidIslandConfiguration.Armour.NONE,
                                clean,
                                AcidIslandConfiguration.Look.SHIPPED,
                                AcidIslandConfiguration.Buried.NONE),
                        new InteractionEffects(java.util.Map.of(
                                "acid-clean-water-drunk",
                                com.uxplima.uxmlib.condition.action.ActionList.parse(
                                        List.of("[console] probe healed %healed%")))),
                        new InteractionEffectPlayer()),
                Messages.bundled());
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        player = createPlayer("Drinker");
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(new Location(world, 8.5, Y, 8.5));
    }

    @Test
    @DisplayName("A plain water bottle is acid, a clean one is not, and another potion is neither")
    void whichBottleIsAcid() {
        ItemStack swiftness = new ItemStack(Material.POTION);
        swiftness.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(PotionType.SWIFTNESS));

        assertThat(AcidWater.isAcid(AcidWater.waterBottle())).isTrue();
        assertThat(AcidWater.isAcid(AcidWater.cleanBottle())).isFalse();
        assertThat(AcidWater.isClean(AcidWater.cleanBottle())).isTrue();
        assertThat(AcidWater.isAcid(swiftness)).isFalse();
        assertThat(AcidWater.isAcid(new ItemStack(Material.GLASS_BOTTLE))).isFalse();
        assertThat(AcidWater.cleanBottle().getItemMeta().getEnchantmentGlintOverride())
                .describedAs("a clean bottle is told apart at a glance")
                .isTrue();
    }

    @Test
    @DisplayName("A furnace and a brewing stand with coal take an acid bottle and give a clean one")
    void theFurnaceAndTheBrewingStandClean() {
        FurnaceRecipe furnace = AcidWater.furnaceRecipe();
        assertThat(furnace.getInputChoice().test(AcidWater.waterBottle())).isTrue();
        assertThat(furnace.getInputChoice().test(AcidWater.cleanBottle())).isFalse();
        assertThat(AcidWater.isClean(furnace.getResult())).isTrue();

        PotionMix brewing = AcidWater.brewingMix();
        assertThat(brewing.getInput().test(AcidWater.waterBottle())).isTrue();
        assertThat(brewing.getInput().test(AcidWater.cleanBottle())).isFalse();
        assertThat(brewing.getIngredient().test(new ItemStack(Material.COAL))).isTrue();
        assertThat(brewing.getIngredient().test(new ItemStack(Material.CHARCOAL)))
                .isTrue();
        assertThat(brewing.getIngredient().test(new ItemStack(Material.STICK))).isFalse();
        assertThat(AcidWater.isClean(brewing.getResult())).isTrue();
    }

    @Test
    @DisplayName("Acid water drunk on an AcidIsland island burns and poisons, and the player is told why")
    void acidWaterBurns() {
        listener.onDrink(new PlayerItemConsumeEvent(player, AcidWater.waterBottle(), EquipmentSlot.HAND));

        assertThat(player.getHealth()).isEqualTo(17.0);
        assertThat(player.getPotionEffect(PotionEffectType.POISON)).isNotNull();
        assertThat(said(player)).anySatisfy(line -> assertThat(line).contains("acid water"));
    }

    @Test
    @DisplayName(
            "Clean water drunk on an AcidIsland island heals as the operator wrote, up to the most, with its effects")
    void cleanWaterHeals() {
        AcidWaterListener heals =
                healing.apply(new AcidIslandConfiguration.CleanWater(5.0, List.of("regeneration:0:5")));
        player.setHealth(10.0);
        player.nextComponentMessage();

        heals.onDrink(new PlayerItemConsumeEvent(player, AcidWater.cleanBottle(), EquipmentSlot.HAND));

        assertThat(player.getHealth()).isEqualTo(15.0);
        assertThat(player.getPotionEffect(PotionEffectType.REGENERATION))
                .extracting(org.bukkit.potion.PotionEffect::getDuration)
                .isEqualTo(100);
        assertThat(player.getPotionEffect(PotionEffectType.POISON)).isNull();
        assertThat(fired).containsExactly("healed 5");
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(player.nextComponentMessage()))
                .contains("5 health");

        player.setHealth(18.5);
        heals.onDrink(new PlayerItemConsumeEvent(player, AcidWater.cleanBottle(), EquipmentSlot.HAND));
        assertThat(player.getHealth()).describedAs("up to the most").isEqualTo(20.0);
        assertThat(fired).last().isEqualTo("healed 1.5");
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(player.nextComponentMessage()))
                .contains("1.5 health");

        player.removePotionEffect(PotionEffectType.REGENERATION);
        heals.onDrink(new PlayerItemConsumeEvent(player, AcidWater.cleanBottle(), EquipmentSlot.HAND));
        assertThat(player.getPotionEffect(PotionEffectType.REGENERATION))
                .describedAs("the effects even at full health")
                .isNotNull();
        assertThat(player.nextComponentMessage())
                .describedAs("nothing to say at full health")
                .isNull();

        player.setHealth(10.0);
        player.teleport(new Location(world, 1008.5, Y, 8.5));
        heals.onDrink(new PlayerItemConsumeEvent(player, AcidWater.cleanBottle(), EquipmentSlot.HAND));
        assertThat(player.getHealth())
                .describedAs("on an island of another mode")
                .isEqualTo(10.0);
        player.teleport(new Location(world, 8.5, Y, 8.5));
        heals.onDrink(new PlayerItemConsumeEvent(player, AcidWater.waterBottle(), EquipmentSlot.HAND));
        assertThat(player.getHealth()).describedAs("acid water does not heal").isEqualTo(7.0);
    }

    @Test
    @DisplayName("What clean water does is read from the file, and what cannot be is the shipped")
    void cleanWaterIsRead() throws Exception {
        assertThat(cleanOf("clean-water { heals = 6, effects = [\"regeneration:0:5\"] }"))
                .isEqualTo(new AcidIslandConfiguration.CleanWater(6, List.of("regeneration:0:5")));
        assertThat(cleanOf("enabled = true")).isEqualTo(AcidIslandConfiguration.CleanWater.SHIPPED);
        assertThat(cleanOf("clean-water { heals = -1 }")).isEqualTo(AcidIslandConfiguration.CleanWater.SHIPPED);
        assertThat(cleanOf("clean-water { heals = 1025 }")).isEqualTo(AcidIslandConfiguration.CleanWater.SHIPPED);
        assertThat(cleanOf(new String(
                        AcidWaterIsMadeCleanTest.class
                                .getResourceAsStream("/modules/acidisland.conf")
                                .readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(AcidIslandConfiguration.CleanWater.SHIPPED);
    }

    private static AcidIslandConfiguration.CleanWater cleanOf(String written) throws Exception {
        return AcidIslandConfiguration.load(org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                        .buildAndLoadString(written))
                .cleanWater();
    }

    @Test
    @DisplayName("Clean water, and any water drunk on an island of another mode, is only water")
    void cleanWaterIsWater() {
        listener.onDrink(new PlayerItemConsumeEvent(player, AcidWater.cleanBottle(), EquipmentSlot.HAND));
        player.teleport(new Location(world, 1008.5, Y, 8.5));
        listener.onDrink(new PlayerItemConsumeEvent(player, AcidWater.waterBottle(), EquipmentSlot.HAND));

        assertThat(player.getHealth()).isEqualTo(20.0);
        assertThat(player.getPotionEffect(PotionEffectType.POISON)).isNull();
    }

    @Test
    @DisplayName(
            "A bottle filled from a cauldron on an AcidIsland island comes out clean, and the cauldron drops a level")
    void aCauldronGivesCleanWater() {
        Block cauldron = world.getBlockAt(10, Y, 8);
        cauldron.setType(Material.WATER_CAULDRON);
        Levelled full = (Levelled) cauldron.getBlockData();
        full.setLevel(3);
        cauldron.setBlockData(full);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE, 2));

        CauldronLevelChangeEvent fill = fill(cauldron, 2);
        listener.onCauldron(fill);

        assertThat(fill.isCancelled()).isTrue();
        assertThat(((Levelled) cauldron.getBlockData()).getLevel()).isEqualTo(2);
        assertThat(player.getInventory().getItemInMainHand().getAmount()).isEqualTo(1);
        assertThat(player.getInventory().all(Material.POTION).values())
                .singleElement()
                .satisfies(bottle -> assertThat(AcidWater.isClean(bottle)).isTrue());

        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE, 1));
        listener.onCauldron(fill(cauldron, 1));
        assertThat(AcidWater.isClean(player.getInventory().getItemInMainHand())).isTrue();
    }

    @Test
    @DisplayName("A cauldron on an island of another mode is left to the server")
    void aCauldronElsewhereIsLeftAlone() {
        Block cauldron = world.getBlockAt(1010, Y, 8);
        cauldron.setType(Material.WATER_CAULDRON);
        player.teleport(new Location(world, 1008.5, Y, 8.5));
        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE, 1));

        CauldronLevelChangeEvent fill = fill(cauldron, 1);
        listener.onCauldron(fill);

        assertThat(fill.isCancelled()).isFalse();
        assertThat(player.getInventory().getItemInMainHand().getType()).isEqualTo(Material.GLASS_BOTTLE);
    }

    @Test
    @DisplayName("A cauldron sea water was poured into gives acid bottles until it is emptied")
    void aCauldronOfSeaWaterIsAcid() {
        Block cauldron = world.getBlockAt(10, Y, 8);
        cauldron.setType(Material.CAULDRON);
        player.getInventory().setItemInMainHand(new ItemStack(Material.WATER_BUCKET));

        listener.onCauldron(change(cauldron, CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY, 3));
        cauldron.setType(Material.WATER_CAULDRON);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
        CauldronLevelChangeEvent bottle = fill(cauldron, 2);
        listener.onCauldron(bottle);

        assertThat(AcidWater.isAcidCauldron(cauldron)).isTrue();
        assertThat(bottle.isCancelled())
                .describedAs("the server fills a plain bottle, and a plain bottle is acid here")
                .isFalse();

        listener.onCauldron(change(cauldron, CauldronLevelChangeEvent.ChangeReason.EVAPORATE, 0));
        assertThat(AcidWater.isAcidCauldron(cauldron)).isFalse();
    }

    @Test
    @DisplayName("A bucket made clean in a furnace keeps the cauldron it is poured into clean")
    void aCleanBucketKeepsTheCauldronClean() {
        Block cauldron = world.getBlockAt(10, Y, 8);
        cauldron.setType(Material.CAULDRON);
        FurnaceRecipe bucket = AcidWater.furnaceBucketRecipe();
        assertThat(bucket.getInputChoice().test(new ItemStack(Material.WATER_BUCKET)))
                .isTrue();
        assertThat(bucket.getInputChoice().test(AcidWater.cleanBucket())).isFalse();
        player.getInventory().setItemInMainHand(bucket.getResult());

        listener.onCauldron(change(cauldron, CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY, 3));
        cauldron.setType(Material.WATER_CAULDRON);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
        listener.onCauldron(fill(cauldron, 2));

        assertThat(AcidWater.isAcidCauldron(cauldron)).isFalse();
        assertThat(AcidWater.isClean(player.getInventory().getItemInMainHand())).isTrue();
    }

    private CauldronLevelChangeEvent change(Block cauldron, CauldronLevelChangeEvent.ChangeReason reason, int level) {
        BlockState next = cauldron.getState();
        if (level == 0) {
            next.setType(Material.CAULDRON);
        } else {
            next.setType(Material.WATER_CAULDRON);
            Levelled data = (Levelled) next.getBlockData();
            data.setLevel(level);
            next.setBlockData(data);
        }
        return new CauldronLevelChangeEvent(cauldron, player, reason, next);
    }

    private CauldronLevelChangeEvent fill(Block cauldron, int level) {
        BlockState next = cauldron.getState();
        Levelled data = (Levelled) next.getBlockData();
        data.setLevel(level);
        next.setBlockData(data);
        return new CauldronLevelChangeEvent(cauldron, player, CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL, next);
    }

    private static List<String> said(PlayerMock player) {
        List<String> said = new ArrayList<>();
        for (Component next = player.nextComponentMessage(); next != null; next = player.nextComponentMessage()) {
            said.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return said;
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
