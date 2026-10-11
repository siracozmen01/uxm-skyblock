package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.runtime.MenuHolder;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is chunks} lists the closed chunks the island can open next, one tile each, and a click on one opens the
 * window of its price, drawn from {@code menus/island-chunk.conf}. A payment there draws the window again.
 */
class TheChunksAreWindowsTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final ChunkBlockStores.Territories territories = new ChunkBlockStores.Territories();
    private final ChunkBlockStores.Payments payments = new ChunkBlockStores.Payments();
    private final IslandStoragePort islands = mock(IslandStoragePort.class);

    @SuppressWarnings("NullAway.Init")
    private PlayerMock ada;

    @SuppressWarnings("NullAway.Init")
    private SkyblockMenuEngine engine;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockPanel panel;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        ada.setLocation(new Location(server.addSimpleWorld("skyblock"), 8, 100, 8));
        ProfileId profile = new ProfileId(ada.getUniqueId());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 24),
                new PlayerUuid(ada.getUniqueId()),
                profile,
                Instant.now());
        when(islands.findIslandIdByProfileId(profile)).thenReturn(Optional.of(island.id()));
        when(islands.findIslandById(island.id())).thenReturn(Optional.of(island));
        ChunkBlockService service = new ChunkBlockService(territories, new ChunkUnlockRules(List.of(2L, 5L), 5));
        service.start(island.id(), 8, 8);
        SchedulerPort scheduler = new InlineSchedulerPort();
        ChunkTier rare = new ChunkTier(
                "rare",
                "@chunkblock.tiers.rare",
                1,
                99,
                1,
                List.of(new ChunkRequirement.Items("oak_log", 16), new ChunkRequirement.Points(2)),
                List.of(),
                List.of());
        IslandBankPort bank = mock(IslandBankPort.class);
        IslandAuthorityPort authorities = mock(IslandAuthorityPort.class);
        IslandAccessService access = mock(IslandAccessService.class);
        when(access.canWithdrawBank(any(), any())).thenReturn(true);
        ChunkUnlocks unlocks = new ChunkUnlocks(
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
                new PlayerChunkPurse(
                        new PlayerChunkPurse.Money() {
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
                        },
                        id -> Optional.empty()),
                (who, tier) -> {},
                uuid -> Optional.of(new ProfileId(uuid)),
                (id, who) -> 0L,
                new ServerNodeId("node-a"));
        engine = ShippedTemplates.engineWith(dataDir, "island-chunks.conf", "island-chunk.conf");
        ChunkWindow window = new ChunkWindow(
                purchase,
                service,
                islands,
                scheduler,
                Messages.bundled(),
                id -> Optional.empty(),
                uuid -> Optional.of(new ProfileId(uuid)),
                (id, who) -> 0L);
        window.useMenuEngine(engine);
        panel = new ChunkBlockPanel(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                uuid -> Optional.of(new ProfileId(uuid)),
                (i, p) -> 0L);
        panel.useMenuEngine(engine);
        panel.usePurchase(purchase);
        panel.useWindow(window);
        engine.install();
    }

    @Test
    @DisplayName("The chunks window lists each closed chunk beside the island, and a click opens the one it shows")
    void theFrontierOpensAChunk() {
        panel.open(ada);
        settle(() -> titleOf().equals("ChunkBlock"));

        MenuRow first = rowAt(19);
        assertThat(first.handle())
                .describedAs("nearest first, then west before east")
                .isEqualTo(new ChunkPos(-1, 0));
        assertThat(loreAt(19)).contains("Rare chunk").contains("Lines paid").contains("0/2");
        assertThat(top().getItem(23))
                .describedAs("four chunks beside the first, no more")
                .isNull();

        click(ChunkWindow.OPEN, first);
        settle(() -> titleOf().equals("Rare chunk"));

        assertThat(loreAt(4)).contains("Rare chunk").contains("-8, 8").contains("Ring");
        assertThat(Objects.requireNonNull(top().getItem(19)).getType()).isEqualTo(Material.OAK_LOG);
        assertThat(loreAt(19))
                .contains("Still to pay")
                .contains("Handed in by you")
                .contains("0/16");
        assertThat(Objects.requireNonNull(top().getItem(20)).getType()).isEqualTo(Material.NETHER_STAR);

        click(ChunkWindow.BYPASS, null);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(Objects.requireNonNull(ada.nextComponentMessage())))
                .contains("Nothing skips");
    }

    @Test
    @DisplayName("Paying in the window takes what the player carries and draws the window again, the line paid")
    void payingDrawsTheWindowAgain() {
        ada.getInventory().addItem(new ItemStack(Material.OAK_LOG, 20));
        panel.open(ada);
        settle(() -> titleOf().equals("ChunkBlock"));
        click(ChunkWindow.OPEN, rowAt(19));
        settle(() -> titleOf().equals("Rare chunk"));

        click(ChunkWindow.PAY_ALL, null);
        settle(() -> loreAt(19).contains("16/16"));

        assertThat(loreAt(19)).contains("Paid");
        assertThat(Objects.requireNonNull(ada.getInventory().getItem(0)).getAmount())
                .isEqualTo(4);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(Objects.requireNonNull(ada.nextComponentMessage())))
                .contains("Paid toward the Rare chunk");
    }

    @Test
    @DisplayName("A payment that opens the chunk leaves the player on the chunks window, that chunk gone from it")
    void anOpenedChunkGoesBackToTheList() {
        ada.getInventory().addItem(new ItemStack(Material.OAK_LOG, 16));
        payments.addPoints(island.id(), 2);
        panel.open(ada);
        settle(() -> titleOf().equals("ChunkBlock"));
        click(ChunkWindow.OPEN, rowAt(19));
        settle(() -> titleOf().equals("Rare chunk"));

        click(ChunkWindow.PAY_ALL, null);
        settle(() -> titleOf().equals("ChunkBlock"));

        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(Objects.requireNonNull(ada.nextComponentMessage())))
                .contains("The Rare chunk is open");
        assertThat(ada.nextComponentMessage())
                .describedAs("and nothing says it is open twice")
                .isNull();
        assertThat(rowAt(19).handle()).isNotEqualTo(new ChunkPos(-1, 0));
    }

    private void click(String verb, @org.jspecify.annotations.Nullable MenuRow row) {
        MenuContext ctx = MenuContext.of(ada, null, 0);
        ShippedTemplates.click(engine, verb, row == null ? ctx : ctx.withEntry(row), ada, ClickKind.LEFT, "");
    }

    private MenuRow rowAt(int slot) {
        MenuHolder holder = (MenuHolder) Objects.requireNonNull(top().getHolder());
        return (MenuRow)
                Objects.requireNonNull(holder.clickAt(slot).orElseThrow().entry());
    }

    private Inventory top() {
        return ada.getOpenInventory().getTopInventory();
    }

    private String loreAt(int slot) {
        ItemStack item = top().getItem(slot);
        if (item == null || item.lore() == null) {
            return "";
        }
        return Objects.requireNonNull(item.lore()).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .collect(Collectors.joining("\n"));
    }

    private String titleOf() {
        Inventory top = ada.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof MenuHolder
                ? PlainTextComponentSerializer.plainText()
                        .serialize(ada.getOpenInventory().title())
                        .strip()
                : "";
    }

    /** Runs the server until {@code done} holds: the engine draws a window off the player's thread first. */
    private void settle(java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            server.getScheduler().waitAsyncTasksFinished();
            if (done.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the server never settled, the window is titled '" + titleOf() + "'");
    }
}
