package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.loot.LootContainers;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
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
 * A container a OneBlock phase draws is filled from a loot table when first opened: the entry's own, else the
 * phase's, and an entry's rarity fires that rarity's effects for the breaker when it appears.
 */
class ADrawnContainerIsFilledFromItsTableTest extends MockBukkitHarness {

    private World world;
    private OneBlockConfiguration config;
    private OneBlockDraws draws;

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("skyblock");
        config = OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        phases = [ {
                            name = "meadow"
                            length = 10
                            loot = "basic"
                            blocks {
                                CHEST = 1
                                BARREL = 1
                                fancy { block = "BARREL", loot = "rich", rarity = "rare" }
                                bare { block = "CHEST", loot = "" }
                                odd { block = "SHULKER_BOX", rarity = "mythic" }
                                dirt { block = "DIRT", rarity = "rare" }
                            }
                        }, {
                            name = "cave"
                            length = 10
                            blocks { CHEST = 1 }
                        } ]
                        """));
        LootTables tables = LootTables.of(
                LootTables.table(
                        "rich",
                        HoconConfigurationLoader.builder()
                                .buildAndLoadString(
                                        "rarities { common { weight = 1 }, rare { weight = 1, effects = loot-rare } }")),
                LootTables.table("basic", HoconConfigurationLoader.builder().buildAndLoadString("rolls = 1")));
        draws = new OneBlockDraws(config::entries, null, () -> tables);
    }

    private Block laid(String drawn) {
        draws.lay(world, 0, 100, 0, drawn);
        return world.getBlockAt(0, 100, 0);
    }

    @Test
    @DisplayName("The entry's own table, else the phase's; a blank one means none, and a phase may name none")
    void whichTable() {
        assertThat(config.entries().tableFor("CHEST", "meadow")).isEqualTo("basic");
        assertThat(config.entries().tableFor("meadow/fancy", "meadow")).isEqualTo("rich");
        assertThat(config.entries().tableFor("meadow/odd", "meadow")).isEqualTo("basic");
        assertThat(config.entries().tableFor("meadow/bare", "meadow")).isNull();
        assertThat(config.entries().tableFor("CHEST", "cave")).isNull();
    }

    @Test
    @DisplayName("A chest, a barrel and a shulker box wait for their table; a block that holds nothing is given none")
    void containers() {
        assertThat(draws.fill(world, 0, 100, 0, "CHEST", "meadow")).isNull();
        Block chest = laid("CHEST");
        assertThat(draws.fill(world, 0, 100, 0, "CHEST", "meadow")).isNull();
        assertThat(LootContainers.waitingFor(chest)).isEqualTo("basic");

        Block barrel = laid("BARREL");
        assertThat(draws.fill(world, 0, 100, 0, "BARREL", "meadow")).isNull();
        assertThat(LootContainers.waitingFor(barrel)).isEqualTo("basic");

        Block box = laid("meadow/odd");
        assertThat(box.getType()).isEqualTo(Material.SHULKER_BOX);
        assertThat(draws.fill(world, 0, 100, 0, "meadow/odd", "meadow"))
                .describedAs("a rarity the table does not name fires nothing")
                .isNull();
        assertThat(LootContainers.waitingFor(box)).isEqualTo("basic");

        laid("meadow/dirt");
        assertThat(draws.fill(world, 0, 100, 0, "meadow/dirt", "meadow"))
                .describedAs("dirt holds nothing, so nothing appeared")
                .isNull();

        Block bare = laid("meadow/bare");
        assertThat(draws.fill(world, 0, 100, 0, "meadow/bare", "meadow")).isNull();
        assertThat(LootContainers.waitingFor(bare)).isNull();
        Block plain = laid("CHEST");
        assertThat(draws.fill(world, 0, 100, 0, "CHEST", "cave")).isNull();
        assertThat(LootContainers.waitingFor(plain)).isNull();
    }

    @Test
    @DisplayName("An entry's rarity names the effects that fire when it appears")
    void rarity() {
        Block barrel = laid("meadow/fancy");

        assertThat(draws.fill(world, 0, 100, 0, "meadow/fancy", "meadow")).isEqualTo("loot-rare");
        assertThat(LootContainers.waitingFor(barrel)).isEqualTo("rich");
    }

    @Test
    @DisplayName("A break that draws a rare container fires its rarity's effects for the breaker")
    void onTheBreak() throws Exception {
        OneBlockConfiguration rare =
                OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        phases = [ {
                            name = "meadow"
                            length = 10
                            blocks { fancy { block = "BARREL", loot = "rich", rarity = "rare" } }
                        } ]
                        """));
        LootTables tables = LootTables.of(LootTables.table(
                "rich",
                HoconConfigurationLoader.builder()
                        .buildAndLoadString("rarities { rare { weight = 1, effects = loot-rare } }")));
        PlayerMock player = createPlayer("Breaker");
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        OneBlockService service = new OneBlockService(memory(), rare.phases(), new SplittableRandom(3));
        service.start(island.id(), 0, 100, 0);
        List<Runnable> tasks = new ArrayList<>();
        List<String> fired = new ArrayList<>();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> tasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> tasks.add(call.getArgument(1, Runnable.class)))
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class));
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        OneBlockListener listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of("loot-rare", ActionList.parse(List.of("[console] probe rare")))),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.useDraws(new OneBlockDraws(rare::entries, null, () -> tables));
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.BARREL);

        listener.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
        while (!tasks.isEmpty()) {
            List<Runnable> due = new ArrayList<>(tasks);
            tasks.clear();
            due.forEach(Runnable::run);
        }

        assertThat(block.getType()).isEqualTo(Material.BARREL);
        assertThat(LootContainers.waitingFor(block)).isEqualTo("rich");
        assertThat(fired).containsExactly("rare");
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new java.util.concurrent.ConcurrentHashMap<>();
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}

            @Override
            public void setBreaks(IslandId islandId, long count) {}
        };
    }
}
