package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
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
 * On a Brix plot the team builds in creative and everybody else looks on in adventure, and whatever a
 * player brought is kept aside: they have it back off the plot or off the server, and nothing they held
 * on the plot comes with them.
 */
class APlotKeepsWhatWasBroughtTest extends MockBukkitHarness {

    private final Set<IslandId> plots = new HashSet<>();
    private final List<ItemStack[]> kept = new ArrayList<>();
    private boolean unreadable;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private BrixModes modes;

    @SuppressWarnings("NullAway.Init")
    private SealedInventory sealed;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock builder;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock visitor;

    @BeforeEach
    void setUpPlot() {
        world = server.addSimpleWorld("plots");
        builder = createPlayer("Builder");
        visitor = createPlayer("Visitor");
        Island plot = island(0, builder.getUniqueId());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(plot, "plots");
        islands.cacheIsland(island(1000, UUID.randomUUID()), "plots");
        BrixService service = new BrixService(new BrixPlotsPort() {
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
        sealed = new SealedInventory(
                new InventoryCodec() {
                    @Override
                    public byte[] write(ItemStack[] items) {
                        ItemStack[] copy = new ItemStack[items.length];
                        for (int slot = 0; slot < items.length; slot++) {
                            copy[slot] = items[slot] == null ? null : items[slot].clone();
                        }
                        kept.add(copy);
                        return ByteBuffer.allocate(4).putInt(kept.size() - 1).array();
                    }

                    @Override
                    public ItemStack[] read(byte[] bytes) {
                        if (unreadable) {
                            throw new IllegalStateException("an item from a newer server");
                        }
                        return kept.get(ByteBuffer.wrap(bytes).getInt());
                    }
                },
                "brix");
        modes = modes(service, islands, BrixConfiguration.Modes.SHIPPED);
        builder.setGameMode(GameMode.SURVIVAL);
        visitor.setGameMode(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("The team builds in creative with empty hands, and has all it brought back off the plot")
    void theTeamBuildsAndHasItsThingsBack() {
        builder.setGameMode(GameMode.ADVENTURE);
        builder.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        builder.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
        builder.setLevel(7);
        builder.setExp(0.5f);
        builder.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 600, 1));

        at(builder, 8);
        assertThat(modes.check(builder)).isEqualTo(GameMode.CREATIVE);
        assertThat(builder.getGameMode()).isEqualTo(GameMode.CREATIVE);
        assertThat(builder.getInventory().isEmpty()).isTrue();
        assertThat(builder.getInventory().getHelmet()).isNull();
        assertThat(said(builder)).contains("kept safe");

        builder.getInventory().setItem(3, new ItemStack(Material.DIAMOND_BLOCK, 64));
        builder.setLevel(30);
        builder.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 6000, 2));
        modes.check(builder);
        assertThat(said(builder)).describedAs("told once, not on every beat").isEmpty();

        at(builder, 1008);
        modes.check(builder);
        assertThat(builder.getGameMode()).isEqualTo(GameMode.ADVENTURE);
        assertThat(builder.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_PICKAXE));
        assertThat(builder.getInventory().getHelmet()).isEqualTo(new ItemStack(Material.LEATHER_HELMET));
        assertThat(builder.getInventory().contains(Material.DIAMOND_BLOCK))
                .describedAs("nothing made on the plot leaves it")
                .isFalse();
        assertThat(builder.getLevel()).isEqualTo(7);
        assertThat(builder.getExp()).isEqualTo(0.5f);
        assertThat(builder.getPotionEffect(PotionEffectType.STRENGTH)).isNull();
        PotionEffect speed = builder.getPotionEffect(PotionEffectType.SPEED);
        assertThat(speed).isNotNull();
        assertThat(speed.getAmplifier()).isEqualTo(1);
        assertThat(said(builder)).contains("items back");
        assertThat(sealed.isSealed(builder)).isFalse();

        modes.check(builder);
        assertThat(said(builder))
                .describedAs("off a plot with nothing kept, nothing is said")
                .isEmpty();
    }

    @Test
    @DisplayName("A window left open closes on the way in and on the way out, so it carries nothing across")
    void anOpenWindowCloses() {
        at(builder, 8);
        builder.openInventory(server.createInventory(null, 27));
        modes.check(builder);
        assertThat(builder.getOpenInventory().getType())
                .describedAs("the window opened before the seal is shut")
                .isEqualTo(org.bukkit.event.inventory.InventoryType.CRAFTING);

        at(builder, 1008);
        builder.openInventory(server.createInventory(null, 54));
        modes.check(builder);
        assertThat(builder.getOpenInventory().getType()).isEqualTo(org.bukkit.event.inventory.InventoryType.CRAFTING);
    }

    @Test
    @DisplayName("A visitor looks on in adventure, and has their own mode and things back off the server")
    void aVisitorLooksOn() {
        visitor.getInventory().setItem(0, new ItemStack(Material.BREAD, 5));
        at(visitor, 8);
        assertThat(modes.check(visitor)).isEqualTo(GameMode.ADVENTURE);
        assertThat(visitor.getInventory().isEmpty()).isTrue();

        modes.onQuit(new PlayerQuitEvent(visitor, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(visitor.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(visitor.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.BREAD, 5));
        assertThat(sealed.isSealed(visitor)).isFalse();
    }

    @Test
    @DisplayName("What was kept is what the server writes, so a server that stopped gives it back when they come back")
    void aServerThatStoppedGivesItBack() {
        builder.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        at(builder, 8);
        modes.check(builder);
        builder.getInventory().setItem(0, new ItemStack(Material.BEDROCK, 64));
        // What the session wrote while the builder stood on the plot: a checkpoint, or its last write at the stop.
        com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord written =
                com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer.snapshotPlayer(
                        builder, new ProfileId(builder.getUniqueId()), 1L);
        // The stop: the seal lived in memory, and the player's own save still holds the plot's bedrock.
        SealedInventory.forget(builder);
        at(builder, 1008);

        BrixService after = new BrixService(new BrixPlotsPort() {
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
        // The join puts what the session wrote on the player.
        com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer.applyToPlayer(builder, written);
        modes(
                        after,
                        new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService()),
                        BrixConfiguration.Modes.SHIPPED)
                .onJoin(new PlayerJoinEvent(builder, Component.empty()));

        assertThat(builder.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(builder.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_PICKAXE));
        assertThat(builder.getInventory().contains(Material.BEDROCK))
                .describedAs("nothing made on the plot was ever written")
                .isFalse();
    }

    @Test
    @DisplayName("Items that cannot be read are left kept on the player rather than thrown away")
    void unreadableItemsStayKept() {
        builder.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        at(builder, 8);
        modes.check(builder);
        builder.getInventory().setItem(5, new ItemStack(Material.DIAMOND_BLOCK));
        unreadable = true;
        at(builder, 1008);

        modes.check(builder);

        assertThat(sealed.isSealed(builder)).isTrue();
        assertThat(builder.getGameMode())
                .describedAs("off the plot even when the items wait")
                .isEqualTo(GameMode.SURVIVAL);
        assertThat(builder.getInventory().contains(Material.DIAMOND_BLOCK))
                .describedAs("what was held on the plot is gone even when the items wait")
                .isFalse();
        unreadable = false;
        modes.check(builder);
        assertThat(builder.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_PICKAXE));
    }

    @Test
    @DisplayName("A player sealed twice keeps what was kept first, and a short read leaves no plot item behind")
    void theFirstSealHolds() {
        builder.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        assertThat(sealed.seal(builder)).isTrue();
        builder.getInventory().setItem(0, new ItemStack(Material.DIAMOND_BLOCK));
        assertThat(sealed.seal(builder)).isFalse();
        assertThat(builder.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND_BLOCK));

        // An older server kept fewer slots: what it gives back is short of the off hand.
        kept.set(0, java.util.Arrays.copyOf(kept.get(0), 36));
        builder.getInventory().setItemInOffHand(new ItemStack(Material.DIAMOND_BLOCK));
        assertThat(sealed.unseal(builder)).isTrue();
        assertThat(builder.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_PICKAXE));
        assertThat(builder.getInventory().contains(Material.DIAMOND_BLOCK)).isFalse();
        assertThat(builder.getInventory().getItemInOffHand().getType()).isEqualTo(Material.AIR);
        assertThat(sealed.unseal(builder)).isFalse();
    }

    @Test
    @DisplayName("A spectator and a player with the keep permission keep their mode and their things")
    void someKeepTheirOwn() {
        visitor.setGameMode(GameMode.SPECTATOR);
        visitor.getInventory().setItem(0, new ItemStack(Material.BREAD));
        at(visitor, 8);
        assertThat(modes.check(visitor)).isEqualTo(GameMode.SPECTATOR);
        assertThat(visitor.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.BREAD));

        builder.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(),
                BrixConfiguration.Modes.SHIPPED.keepPermission(),
                true);
        at(builder, 8);
        assertThat(modes.check(builder)).isEqualTo(GameMode.SURVIVAL);
        assertThat(sealed.isSealed(builder)).isFalse();
    }

    @Test
    @DisplayName("The modes are read from the file, and a visitor in creative or spectator falls back")
    void theModesAreBounded() throws Exception {
        assertThat(modesWritten("modes { visit = \"CREATIVE\" }")).isEqualTo(BrixConfiguration.Modes.SHIPPED);
        assertThat(modesWritten("modes { visit = \"SPECTATOR\" }")).isEqualTo(BrixConfiguration.Modes.SHIPPED);
        assertThat(modesWritten("modes { build = \"SPECTATOR\" }")).isEqualTo(BrixConfiguration.Modes.SHIPPED);
        BrixConfiguration.Modes survival =
                modesWritten("modes { build = \"survival\", visit = \"survival\", keep-permission = \"\" }");
        assertThat(survival.build()).isEqualTo(GameMode.SURVIVAL);
        assertThat(survival.visit()).isEqualTo(GameMode.SURVIVAL);
        assertThat(survival.keepPermission()).isEmpty();
    }

    private BrixModes modes(BrixService service, IslandProtectionListener islands, BrixConfiguration.Modes written) {
        return new BrixModes(
                service,
                islands,
                mock(SchedulerPort.class),
                written,
                sealed,
                Messages.bundled(),
                InteractionEffects.none(),
                new InteractionEffectPlayer());
    }

    private static BrixConfiguration.Modes modesWritten(String hocon) throws Exception {
        return BrixConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon))
                .modes();
    }

    private void at(PlayerMock player, int x) {
        player.teleport(new Location(world, x + 0.5, 101, 8.5));
    }

    /** Everything the player was told since the last look, as plain text. */
    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }

    private static Island island(int centreX, UUID owner) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(owner),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
