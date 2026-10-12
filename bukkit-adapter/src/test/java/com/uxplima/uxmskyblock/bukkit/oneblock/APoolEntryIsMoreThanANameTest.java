package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockEntries;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A pool entry may be more than a name: a block with its states, a spawner of a chosen creature, and a creature with
 * a name, a size and what it wears. What the server does not know is left out, and the rest of the phase stays.
 */
class APoolEntryIsMoreThanANameTest extends MockBukkitHarness {

    private static OneBlockConfiguration read(String phase) throws Exception {
        return OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("phases = [ { name = \"meadow\", length = 10, " + phase + " } ]"));
    }

    @Test
    @DisplayName(
            "States, a spawner and a dressed creature are read, each under its phase, and what cannot be is left out")
    void read() throws Exception {
        OneBlockConfiguration config = read("""
                blocks {
                    DIRT = 4
                    "oak_stairs[facing=north]" = 2
                    "oak_stairs[facing=sideways]" = 1
                    keeper { weight = 3, block = "SPAWNER", spawns = "zombie" }
                    ghost { block = "SPAWNER", spawns = "no_such_creature" }
                }
                creature-chance = 0.5
                creatures {
                    cow = 1
                    boss { weight = 2, type = "zombie", name = "<red>Keeper", scale = 1.5,
                           equipment { head = "IRON_HELMET", hand = "IRON_SWORD", tail = "STICK", feet = "NOT_AN_ITEM" } }
                    giant { type = "zombie", scale = 100 }
                    nothing { type = "no_such_creature" }
                }
                """);

        Map<String, Double> blocks =
                config.phases().phases().getFirst().blockPool().weights();
        assertThat(blocks)
                .containsOnlyKeys(
                        "DIRT",
                        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                        "meadow/keeper");
        assertThat(blocks.get("meadow/keeper")).isEqualTo(3);
        assertThat(config.entries().blocks().get("meadow/keeper"))
                .isEqualTo(new OneBlockEntries.Block("SPAWNER", "ZOMBIE"));
        Map<String, Double> creatures =
                config.phases().phases().getFirst().creaturePool().weights();
        assertThat(creatures).containsOnlyKeys("COW", "meadow/boss");
        assertThat(config.entries().creatures().get("meadow/boss"))
                .isEqualTo(new OneBlockEntries.Creature(
                        "ZOMBIE", "<red>Keeper", 1.5, Map.of("head", "IRON_HELMET", "hand", "IRON_SWORD")));
    }

    @Test
    @DisplayName("A block is laid with its states, and a spawner spawns the creature its entry names")
    void laid() throws Exception {
        OneBlockConfiguration config = read("""
                blocks {
                    "oak_stairs[facing=north]" = 1
                    keeper { block = "SPAWNER", spawns = "zombie" }
                }
                """);
        OneBlockDraws draws = new OneBlockDraws(config::entries, Messages.bundled());
        World world = server.addSimpleWorld("oneblock");
        String stairs = config.phases().phases().getFirst().blockPool().weights().keySet().stream()
                .filter(name -> name.contains("stairs"))
                .findFirst()
                .orElseThrow();

        draws.lay(world, 0, 64, 0, stairs);
        draws.lay(world, 1, 64, 0, "meadow/keeper");
        draws.lay(world, 2, 64, 0, "GOLD_BLOCK");

        assertThat(world.getBlockAt(0, 64, 0).getBlockData())
                .isInstanceOfSatisfying(
                        Stairs.class, read -> assertThat(read.getFacing()).isEqualTo(BlockFace.NORTH));
        assertThat(world.getBlockAt(1, 64, 0).getState())
                .isInstanceOfSatisfying(
                        CreatureSpawner.class,
                        spawner -> assertThat(spawner.getSpawnedType()).isEqualTo(EntityType.ZOMBIE));
        assertThat(world.getBlockAt(2, 64, 0).getType()).isEqualTo(Material.GOLD_BLOCK);
        assertThat(draws.named("meadow/keeper")).isEqualTo("spawner");
        assertThat(draws.named(stairs)).isEqualTo("oak_stairs");
    }

    @Test
    @DisplayName("A creature comes named, sized and dressed, and what it wears never drops")
    void dressed() throws Exception {
        OneBlockConfiguration config = read("""
                blocks { DIRT = 1 }
                creatures {
                    boss { type = "zombie", name = "<red>Keeper", scale = 1.5, equipment { head = "IRON_HELMET", hand = "IRON_SWORD" } }
                }
                """);
        OneBlockDraws draws = new OneBlockDraws(config::entries, Messages.bundled());
        World world = server.addSimpleWorld("oneblock");

        Entity spawned = draws.spawn(new Location(world, 0.5, 65, 0.5), "meadow/boss", null);
        Entity plain = draws.spawn(new Location(world, 0.5, 65, 0.5), "COW", null);

        assertThat(spawned).isInstanceOfSatisfying(LivingEntity.class, living -> {
            assertThat(living.getType()).isEqualTo(EntityType.ZOMBIE);
            assertThat(PlainTextComponentSerializer.plainText()
                            .serialize(java.util.Objects.requireNonNull(living.customName())))
                    .isEqualTo("Keeper");
            assertThat(living.isCustomNameVisible()).isTrue();
            assertThat(java.util.Objects.requireNonNull(living.getEquipment())
                            .getHelmet()
                            .getType())
                    .isEqualTo(Material.IRON_HELMET);
            assertThat(living.getEquipment().getItemInMainHand().getType()).isEqualTo(Material.IRON_SWORD);
            assertThat(living.getEquipment().getDropChance(EquipmentSlot.HEAD)).isZero();
        });
        assertThat(plain).isNotNull();
        assertThat(java.util.Objects.requireNonNull(plain).getType()).isEqualTo(EntityType.COW);
        assertThat(plain.customName()).isNull();
    }

    @Test
    @DisplayName("A creature written larger is set to its scale, and one written at its usual size is left as it is")
    void sized() throws Exception {
        OneBlockConfiguration config = read("""
                blocks { DIRT = 1 }
                creatures {
                    big { type = "zombie", scale = 1.5 }
                    usual { type = "zombie" }
                }
                """);
        OneBlockDraws draws = new OneBlockDraws(config::entries, Messages.bundled());
        World world = org.mockito.Mockito.mock(World.class);
        LivingEntity zombie = org.mockito.Mockito.mock(LivingEntity.class);
        org.bukkit.attribute.AttributeInstance scale =
                org.mockito.Mockito.mock(org.bukkit.attribute.AttributeInstance.class);
        org.mockito.Mockito.when(world.spawnEntity(
                        org.mockito.ArgumentMatchers.any(Location.class),
                        org.mockito.ArgumentMatchers.eq(EntityType.ZOMBIE)))
                .thenReturn(zombie);
        org.mockito.Mockito.when(zombie.getAttribute(org.bukkit.attribute.Attribute.SCALE))
                .thenReturn(scale);

        draws.spawn(new Location(world, 0, 0, 0), "meadow/big", null);
        draws.spawn(new Location(world, 0, 0, 0), "meadow/usual", null);

        org.mockito.Mockito.verify(scale).setBaseValue(1.5);
        org.mockito.Mockito.verify(scale, org.mockito.Mockito.times(1))
                .setBaseValue(org.mockito.ArgumentMatchers.anyDouble());
    }
}
