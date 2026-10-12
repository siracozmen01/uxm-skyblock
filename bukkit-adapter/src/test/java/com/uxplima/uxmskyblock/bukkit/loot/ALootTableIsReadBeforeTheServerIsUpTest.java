package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The configuration is read before the server is up and before the plugins whose items a table may name. An item
 * made then fails, and the game's own item classes are left broken for the rest of the process.
 */
@Isolated
class ALootTableIsReadBeforeTheServerIsUpTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("The shipped table reads with no server, and no item is made until a roll lands on it")
    void readWithNoServer() throws Exception {
        assertThat(MockBukkit.isMocked()).isFalse();
        Files.copy(Path.of("src/main/resources/loot/treasure.conf"), folder.resolve("treasure.conf"));

        LootTables tables = LootTables.read(folder);

        assertThat(tables.problems()).isEmpty();
        assertThat(tables.named("treasure").orElseThrow().entries())
                .filteredOn(LootEntry.Item.class::isInstance)
                .hasSize(6);
    }
}
