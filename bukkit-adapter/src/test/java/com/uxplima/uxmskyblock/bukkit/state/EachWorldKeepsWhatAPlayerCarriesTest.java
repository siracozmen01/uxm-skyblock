package com.uxplima.uxmskyblock.bukkit.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A server that plays several game modes gave a player one inventory, one level and one set of advancements for all
 * of them. Each group of worlds now keeps its own, written with the profile's inventory, and a player crossing
 * between them puts down what they carry in the group they leave and picks up what they had in the group they enter.
 */
class EachWorldKeepsWhatAPlayerCarriesTest extends MockBukkitHarness {

    private final List<ItemStack[]> sealed = new ArrayList<>();
    private final Map<UUID, Set<String>> earned = new HashMap<>();
    private final List<Boolean> earnedWhileSwapping = new ArrayList<>();
    private final AtomicReference<CarriedStateConfiguration> config =
            new AtomicReference<>(CarriedStateConfiguration.SHIPPED);
    private final CarriedStatesInMemory store = new CarriedStatesInMemory();
    private final Deque<Runnable> held = new ArrayDeque<>();
    private boolean holdAsync;

    private final InventoryCodec codec = new InventoryCodec() {
        @Override
        public byte[] write(ItemStack[] items) {
            ItemStack[] copy = new ItemStack[items.length];
            for (int slot = 0; slot < items.length; slot++) {
                copy[slot] = items[slot] == null ? null : items[slot].clone();
            }
            sealed.add(copy);
            return ByteBuffer.allocate(4).putInt(sealed.size() - 1).array();
        }

        @Override
        public ItemStack[] read(byte[] bytes) {
            return sealed.get(ByteBuffer.wrap(bytes).getInt());
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

    /** Runs everything at once, or holds what runs off the player's thread until {@link #release}. */
    private final SchedulerPort scheduler = new SchedulerPort() {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            if (holdAsync) {
                held.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            async(task);
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    };

    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private final PlayerStates.Sessions sessionsView = new PlayerStates.Sessions() {
        @Override
        public @Nullable ActiveSession session(UUID player) {
            return sessions.get(player);
        }

        @Override
        public boolean inPlay(UUID player) {
            ActiveSession session = sessions.get(player);
            return session != null && session.inPlay(System.nanoTime());
        }

        @Override
        public ServerNodeId node() {
            return ServerNodeId.of("lab");
        }
    };

    private World main;
    private World boxed;
    private World nether;
    private PlayerMock ada;
    private ProfileId profile;
    private PlayerStates states;

    @BeforeEach
    void setUp() {
        main = server.addSimpleWorld("world");
        boxed = server.addSimpleWorld("boxed");
        nether = server.addSimpleWorld("skyblock_nether");
        ada = createPlayer("Ada");
        ada.setGameMode(GameMode.SURVIVAL);
        states = states(advancements);
        ada.teleport(new Location(main, 0, 64, 0));
        profile = ProfileId.of(UUID.randomUUID());
        startSession(states, profile);
    }

    private PlayerStates states(PlayerStates.Advancements with) {
        return new PlayerStates(
                config::get, () -> List.of("world", "boxed"), codec, with, store, sessionsView, scheduler);
    }

    /** The session puts the profile's state on Ada and the carried group is read, as a join does. */
    private ActiveSession startSession(PlayerStates on, ProfileId profileId) {
        store.inventories.putIfAbsent(
                profileId, ProfileInventoryRecord.createDefault(profileId, new byte[0], new byte[0]));
        // The session writes on from the version the join read.
        ActiveSession session = new ActiveSession(
                PlayerUuid.of(ada.getUniqueId()),
                profileId,
                1,
                java.util.Objects.requireNonNull(store.inventories.get(profileId))
                        .version());
        session.holdUntil(System.nanoTime() + Duration.ofHours(1).toNanos());
        session.enterPlay();
        sessions.put(ada.getUniqueId(), session);
        on.sessionActive(ada);
        return session;
    }

    private ActiveSession session() {
        return java.util.Objects.requireNonNull(sessions.get(ada.getUniqueId()));
    }

    private ProfileInventoryRecord stored() {
        return java.util.Objects.requireNonNull(store.inventories.get(profile));
    }

    private void release() {
        while (!held.isEmpty()) {
            held.poll().run();
        }
    }

    private boolean to(World world) {
        ada.teleport(new Location(world, 0, 64, 0));
        return states.arrived(ada);
    }

    @Test
    @DisplayName("Crossing into another group puts everything down, and coming back picks it all up again")
    void everythingCrossesBack() {
        assertThat(states.carried(ada))
                .describedAs("the first group a profile stands in is the one it carries for")
                .contains("world");
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
        assertThat(states.carried(ada)).contains("boxed");
        ActiveSession session = session();
        assertThat(stored().version())
                .describedAs("the crossing is written as the profile's inventory")
                .isEqualTo(2);
        assertThat(session.lastDurableVersion())
                .describedAs("and the session writes on from it")
                .isEqualTo(2);
        assertThat(session.inPlay(System.nanoTime())).isTrue();

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
        to(boxed);
        ada.getInventory().setItem(0, new ItemStack(Material.OAK_LOG));

        assertThat(to(nether)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.OAK_LOG));
        assertThat(states.carried(ada)).contains("boxed");

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
        store.groups.put(profile, "main");
        states.sessionActive(ada);
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));

        assertThat(to(boxed)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));

        config.set(new CarriedStateConfiguration(false, Map.of(), CarriedStateConfiguration.Keeps.ALL));
        assertThat(to(main)).isFalse();
        assertThat(to(boxed)).isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(store.crossings).isZero();
    }

    @Test
    @DisplayName("What a group does not keep apart is carried everywhere")
    void whatIsNotKeptIsCarried() {
        config.set(new CarriedStateConfiguration(
                true, Map.of(), new CarriedStateConfiguration.Keeps(false, true, true, true, true, true, true)));
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        ada.setLevel(12);

        to(boxed);

        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(ada.getLevel()).describedAs("the level is still kept apart").isZero();
    }

    @Test
    @DisplayName("What a creative place kept aside is given back before the swap, so nothing made there crosses")
    void aSealIsLiftedFirst() {
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
    @DisplayName("A seal the crossing could not lift is written as what was put down, and never given back again")
    void anUnliftedSealIsLetGo() {
        ada.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        // Written in a form the crossing cannot read back, so the seal stays on.
        new SealedInventory(
                        new InventoryCodec() {
                            @Override
                            public byte[] write(ItemStack[] items) {
                                return new byte[] {9};
                            }

                            @Override
                            public ItemStack[] read(byte[] bytes) {
                                throw new IllegalStateException("unreadable");
                            }
                        },
                        "brix")
                .seal(ada);
        ada.getInventory().setItem(0, new ItemStack(Material.BEDROCK, 64));

        assertThat(to(boxed)).isTrue();

        assertThat(SealedInventory.holds(ada)).isFalse();
        assertThat(to(main)).isTrue();
        assertThat(ada.getInventory().getItem(0))
                .describedAs("the pickaxe kept, once, and never the bedrock")
                .isEqualTo(new ItemStack(Material.IRON_PICKAXE));
        assertThat(ada.getInventory().contains(Material.BEDROCK)).isFalse();
    }

    @Test
    @DisplayName("An advancement handed back in a swap is not announced, and one earned outside a swap is")
    void aHandedBackAdvancementIsQuiet() {
        org.bukkit.advancement.Advancement advancement =
                org.mockito.Mockito.mock(org.bukkit.advancement.Advancement.class);
        List<net.kyori.adventure.text.@Nullable Component> said = new ArrayList<>();
        PlayerStateListener[] listener = new PlayerStateListener[1];
        PlayerStates quiet = states(new PlayerStates.Advancements() {
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
        listener[0] = new PlayerStateListener(quiet, player -> {});
        quiet.sessionActive(ada);
        ada.teleport(new Location(boxed, 0, 64, 0));

        listener[0].onWorldChange(new org.bukkit.event.player.PlayerChangedWorldEvent(ada, main));
        org.bukkit.event.player.PlayerAdvancementDoneEvent outside =
                new org.bukkit.event.player.PlayerAdvancementDoneEvent(
                        ada, advancement, net.kyori.adventure.text.Component.text("done"));
        listener[0].onAdvancement(outside);

        assertThat(said).containsExactly((net.kyori.adventure.text.Component) null);
        assertThat(outside.message()).isEqualTo(net.kyori.adventure.text.Component.text("done"));
    }

    @Test
    @DisplayName("What an advancement pays when it is given back in a swap does not stay, so crossing earns nothing")
    void aGivenBackRewardDoesNotStay() {
        PlayerStates paying = states(new PlayerStates.Advancements() {
            @Override
            public Set<String> earned(Player player) {
                return earned.getOrDefault(player.getUniqueId(), Set.of());
            }

            @Override
            public void earn(Player player, Set<String> criteria) {
                // Vanilla pays experience and loot each time an advancement is awarded.
                for (String ignored : criteria) {
                    player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
                    player.setLevel(player.getLevel() + 10);
                }
                earned.put(player.getUniqueId(), Set.copyOf(criteria));
            }
        });
        paying.sessionActive(ada);
        ada.setLevel(3);
        earned.put(ada.getUniqueId(), Set.of("minecraft:story/mine_diamond|diamond"));
        ada.teleport(new Location(boxed, 0, 64, 0));
        paying.arrived(ada);

        ada.teleport(new Location(main, 0, 64, 0));
        assertThat(paying.arrived(ada)).isTrue();

        assertThat(earned.get(ada.getUniqueId())).containsExactly("minecraft:story/mine_diamond|diamond");
        assertThat(ada.getInventory().getItem(0))
                .describedAs("the diamonds the advancement paid again are gone")
                .isNull();
        assertThat(ada.getLevel()).describedAs("and so is the experience").isEqualTo(3);
    }

    @Test
    @DisplayName("Until the crossing is written the player holds nothing they can use, and it lands once only")
    void aCrossingInFlightHoldsThePlayer() {
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        holdAsync = true;

        assertThat(to(boxed)).isTrue();

        ActiveSession session = session();
        assertThat(session.inPlay(System.nanoTime()))
                .describedAs("the session gate holds every drop, pickup and click")
                .isFalse();
        assertThat(states.pending(ada)).isTrue();
        assertThat(states.arrived(ada))
                .describedAs("a second arrival starts no second crossing")
                .isFalse();
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        ada.teleport(new Location(main, 0, 64, 0));
        assertThat(states.pending(ada))
                .describedAs("held until it lands, even back in the world the crossing left")
                .isTrue();
        ada.teleport(new Location(boxed, 0, 64, 0));

        release();

        assertThat(store.crossings).isEqualTo(1);
        assertThat(ada.getInventory().getItem(0)).isNull();
        assertThat(session.inPlay(System.nanoTime())).isTrue();
        assertThat(states.pending(ada)).isFalse();
    }

    @Test
    @DisplayName("A refused crossing leaves the player as they were, in play, and the next arrival tries again")
    void aRefusedCrossingChangesNothing() {
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        store.refuse = true;

        assertThat(to(boxed)).isTrue();

        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(states.carried(ada)).contains("world");
        assertThat(session().inPlay(System.nanoTime())).isTrue();
        assertThat(PlayerStates.isSwapping(ada)).isFalse();

        store.refuse = false;
        assertThat(states.arrived(ada)).isTrue();
        assertThat(ada.getInventory().getItem(0)).isNull();
    }

    @Test
    @DisplayName("A crossing that lands after the player left gives them nothing now: the join puts back what it wrote")
    void aCrossingLandingAfterTheQuitIsWritten() {
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        holdAsync = true;
        to(boxed);

        ada.disconnect();
        release();

        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(stored().version()).isEqualTo(2);
        assertThat(store.groups.get(profile)).isEqualTo("boxed");
        assertThat(PlayerStates.isSwapping(ada)).isFalse();
    }

    @Test
    @DisplayName("A session that starts in another group's world crosses once the session holds the state")
    void aSessionStartingElsewhereCrosses() {
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        to(boxed);
        ada.getInventory().setItem(0, new ItemStack(Material.OAK_LOG));
        states.left(ada);
        assertThat(states.carried(ada)).isEmpty();
        ada.teleport(new Location(main, 0, 64, 0));
        assertThat(states.arrived(ada))
                .describedAs("nothing crosses before the session reads the group")
                .isFalse();

        startSession(states, profile);

        assertThat(states.carried(ada)).contains("world");
        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("Nothing crosses while the session is out of the player's hands, as in a profile switch")
    void nothingCrossesOutOfPlay() {
        session().leavePlay();

        assertThat(to(boxed)).isFalse();

        assertThat(store.crossings).isZero();
        session().enterPlay();
        assertThat(states.arrived(ada)).isTrue();
    }

    @Test
    @DisplayName("A group read for a profile the player has left by then is not used")
    void aGroupReadForALeftProfileIsDropped() {
        states.left(ada);
        holdAsync = true;
        states.sessionActive(ada);
        session().setActiveProfileId(ProfileId.of(UUID.randomUUID()));

        release();

        assertThat(states.carried(ada)).isEmpty();
    }

    @Test
    @DisplayName(
            "Until the group is read the player is held in every world a group names, and a failed read is tried again")
    void anUnreadGroupHoldsThePlayer() {
        PlayerStateListener listener = new PlayerStateListener(states, player -> {});
        states.left(ada);
        store.unreachable = true;
        states.sessionActive(ada);

        assertThat(states.carried(ada)).isEmpty();
        assertThat(states.pending(ada)).describedAs("held, not let through").isTrue();
        org.bukkit.event.player.PlayerDropItemEvent drop = new org.bukkit.event.player.PlayerDropItemEvent(
                ada, ada.getWorld().dropItem(ada.getLocation(), new ItemStack(Material.DIAMOND)));
        listener.onDrop(drop);
        assertThat(drop.isCancelled()).isTrue();
        ada.teleport(new Location(nether, 0, 64, 0));
        assertThat(states.pending(ada))
                .describedAs("a world no group names keeps what the player brought")
                .isFalse();
        ada.teleport(new Location(main, 0, 64, 0));

        store.unreachable = false;
        assertThat(states.arrived(ada)).isFalse();

        assertThat(states.carried(ada)).describedAs("read on the next arrival").contains("world");
        assertThat(states.pending(ada)).isFalse();
    }

    @Test
    @DisplayName("A window's write still to land holds the crossing back until it has")
    void aWindowWriteHoldsTheCrossing() {
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        session().selfWriteBegun();

        assertThat(to(boxed)).isFalse();
        assertThat(store.crossings).isZero();
        assertThat(states.pending(ada)).isTrue();

        session().selfWriteLanded();
        assertThat(states.arrived(ada)).isTrue();
        assertThat(ada.getInventory().getItem(0)).isNull();
    }

    @Test
    @DisplayName("A window that writes as it closes is closed before the crossing, which then waits for its write")
    void aWindowClosedByTheCrossingIsWaitedFor() {
        org.bukkit.inventory.Inventory vault = server.createInventory(null, 27);
        server.getPluginManager()
                .registerEvents(
                        new org.bukkit.event.Listener() {
                            @org.bukkit.event.EventHandler
                            @SuppressWarnings("UnusedMethod") // called by the server's event bus
                            public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
                                if (event.getInventory().equals(vault)) {
                                    session().selfWriteBegun();
                                }
                            }
                        },
                        org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin());
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        ada.teleport(new Location(boxed, 0, 64, 0));
        // Open as the crossing starts, so only the crossing's own close writes it.
        ada.openInventory(vault);
        assertThat(session().selfWriteOutstanding()).isFalse();

        assertThat(states.arrived(ada)).isFalse();

        assertThat(ada.getOpenInventory().getTopInventory()).isNotEqualTo(vault);
        assertThat(store.crossings).isZero();
        session().selfWriteLanded();
        assertThat(states.arrived(ada)).isTrue();
        assertThat(ada.getInventory().getItem(0)).isNull();
    }

    @Test
    @DisplayName("Each profile carries for its own group and keeps its own things")
    void eachProfileKeepsItsOwn() {
        to(boxed);
        ProfileId other = ProfileId.of(UUID.randomUUID());
        states.left(ada);

        startSession(states, other);

        assertThat(store.groups.get(other))
                .describedAs("a profile that never stood anywhere carries for the group it starts in")
                .isEqualTo("boxed");
        assertThat(store.groups.get(profile)).isEqualTo("boxed");
        assertThat(store.crossings).isEqualTo(1);
    }

    @Test
    @DisplayName("Landed and not swapped yet, nothing is dropped or put away, and the swap is asked for")
    void nothingLeavesBeforeTheSwap() {
        List<Player> asked = new ArrayList<>();
        PlayerStateListener listener = new PlayerStateListener(states, asked::add);
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        ada.teleport(new Location(boxed, 0, 64, 0));

        assertThat(states.pending(ada)).isTrue();
        org.bukkit.event.player.PlayerDropItemEvent drop = new org.bukkit.event.player.PlayerDropItemEvent(
                ada, ada.getWorld().dropItem(ada.getLocation(), new ItemStack(Material.DIAMOND)));
        listener.onDrop(drop);
        org.bukkit.event.inventory.InventoryOpenEvent open =
                new org.bukkit.event.inventory.InventoryOpenEvent(ada.openInventory(server.createInventory(null, 27)));
        listener.onOpen(open);

        assertThat(drop.isCancelled()).isTrue();
        assertThat(open.isCancelled()).isTrue();
        assertThat(asked).containsExactly(ada, ada);

        listener.arrive(ada);
        assertThat(states.pending(ada)).isFalse();
        org.bukkit.event.player.PlayerDropItemEvent later = new org.bukkit.event.player.PlayerDropItemEvent(
                ada, ada.getWorld().dropItem(ada.getLocation(), new ItemStack(Material.OAK_LOG)));
        listener.onDrop(later);
        assertThat(later.isCancelled())
                .describedAs("once swapped the player drops what they like")
                .isFalse();
        assertThat(asked).hasSize(2);
    }

    @Test
    @DisplayName("A teleport into another world and a respawn ask for the swap, a teleport within a world does not")
    void aTeleportAsksForTheSwap() {
        List<Player> asked = new ArrayList<>();
        PlayerStateListener listener = new PlayerStateListener(states, asked::add);

        listener.onTeleport(new org.bukkit.event.player.PlayerTeleportEvent(
                ada, new Location(main, 0, 64, 0), new Location(main, 50, 64, 0)));
        assertThat(asked).isEmpty();

        listener.onTeleport(new org.bukkit.event.player.PlayerTeleportEvent(
                ada, new Location(main, 0, 64, 0), new Location(boxed, 0, 64, 0)));
        assertThat(asked).containsExactly(ada);

        listener.onRespawn(new org.bukkit.event.player.PlayerRespawnEvent(
                ada,
                new Location(boxed, 0, 64, 0),
                false,
                false,
                false,
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH));
        assertThat(asked).containsExactly(ada, ada);
    }

    @Test
    @DisplayName("A player who left before the swap ran is not swapped, and quitting forgets the group")
    void anOfflinePlayerIsLeftAlone() {
        PlayerStateListener listener = new PlayerStateListener(states, player -> {});
        ada.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        ada.teleport(new Location(boxed, 0, 64, 0));

        ada.disconnect();
        listener.arrive(ada);

        assertThat(ada.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(store.groups.get(profile)).isEqualTo("world");
        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(
                ada,
                net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(states.carried(ada)).isEmpty();
    }

    @Test
    @DisplayName("Landed and not swapped yet, nothing is placed, used, emptied, picked up or thrown in the new world")
    void nothingCrossesAnyOtherWay() {
        List<Player> asked = new ArrayList<>();
        PlayerStateListener listener = new PlayerStateListener(states, asked::add);
        to(main);
        ada.getInventory().setItem(0, new ItemStack(Material.SHULKER_BOX));
        ada.teleport(new Location(boxed, 0, 64, 0));
        org.bukkit.block.Block block = boxed.getBlockAt(0, 63, 0);
        org.bukkit.entity.ArmorStand stand =
                boxed.spawn(new Location(boxed, 1, 64, 0), org.bukkit.entity.ArmorStand.class);
        org.bukkit.entity.Item lying = boxed.dropItem(new Location(boxed, 0, 64, 1), new ItemStack(Material.OAK_LOG));
        org.bukkit.entity.Snowball snowball =
                boxed.spawn(new Location(boxed, 0, 65, 0), org.bukkit.entity.Snowball.class);
        snowball.setShooter(ada);
        java.util.function.Supplier<List<org.bukkit.event.Cancellable>> events = () -> List.of(
                new org.bukkit.event.block.BlockPlaceEvent(
                        block,
                        block.getState(),
                        block,
                        new ItemStack(Material.SHULKER_BOX),
                        ada,
                        true,
                        org.bukkit.inventory.EquipmentSlot.HAND),
                new org.bukkit.event.entity.EntityPlaceEvent(
                        stand, ada, block, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND),
                new org.bukkit.event.player.PlayerInteractEvent(
                        ada,
                        org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                        new ItemStack(Material.SHULKER_BOX),
                        block,
                        org.bukkit.block.BlockFace.UP),
                new org.bukkit.event.player.PlayerInteractEntityEvent(ada, stand),
                new org.bukkit.event.player.PlayerInteractAtEntityEvent(ada, stand, new org.bukkit.util.Vector()),
                new org.bukkit.event.player.PlayerArmorStandManipulateEvent(
                        ada,
                        stand,
                        new ItemStack(Material.DIAMOND_HELMET),
                        new ItemStack(Material.AIR),
                        org.bukkit.inventory.EquipmentSlot.HEAD,
                        org.bukkit.inventory.EquipmentSlot.HAND),
                new org.bukkit.event.player.PlayerBucketEmptyEvent(
                        ada,
                        block,
                        block,
                        org.bukkit.block.BlockFace.UP,
                        Material.LAVA_BUCKET,
                        new ItemStack(Material.LAVA_BUCKET),
                        org.bukkit.inventory.EquipmentSlot.HAND),
                new org.bukkit.event.entity.EntityPickupItemEvent(ada, lying, 0),
                new org.bukkit.event.entity.ProjectileLaunchEvent(snowball));
        List<org.bukkit.event.Cancellable> pending = events.get();
        fire(listener, pending);

        assertThat(pending)
                .describedAs("every way out is held while the swap is pending")
                .allMatch(org.bukkit.event.Cancellable::isCancelled);
        assertThat(asked).hasSize(pending.size()).containsOnly(ada);

        listener.arrive(ada);
        List<org.bukkit.event.Cancellable> swapped = events.get();
        fire(listener, swapped);
        assertThat(swapped)
                .describedAs("once swapped the player plays as they like")
                .noneMatch(org.bukkit.event.Cancellable::isCancelled);
        assertThat(asked).hasSize(pending.size());
    }

    private static void fire(PlayerStateListener listener, List<org.bukkit.event.Cancellable> events) {
        for (org.bukkit.event.Cancellable event : events) {
            switch (event) {
                case org.bukkit.event.block.BlockPlaceEvent place -> listener.onPlace(place);
                case org.bukkit.event.entity.EntityPlaceEvent place -> listener.onPlaceEntity(place);
                case org.bukkit.event.player.PlayerInteractEvent interact -> listener.onInteract(interact);
                case org.bukkit.event.player.PlayerArmorStandManipulateEvent stand -> listener.onArmorStand(stand);
                case org.bukkit.event.player.PlayerInteractAtEntityEvent at -> listener.onInteractAtEntity(at);
                case org.bukkit.event.player.PlayerInteractEntityEvent entity -> listener.onInteractEntity(entity);
                case org.bukkit.event.player.PlayerBucketEmptyEvent bucket -> listener.onBucket(bucket);
                case org.bukkit.event.entity.EntityPickupItemEvent pickup -> listener.onPickup(pickup);
                case org.bukkit.event.entity.ProjectileLaunchEvent thrown -> listener.onThrow(thrown);
                default -> throw new IllegalArgumentException("no handler for " + event);
            }
        }
    }

    @Test
    @DisplayName("A player who dies before the swap keeps what they carry, so nothing of it drops in the new world")
    void aDeathBeforeTheSwapDropsNothing() {
        PlayerStateListener listener = new PlayerStateListener(states, player -> {});
        to(main);
        ada.teleport(new Location(boxed, 0, 64, 0));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 64)));
        org.bukkit.event.entity.PlayerDeathEvent death = new org.bukkit.event.entity.PlayerDeathEvent(
                ada,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC)
                        .build(),
                drops,
                40,
                net.kyori.adventure.text.Component.text("died"),
                true);

        listener.onDeath(death);

        assertThat(death.getDrops()).isEmpty();
        assertThat(death.getKeepInventory()).isTrue();
        assertThat(death.getKeepLevel()).isTrue();
        assertThat(death.getDroppedExp()).isZero();

        listener.arrive(ada);
        List<ItemStack> later = new ArrayList<>(List.of(new ItemStack(Material.OAK_LOG)));
        org.bukkit.event.entity.PlayerDeathEvent swapped = new org.bukkit.event.entity.PlayerDeathEvent(
                ada,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC)
                        .build(),
                later,
                40,
                net.kyori.adventure.text.Component.text("died"),
                true);
        listener.onDeath(swapped);
        assertThat(swapped.getDrops())
                .describedAs("a swapped player dies as anyone does")
                .hasSize(1);
        assertThat(swapped.getKeepInventory()).isFalse();
    }
}
