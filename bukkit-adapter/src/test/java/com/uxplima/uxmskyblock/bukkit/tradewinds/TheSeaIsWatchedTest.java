package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsVoyageEvents;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.loot.LootFiller;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards.Struck;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Hazard;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Planned;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * What a vessel at sea meets is read from {@code voyage-events} of {@code modules/tradewinds.conf}, and told to its
 * crew online as it happens: a storm, a toll, boarders next to the crew aboard, and a find stowed or lost.
 */
class TheSeaIsWatchedTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private static TradeWindsVoyageEvents read(String written) throws Exception {
        return TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .voyageEvents();
    }

    @Test
    @DisplayName("Each event is read with its chance, a chance left out is none, and a part that cannot be falls back")
    void read() throws Exception {
        TradeWindsVoyageEvents events = read("""
                voyage-events {
                    storm { chance = 0.5, delay = "2m" }
                    pirates { chance = 0.25, toll = 900, creature = "zombie", boarders = 2 }
                    find { chance = 1, loot = "wreck" }
                }
                """);

        assertThat(events.chances()).isEqualTo(new SeaHazards.Chances(0.5, Duration.ofMinutes(2), 0.25, 900, 1));
        assertThat(events.boarderType()).contains(EntityType.ZOMBIE);
        assertThat(events.boarders()).isEqualTo(2);
        assertThat(events.loot()).isEqualTo("wreck");
        assertThat(read("voyage-events { find { chance = 0.2 } }").chances())
                .isEqualTo(new SeaHazards.Chances(0, Duration.ZERO, 0, 0, 0.2));
        assertThat(read("voyage-events { storm { chance = 2 } }"))
                .describedAs("a chance that cannot be")
                .isEqualTo(TradeWindsVoyageEvents.SHIPPED);
        assertThat(read("voyage-events { storm { chance = 1, delay = \"soon\" } }"))
                .isEqualTo(TradeWindsVoyageEvents.SHIPPED);
        assertThat(read("voyage-events { pirates { chance = 1, boarders = 17 } }")
                        .boarders())
                .isZero();
        assertThat(read("voyage-events { pirates { chance = 1, creature = \"kraken\", boarders = 2 } }")
                        .boarderType())
                .isEmpty();
        assertThat(read("enabled = true")).isEqualTo(TradeWindsVoyageEvents.SHIPPED);
        assertThat(read("voyage-events {}").chances())
                .describedAs("a block that writes no event has none")
                .isEqualTo(SeaHazards.Chances.NONE);
        try (var in = TheSeaIsWatchedTest.class.getResourceAsStream("/modules/tradewinds.conf")) {
            String text = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(read(text)).isEqualTo(TradeWindsVoyageEvents.SHIPPED);
        }
    }

    @Test
    @DisplayName("The crew online is told of each event, boarders come next to the crew aboard, and a find is stowed")
    void watched() {
        World sea = server.addSimpleWorld("sea");
        PlayerMock ada = createPlayer("Ada");
        PlayerMock bo = createPlayer("Bo");
        ada.teleport(sea.getSpawnLocation());
        bo.teleport(sea.getSpawnLocation().add(1000, 0, 0));
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Island island = crewed(vessel, ada, bo);
        SeaHazards hazards = mock(SeaHazards.class);
        Planned storm = new Planned(vessel, Hazard.STORM, "saltmarsh", NOW);
        Planned pirates = new Planned(vessel, Hazard.PIRATES, "saltmarsh", NOW);
        Planned find = new Planned(vessel, Hazard.FIND, "saltmarsh", NOW);
        when(hazards.strike())
                .thenReturn(List.of(
                        new Struck.Storm(storm, Duration.ofSeconds(30), NOW.plusSeconds(90)),
                        new Struck.Toll(pirates, 20_000),
                        new Struck.Boarded(pirates),
                        new Struck.Find(find)));
        List<byte[]> filled = new ArrayList<>();
        when(hazards.stow(any(), any())).thenAnswer(call -> {
            java.util.function.BiFunction<byte[], Integer, Optional<byte[]>> fill = call.getArgument(1);
            fill.apply(new byte[0], 27).ifPresent(filled::add);
            return SeaHazards.Stowed.STOWED;
        });
        SeaWatch watch = watch(hazards, island, "voyage-events { pirates { creature = \"zombie\", boarders = 2 } }");

        watch.watch();

        assertThat(said(ada))
                .anyMatch(line -> line.contains("storm") && line.contains("Saltmarsh") && line.contains("30s"))
                .anyMatch(line -> line.contains("took 200.00"))
                .anyMatch(line -> line.contains("Pirates board"))
                .anyMatch(line -> line.contains("It is in the hold"));
        assertThat(said(bo)).describedAs("the crew elsewhere is told too").hasSize(4);
        assertThat(sea.getEntities())
                .filteredOn(entity -> entity.getType() == EntityType.ZOMBIE)
                .describedAs("two next to Ada, who is aboard, and none next to Bo, who is not")
                .hasSize(2)
                .allSatisfy(zombie -> assertThat(zombie.getLocation().distance(ada.getLocation()))
                        .isLessThan(4));
        assertThat(filled)
                .singleElement()
                .satisfies(hold -> assertThat(BukkitInventorySerializer.deserializeItemStacks(hold))
                        .anySatisfy(stack -> assertThat(stack)
                                .isNotNull()
                                .extracting(ItemStack::getType)
                                .isEqualTo(Material.GOLD_INGOT)));
    }

    @Test
    @DisplayName("A find the hold has no room for is lost, and said so; one not stowed yet says nothing")
    void lost() {
        PlayerMock ada = createPlayer("Ada");
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Island island = crewed(vessel, ada);
        SeaHazards hazards = mock(SeaHazards.class);
        Struck.Find find = new Struck.Find(new Planned(vessel, Hazard.FIND, "saltmarsh", NOW));
        when(hazards.strike()).thenReturn(List.of(find));
        when(hazards.stow(any(), any())).thenReturn(SeaHazards.Stowed.NO_ROOM).thenReturn(SeaHazards.Stowed.NOT_NOW);
        SeaWatch watch = watch(hazards, island, "enabled = true");

        watch.watch();
        assertThat(said(ada)).singleElement().asString().contains("no room");
        watch.watch();
        assertThat(said(ada)).isEmpty();

        watch.sailed(vessel, "saltmarsh", NOW, NOW.plusSeconds(60));
        verify(hazards).plan(vessel, "saltmarsh", NOW, NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("Customs tell the crew what they seized and the standing it cost, or that the hold was clean")
    void customs() {
        PlayerMock ada = createPlayer("Ada");
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Island island = crewed(vessel, ada);
        SeaHazards hazards = mock(SeaHazards.class);
        Planned customs = new Planned(vessel, Hazard.CUSTOMS, "emerald-bay", NOW);
        when(hazards.strike())
                .thenReturn(List.of(new Struck.Inspected(customs, Map.of("GUNPOWDER", 10, "TNT", 2), 50_000)))
                .thenReturn(List.of(new Struck.Inspected(customs, Map.of(), 0)));
        SeaWatch watch = watch(hazards, island, "enabled = true");

        watch.watch();
        assertThat(said(ada))
                .singleElement()
                .asString()
                .contains("Emerald Bay", "10 Gunpowder", "2 TNT", "fell by", "500.00");
        watch.watch();
        assertThat(said(ada)).singleElement().asString().contains("found nothing");
    }

    @Test
    @DisplayName("A find goes into the hold whole, or not at all")
    void whole() {
        byte[] full =
                BukkitInventorySerializer.serializeItemStacks(new ItemStack[] {new ItemStack(Material.STONE, 64)});
        assertThat(HoldGoods.stowAll(full, 1, List.of(new ItemStack(Material.GOLD_INGOT, 3))))
                .isEmpty();
        byte[] half =
                BukkitInventorySerializer.serializeItemStacks(new ItemStack[] {new ItemStack(Material.STONE, 60)});
        assertThat(HoldGoods.stowAll(
                        half, 1, List.of(new ItemStack(Material.STONE, 3), new ItemStack(Material.STONE, 3))))
                .describedAs("the first fits and the second does not: none goes in")
                .isEmpty();
        assertThat(HoldGoods.stowAll(half, 2, List.of(new ItemStack(Material.GOLD_INGOT, 3))))
                .get()
                .satisfies(hold -> assertThat(BukkitInventorySerializer.deserializeItemStacks(hold))
                        .anySatisfy(stack -> assertThat(stack)
                                .isNotNull()
                                .extracting(ItemStack::getAmount)
                                .isEqualTo(3)));
    }

    private static Island crewed(IslandId vessel, PlayerMock owner, PlayerMock... crew) {
        Island island = Island.create(
                vessel,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(owner.getUniqueId()),
                NOW);
        for (PlayerMock member : crew) {
            island = island.addMember(new IslandMember(
                    new PlayerUuid(member.getUniqueId()), new ProfileId(member.getUniqueId()), IslandRole.MEMBER, NOW));
        }
        return island;
    }

    private final Island elsewhere = Island.create(
            IslandId.of(UUID.randomUUID()),
            IslandBounds.fromCenterAndRadius(1000, 0, 50),
            new PlayerUuid(UUID.randomUUID()),
            new ProfileId(UUID.randomUUID()),
            NOW);

    private SeaWatch watch(SeaHazards hazards, Island island, String file) {
        TradeWindsVoyageEvents events;
        try {
            events = read(file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        LootTables tables;
        try {
            tables = LootTables.of(LootTables.table(
                    events.loot(),
                    HoconConfigurationLoader.builder()
                            .buildAndLoadString(
                                    "rolls = 1\nentries = [ { amount = 3, item { material = GOLD_INGOT } } ]")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        SeaWatch watch = new SeaWatch(
                hazards,
                events,
                () -> tables,
                LootFiller.SERVER,
                scheduler,
                Messages.bundled(),
                server::getPlayer,
                id -> id.equals(island.id()) ? Optional.of(island) : Optional.empty(),
                // Bo stands on an island of their own, another than the vessel.
                at -> Optional.of(at.getX() < 100 ? island : elsewhere),
                new Random(3));
        watch.useEffects(new InteractionEffectPlayer(null, Messages.bundled()), new InteractionEffects(Map.of()));
        return watch;
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        net.kyori.adventure.text.Component line;
        while ((line = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }
}
