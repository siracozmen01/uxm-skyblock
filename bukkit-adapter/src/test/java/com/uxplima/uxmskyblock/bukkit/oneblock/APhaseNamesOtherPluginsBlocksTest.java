package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import com.nexomc.nexo.api.NexoBlocks;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.outside.OutsideContent;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import dev.lone.itemsadder.api.CustomBlock;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A OneBlock phase may name ItemsAdder, Nexo and CraftEngine blocks and MythicMobs creatures, with the plugin's word
 * in front. Each is read when the plugin is on the server and laid through it, and left out with one warning when it
 * is not; a block the plugin cannot lay is dirt.
 */
class APhaseNamesOtherPluginsBlocksTest extends MockBukkitHarness {

    @BeforeEach
    void forget() {
        CustomBlock.PLACED.clear();
        NexoBlocks.PLACED.clear();
        CraftEngineBlocks.PLACED.clear();
    }

    private static OneBlockConfiguration.Loaded load(String blocks, String creatures, String moments) throws Exception {
        return OneBlockConfiguration.loadReporting(
                HoconConfigurationLoader.builder().buildAndLoadString("""
                                phases = [ {
                                    name = "plains"
                                    length = 10
                                    blocks { DIRT = 1, %s }
                                    creature-chance = 0.5
                                    creatures { cow = 1, %s }
                                    moments { %s }
                                } ]
                                """.formatted(blocks, creatures, moments)), null);
    }

    @Test
    @DisplayName("A plugin on the server has its names read; one that is not has each of them left out, said")
    void read() throws Exception {
        MockBukkit.createMockPlugin("ItemsAdder");
        MockBukkit.createMockPlugin("MythicMobs");

        OneBlockConfiguration.Loaded loaded = load(
                "\"itemsadder:myitems:ruby_ore\" = 2, \"nexo:amber\" = 1",
                "\"mythicmobs:SkeletonKing\" = 1",
                "3 { block = \"itemsadder:myitems:ruby_ore\", creature = \"mythicmobs:SkeletonKing\" }, "
                        + "4 { block = \"nexo:amber\" }");

        var pool = loaded.config().phases().phases().getFirst();
        assertThat(pool.blockPool().weights())
                .containsEntry("itemsadder:myitems:ruby_ore", 2.0)
                .doesNotContainKey("nexo:amber");
        assertThat(pool.creaturePool().weights()).containsKey("mythicmobs:SkeletonKing");
        assertThat(loaded.config().momentAt("plains", 3)).get().satisfies(moment -> {
            assertThat(moment.block()).isEqualTo("itemsadder:myitems:ruby_ore");
            assertThat(moment.creature()).isEqualTo("mythicmobs:SkeletonKing");
        });
        assertThat(loaded.config().momentAt("plains", 4)).isEmpty();
        assertThat(loaded.problems())
                .anySatisfy(line -> assertThat(line).contains("'nexo:amber'", "Nexo is not on this server"))
                .anySatisfy(line -> assertThat(line).contains("moment 4"))
                .hasSize(2);
    }

    @Test
    @DisplayName("A drawn block is laid through its plugin, and one it cannot lay, or of a plugin gone, is dirt")
    void laid() {
        MockBukkit.createMockPlugin("ItemsAdder");
        MockBukkit.createMockPlugin("Nexo");
        MockBukkit.createMockPlugin("CraftEngine");
        World world = server.addSimpleWorld("skyblock");
        OneBlockDraws draws = OneBlockDraws.PLAIN;
        world.getBlockAt(0, 100, 0).setType(Material.STONE);

        draws.lay(world, 0, 100, 0, "itemsadder:myitems:ruby_ore");
        draws.lay(world, 1, 100, 0, "nexo:amber");
        draws.lay(world, 2, 100, 0, "craftengine:default:topaz_ore");

        assertThat(CustomBlock.PLACED).containsExactly("myitems:ruby_ore 0,100,0");
        assertThat(world.getBlockAt(0, 100, 0).getType())
                .describedAs("the plugin's own block stands, not one of ours")
                .isEqualTo(Material.STONE);
        assertThat(NexoBlocks.PLACED).containsExactly("amber 1,100,0");
        assertThat(CraftEngineBlocks.PLACED).containsExactly("default:topaz_ore 2 true false");
        assertThat(draws.named("itemsadder:myitems:ruby_ore")).isEqualTo("myitems:ruby_ore");

        draws.lay(world, 3, 100, 0, "itemsadder:myitems:nothing");
        draws.lay(world, 5, 100, 0, "craftengine:default:nothing");
        assertThat(world.getBlockAt(5, 100, 0).getType()).isEqualTo(Material.DIRT);
        draws.lay(world, 4, 100, 0, "mythicmobs:SkeletonKing");
        assertThat(world.getBlockAt(3, 100, 0).getType()).isEqualTo(Material.DIRT);
        assertThat(world.getBlockAt(4, 100, 0).getType())
                .describedAs("a creature's name is no block")
                .isEqualTo(Material.DIRT);
    }

    @Test
    @DisplayName("A drawn creature comes through its plugin, and nothing comes for one it does not know")
    void brought() {
        MockBukkit.createMockPlugin("MythicMobs");
        World world = server.addSimpleWorld("skyblock");
        Location at = new Location(world, 0.5, 101, 0.5);

        Entity king = OneBlockDraws.PLAIN.spawn(at, "mythicmobs:SkeletonKing", null);

        assertThat(king).isNotNull().extracting(Entity::getType).isEqualTo(EntityType.ZOMBIE);
        assertThat(OneBlockDraws.PLAIN.spawn(at, "mythicmobs:Nobody", null)).isNull();
        assertThat(OneBlockDraws.PLAIN.plainCreatureName("mythicmobs:SkeletonKing"))
                .isEqualTo("skeletonking");
    }

    @Test
    @DisplayName("A plugin that is not on the server lays nothing, and a name of the server's own is no plugin's")
    void absent() {
        World world = server.addSimpleWorld("skyblock");

        OneBlockDraws.PLAIN.lay(world, 0, 100, 0, "nexo:amber");

        assertThat(NexoBlocks.PLACED).isEmpty();
        assertThat(world.getBlockAt(0, 100, 0).getType()).isEqualTo(Material.DIRT);
        assertThat(OneBlockDraws.PLAIN.spawn(new Location(world, 0, 101, 0), "mythicmobs:SkeletonKing", null))
                .isNull();
        assertThat(OutsideContent.named("minecraft:stone", OutsideContent.Kind.BLOCK))
                .isEmpty();
        assertThat(OutsideContent.named("mythicmobs:SkeletonKing", OutsideContent.Kind.BLOCK))
                .isEmpty();
        assertThat(OutsideContent.named("nexo:", OutsideContent.Kind.BLOCK)).isEmpty();
    }
}
