package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsPiracy;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Piracy;
import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort.Plunder;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Piracy is read from {@code piracy} of {@code modules/tradewinds.conf}, off as shipped, and {@code /is plunder}
 * takes part of the hold of the vessel a crew member stands by at sea, off its deck, telling both crews.
 */
class ACrewBoardsAtSeaTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final TradeWindsPiracy ON = new TradeWindsPiracy(true, 0.25, Duration.ofMinutes(30), 8);

    private final Piracy piracy = mock(Piracy.class);
    private final VesselService vessels = mock(VesselService.class);

    @Test
    @DisplayName("Piracy is read with its share, cooldown and deck, off as shipped, and falls back when it cannot be")
    void read() throws Exception {
        assertThat(load("piracy { enabled = true, share = 0.5, cooldown = \"1h\", deck-radius = 12 }"))
                .isEqualTo(new TradeWindsPiracy(true, 0.5, Duration.ofHours(1), 12));
        assertThat(load("enabled = true")).isEqualTo(TradeWindsPiracy.SHIPPED);
        assertThat(TradeWindsPiracy.SHIPPED.enabled()).isFalse();
        assertThat(load("piracy { enabled = true, share = 0 }")).isEqualTo(TradeWindsPiracy.SHIPPED);
        assertThat(load("piracy { enabled = true, share = 1.5 }")).isEqualTo(TradeWindsPiracy.SHIPPED);
        assertThat(load("piracy { enabled = true, cooldown = \"soon\" }")).isEqualTo(TradeWindsPiracy.SHIPPED);
        assertThat(load("piracy { enabled = true, deck-radius = 200 }")).isEqualTo(TradeWindsPiracy.SHIPPED);
        assertThat(load("piracy { enabled = true }"))
                .isEqualTo(new TradeWindsPiracy(true, 0.25, Duration.ofMinutes(30), 8));
    }

    @Test
    @DisplayName("A plunder takes the share of each stack, rounded down, into the other hold, whole or not at all")
    void split() {
        byte[] target = BukkitInventorySerializer.serializeItemStacks(new ItemStack[] {
            new ItemStack(Material.GOLD_INGOT, 10),
            new ItemStack(Material.IRON_INGOT, 7),
            new ItemStack(Material.STONE, 3)
        });

        Piracy.Haul haul = HoldGoods.plunder(target, new byte[0], 9, 0.25).orElseThrow();

        assertThat(haul.items()).isEqualTo(3);
        assertThat(amounts(haul.target())).containsExactly("GOLD_INGOT 8", "IRON_INGOT 6", "STONE 3");
        assertThat(amounts(haul.raider())).containsExactlyInAnyOrder("GOLD_INGOT 2", "IRON_INGOT 1");
        assertThat(amounts(HoldGoods.plunder(target, new byte[0], 9, 1)
                        .orElseThrow()
                        .target()))
                .describedAs("all of it")
                .isEmpty();
        byte[] full = BukkitInventorySerializer.serializeItemStacks(new ItemStack[] {new ItemStack(Material.DIRT, 64)});
        assertThat(HoldGoods.plunder(target, full, 1, 0.25))
                .describedAs("no room")
                .isEmpty();
        assertThat(HoldGoods.plunder(
                        BukkitInventorySerializer.serializeItemStacks(
                                new ItemStack[] {new ItemStack(Material.STONE, 3)}),
                        new byte[0],
                        9,
                        0.25))
                .describedAs("a quarter of three is none")
                .isEmpty();
        assertThat(HoldGoods.plunder(new byte[0], new byte[0], 9, 0.25)).isEmpty();
    }

    @Test
    @DisplayName("Off, by no vessel, on its deck or one's own vessel: nothing is plundered, and each says why")
    void refused() {
        World sea = server.addSimpleWorld("sea");
        PlayerMock ada = createPlayer("Ada");
        Island target = vessel(IslandId.of(UUID.randomUUID()), UUID.randomUUID());
        Island own = vessel(IslandId.of(UUID.randomUUID()), ada.getUniqueId());
        ada.teleport(new Location(sea, 100, 64, 0));

        boarding(TradeWindsPiracy.SHIPPED, target, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("not allowed");

        boarding(ON, null, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("not by a TradeWinds vessel");

        ada.teleport(new Location(sea, 5, 64, 5));
        boarding(ON, target, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("deck is protected");

        ada.teleport(new Location(sea, 1, 64, 1));
        boarding(ON, own, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("your own vessel");
        Island crewed = vessel(IslandId.of(UUID.randomUUID()), UUID.randomUUID())
                .addMember(new IslandMember(
                        new PlayerUuid(ada.getUniqueId()), new ProfileId(ada.getUniqueId()), IslandRole.MEMBER, NOW));
        boarding(ON, crewed, own, ada).plunder(ada);
        assertThat(said(ada))
                .describedAs("a vessel one crews, not owns")
                .singleElement()
                .asString()
                .contains("your own vessel");
        verify(piracy, never()).plunder(any(), any(), any());
    }

    @Test
    @DisplayName("A plunder at sea goes through the vessels, and both crews online are told")
    void plundered() {
        World sea = server.addSimpleWorld("sea");
        PlayerMock ada = createPlayer("Ada");
        PlayerMock bo = createPlayer("Bo");
        Island target = vessel(IslandId.of(UUID.randomUUID()), bo.getUniqueId());
        Island own = vessel(IslandId.of(UUID.randomUUID()), ada.getUniqueId());
        ada.teleport(new Location(sea, 100, 64, 0));
        when(piracy.plunder(eq(own.id()), eq(target.id()), any()))
                .thenReturn(Result.err("tradewinds.piracy.too_soon"))
                .thenReturn(Result.ok(new Plunder(own.id(), target.id(), NOW, 7)));

        boarding(ON, target, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("too recently", "30m");

        boarding(ON, target, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("took 7 items", "Bo");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Piracy.Split> split = org.mockito.ArgumentCaptor.forClass(Piracy.Split.class);
        verify(piracy, org.mockito.Mockito.times(2)).plunder(eq(own.id()), eq(target.id()), split.capture());
        byte[] hold =
                BukkitInventorySerializer.serializeItemStacks(new ItemStack[] {new ItemStack(Material.GOLD_INGOT, 8)});
        assertThat(amounts(split.getValue()
                        .split(hold, new byte[0], 9)
                        .orElseThrow()
                        .raider()))
                .describedAs("the share the file writes")
                .containsExactly("GOLD_INGOT 2");
        assertThat(said(bo)).singleElement().asString().contains("Pirates", "Ada", "took 7 items");
    }

    @Test
    @DisplayName("Only a member whose role steers their vessel plunders with it, and only with a vessel")
    void role() {
        World sea = server.addSimpleWorld("sea");
        PlayerMock ada = createPlayer("Ada");
        Island target = vessel(IslandId.of(UUID.randomUUID()), UUID.randomUUID());
        UUID captain = UUID.randomUUID();
        Island own = vessel(IslandId.of(UUID.randomUUID()), captain)
                .addMember(new IslandMember(
                        new PlayerUuid(ada.getUniqueId()), new ProfileId(ada.getUniqueId()), IslandRole.MEMBER, NOW));
        ada.teleport(new Location(sea, 100, 64, 0));

        boarding(ON, target, own, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("cannot do that");

        Island owned = vessel(own.id(), ada.getUniqueId());
        when(vessels.isVessel(own.id())).thenReturn(false);
        boarding(ON, target, owned, ada).plunder(ada);
        assertThat(said(ada)).singleElement().asString().contains("crew no TradeWinds vessel");
        verify(piracy, never()).plunder(any(), any(), any());
    }

    private static TradeWindsPiracy load(String written) throws Exception {
        return TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .piracy();
    }

    private Island vessel(IslandId id, UUID owner) {
        when(vessels.isVessel(id)).thenReturn(true);
        return Island.create(
                id, IslandBounds.fromCenterAndRadius(0, 0, 200), new PlayerUuid(owner), new ProfileId(owner), NOW);
    }

    private Boarding boarding(
            TradeWindsPiracy config,
            com.uxplima.uxmskyblock.core.domain.island.@org.jspecify.annotations.Nullable Island target,
            Island own,
            PlayerMock player) {
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
        ActiveSession session = mock(ActiveSession.class);
        when(session.activeProfileId()).thenReturn(new ProfileId(player.getUniqueId()));
        CargoHolds.Sessions sessions = new CargoHolds.Sessions() {
            @Override
            public ActiveSession session(UUID uuid) {
                return session;
            }

            @Override
            public void fence(UUID uuid, String why) {}
        };
        Map<IslandId, Island> islands = new java.util.HashMap<>();
        islands.put(own.id(), own);
        if (target != null) {
            islands.put(target.id(), target);
        }
        Boarding boarding = new Boarding(
                piracy,
                config,
                vessels,
                at -> Optional.ofNullable(target),
                profile -> Optional.of(own.id()),
                id -> Optional.ofNullable(islands.get(id)),
                sessions,
                server::getPlayer,
                uuid -> Objects.requireNonNullElse(server.getOfflinePlayer(uuid).getName(), "someone"),
                scheduler,
                Messages.bundled());
        boarding.useEffects(new InteractionEffectPlayer(null, Messages.bundled()), new InteractionEffects(Map.of()));
        return boarding;
    }

    private static List<String> amounts(byte[] hold) {
        return Arrays.stream(BukkitInventorySerializer.deserializeItemStacks(hold))
                .filter(Objects::nonNull)
                .filter(stack -> !stack.getType().isAir())
                .map(stack -> stack.getType().name() + " " + stack.getAmount())
                .toList();
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
