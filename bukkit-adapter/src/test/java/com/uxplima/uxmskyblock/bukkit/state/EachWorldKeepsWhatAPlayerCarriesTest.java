package com.uxplima.uxmskyblock.bukkit.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A server that plays several game modes gave a player one inventory, one level and one set of advancements for all
 * of them. Each group of worlds now keeps its own, and a player crossing between them puts down what they carry in
 * the group they leave and picks up what they had in the group they enter.
 */
class EachWorldKeepsWhatAPlayerCarriesTest extends MockBukkitHarness {

    private final List<ItemStack[]> kept = new ArrayList<>();
    private final Map<UUID, Set<String>> earned = new HashMap<>();
    private final List<Boolean> earnedWhileSwapping = new ArrayList<>();
    private final AtomicReference<CarriedStateConfiguration> config =
            new AtomicReference<>(CarriedStateConfiguration.SHIPPED);

    private final InventoryCodec codec = new InventoryCodec() {
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
            return kept.get(ByteBuffer.wrap(bytes).getInt());
        }
    };

    private final PlayerStates.Advancements advancements = new PlayerStates.Advancements() {
        @Override
        public Set<String> earned(Player player) {
            return earned.getOrDefault(player.getUniqueId(), Set.of());
        }

        @Override
        public void earn(Player player, Set<String> criteria) {
            earnedWhileSwapping.add(PlayerStates.isSwapping(player));
            earned.put(player.getUniqueId(), Set.copyOf(criteria));
        }
    };

    private World main;
    private World boxed;
    private World nether;
    private PlayerMock ada;
    private PlayerStates states;

    @BeforeEach
    void setUp() {
        main = server.addSimpleWorld("world");
        boxed = server.addSimpleWorld("boxed");
        nether = server.addSimpleWorld("skyblock_nether");
        ada = createPlayer("Ada");
        ada.setGameMode(GameMode.SURVIVAL);
        states = new PlayerStates(config::get, () -> List.of("world", "boxed"), codec, advancements);
    }

    private boolean to(World world) {
        ada.teleport(new Location(world, 0, 64, 0));
        return states.arrived(ada);
    }

    @Test
    @DisplayName("Crossing into another group puts everything down, and coming back picks it all up again")
    void everythingCrossesBack() {
        assertThat(to(main))
                .describedAs("the first group a player stands in is theirs")
                .isFalse();
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        ada.getEnderChest().setItem(3, new ItemStack(Material.EMERALD, 5));
        ada.setLevel(30);
        ada.setHealth(5);
        ada.setFoodLevel(7);
        ada.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 600, 1));
        earned.put(ada.getUniqueId(), Set.of("minecraft:story/mine_diamond|diamond"));

        assertThat(to(boxed)).isTrue();

        assertThat(ada.getInventory().getItem(0))
                .describedAs("nothing walks onto the Boxed island")
                .isNull();
        assertThat(ada.getEnderChest().getItem(3)).isNull();
        assertThat(ada.getLevel()).isZero();
        assertThat(ada.getHealth())
                .describedAs("a group never stood in starts at full health")
                .isEqualTo(20.0);
        assertThat(ada.getFoodLevel()).isEqualTo(20);
        assertThat(ada.getActivePotionEffects()).isEmpty();
        assertThat(earned.get(ada.getUniqueId()))
                .describedAs("so the Boxed box grows for an advancement earned elsewhere")
                .isEmpty();
        assertThat(PlayerStates.carried(ada)).contains("boxed");

        ada.getInventory().setItem(1, new ItemStack(Material.OAK_LOG, 3));
        assertThat(to(main)).isTrue();

        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 64));
        assertThat(ada.getInventory().getItem(1))
                .describedAs("the Boxed logs stay in Boxed")
                .isNull();
        assertThat(ada.getEnderChest().getItem(3)).isEqualTo(new ItemStack(Material.EMERALD, 5));
        assertThat(ada.getLevel()).isEqualTo(30);
        assertThat(ada.getHealth()).isEqualTo(5.0);
        assertThat(ada.getFoodLevel()).isEqualTo(7);
        assertThat(ada.getActivePotionEffects())
                .extracting(PotionEffect::getType)
                .containsExactly(PotionEffectType.SPEED);
        assertThat(earned.get(ada.getUniqueId())).containsExactly("minecraft:story/mine_diamond|diamond");
        assertThat(earnedWhileSwapping)
                .describedAs("advancements given back are given in the swap")
                .containsOnly(true);
        assertThat(PlayerStates.isSwapping(ada)).isFalse();

        assertThat(to(boxed)).isTrue();
        assertThat(ada.getInventory().getItem(1))
                .describedAs("and are there again")
                .isEqualTo(new ItemStack(Material.OAK_LOG, 3));
    }

    @Test
    @DisplayName("A world no group names keeps what the player brought, whichever group it was")
    void anUnnamedWorldKeepsWhatWasBrought() {
        to(main);
        to(boxed);
        ada.getInventory().setItem(0, new ItemStack(Material.OAK_LOG));

        assertThat(to(nether)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.OAK_LOG));
        assertThat(PlayerStates.carried(ada)).contains("boxed");

        assertThat(to(main))
                .describedAs("the Boxed things are put down in Boxed, not in the Nether")
                .isTrue();
        assertThat(ada.getInventory().getItem(0)).isNull();
        assertThat(to(boxed)).isTrue();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.OAK_LOG));
    }

    @Test
    @DisplayName("Worlds the file puts in one group share, and a server with it switched off swaps nothing")
    void groupsAndTheSwitch() {
        config.set(new CarriedStateConfiguration(
                true, Map.of("main", List.of("world", "boxed")), CarriedStateConfiguration.Keeps.ALL));
        to(main);
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));

        assertThat(to(boxed)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));

        config.set(new CarriedStateConfiguration(false, Map.of(), CarriedStateConfiguration.Keeps.ALL));
        assertThat(to(main)).isFalse();
        assertThat(to(boxed)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("What a group does not keep apart is carried everywhere")
    void whatIsNotKeptIsCarried() {
        config.set(new CarriedStateConfiguration(
                true, Map.of(), new CarriedStateConfiguration.Keeps(false, true, true, true, true, true, true)));
        to(main);
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        ada.setLevel(12);

        to(boxed);

        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(ada.getLevel()).describedAs("the level is still kept apart").isZero();
    }

    @Test
    @DisplayName("What a creative place kept aside is given back before the swap, so nothing made there crosses")
    void aSealIsLiftedFirst() {
        to(main);
        ada.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        new SealedInventory(codec, "brix").seal(ada);
        ada.setGameMode(GameMode.CREATIVE);
        ada.getInventory().setItem(0, new ItemStack(Material.BEDROCK, 64));

        to(boxed);
        assertThat(SealedInventory.holds(ada)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isNull();

        to(main);
        assertThat(ada.getInventory().getItem(0))
                .describedAs("the pickaxe the player brought to the plot, never the creative bedrock")
                .isEqualTo(new ItemStack(Material.IRON_PICKAXE));
    }

    @Test
    @DisplayName("An advancement handed back in a swap is not announced, and one earned outside a swap is")
    void aHandedBackAdvancementIsQuiet() {
        org.bukkit.advancement.Advancement advancement =
                org.mockito.Mockito.mock(org.bukkit.advancement.Advancement.class);
        List<net.kyori.adventure.text.@org.jspecify.annotations.Nullable Component> said = new ArrayList<>();
        PlayerStateListener[] listener = new PlayerStateListener[1];
        PlayerStates quiet =
                new PlayerStates(config::get, () -> List.of("world", "boxed"), codec, new PlayerStates.Advancements() {
                    @Override
                    public Set<String> earned(Player player) {
                        return Set.of();
                    }

                    @Override
                    public void earn(Player player, Set<String> criteria) {
                        org.bukkit.event.player.PlayerAdvancementDoneEvent event =
                                new org.bukkit.event.player.PlayerAdvancementDoneEvent(
                                        player, advancement, net.kyori.adventure.text.Component.text("done"));
                        listener[0].onAdvancement(event);
                        said.add(event.message());
                    }
                });
        listener[0] = new PlayerStateListener(quiet);
        ada.teleport(new Location(main, 0, 64, 0));
        quiet.arrived(ada);
        ada.teleport(new Location(boxed, 0, 64, 0));

        listener[0].onWorldChange(new org.bukkit.event.player.PlayerChangedWorldEvent(ada, main));
        org.bukkit.event.player.PlayerAdvancementDoneEvent outside =
                new org.bukkit.event.player.PlayerAdvancementDoneEvent(
                        ada, advancement, net.kyori.adventure.text.Component.text("done"));
        listener[0].onAdvancement(outside);

        assertThat(said).containsExactly((net.kyori.adventure.text.Component) null);
        assertThat(outside.message()).isEqualTo(net.kyori.adventure.text.Component.text("done"));
    }
}
