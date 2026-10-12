package com.uxplima.uxmskyblock.bukkit.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A place in a shared dimension world belongs to the island it lies over, by that island's home world. */
class ServerWorldsTest extends MockBukkitHarness {

    @Test
    @DisplayName("In a shared dimension world the home is the world of the island over the place, else nothing")
    void theHomeOfAPlace() {
        World nether = server.addSimpleWorld("skyblock_nether");
        World lobby = server.addSimpleWorld("lobby");
        ServerWorlds worlds = new ServerWorlds(
                () -> List.of("world", "boxed"),
                () -> "world",
                () -> Set.of("skyblock_nether", "skyblock_the_end"),
                () -> List.of("world", "boxed"),
                (world, x, z) -> world.equals("boxed") && x == 5120 && z == 0);

        assertThat(worlds.homeOf(new Location(nether, 5120, 64, 0))).contains("boxed");
        assertThat(worlds.homeOf(new Location(nether, 0, 64, 0)))
                .describedAs("away from every island")
                .isEmpty();
        assertThat(worlds.homeOf(new Location(lobby, 5120, 64, 0)))
                .describedAs("not a dimension world")
                .isEmpty();
        assertThat(worlds.presetWorlds()).containsExactly("world", "boxed");
        assertThat(worlds.islandWorld()).isEqualTo("world");
    }
}
