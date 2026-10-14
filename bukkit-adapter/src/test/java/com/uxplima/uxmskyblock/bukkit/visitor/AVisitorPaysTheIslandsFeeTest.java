package com.uxplima.uxmskyblock.bukkit.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.visit.IslandVisitFeeService;
import com.uxplima.uxmskyblock.core.application.visit.VisitFeeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** SuperiorSkyblock2 lets an island charge its visitors; here a visit was free whatever the owner wanted. */
class AVisitorPaysTheIslandsFeeTest extends MockBukkitHarness {

    /** A clock the test moves. */
    private static final class Moving extends Clock {
        Instant now = Instant.parse("2026-10-14T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Map<IslandId, Long> kept = new HashMap<>();
    private final Map<UUID, Double> purses = new HashMap<>();
    private final List<String> credited = new ArrayList<>();
    private final Moving clock = new Moving();
    private boolean economy = true;
    private boolean bankTakes = true;
    private boolean giveBack = true;
    private long most = 1_000_000;
    private PlayerMock owner;
    private PlayerMock visitor;
    private ProfileId ownerProfile;
    private ProfileId visitorProfile;
    private Island island;
    private VisitFees fees;

    @BeforeEach
    void setUp() {
        owner = createPlayer("Owner");
        visitor = createPlayer("Guest");
        ownerProfile = new ProfileId(UUID.randomUUID());
        visitorProfile = new ProfileId(UUID.randomUUID());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 32),
                new PlayerUuid(owner.getUniqueId()),
                ownerProfile,
                Instant.now());
        purses.put(visitor.getUniqueId(), 100.0);
        fees = new VisitFees(
                new IslandVisitFeeService(
                        new VisitFeeStoragePort() {
                            @Override
                            public long feeOf(IslandId id) {
                                return kept.getOrDefault(id, 0L);
                            }

                            @Override
                            public void setFee(IslandId id, long minorUnits) {
                                kept.put(id, minorUnits);
                            }
                        },
                        new IslandAccessService()),
                id -> id.equals(island.id()) ? Optional.of(island) : Optional.empty(),
                profile -> profile.equals(ownerProfile) ? Optional.of(island.id()) : Optional.empty(),
                new VisitFees.Wallet() {
                    @Override
                    public boolean available() {
                        return economy;
                    }

                    @Override
                    public boolean take(org.bukkit.entity.Player player, double amount) {
                        double held = purses.getOrDefault(player.getUniqueId(), 0.0);
                        if (held < amount) {
                            return false;
                        }
                        purses.put(player.getUniqueId(), held - amount);
                        return true;
                    }

                    @Override
                    public boolean give(org.bukkit.entity.Player player, double amount) {
                        if (giveBack) {
                            purses.merge(player.getUniqueId(), amount, Double::sum);
                        }
                        return giveBack;
                    }

                    @Override
                    public String format(double amount) {
                        return String.format(java.util.Locale.ROOT, "$%.2f", amount);
                    }
                },
                (to, payer, minorUnits) -> {
                    if (!bankTakes) {
                        return false;
                    }
                    credited.add(to + " " + payer + " " + minorUnits);
                    return true;
                },
                () -> most,
                new InlineSchedulerPort(),
                Messages.bundled(),
                clock);
    }

    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(next)).append('\n');
        }
        return all.toString();
    }

    @Test
    @DisplayName(
            "A visitor owes the fee; a member, staff who bypass, and any visitor while fees or the economy are off owe nothing")
    void whoOwes() {
        kept.put(island.id(), 2_550L);
        assertThat(fees.owed(island.id(), visitor, visitorProfile)).isEqualTo(2_550);
        assertThat(fees.owed(island.id(), visitor, null)).isEqualTo(2_550);
        IslandId gone = IslandId.of(UUID.randomUUID());
        kept.put(gone, 70L);
        assertThat(fees.owed(gone, visitor, visitorProfile))
                .describedAs("an island not found makes nobody a member")
                .isEqualTo(70);
        assertThat(fees.owed(island.id(), owner, ownerProfile)).isZero();
        island = island.addMember(new IslandMember(
                new PlayerUuid(visitor.getUniqueId()), visitorProfile, IslandRole.MEMBER, Instant.now()));
        assertThat(fees.owed(island.id(), visitor, visitorProfile))
                .describedAs("a member")
                .isZero();

        PlayerMock staff = createPlayer("Staff");
        staff.addAttachment(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), "uxmskyblock.admin.bypass", true);
        assertThat(fees.owed(island.id(), staff, null)).isZero();
        assertThat(fees.owed(island.id(), createPlayer("Other"), null)).isEqualTo(2_550);
        economy = false;
        assertThat(fees.owed(island.id(), createPlayer("Third"), null)).isZero();
        economy = true;
        most = 0;
        assertThat(fees.owed(island.id(), createPlayer("Fourth"), null)).isZero();
    }

    @Test
    @DisplayName("A fee shown is agreed to for a minute, for that island, at that fee or less")
    void agreedForAMinute() {
        assertThat(fees.agreed(visitor, island.id(), 500))
                .describedAs("never shown")
                .isFalse();
        fees.ask(visitor, island.id(), 500, "/is visit Owner confirm");
        assertThat(said(visitor)).contains("$5.00").contains("/is visit Owner confirm");
        assertThat(fees.agreed(visitor, island.id(), 500)).isTrue();
        assertThat(fees.agreed(visitor, island.id(), 400))
                .describedAs("lowered since")
                .isTrue();
        assertThat(fees.agreed(visitor, island.id(), 501))
                .describedAs("raised since")
                .isFalse();
        assertThat(fees.agreed(visitor, IslandId.of(UUID.randomUUID()), 500))
                .describedAs("another island")
                .isFalse();
        assertThat(fees.agreed(owner, island.id(), 500))
                .describedAs("another player")
                .isFalse();
        clock.now = clock.now.plus(VisitFees.ASKED_FOR);
        assertThat(fees.agreed(visitor, island.id(), 500)).isTrue();
        clock.now = clock.now.plus(Duration.ofMillis(1));
        assertThat(fees.agreed(visitor, island.id(), 500))
                .describedAs("a minute on")
                .isFalse();
    }

    @Test
    @DisplayName(
            "Paying takes the fee from the wallet into the island's bank, then moves the visitor, and forgets the agreement")
    void paying() {
        fees.ask(visitor, island.id(), 2_550, "/x");
        said(visitor);
        List<String> moved = new ArrayList<>();
        fees.pay(visitor, island.id(), 2_550, () -> moved.add("moved"));
        assertThat(purses.get(visitor.getUniqueId())).isEqualTo(74.5);
        assertThat(credited).containsExactly(island.id() + " " + new PlayerUuid(visitor.getUniqueId()) + " 2550");
        assertThat(moved).containsExactly("moved");
        assertThat(said(visitor)).contains("$25.50");
        assertThat(fees.agreed(visitor, island.id(), 2_550)).isFalse();
    }

    @Test
    @DisplayName("A wallet that cannot pay stops the visit; a bank that refuses gives the money back and stops it")
    void notPaid() {
        List<String> moved = new ArrayList<>();
        fees.pay(visitor, island.id(), 20_000, () -> moved.add("moved"));
        assertThat(said(visitor)).contains("cannot pay").contains("$200.00");
        assertThat(purses.get(visitor.getUniqueId())).isEqualTo(100.0);

        bankTakes = false;
        fees.pay(visitor, island.id(), 1_000, () -> moved.add("moved"));
        assertThat(said(visitor)).contains("nothing was taken");
        assertThat(purses.get(visitor.getUniqueId())).isEqualTo(100.0);

        giveBack = false;
        fees.pay(visitor, island.id(), 1_000, () -> moved.add("moved"));
        assertThat(purses.get(visitor.getUniqueId())).isEqualTo(90.0);
        assertThat(moved).isEmpty();
        assertThat(credited).isEmpty();
    }

    @Test
    @DisplayName("A bank that throws counts as refusing: the money goes back and nobody moves")
    void aBankThatThrows() {
        VisitFees throwing = new VisitFees(
                mock(IslandVisitFeeService.class),
                id -> Optional.empty(),
                profile -> Optional.empty(),
                new VisitFees.Wallet() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public boolean take(org.bukkit.entity.Player player, double amount) {
                        purses.merge(player.getUniqueId(), -amount, Double::sum);
                        return true;
                    }

                    @Override
                    public boolean give(org.bukkit.entity.Player player, double amount) {
                        purses.merge(player.getUniqueId(), amount, Double::sum);
                        return true;
                    }

                    @Override
                    public String format(double amount) {
                        return "$" + amount;
                    }
                },
                (to, payer, minorUnits) -> {
                    throw new IllegalStateException("down");
                },
                () -> 100,
                new InlineSchedulerPort(),
                Messages.bundled(),
                clock);
        List<String> moved = new ArrayList<>();
        throwing.pay(visitor, island.id(), 1_000, () -> moved.add("moved"));
        assertThat(purses.get(visitor.getUniqueId())).isEqualTo(100.0);
        assertThat(moved).isEmpty();
    }

    @Test
    @DisplayName("/is visitfee shows the fee and sets it in money, down to the cent, up to the most")
    void theCommand() throws Exception {
        com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator sessions =
                mock(com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator.class);
        when(sessions.activeProfile(owner.getUniqueId())).thenReturn(Optional.of(ownerProfile));
        com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener protection =
                mock(com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener.class);
        when(protection.spatialIndex()).thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex());
        com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree tree =
                new com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree(
                        mock(com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase.class),
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandLocationService.class),
                        mock(com.uxplima.uxmskyblock.core.application.flag.IslandFlagService.class),
                        mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankService.class),
                        mock(com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort.class),
                        mock(com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService.class),
                        mock(com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort.class),
                        new com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog(),
                        mock(com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine.class),
                        protection,
                        sessions,
                        new InlineSchedulerPort(),
                        Messages.bundled(),
                        com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration.defaults(),
                        com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-1"),
                        "world");
        CommandDispatcher<CommandSourceStack> players = new CommandDispatcher<>();
        players.register(tree.buildRoot());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(owner);

        players.execute("island visitfee", source);
        assertThat(said(owner)).describedAs("no fees on this server").contains("do not charge");

        tree.useVisitFees(fees);
        players.execute("island visitfee", source);
        assertThat(said(owner)).contains("nothing").contains("$10000.00");
        players.execute("island visitfee 12.349", source);
        assertThat(said(owner)).contains("$12.34");
        assertThat(kept.get(island.id())).isEqualTo(1_234);
        players.execute("island visitfee", source);
        assertThat(said(owner)).contains("charges visitors $12.34");
        players.execute("island visitfee 10000.01", source);
        assertThat(said(owner)).contains("up to $10000.00");
        players.execute("island visitfee 99999999999999999999", source);
        assertThat(said(owner)).contains("up to $10000.00");
        assertThat(kept.get(island.id())).isEqualTo(1_234);
        players.execute("island visitfee 0", source);
        assertThat(said(owner)).contains("pay nothing");
        assertThat(kept.get(island.id())).isZero();

        when(source.getSender()).thenReturn(visitor);
        players.execute("island visitfee 5", source);
        assertThat(said(visitor)).contains("island of your own");
        economy = false;
        when(source.getSender()).thenReturn(owner);
        players.execute("island visitfee 5", source);
        assertThat(said(owner)).contains("do not charge");
    }

    @Test
    @DisplayName("A member whose role may not change the settings is refused the fee")
    void aMemberIsRefused() {
        ProfileId member = new ProfileId(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(new PlayerUuid(visitor.getUniqueId()), member, IslandRole.MEMBER, Instant.now()));
        VisitFees memberFees = new VisitFees(
                new IslandVisitFeeService(mock(VisitFeeStoragePort.class), new IslandAccessService()),
                id -> Optional.of(island),
                profile -> Optional.of(island.id()),
                new VisitFees.Wallet() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public boolean take(org.bukkit.entity.Player player, double amount) {
                        return false;
                    }

                    @Override
                    public boolean give(org.bukkit.entity.Player player, double amount) {
                        return false;
                    }

                    @Override
                    public String format(double amount) {
                        return "$" + amount;
                    }
                },
                (to, payer, minorUnits) -> true,
                () -> 100,
                new InlineSchedulerPort(),
                Messages.bundled(),
                clock);
        memberFees.set(visitor, member, 1);
        assertThat(said(visitor)).contains("cannot set");
    }
}
