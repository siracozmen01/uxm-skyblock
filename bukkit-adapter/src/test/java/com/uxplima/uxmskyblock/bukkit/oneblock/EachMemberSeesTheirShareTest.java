package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributionPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributions;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Each break on a OneBlock island is counted for the player whose break it was, and the panel and the placeholders
 * tell a member their own share and who broke the most.
 */
class EachMemberSeesTheirShareTest extends MockBukkitHarness {

    @TempDir
    Path dataDir;

    private final List<Runnable> regionTasks = new ArrayList<>();

    @Test
    @DisplayName("Each member's breaks count for them, and the panel tells their own and the most")
    void share() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        PlayerMock ada = createPlayer("Ada");
        PlayerMock bo = createPlayer("Bo");
        PlayerMock cy = createPlayer("Cy");
        Island island = Island.create(
                        IslandId.of(UUID.randomUUID()),
                        IslandBounds.fromCenterAndRadius(0, 0, 50),
                        new PlayerUuid(ada.getUniqueId()),
                        new ProfileId(ada.getUniqueId()),
                        Instant.now())
                .addMember(new IslandMember(
                        new PlayerUuid(bo.getUniqueId()),
                        new ProfileId(bo.getUniqueId()),
                        IslandRole.MEMBER,
                        Instant.now()));
        OneBlockConfiguration config = OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("phases = [ { name = \"meadow\", length = 50, blocks { DIRT = 1 } } ]"));
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        service.start(island.id(), 0, 100, 0);
        OneBlockContributions contributions = new OneBlockContributions(OneBlockContributionPort.NONE);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        OneBlockListener listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of()),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.useContributions(contributions);

        breakIt(listener, world, ada);
        breakIt(listener, world, bo);
        breakIt(listener, world, bo);
        breakIt(listener, world, bo);

        assertThat(contributions.of(island.id(), new PlayerUuid(bo.getUniqueId())))
                .isEqualTo(3);
        assertThat(contributions.of(island.id(), new PlayerUuid(ada.getUniqueId())))
                .isEqualTo(1);

        OneBlockPanel panel = new OneBlockPanel(
                service, mock(IslandStoragePort.class), scheduler, Messages.bundled(), id -> Optional.empty());
        panel.useContributions(contributions);
        panel.useNames(uuid -> uuid.equals(bo.getUniqueId()) ? "Bo" : "someone");
        Map<String, String> forAda = panel.values(ada, island.id(), service.phases(), 4);
        assertThat(forAda)
                .containsEntry("my_breaks", "1")
                .containsEntry("top_member", "Bo")
                .containsEntry("top_member_breaks", "3");
        assertThat(panel.values(cy, island.id(), service.phases(), 4))
                .describedAs("a viewer who broke none")
                .containsEntry("my_breaks", "0");
        assertThat(panel.values(ada, null, service.phases(), 4))
                .describedAs("no island, no shares")
                .containsEntry("my_breaks", "0")
                .containsEntry("top_member", "");
        assertThat(panel.placeholder(bo, island.id().value(), "my_breaks")).isEqualTo("3");
        assertThat(panel.placeholder(ada, island.id().value(), "top_member")).isEqualTo("Bo");

        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-oneblock.conf");
        panel.useMenuEngine(engine);
        String tile = ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                java.util.Objects.requireNonNull(
                        ShippedTemplates.spec("island-oneblock.conf").items().get("broken")),
                MenuContext.of(ada, null, 0, forAda));
        assertThat(tile).contains("By you", "Most", "Bo").doesNotContain("<argument_");
    }

    @Test
    @DisplayName("Before anyone broke a block the panel names nobody as the most")
    void nobodyYet() throws Exception {
        PlayerMock ada = createPlayer("Ada");
        OneBlockConfiguration config = OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("phases = [ { name = \"meadow\", length = 50, blocks { DIRT = 1 } } ]"));
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        OneBlockPanel panel = new OneBlockPanel(
                service,
                mock(IslandStoragePort.class),
                mock(SchedulerPort.class),
                Messages.bundled(),
                id -> Optional.empty());
        panel.useContributions(new OneBlockContributions(OneBlockContributionPort.NONE));

        assertThat(panel.values(ada, IslandId.of(UUID.randomUUID()), service.phases(), 0))
                .containsEntry("my_breaks", "0")
                .containsEntry("top_member", "")
                .containsEntry("top_member_breaks", "");
    }

    private void breakIt(OneBlockListener listener, World world, PlayerMock player) {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new HashMap<>();
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
