package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.condition.Wallet;
import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A closed chunk is a window of its price: its grade, where it lies, every line with what is paid and what is left,
 * and what its bypass takes. The chunks window lists the closed chunks the island can open next.
 */
class AChunkIsAWindowOfItsPriceTest extends MockBukkitHarness {

    private static final ChunkPos EAST = new ChunkPos(1, 0);
    private static final ChunkRequirement LOGS = new ChunkRequirement.Items("oak_log", 32);
    private static final ChunkRequirement MONEY = new ChunkRequirement.Money(250_050, Source.ISLAND);
    private static final ChunkRequirement LEVEL = new ChunkRequirement.Level(ChunkRequirement.Level.LADDER);
    private static final ChunkRequirement POINTS = new ChunkRequirement.Points(5);

    private final ChunkBlockStores.Territories territories = new ChunkBlockStores.Territories();
    private final ChunkBlockStores.Payments payments = new ChunkBlockStores.Payments();
    private final IslandStoragePort islands = mock(IslandStoragePort.class);
    private final AtomicLong level = new AtomicLong(3);

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private ChunkWindow window;

    @SuppressWarnings("NullAway.Init")
    private ChunkUnlocks unlocks;

    @BeforeEach
    void setUpWindow() {
        World world = server.addSimpleWorld("skyblock");
        player = createPlayer("Reader");
        player.setLocation(new Location(world, 8, 100, 8));
        ProfileId profile = new ProfileId(player.getUniqueId());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 24),
                new PlayerUuid(player.getUniqueId()),
                profile,
                Instant.now());
        when(islands.findIslandIdByProfileId(profile)).thenReturn(Optional.of(island.id()));
        when(islands.findIslandById(island.id())).thenReturn(Optional.of(island));
        service = new ChunkBlockService(territories, new ChunkUnlockRules(List.of(2L, 5L), 5));
        service.start(island.id(), 8, 8);
        SchedulerPort scheduler = mock(SchedulerPort.class);
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
        ChunkTier rare = new ChunkTier(
                "rare",
                "@chunkblock.tiers.rare",
                1,
                99,
                1,
                List.of(LOGS, MONEY, LEVEL, POINTS),
                List.of(
                        new ChunkRequirement.Currency("gems", 25, Source.PLAYER),
                        new ChunkRequirement.Items("diamond", 2)),
                List.of(),
                3);
        IslandBankPort bank = mock(IslandBankPort.class);
        IslandAuthorityPort authorities = mock(IslandAuthorityPort.class);
        IslandAccessService access = mock(IslandAccessService.class);
        when(access.canWithdrawBank(any(), any())).thenReturn(true);
        unlocks = new ChunkUnlocks(
                service,
                payments,
                new ChunkTiers(List.of(rare)),
                bank,
                new IslandBankService(bank, islands, authorities),
                authorities);
        ChunkPurchase purchase = new ChunkPurchase(
                unlocks,
                islands,
                access,
                scheduler,
                Messages.bundled(),
                new PlayerChunkPurse(nobodysMoney(), id -> Optional.empty()),
                (who, tier) -> {},
                uuid -> Optional.of(new ProfileId(uuid)),
                (id, who) -> level.get(),
                new ServerNodeId("node-a"));
        window = new ChunkWindow(
                purchase,
                service,
                islands,
                scheduler,
                Messages.bundled(),
                id -> id.equals("gems") ? Optional.of(gems()) : Optional.empty(),
                uuid -> Optional.of(new ProfileId(uuid)),
                (id, who) -> level.get());
    }

    private ChunkUnlocks.Offer offer(ChunkPos chunk) {
        return unlocks.offer(island.id(), chunk, island.bounds()).orElseThrow();
    }

    @Test
    @DisplayName("The window names the chunk's grade, where it lies, the level, the points and what its bypass takes")
    void theWindowsValues() {
        payments.add(island.id(), EAST, LOGS.key(), 32);
        payments.addPoints(island.id(), 7);

        Map<String, String> values = window.values(player, offer(EAST), new ChunkPos(0, 0), 3);

        assertThat(values)
                .containsEntry("tier", "Rare")
                .containsEntry("x", "24")
                .containsEntry("z", "8")
                .containsEntry("ring", "1")
                .containsEntry("level", "3")
                .containsEntry("needed", "2")
                .containsEntry("points", "7")
                .containsEntry("gives", "3")
                .containsEntry("lines", "3")
                .containsEntry("paid", "1")
                .containsEntry("bypass", "25 Gems, 2 <lang:item.minecraft.diamond>");
        assertThat(window.values(player, offer(new ChunkPos(-2, 1)), new ChunkPos(0, 0), 3))
                .describedAs("the ring is counted from the island's first chunk")
                .containsEntry("ring", "2")
                .containsEntry("x", "-24")
                .containsEntry("z", "24");
        assertThat(window.values(player, offer(EAST), new ChunkPos(4, 0), 3)).containsEntry("ring", "3");

        ChunkTier plain = new ChunkTier("plain", "Plain", 1, 9, 1, List.of(LOGS), List.of(), List.of());
        assertThat(window.values(
                        player,
                        new ChunkUnlocks.Offer(
                                EAST,
                                plain,
                                com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments.none(),
                                ChunkTerritory.Unlock.UNLOCKED,
                                2,
                                0),
                        new ChunkPos(0, 0),
                        3))
                .describedAs("a grade nothing skips says so")
                .containsEntry("bypass", "nothing")
                .containsEntry("tier", "Plain")
                .containsEntry("needed", "0");
    }

    @Test
    @DisplayName("Each line of the price is a tile: what it asks, paid of needed, what is left, and who pays it")
    void eachLineIsATile() {
        payments.add(island.id(), EAST, LOGS.key(), 20);
        List<MenuRow> rows = window.lines(player, offer(EAST), 1);

        assertThat(rows).extracting(MenuRow::handle).containsExactly(LOGS, MONEY, LEVEL, POINTS);
        assertThat(rows.get(0).words())
                .containsEntry("what", "<lang:block.minecraft.oak_log>")
                .containsEntry("paid", "20")
                .containsEntry("needed", "32")
                .containsEntry("left", "12")
                .containsEntry("from", "Handed in by you")
                .containsEntry("how", "hand in what you carry")
                .containsEntry("state", "Still to pay")
                .containsEntry("material", "OAK_LOG");
        assertThat(rows.get(1).words())
                .containsEntry("what", "Money")
                .containsEntry("needed", "2500.50")
                .containsEntry("left", "2500.50")
                .containsEntry("from", "Paid from the island bank")
                .containsEntry("material", "GOLD_INGOT");
        assertThat(rows.get(2).words())
                .describedAs("the level is held, never paid: level one of the two the ladder asks")
                .containsEntry("what", "Island level")
                .containsEntry("paid", "1")
                .containsEntry("needed", "2")
                .containsEntry("left", "1")
                .containsEntry("from", "Held by the island's level")
                .containsEntry("state", "Not reached yet")
                .containsEntry("material", "EXPERIENCE_BOTTLE");
        assertThat(rows.get(3).words())
                .containsEntry("what", "Chunk points")
                .containsEntry("from", "Paid with the island's chunk points")
                .containsEntry("material", "NETHER_STAR");

        List<MenuRow> high = window.lines(player, offer(EAST), 9);
        assertThat(high.get(2).words())
                .describedAs("a level past the one asked is the one asked, and nothing is left")
                .containsEntry("paid", "2")
                .containsEntry("left", "0")
                .containsEntry("state", "Reached");
        assertThat(high.get(0).words()).containsEntry("state", "Still to pay");
    }

    @Test
    @DisplayName("A currency of the bank wears its own name and icon, and one the bank does not keep its id")
    void aCurrencyWearsItsOwnName() {
        assertThat(window.what(player, new ChunkRequirement.Currency("gems", 1, Source.PLAYER)))
                .isEqualTo("Gems");
        assertThat(window.material(new ChunkRequirement.Currency("gems", 1, Source.PLAYER)))
                .isEqualTo("EMERALD");
        assertThat(window.what(player, new ChunkRequirement.Currency("tokens", 1, Source.PLAYER)))
                .isEqualTo("tokens");
        assertThat(window.material(new ChunkRequirement.Currency("tokens", 1, Source.PLAYER)))
                .isEqualTo("SUNFLOWER");
        assertThat(window.material(new ChunkRequirement.Items("no_such_thing", 1)))
                .isEqualTo("BARRIER");
        assertThat(ChunkWindow.source(new ChunkRequirement.Currency("gems", 1, Source.ISLAND)))
                .isEqualTo("bank");
        assertThat(ChunkWindow.source(new ChunkRequirement.Money(1, Source.PLAYER)))
                .isEqualTo("player");
    }

    @Test
    @DisplayName("The chunks an island can open next are those beside its open ground, inside its edge, nearest first")
    void theFrontier() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(8, 8, 24);
        ChunkTerritory one = new ChunkTerritory(new ChunkPos(0, 0), List.of());
        assertThat(ChunkWindow.frontier(one, bounds))
                .containsExactly(new ChunkPos(-1, 0), new ChunkPos(0, -1), new ChunkPos(0, 1), new ChunkPos(1, 0));

        ChunkTerritory two = new ChunkTerritory(new ChunkPos(0, 0), List.of(new ChunkPos(1, 0)));
        assertThat(ChunkWindow.frontier(two, bounds))
                .describedAs("never an open chunk, never past the edge, ring two after ring one")
                .containsExactly(
                        new ChunkPos(-1, 0),
                        new ChunkPos(0, -1),
                        new ChunkPos(0, 1),
                        new ChunkPos(1, -1),
                        new ChunkPos(1, 1),
                        new ChunkPos(2, 0));
        assertThat(ChunkWindow.frontier(new ChunkTerritory(new ChunkPos(0, 0), List.of(new ChunkPos(-1, 0))), bounds))
                .describedAs("the edge stops it: blocks -16 to 32 hold no chunk -2")
                .doesNotContain(new ChunkPos(-2, 0))
                .contains(new ChunkPos(-1, 1));
    }

    @Test
    @DisplayName("Each chunk of the frontier is a tile of the chunks window, holding its chunk")
    void theFrontierIsTiles() {
        payments.add(island.id(), EAST, LOGS.key(), 32);
        List<MenuRow> rows = window.frontierRows(player, island.id(), island.bounds());

        assertThat(rows).extracting(MenuRow::handle).hasSize(4).contains(EAST);
        MenuRow east = rows.stream()
                .filter(row -> row.handle().equals(EAST))
                .findFirst()
                .orElseThrow();
        assertThat(east.words())
                .containsOnlyKeys("tier", "x", "z", "ring", "lines", "paid")
                .containsEntry("tier", "Rare")
                .containsEntry("paid", "1")
                .containsEntry("lines", "3");
        assertThat(window.frontierRows(player, IslandId.of(UUID.randomUUID()), island.bounds()))
                .describedAs("an island that is no ChunkBlock island has none")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "A click on a line pays it toward the chunk the window shows, and one on the level only reads it again")
    void aClickPaysTheLine() {
        player.getInventory().addItem(new ItemStack(Material.OAK_LOG, 40));
        window.clicked(player, LOGS);
        assertThat(count(Material.OAK_LOG))
                .describedAs("no window up, nothing to pay toward")
                .isEqualTo(40);

        window.show(player, EAST);
        assertThat(window.chunkOf(player)).contains(EAST);
        window.clicked(player, LEVEL);
        assertThat(count(Material.OAK_LOG)).isEqualTo(40);
        assertThat(said()).isEmpty();

        window.clicked(player, LOGS);
        assertThat(count(Material.OAK_LOG)).isEqualTo(8);
        assertThat(said()).singleElement().asString().contains("Paid toward the Rare chunk");
    }

    @Test
    @DisplayName("An open chunk has no window of a price, and says so")
    void anOpenChunkHasNoPrice() {
        territories.opened.computeIfAbsent(island.id(), id -> new ArrayList<>()).add(EAST);
        service.forget(island.id());
        window.show(player, EAST);
        assertThat(said()).singleElement().asString().contains("already open");
        assertThat(window.chunkOf(player)).isEmpty();
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

    private List<String> said() {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private static PlayerChunkPurse.Money nobodysMoney() {
        return new PlayerChunkPurse.Money() {
            @Override
            public double balance(org.bukkit.entity.Player who) {
                return 0;
            }

            @Override
            public boolean withdraw(org.bukkit.entity.Player who, double amount) {
                return false;
            }

            @Override
            public boolean deposit(org.bukkit.entity.Player who, double amount) {
                return false;
            }
        };
    }

    private static BankWallets.Kept gems() {
        return new BankWallets.Kept(
                new BankCurrencySpec(
                        "gems",
                        BankCurrencySpec.Type.PLAYERPOINTS,
                        0,
                        true,
                        "Gems",
                        "EMERALD",
                        Map.of(),
                        BankCurrencySpec.DEFAULT_QUICK),
                Wallet.empty(),
                "gems");
    }
}
