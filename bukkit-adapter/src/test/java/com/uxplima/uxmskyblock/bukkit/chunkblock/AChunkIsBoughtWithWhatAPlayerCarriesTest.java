package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.condition.Wallet;
import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkPaymentPort;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement.Source;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A member pays for a chunk of their island with what they carry: items out of the inventory, their own money and a
 * currency they hold. What the chunk did not need goes back, a bypass takes all of itself or nothing, and the grade's
 * rewards fire for whoever opens it.
 */
class AChunkIsBoughtWithWhatAPlayerCarriesTest extends MockBukkitHarness {

    private static final ChunkPos EAST = new ChunkPos(1, 0);

    private final Map<IslandId, ChunkPos> origins = new HashMap<>();
    private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
    private final Map<IslandId, List<ChunkPos>> bought = new HashMap<>();
    private final Payments payments = new Payments();
    private final IslandStoragePort islands = mock(IslandStoragePort.class);
    private final IslandAccessService access = mock(IslandAccessService.class);
    private final AtomicLong level = new AtomicLong();
    private final List<String> rewarded = new ArrayList<>();
    private final AtomicInteger redrawn = new AtomicInteger();
    private double money;
    private double held;
    private int walletMoves;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Buyer");
        player.setLocation(new Location(world, 8, 100, 8));
        ProfileId profile = new ProfileId(player.getUniqueId());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(player.getUniqueId()),
                profile,
                Instant.now());
        when(islands.findIslandIdByProfileId(profile)).thenReturn(Optional.of(island.id()));
        when(islands.findIslandById(island.id())).thenReturn(Optional.of(island));
        when(access.canWithdrawBank(any(), any())).thenReturn(true);
        service = new ChunkBlockService(memory(), new ChunkUnlockRules(List.of(2L, 5L), 5));
        service.start(island.id(), 8, 8);
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
    }

    private ChunkPurchase selling(List<ChunkRequirement> cost, List<ChunkRequirement> bypass) {
        ChunkTier tier = new ChunkTier("rare", "@chunkblock.tiers.rare", 1, 99, 1, cost, bypass, List.of(), 2);
        IslandBankPort bank = mock(IslandBankPort.class);
        IslandAuthorityPort authorities = mock(IslandAuthorityPort.class);
        ChunkUnlocks unlocks = new ChunkUnlocks(
                service,
                payments,
                new ChunkTiers(List.of(tier)),
                bank,
                new IslandBankService(bank, islands, authorities),
                authorities);
        return new ChunkPurchase(
                unlocks,
                islands,
                access,
                scheduler,
                Messages.bundled(),
                new PlayerChunkPurse(new Purse(), id -> id.equals("points") ? Optional.of(points()) : Optional.empty()),
                (who, opening) -> rewarded.add(who.getName() + ":" + opening.id()),
                uuid -> Optional.of(new ProfileId(uuid)),
                (id, who) -> level.get(),
                new ServerNodeId("node-a"));
    }

    @Test
    @DisplayName("Plain items are handed in a part at a time, and the chunk opens on the last of them")
    void itemsAreHandedIn() {
        ChunkPurchase purchase = selling(
                List.of(new ChunkRequirement.Items("cobblestone", 64), new ChunkRequirement.Items("oak_log", 16)),
                List.of());
        player.getInventory().addItem(renamed(Material.COBBLESTONE, 10));
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 36));

        purchase.payAll(player, EAST, redrawn::incrementAndGet);

        assertThat(count(Material.COBBLESTONE))
                .describedAs("64 of the plain ones went in")
                .isEqualTo(36 + 10);
        assertThat(count(renamed(Material.COBBLESTONE, 1)))
                .describedAs("a renamed item is never taken")
                .isEqualTo(10);
        assertThat(said()).singleElement().asString().contains("Paid toward the Rare chunk");
        assertThat(service.isOpen(island.id(), EAST)).contains(false);
        assertThat(redrawn).hasValue(1);

        purchase.payAll(player, EAST, redrawn::incrementAndGet);
        assertThat(said()).singleElement().asString().contains("none of what this chunk still needs");
        assertThat(count(Material.COBBLESTONE)).isEqualTo(46);

        player.getInventory().addItem(new ItemStack(Material.OAK_LOG, 20));
        purchase.payAll(player, EAST, redrawn::incrementAndGet);

        assertThat(count(Material.OAK_LOG)).isEqualTo(4);
        assertThat(said()).singleElement().asString().contains("The Rare chunk is open");
        assertThat(service.isOpen(island.id(), EAST)).contains(true);
        assertThat(bought.get(island.id()))
                .describedAs("a chunk paid with items is bought")
                .containsExactly(EAST);
        assertThat(rewarded).containsExactly("Buyer:rare");
        assertThat(redrawn).hasValue(3);
    }

    @Test
    @DisplayName("Money comes out of the player's own purse as far as it reaches")
    void moneyIsTakenAsFarAsItReaches() {
        ChunkPurchase purchase = selling(List.of(new ChunkRequirement.Money(5000, Source.PLAYER)), List.of());
        money = 30.25;

        purchase.payAll(player, EAST, () -> {});
        assertThat(money).isZero();
        assertThat(payments.find(island.id(), EAST).remaining(new ChunkRequirement.Money(5000, Source.PLAYER)))
                .isEqualTo(5000 - 3025);

        money = 100;
        purchase.payAll(player, EAST, () -> {});
        assertThat(money).isEqualTo(100 - 19.75);
        assertThat(service.isOpen(island.id(), EAST)).contains(true);
    }

    @Test
    @DisplayName("One line is paid on its own, and a line already paid takes nothing")
    void oneLineAlone() {
        ChunkRequirement stone = new ChunkRequirement.Items("stone", 8);
        ChunkRequirement logs = new ChunkRequirement.Items("oak_log", 8);
        ChunkPurchase purchase = selling(List.of(stone, logs), List.of());
        player.getInventory().addItem(new ItemStack(Material.STONE, 20), new ItemStack(Material.OAK_LOG, 20));

        purchase.payLine(player, EAST, stone, () -> {});
        assertThat(count(Material.STONE)).isEqualTo(12);
        assertThat(count(Material.OAK_LOG))
                .describedAs("the other line is left alone")
                .isEqualTo(20);
        assertThat(said()).singleElement().asString().contains("Paid toward");

        purchase.payLine(player, EAST, stone, () -> {});
        assertThat(count(Material.STONE)).isEqualTo(12);
        assertThat(said()).singleElement().asString().contains("paid already");

        purchase.payLine(player, EAST, new ChunkRequirement.Items("dirt", 1), () -> {});
        assertThat(said()).singleElement().asString().contains("does not ask for that");
    }

    @Test
    @DisplayName("What could not be recorded goes back to the player, a full inventory's worth at their feet")
    void whatFailsGoesBack() {
        ChunkPurchase purchase = selling(List.of(new ChunkRequirement.Items("stone", 70)), List.of());
        player.getInventory().addItem(new ItemStack(Material.STONE, 64), new ItemStack(Material.STONE, 6));
        payments.failing = true;
        purchase.payAll(player, EAST, () -> {});

        assertThat(count(Material.STONE)).isEqualTo(70);
        assertThat(said()).singleElement().asString().contains("could not be saved");

        ItemStack[] full = new ItemStack[player.getInventory().getContents().length];
        for (int slot = 0; slot < full.length; slot++) {
            full[slot] = new ItemStack(Material.DIRT, 64);
        }
        player.getInventory().setContents(full);
        new PlayerChunkPurse(new Purse(), id -> Optional.empty())
                .giveBack(player, new ChunkRequirement.Items("stone", 1), 70);
        assertThat(world.getEntitiesByClass(Item.class))
                .extracting(item -> item.getItemStack().getAmount())
                .containsExactlyInAnyOrder(64, 6);
    }

    @Test
    @DisplayName("A bypass takes every line of itself in full, or nothing at all")
    void aBypassIsWholeOrNothing() {
        ChunkPurchase purchase = selling(
                List.of(new ChunkRequirement.Items("diamond_block", 64)),
                List.of(
                        new ChunkRequirement.Items("diamond", 2),
                        new ChunkRequirement.Currency("points", 25, Source.PLAYER)));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));
        held = 24;

        purchase.bypass(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("nothing was taken");
        assertThat(count(Material.DIAMOND)).isEqualTo(2);
        assertThat(held).isEqualTo(24);
        assertThat(walletMoves)
                .describedAs("nothing was taken out to be put back")
                .isZero();
        assertThat(service.isOpen(island.id(), EAST)).contains(false);

        held = 30;
        purchase.bypass(player, new ChunkPos(3, 0), () -> {});
        assertThat(said()).singleElement().asString().contains("shares a side");
        assertThat(count(Material.DIAMOND))
                .describedAs("a chunk that cannot open gives it all back")
                .isEqualTo(2);
        assertThat(held).isEqualTo(30);

        held = 30;
        purchase.bypass(player, EAST, () -> {});
        assertThat(count(Material.DIAMOND)).isZero();
        assertThat(held).isEqualTo(5);
        assertThat(said()).singleElement().asString().contains("is open");
        assertThat(bought.get(island.id())).containsExactly(EAST);
        assertThat(rewarded).containsExactly("Buyer:rare");
    }

    @Test
    @DisplayName("A grade with no bypass says so, and takes nothing")
    void noBypass() {
        ChunkPurchase purchase = selling(List.of(new ChunkRequirement.Items("stone", 1)), List.of());
        purchase.bypass(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("Nothing skips");
    }

    @Test
    @DisplayName("Only a role that may take from the bank spends the island's chunk points")
    void theIslandPaysOnlyThroughTheRightRole() {
        ChunkPurchase purchase = selling(List.of(new ChunkRequirement.Points(3)), List.of());
        payments.addPoints(island.id(), 5);
        when(access.canWithdrawBank(any(), any())).thenReturn(false);

        purchase.payAll(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("may not spend from the island bank");
        assertThat(payments.points(island.id())).isEqualTo(5);

        when(access.canWithdrawBank(any(), any())).thenReturn(true);
        purchase.payAll(player, EAST, () -> {});
        assertThat(payments.points(island.id()))
                .describedAs("three spent, two given for opening it")
                .isEqualTo(4);
        assertThat(said()).singleElement().asString().contains("is open");
    }

    @Test
    @DisplayName("A chunk priced by the level opens once the level is there, and says which level it needs")
    void theLevelHolds() {
        ChunkPurchase purchase = selling(List.of(new ChunkRequirement.Level(ChunkRequirement.Level.LADDER)), List.of());
        level.set(1);
        purchase.payAll(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("needs level 2").contains("level 1");
        assertThat(service.isOpen(island.id(), EAST)).contains(false);

        level.set(2);
        purchase.payAll(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("is open");
        assertThat(bought.getOrDefault(island.id(), List.of()))
                .describedAs("a chunk held by the level alone is not bought")
                .isEmpty();
        assertThat(rewarded).containsExactly("Buyer:rare");

        service.onLevel(island.id(), 0);
        assertThat(service.isOpen(island.id(), EAST)).contains(false);
        purchase.payAll(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("is open");
        assertThat(rewarded)
                .describedAs("opened again, it rewards nobody again")
                .containsExactly("Buyer:rare");
    }

    @Test
    @DisplayName("Paid in full but short of its level, a chunk says the level it waits for")
    void paidButShortOfTheLevel() {
        ChunkPurchase purchase =
                selling(List.of(new ChunkRequirement.Items("stone", 4), new ChunkRequirement.Level(7)), List.of());
        player.getInventory().addItem(new ItemStack(Material.STONE, 4));
        level.set(3);

        purchase.payAll(player, EAST, () -> {});
        assertThat(count(Material.STONE)).isZero();
        assertThat(said()).singleElement().asString().contains("needs level 7").contains("level 3");

        level.set(7);
        purchase.payAll(player, EAST, () -> {});
        assertThat(said()).singleElement().asString().contains("is open");
    }

    @Test
    @DisplayName("Facing a chunk and asking to open it pays toward it through its grade")
    void theFacedChunkIsPaidFor() {
        ChunkPurchase purchase = selling(List.of(new ChunkRequirement.Items("stone", 4)), List.of());
        ChunkBlockPanel panel = new ChunkBlockPanel(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                uuid -> Optional.of(new ProfileId(uuid)),
                (i, p) -> 0L);
        panel.usePurchase(purchase);
        player.getInventory().addItem(new ItemStack(Material.STONE, 4));
        player.setLocation(new Location(world, 8, 100, 14, -90, 0));

        panel.unlockFaced(player);

        assertThat(said()).singleElement().asString().contains("is open");
        assertThat(service.isOpen(island.id(), EAST)).contains(true);
    }

    @Test
    @DisplayName("A grade's rewards are its own lines, read once, an unreadable one left out")
    void rewardsAreTheGradesLines() {
        ChunkTier tier = new ChunkTier(
                "epic",
                "Epic",
                1,
                9,
                1,
                List.of(new ChunkRequirement.Points(1)),
                List.of(),
                List.of("[message] <gold>An epic chunk!", "[nonsense] what", "[message] second"));
        ChunkRewards rewards = new ChunkRewards(new ChunkTiers(List.of(tier)), new InteractionEffectPlayer(scheduler));

        assertThat(rewards.lines("epic")).isEqualTo(2);
        rewards.accept(player, tier);
        assertThat(said()).containsExactly("An epic chunk!", "second");
    }

    @Test
    @DisplayName("An item a server does not know is no item to take")
    void anUnknownItem() {
        assertThat(PlayerChunkPurse.material(new ChunkRequirement.Items("no_such_thing", 1)))
                .isEmpty();
        assertThat(PlayerChunkPurse.material(new ChunkRequirement.Items("water", 1)))
                .describedAs("a block that is no item")
                .isEmpty();
        assertThat(PlayerChunkPurse.material(new ChunkRequirement.Items("oak_log", 1)))
                .contains(Material.OAK_LOG);
    }

    private BankWallets.Kept points() {
        Wallet wallet = new Wallet() {
            @Override
            public double balance(@Nullable Player who, String currency) {
                return held;
            }

            @Override
            public boolean withdraw(@Nullable Player who, String currency, double amount) {
                if (held < amount) {
                    return false;
                }
                walletMoves++;
                held -= amount;
                return true;
            }

            @Override
            public boolean deposit(@Nullable Player who, String currency, double amount) {
                walletMoves++;
                held += amount;
                return true;
            }
        };
        return new BankWallets.Kept(
                new BankCurrencySpec(
                        "points",
                        BankCurrencySpec.Type.PLAYERPOINTS,
                        0,
                        true,
                        "points",
                        "EMERALD",
                        Map.of(),
                        BankCurrencySpec.DEFAULT_QUICK),
                wallet,
                "points");
    }

    private ItemStack renamed(Material type, int amount) {
        ItemStack stack = new ItemStack(type, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Lucky stone"));
        stack.setItemMeta(meta);
        return stack;
    }

    private int count(Material type) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == type) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private int count(ItemStack like) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(like)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private List<String> said() {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private final class Purse implements PlayerChunkPurse.Money {
        @Override
        public double balance(Player who) {
            return money;
        }

        @Override
        public boolean withdraw(Player who, double amount) {
            if (money + 1e-9 < amount) {
                return false;
            }
            money -= amount;
            return true;
        }

        @Override
        public boolean deposit(Player who, double amount) {
            money += amount;
            return true;
        }
    }

    private ChunkTerritoryPort memory() {
        return new ChunkTerritoryPort() {
            @Override
            public Map<IslandId, ChunkTerritory> findAll() {
                return Map.of();
            }

            @Override
            public Optional<ChunkTerritory> find(IslandId islandId) {
                ChunkPos origin = origins.get(islandId);
                return origin == null
                        ? Optional.empty()
                        : Optional.of(new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of())));
            }

            @Override
            public void start(IslandId islandId, ChunkPos origin) {
                origins.putIfAbsent(islandId, origin);
            }

            @Override
            public boolean open(IslandId islandId, ChunkPos chunk, int order) {
                return opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
            }

            @Override
            public boolean open(IslandId islandId, ChunkPos chunk, int order, boolean paidFor) {
                if (paidFor) {
                    bought.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
                }
                return open(islandId, chunk, order);
            }

            @Override
            public void close(IslandId islandId, List<ChunkPos> chunks) {
                opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
            }
        };
    }

    private static final class Payments implements ChunkPaymentPort {
        private final Map<String, Long> paid = new HashMap<>();
        private final Map<IslandId, Long> points = new HashMap<>();
        boolean failing;

        private String at(IslandId islandId, ChunkPos chunk) {
            return islandId + "/" + chunk.x() + "," + chunk.z() + "/";
        }

        @Override
        public ChunkPayments find(IslandId islandId, ChunkPos chunk) {
            Map<String, Long> found = new HashMap<>();
            paid.forEach((key, amount) -> {
                if (key.startsWith(at(islandId, chunk))) {
                    found.put(key.substring(at(islandId, chunk).length()), amount);
                }
            });
            return new ChunkPayments(found);
        }

        @Override
        public void add(IslandId islandId, ChunkPos chunk, String key, long amount) {
            if (failing) {
                throw new IllegalStateException("the table is gone");
            }
            paid.merge(at(islandId, chunk) + key, amount, Long::sum);
        }

        @Override
        public void clear(IslandId islandId, ChunkPos chunk) {
            paid.keySet().removeIf(key -> key.startsWith(at(islandId, chunk)));
        }

        @Override
        public long points(IslandId islandId) {
            return points.getOrDefault(islandId, 0L);
        }

        @Override
        public void addPoints(IslandId islandId, long amount) {
            points.merge(islandId, amount, Long::sum);
        }

        @Override
        public boolean spendPoints(IslandId islandId, long amount) {
            if (points(islandId) < amount) {
                return false;
            }
            points.merge(islandId, -amount, Long::sum);
            return true;
        }
    }
}
