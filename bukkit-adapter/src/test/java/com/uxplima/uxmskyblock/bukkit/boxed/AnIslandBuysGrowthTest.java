package com.uxplima.uxmskyblock.bukkit.boxed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurse;
import com.uxplima.uxmskyblock.bukkit.config.BoxedBuy;
import com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.requirement.LinePayment;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxSide;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A Boxed island buys growth with the lines {@code buy} asks, paid from the buyer's purse and the island bank and
 * given back whole when any part cannot be taken, and chooses the side its box grows toward.
 */
class AnIslandBuysGrowthTest extends MockBukkitHarness {

    private static final Requirement OWN_30 =
            new Requirement.Money(3000, Requirement.Source.PLAYER, Requirement.Mode.TAKE);
    private static final Requirement BANK_50 =
            new Requirement.Money(5000, Requirement.Source.ISLAND, Requirement.Mode.TAKE);

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final Map<IslandId, Long> earned = new HashMap<>();
    private final Set<String> rows = new HashSet<>();
    private final Map<UUID, Long> wallets = new HashMap<>();
    private final List<IslandId> changed = new ArrayList<>();
    private final List<String> fired = new ArrayList<>();
    private long bank = 0;
    private boolean bankLets = true;
    private boolean bankFails;
    private Runnable whileTaking = () -> {};

    @SuppressWarnings("NullAway.Init")
    private PlayerMock owner;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    private final BoxedService service = new BoxedService(
            new BoxedIslandsPort() {
                @Override
                public Map<IslandId, Long> findAll() {
                    return Map.copyOf(earned);
                }

                @Override
                public OptionalLong find(IslandId id) {
                    Long blocks = earned.get(id);
                    return blocks == null ? OptionalLong.empty() : OptionalLong.of(blocks);
                }

                @Override
                public void add(IslandId id) {
                    earned.putIfAbsent(id, 0L);
                }

                @Override
                public boolean earn(IslandId id, String advancement, int blocks) {
                    if (!rows.add(id + advancement)) {
                        return false;
                    }
                    earned.merge(id, (long) blocks, Long::sum);
                    return true;
                }

                @Override
                public void grant(IslandId id, long blocks) {
                    earned.merge(id, blocks, Long::sum);
                }

                @Override
                public long bought(IslandId id) {
                    return rows.stream()
                            .filter(row -> row.startsWith(id + BOUGHT))
                            .count();
                }
            },
            new BoxRules(5, 1, 9, Map.of(), List.of(), AdvancementWorth.flat(1)));

    @BeforeEach
    void setUpTheShop() {
        owner = createPlayer("Owner");
        island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 5),
                PlayerUuid.of(owner.getUniqueId()),
                ProfileId.of(owner.getUniqueId()),
                Instant.now());
        service.start(islandId);
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
    }

    @Test
    @DisplayName(
            "Growth is bought with the buyer's money: the box grows, the purchase is counted, and the effect fires")
    void bought() {
        wallets.put(owner.getUniqueId(), 10_000L);

        shop(new BoxedBuy(2, List.of(OWN_30), 0)).buy(owner);

        assertThat(service.radius(islandId)).hasValue(7);
        assertThat(service.bought(islandId)).isEqualTo(1);
        assertThat(wallets).containsEntry(owner.getUniqueId(), 7_000L);
        assertThat(changed).containsExactly(islandId);
        assertThat(said(owner)).singleElement().asString().contains("bought growth", "grew by 2", "15×15");
        assertThat(fired).containsExactly("bought 2 15");

        shop(new BoxedBuy(2, List.of(OWN_30), 0)).buy(owner);
        assertThat(service.bought(islandId))
                .describedAs("a second purchase, once the first is over")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("A line that does not hold is said, and nothing is taken or grown")
    void missing() {
        wallets.put(owner.getUniqueId(), 1_000L);

        shop(new BoxedBuy(2, List.of(OWN_30), 0)).buy(owner);

        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(wallets).containsEntry(owner.getUniqueId(), 1_000L);
        assertThat(said(owner))
                .containsExactly("ERROR ▶ Growth asks for:", " • 30 of your money")
                .hasSize(2);
    }

    @Test
    @DisplayName("The bank pays for a member who may spend from it, and what the buyer paid comes back when it cannot")
    void theBankPays() {
        wallets.put(owner.getUniqueId(), 10_000L);
        bank = 10_000;
        BoxedShop shop = shop(new BoxedBuy(1, List.of(OWN_30, BANK_50), 0));

        bankLets = false;
        shop.buy(owner);
        assertThat(service.radius(islandId))
                .describedAs("a member who may not spend from the bank")
                .hasValue(5);
        said(owner);

        bankLets = true;
        bankFails = true;
        shop.buy(owner);
        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(wallets).describedAs("given back whole").containsEntry(owner.getUniqueId(), 10_000L);
        assertThat(said(owner)).first().asString().contains("asks for");

        bankFails = false;
        shop.buy(owner);
        assertThat(service.radius(islandId)).hasValue(6);
        assertThat(bank).isEqualTo(5_000);
        assertThat(wallets).containsEntry(owner.getUniqueId(), 7_000L);
    }

    @Test
    @DisplayName("No growth is sold while buying is off, to no Boxed island, past the box's reach or past most")
    void refused() {
        wallets.put(owner.getUniqueId(), 100_000L);

        shop(BoxedBuy.OFF).buy(owner);
        assertThat(said(owner)).singleElement().asString().contains("cannot be bought");

        shop(new BoxedBuy(1, List.of(OWN_30), 1)).buy(owner);
        shop(new BoxedBuy(1, List.of(OWN_30), 1)).buy(owner);
        assertThat(service.bought(islandId)).isEqualTo(1);
        assertThat(said(owner)).last().asString().contains("all the growth it may");

        service.grant(islandId, 10);
        shop(new BoxedBuy(1, List.of(OWN_30), 0)).buy(owner);
        assertThat(said(owner)).singleElement().asString().contains("as far as a box can");

        service.forget(islandId);
        earned.remove(islandId);
        service.forget(islandId);
        shop(new BoxedBuy(1, List.of(OWN_30), 0)).buy(owner);
        assertThat(said(owner))
                .describedAs("an island no longer Boxed")
                .singleElement()
                .asString()
                .contains("no Boxed island");

        PlayerMock stranger = createPlayer("Stranger");
        shop(new BoxedBuy(1, List.of(OWN_30), 0)).buy(stranger);
        assertThat(said(stranger)).singleElement().asString().contains("no Boxed island");
        assertThat(wallets).containsEntry(owner.getUniqueId(), 97_000L);
    }

    @Test
    @DisplayName("An island being sold growth is not sold it twice at once")
    void oneAtATime() {
        wallets.put(owner.getUniqueId(), 100_000L);
        BoxedShop shop = shop(new BoxedBuy(1, List.of(OWN_30), 0));
        whileTaking = () -> {
            whileTaking = () -> {};
            shop.buy(owner);
        };

        shop.buy(owner);

        assertThat(service.bought(islandId)).isEqualTo(1);
        assertThat(wallets).containsEntry(owner.getUniqueId(), 97_000L);
    }

    @Test
    @DisplayName(
            "The owner chooses the side the box grows toward; a role that may not move it, and a wrong word, may not")
    void aSideIsChosen() {
        BoxedShop shop = shop(new BoxedBuy(1, List.of(OWN_30), 0));

        shop.side(owner, null);
        assertThat(said(owner)).singleElement().asString().contains("every side", "north_east");
        shop.side(owner, "up");
        assertThat(said(owner)).singleElement().asString().contains("Choose one of");
        shop.side(owner, "East");
        assertThat(service.side(islandId)).isEqualTo(BoxSide.EAST);
        assertThat(said(owner)).singleElement().asString().contains("the east", "from now on");

        PlayerMock member = createPlayer("Member");
        island = island.addMember(new IslandMember(
                PlayerUuid.of(member.getUniqueId()),
                ProfileId.of(member.getUniqueId()),
                IslandRole.MEMBER,
                Instant.now()));
        shop.side(member, "west");
        assertThat(service.side(islandId)).isEqualTo(BoxSide.EAST);
        assertThat(said(member)).singleElement().asString().contains("cannot choose");
        island = island.addMember(new IslandMember(
                PlayerUuid.of(member.getUniqueId()),
                ProfileId.of(member.getUniqueId()),
                new IslandRole(
                        "MOVER",
                        400,
                        "Mover",
                        java.util.EnumSet.of(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BOX_MOVE),
                        false),
                Instant.now()));
        shop.side(member, "west");
        assertThat(service.side(islandId))
                .describedAs("a role that may move the box")
                .isEqualTo(BoxSide.WEST);

        PlayerMock stranger = createPlayer("Stranger");
        shop.side(stranger, "west");
        assertThat(said(stranger)).singleElement().asString().contains("no Boxed island");
    }

    @Test
    @DisplayName("What a purchase asks is read from the file, and one that cannot be turns buying off")
    void read() throws Exception {
        assertThat(load("buy { blocks = 3, lines = [\"take money 30\", \"take money 50 island\"], most = 4 }"))
                .isEqualTo(new BoxedBuy(3, List.of(OWN_30, BANK_50), 4));
        assertThat(load("enabled = true")).isEqualTo(BoxedBuy.SHIPPED);
        assertThat(BoxedConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString(new String(
                                        AnIslandBuysGrowthTest.class
                                                .getResourceAsStream("/modules/boxed.conf")
                                                .readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .buy())
                .describedAs("the shipped file")
                .isEqualTo(BoxedBuy.SHIPPED);
        assertThat(BoxedBuy.SHIPPED.on()).isTrue();
        assertThat(BoxedBuy.OFF.on()).isFalse();
        for (String wrong : List.of(
                "buy { lines = [\"take gold\"] }",
                "buy { lines = [\"take points 5\"] }",
                "buy { lines = [\"have wait 1h\"] }",
                "buy { blocks = 0 }",
                "buy { blocks = 401 }",
                "buy { most = -1 }")) {
            assertThat(load(wrong)).describedAs(wrong).isEqualTo(BoxedBuy.OFF);
        }
        assertThat(load("buy { lines = [] }").on()).isFalse();
    }

    private static BoxedBuy load(String written) throws Exception {
        return BoxedConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .buy();
    }

    private BoxedShop shop(BoxedBuy buy) {
        SchedulerPort now = new Immediate();
        ChunkPurse purse = new ChunkPurse() {
            @Override
            public long carried(Player player, ChunkRequirement line) {
                return wallets.getOrDefault(player.getUniqueId(), 0L);
            }

            @Override
            public long take(Player player, ChunkRequirement line, long wanted) {
                whileTaking.run();
                long held = wallets.getOrDefault(player.getUniqueId(), 0L);
                long got = Math.min(held, wanted);
                wallets.put(player.getUniqueId(), held - got);
                return got;
            }

            @Override
            public void giveBack(Player player, ChunkRequirement line, long amount) {
                wallets.merge(player.getUniqueId(), amount, Long::sum);
            }
        };
        LinePayment.IslandFunds funds = new LinePayment.IslandFunds() {
            @Override
            public long held(IslandId id, Requirement line) {
                return bank;
            }

            @Override
            public boolean take(IslandId id, Player payer, Requirement line) {
                if (bankFails) {
                    return false;
                }
                bank -= ((Requirement.Money) line).minor();
                return true;
            }

            @Override
            public void giveBack(IslandId id, Player payer, Requirement line) {
                bank += ((Requirement.Money) line).minor();
            }

            @Override
            public boolean mayTake(Island of, Player payer) {
                return bankLets;
            }
        };
        return new BoxedShop(
                service,
                buy,
                new LinePayment(now, purse, funds, (of, player) -> 0L),
                uuid -> Optional.of(ProfileId.of(uuid)),
                profile -> island.isMember(profile) ? Optional.of(islandId) : Optional.empty(),
                id -> id.equals(islandId) ? Optional.of(island) : Optional.empty(),
                changed::add,
                now,
                Messages.bundled(),
                new InteractionEffectPlayer(now, Messages.bundled()),
                new InteractionEffects(Map.of(
                        "boxed-grew",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe bought %blocks% %size%")))));
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (Component next = player.nextComponentMessage(); next != null; next = player.nextComponentMessage()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    /** Runs everything at once, on the caller's thread. */
    private static final class Immediate implements SchedulerPort {
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
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
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
    }
}
