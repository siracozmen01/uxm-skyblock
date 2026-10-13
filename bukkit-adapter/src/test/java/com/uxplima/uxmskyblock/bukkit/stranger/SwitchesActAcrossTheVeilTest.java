package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
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
 * A lever or a button used on a StrangerRealms island passes its power to the one at the same place across the veil,
 * and a door, trapdoor or gate opened or shut opens or shuts the one there; nothing else changes, and nowhere else.
 */
class SwitchesActAcrossTheVeilTest extends MockBukkitHarness {

    private static final String UPSIDE_DOWN = "skyblock_nether";
    private static final int Y = 70;

    private final Set<IslandId> stranger = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World land;

    @SuppressWarnings("NullAway.Init")
    private World upsideDown;

    @SuppressWarnings("NullAway.Init")
    private World elsewhere;

    @SuppressWarnings("NullAway.Init")
    private LinkedSwitches switches;

    @SuppressWarnings("NullAway.Init")
    private Realms realms;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpRealms() {
        land = server.addSimpleWorld("realms");
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        elsewhere = server.addSimpleWorld("elsewhere");
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        Island strange = island(0);
        islands.cacheIsland(strange, "realms");
        islands.cacheIsland(island(1000), "realms");
        // A StrangerRealms island in a world the operator does not make islands in: nothing glimmers there.
        Island stray = island(0);
        islands.cacheIsland(stray, "elsewhere");
        stranger.add(strange.id());
        stranger.add(stray.id());
        StrangerRealmsService service = new StrangerRealmsService(new StrangerRealmsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stranger);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stranger.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stranger.add(islandId);
            }

            @Override
            public int farthestReach() {
                return 0;
            }
        });
        service.prime();
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        realms = new Realms(service, islands, () -> UPSIDE_DOWN, () -> List.of("realms"));
        switches = new LinkedSwitches(
                realms,
                scheduler,
                new StrangerRealmsConfiguration.Switches(
                        true, List.of("LEVER", "*_BUTTON", "*_DOOR", "OAK_FENCE_GATE")));
        player = createPlayer("Switcher");
    }

    @Test
    @DisplayName("A lever on the land powers the lever below, and turned off turns it off")
    void aLeverActsBelow() {
        Block lever = put(land, 8, Material.LEVER);
        Block below = put(upsideDown, 8, Material.LEVER);

        power(lever, true);
        assertThat(powered(below)).isTrue();
        power(lever, false);
        assertThat(powered(below)).isFalse();
    }

    @Test
    @DisplayName("A button in the Upside Down presses the button above and lets it go")
    void aButtonActsAbove() {
        Block button = put(upsideDown, 8, Material.STONE_BUTTON);
        Block above = put(land, 8, Material.STONE_BUTTON);

        power(button, true);
        assertThat(powered(above)).isTrue();
        power(button, false);
        assertThat(powered(above)).isFalse();
    }

    @Test
    @DisplayName("A door opened on the land opens the door below, once the use has opened it, and shut shuts it")
    void aDoorActsBelow() {
        Block door = put(land, 8, Material.OAK_DOOR);
        Block below = put(upsideDown, 8, Material.OAK_DOOR);

        use(door, true, Action.RIGHT_CLICK_BLOCK, Event.Result.DEFAULT);
        assertThat(open(below)).isTrue();
        use(door, false, Action.RIGHT_CLICK_BLOCK, Event.Result.DEFAULT);
        assertThat(open(below)).isFalse();

        Block gate = put(upsideDown, 20, Material.OAK_FENCE_GATE);
        Block gateAbove = put(land, 20, Material.OAK_FENCE_GATE);
        use(gate, true, Action.RIGHT_CLICK_BLOCK, Event.Result.DEFAULT);
        assertThat(open(gateAbove)).isTrue();
    }

    @Test
    @DisplayName("Only a block of the same kind changes, only a switch the operator links, and only a use that took")
    void onlyWhatIsLinked() {
        Block door = put(land, 8, Material.OAK_DOOR);
        Block stone = put(upsideDown, 8, Material.SPRUCE_DOOR);
        use(door, true, Action.RIGHT_CLICK_BLOCK, Event.Result.DEFAULT);
        assertThat(open(stone)).describedAs("another kind of door").isFalse();

        Block trapdoor = put(land, 12, Material.OAK_TRAPDOOR);
        Block trapdoorBelow = put(upsideDown, 12, Material.OAK_TRAPDOOR);
        use(trapdoor, true, Action.RIGHT_CLICK_BLOCK, Event.Result.DEFAULT);
        assertThat(open(trapdoorBelow)).describedAs("not linked").isFalse();

        Block gate = put(land, 16, Material.OAK_FENCE_GATE);
        Block gateBelow = put(upsideDown, 16, Material.OAK_FENCE_GATE);
        use(gate, true, Action.LEFT_CLICK_BLOCK, Event.Result.DEFAULT);
        assertThat(open(gateBelow)).describedAs("a hit").isFalse();
        use(gate, true, Action.RIGHT_CLICK_BLOCK, Event.Result.DENY);
        assertThat(open(gateBelow)).describedAs("a use refused").isFalse();

        Block ironDoor = put(land, 24, Material.IRON_DOOR);
        Block ironBelow = put(upsideDown, 24, Material.IRON_DOOR);
        power(ironDoor, true);
        assertThat(powered(ironBelow))
                .describedAs("a door's power is no switch's")
                .isFalse();

        Block note = put(land, 28, Material.NOTE_BLOCK);
        Block noteBelow = put(upsideDown, 28, Material.NOTE_BLOCK);
        power(note, true);
        assertThat(powered(noteBelow)).describedAs("not linked").isFalse();
    }

    @Test
    @DisplayName("Another mode's island, a world islands are not made in and no island link nothing")
    void elsewhereNothingActs() {
        Block lever = put(land, 1008, Material.LEVER);
        Block below = put(upsideDown, 1008, Material.LEVER);
        power(lever, true);
        assertThat(powered(below)).isFalse();

        Block stray = put(elsewhere, 8, Material.LEVER);
        Block strayBelow = put(upsideDown, 8, Material.LEVER);
        power(stray, true);
        assertThat(powered(strayBelow)).isFalse();

        Block far = put(land, 50_000, Material.LEVER);
        Block farBelow = put(upsideDown, 50_000, Material.LEVER);
        power(far, true);
        assertThat(powered(farBelow)).isFalse();
    }

    @Test
    @DisplayName("The switches are read from the file, linked as shipped")
    void theSwitchesAreRead() throws Exception {
        assertThat(StrangerRealmsConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("switches { enabled = false, blocks = [\"LEVER\"] }"))
                        .switches())
                .isEqualTo(new StrangerRealmsConfiguration.Switches(false, List.of("LEVER")));
        assertThat(StrangerRealmsConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("enabled = true"))
                        .switches())
                .isEqualTo(StrangerRealmsConfiguration.Switches.SHIPPED);
        assertThat(StrangerRealmsConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString(new String(
                                        SwitchesActAcrossTheVeilTest.class
                                                .getResourceAsStream("/modules/strangerrealms.conf")
                                                .readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .switches())
                .isEqualTo(StrangerRealmsConfiguration.Switches.SHIPPED);
    }

    private Block put(World world, int x, Material type) {
        Block block = world.getBlockAt(x, Y, 8);
        block.setType(type);
        return block;
    }

    /** Sets the switch's power as the game would, then tells the listener the game changed it. */
    private void power(Block block, boolean on) {
        if (block.getBlockData() instanceof Powerable data) {
            data.setPowered(on);
            block.setBlockData(data);
        }
        switches.onPower(new BlockRedstoneEvent(block, on ? 0 : 15, on ? 15 : 0));
    }

    /** A use of the block, which has opened or shut it as the game would. */
    private void use(Block block, boolean opens, Action action, Event.Result result) {
        if (block.getBlockData() instanceof Openable data) {
            data.setOpen(opens);
            block.setBlockData(data);
        }
        PlayerInteractEvent event = new PlayerInteractEvent(player, action, null, block, BlockFace.UP);
        event.setUseInteractedBlock(result);
        switches.onUse(event);
    }

    private static boolean powered(Block block) {
        return block.getBlockData() instanceof Powerable data && data.isPowered();
    }

    private static boolean open(Block block) {
        return block.getBlockData() instanceof Openable data && data.isOpen();
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
