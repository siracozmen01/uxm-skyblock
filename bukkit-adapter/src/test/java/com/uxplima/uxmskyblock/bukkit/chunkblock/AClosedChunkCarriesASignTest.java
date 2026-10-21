package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.packet.display.FakeHologramPackets;
import com.uxplima.uxmlib.packet.display.FakeRegionScheduler;
import com.uxplima.uxmlib.packet.display.HologramProbe;
import com.uxplima.uxmlib.packet.display.PacketHologramClicks;
import com.uxplima.uxmlib.packet.display.PacketLine;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Each closed chunk a ChunkBlock island can open next carries a sign naming its grade, just inside the side it shares
 * with open ground; a member's click on it opens the chunk's window, and a visitor's is told it is not theirs.
 */
class AClosedChunkCarriesASignTest extends MockBukkitHarness {

    private static final ChunkTier RARE = new ChunkTier(
            "rare", "@chunkblock.tiers.rare", 1, 99, 1, List.of(new ChunkRequirement.Points(1)), List.of(), List.of());

    /** What stands in each chunk: the owner and where. */
    private final Map<ChunkPos, List<String>> standing = new HashMap<>();

    /** The words of every sign drawn, asked of a viewer the way a frame asks them. */
    private final List<Function<Player, Component>> texts = new ArrayList<>();

    /** What a click on each chunk's sign does. */
    private final Map<ChunkPos, Consumer<Player>> clicks = new HashMap<>();

    private final SignPainter painter = new SignPainter() {
        @Override
        public void draw(
                World world,
                String owner,
                ChunkPos chunk,
                ChunkSigns.Spot spot,
                Function<Player, Component> text,
                Consumer<Player> clicked) {
            standing.computeIfAbsent(chunk, at -> new ArrayList<>())
                    .add(owner + " " + spot.x() + "," + spot.y() + "," + spot.z());
            texts.add(text);
            clicks.put(chunk, clicked);
        }

        @Override
        public int clear(Chunk chunk, String owner) {
            List<String> here = standing.getOrDefault(new ChunkPos(chunk.getX(), chunk.getZ()), new ArrayList<>());
            int before = here.size();
            here.removeIf(sign -> sign.startsWith(owner + " "));
            return before - here.size();
        }

        @Override
        public int unloaded(Chunk chunk) {
            List<String> here = standing.remove(new ChunkPos(chunk.getX(), chunk.getZ()));
            return here == null ? 0 : here.size();
        }
    };

    private final ChunkBlockStores.Territories territories = new ChunkBlockStores.Territories();
    private final AtomicInteger locationReads = new AtomicInteger();
    private final List<ChunkPos> windows = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock owner;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock visitor;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private ChunkSigns signs;

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        owner = createPlayer("Owner");
        visitor = createPlayer("Visitor");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(owner.getUniqueId()),
                Instant.now());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island, "skyblock");
        service = new ChunkBlockService(territories, new ChunkUnlockRules(List.of(1L, 1L), 1));
        service.start(island.id(), 8, 8);
        signs = new ChunkSigns(
                service,
                new ChunkTiers(List.of(RARE)),
                islands,
                new InlineSchedulerPort(),
                painter,
                Messages.bundled(),
                id -> {
                    locationReads.incrementAndGet();
                    return Optional.of(new IslandLocation(id, "skyblock", island.bounds(), 8, 100, 8, 0, 0));
                },
                uuid -> Optional.of(new ProfileId(uuid)),
                (player, chunk) -> windows.add(chunk));
        service.whenOpened(signs::opened);
        service.whenClosed(signs::closed);
    }

    private List<String> signsIn(int chunkX, int chunkZ) {
        return standing.getOrDefault(new ChunkPos(chunkX, chunkZ), List.of());
    }

    private void load(int chunkX, int chunkZ) {
        world.getChunkAt(chunkX, chunkZ).load();
        signs.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(chunkX, chunkZ), false));
    }

    @Test
    @DisplayName(
            "A closed chunk beside the open one stands its sign just inside the side they share, at the spawn's height")
    void aClosedChunkStandsItsSign() {
        load(1, 0);
        load(1, 0);
        load(0, 0);

        String owner = island.id().value().toString();
        assertThat(signsIn(1, 0))
                .describedAs("one sign, however often it loads")
                .containsExactly(owner + " 17.0,100.0,8.0");
        assertThat(signsIn(0, 0)).describedAs("the open chunk carries none").isEmpty();
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(texts.getFirst().apply(this.owner)))
                .describedAs("asked of each reader, in their language")
                .isEqualTo("Rare chunk\nClick to see its price");
        assertThat(locationReads).describedAs("the island's place is read once").hasValue(1);
    }

    @Test
    @DisplayName("A chunk two steps away, or past the island's edge, carries no sign")
    void onlyTheNextChunksCarryOne() {
        load(2, 0);
        load(4, 0);
        assertThat(signsIn(2, 0)).isEmpty();
        assertThat(signsIn(4, 0)).isEmpty();
    }

    @Test
    @DisplayName("A closed chunk beside open ground but past the island's edge carries no sign")
    void theEdgeStopsTheSigns() {
        world.getChunkAt(4, 0).load();
        for (int x = 1; x <= 3; x++) {
            service.unlock(island.id(), new ChunkPos(x, 0), island.bounds(), 9);
        }
        load(4, 0);
        assertThat(service.isOpen(island.id(), new ChunkPos(3, 0))).contains(true);
        assertThat(signsIn(4, 0)).isEmpty();
    }

    @Test
    @DisplayName("Opening a chunk takes its sign down and stands one on each closed chunk beside it")
    void openingMovesTheSigns() {
        load(1, 0);
        load(2, 0);
        load(1, 1);

        service.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 9);

        assertThat(signsIn(1, 0)).isEmpty();
        assertThat(signsIn(2, 0)).hasSize(1).first().asString().endsWith(" 33.0,100.0,8.0");
        assertThat(signsIn(1, 1)).hasSize(1);

        service.onLevel(island.id(), 0);
        assertThat(signsIn(1, 0)).describedAs("closed again, it can open again").hasSize(1);
        assertThat(signsIn(2, 0)).isEmpty();
    }

    @Test
    @DisplayName("A sign stands on the side nearest the first chunk, then west, east, north and south")
    void theSignsSide() {
        ChunkTerritory one = new ChunkTerritory(new ChunkPos(0, 0), List.of());
        assertThat(ChunkSigns.spot(new ChunkPos(-1, 0), one, 64)).contains(new ChunkSigns.Spot(-1, 64, 8));
        assertThat(ChunkSigns.spot(new ChunkPos(0, 1), one, 64)).contains(new ChunkSigns.Spot(8, 64, 17));
        assertThat(ChunkSigns.spot(new ChunkPos(0, -1), one, 64)).contains(new ChunkSigns.Spot(8, 64, -1));
        assertThat(ChunkSigns.spot(new ChunkPos(5, 5), one, 64)).isEmpty();

        ChunkTerritory corner = new ChunkTerritory(new ChunkPos(0, 0), List.of(new ChunkPos(1, 0), new ChunkPos(0, 1)));
        assertThat(ChunkSigns.spot(new ChunkPos(1, 1), corner, 64))
                .describedAs("both sides are ring one: west comes first")
                .contains(new ChunkSigns.Spot(17, 64, 24));

        ChunkTerritory far = new ChunkTerritory(
                new ChunkPos(0, 0), List.of(new ChunkPos(1, 0), new ChunkPos(2, 0), new ChunkPos(2, 1)));
        assertThat(ChunkSigns.spot(new ChunkPos(1, 1), far, 64))
                .describedAs("the north side's chunk is nearer the first than the east side's")
                .contains(new ChunkSigns.Spot(24, 64, 17));
    }

    @Test
    @DisplayName("A web that guards another world stands no sign here, and leaves a click here to the web that does")
    void aWebGuardsItsOwnWorld() {
        signs.guarding("skyblock_nether"::equals);
        load(1, 0);
        assertThat(signsIn(1, 0)).isEmpty();

        signs.clicked(owner, island.id().value().toString(), new ChunkPos(1, 0), new Location(world, 17, 100, 8));
        assertThat(windows).describedAs("left to the web that guards it").isEmpty();
        assertThat(owner.nextComponentMessage()).isNull();

        signs.guarding("skyblock"::equals);
        load(1, 0);
        assertThat(signsIn(1, 0)).hasSize(1);
    }

    @Test
    @DisplayName(
            "A member's click opens the chunk's window, a visitor's is told no, and another island's opens nothing")
    void aClickOpensTheWindow() {
        load(1, 0);
        Consumer<Player> sign = java.util.Objects.requireNonNull(clicks.get(new ChunkPos(1, 0)));

        sign.accept(owner);
        assertThat(windows).containsExactly(new ChunkPos(1, 0));

        sign.accept(visitor);
        assertThat(windows).hasSize(1);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(visitor.nextComponentMessage())))
                .contains("Only this island's members");

        signs.clicked(owner, UUID.randomUUID().toString(), new ChunkPos(1, 0), new Location(world, 17, 100, 8));
        assertThat(windows)
                .describedAs("a sign of another island opens nothing")
                .hasSize(1);
    }

    @Test
    @DisplayName("A chunk loaded before its island's web was read stands its sign once the loaded chunks are drawn")
    void aChunkLoadedEarlierStandsItsSign() {
        world.getChunkAt(1, 0).load();
        assertThat(signsIn(1, 0)).isEmpty();

        signs.drawLoaded(List.of(world));

        assertThat(signsIn(1, 0)).hasSize(1);
    }

    @Test
    @DisplayName("A chunk that unloads takes its sign down, and stands it again when it loads")
    void anUnloadTakesTheSignDown() {
        load(1, 0);
        assertThat(signsIn(1, 0)).hasSize(1);

        signs.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(1, 0)));
        assertThat(signsIn(1, 0)).isEmpty();

        load(1, 0);
        assertThat(signsIn(1, 0)).hasSize(1);
    }

    @Test
    @DisplayName("A sign is sent to whoever is near, in their words, over a box a click lands on, and never spawned")
    void theSignIsSentNeverSpawned() {
        FakeHologramPackets packets = new FakeHologramPackets();
        FakeRegionScheduler frames = new FakeRegionScheduler();
        ChunkSignHolograms holograms = new ChunkSignHolograms(frames, packets, new PacketHologramClicks(frames));
        owner.teleport(new Location(world, 17, 100, 10));
        visitor.teleport(new Location(world, 17, 100, 6));

        holograms.draw(
                world,
                "island-1",
                new ChunkPos(1, 0),
                new ChunkSigns.Spot(17, 100, 8),
                viewer -> Component.text("for " + viewer.getName()),
                player -> {});
        frames.firstFrame();

        HologramProbe probe = new HologramProbe(packets);
        assertThat(probe.heldBy(owner)).containsExactly(PacketLine.text(Component.text("for Owner")));
        assertThat(probe.heldBy(visitor)).containsExactly(PacketLine.text(Component.text("for Visitor")));
        assertThat(packets.packetsFor(owner).toString())
                .describedAs("with a box a click lands on")
                .contains("InteractionSize");
        assertThat(world.getEntitiesByClass(TextDisplay.class)).isEmpty();
        assertThat(world.getEntitiesByClass(Interaction.class)).isEmpty();

        assertThat(holograms.clear(world.getChunkAt(1, 0), "island-2"))
                .describedAs("another island's sign is not this one's to take down")
                .isZero();
        assertThat(holograms.clear(world.getChunkAt(1, 0), "island-1")).isEqualTo(1);
        assertThat(probe.heldBy(owner)).isEmpty();

        holograms.draw(
                world,
                "island-1",
                new ChunkPos(1, 0),
                new ChunkSigns.Spot(17, 100, 8),
                viewer -> Component.text("x"),
                player -> {});
        assertThat(holograms.unloaded(world.getChunkAt(1, 0))).isEqualTo(1);
        assertThat(holograms.standing()).isZero();
    }
}
