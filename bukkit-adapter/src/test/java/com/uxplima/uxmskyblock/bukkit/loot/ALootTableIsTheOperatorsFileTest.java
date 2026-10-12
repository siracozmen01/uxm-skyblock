package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A loot table is a file the operator writes, one a table, and a file that cannot be read is left out with why. */
class ALootTableIsTheOperatorsFileTest extends MockBukkitHarness {

    @TempDir
    Path folder;

    @Test
    @DisplayName("A span is a number or two with a dash, and anything else says what it read")
    void spans() {
        assertThat(Span.parse("2-4")).isEqualTo(new Span(2, 4));
        assertThat(Span.parse(" 3 ")).isEqualTo(Span.of(3));
        assertThatThrownBy(() -> Span.parse("lots")).hasMessageContaining("lots");
        assertThatThrownBy(() -> Span.parse("5-2")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The shipped table reads as written: its rolls, its rarities and every kind of entry")
    void theShippedTable() throws Exception {
        Files.copy(Path.of("src/main/resources/loot/treasure.conf"), folder.resolve("treasure.conf"));

        LootTables tables = LootTables.read(folder);

        assertThat(tables.problems()).isEmpty();
        LootTable treasure = tables.named("treasure").orElseThrow();
        assertThat(treasure.rolls()).isEqualTo(new Span(3, 5));
        assertThat(treasure.rarities()).containsOnlyKeys("common", "rare", "legendary");
        assertThat(treasure.rarities())
                .extractingByKey("legendary")
                .extracting(Rarity::effects)
                .isEqualTo("loot-legendary");
        assertThat(treasure.entries()).hasSize(8);
        assertThat(treasure.entries())
                .filteredOn(LootEntry.Item.class::isInstance)
                .hasSize(6);
        assertThat(treasure.entries())
                .filteredOn(LootEntry.Vanilla.class::isInstance)
                .hasSize(1);
        assertThat(treasure.entries())
                .filteredOn(LootEntry.Actions.class::isInstance)
                .hasSize(1);
        LootEntry.Item bread = (LootEntry.Item) treasure.entries().get(0);
        assertThat(bread.item().getType()).isEqualTo(Material.BREAD);
        assertThat(bread.amount()).isEqualTo(new Span(4, 12));
        LootEntry.Item scrap = (LootEntry.Item) treasure.entries().get(6);
        assertThat(scrap.slot()).hasValue(13);
    }

    @Test
    @DisplayName("A table with no rarities has one, common, and an entry that names none is common")
    void oneRarityByDefault() throws Exception {
        write("plain.conf", "entries = [ { item { material = STONE } } ]");

        LootTable plain = LootTables.read(folder).named("PLAIN").orElseThrow();

        assertThat(plain.rarities()).containsOnlyKeys("common");
        assertThat(plain.rolls()).isEqualTo(Span.of(1));
        assertThat(plain.entries())
                .singleElement()
                .extracting(LootEntry::weight)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A file that cannot be read is left out with the reason, and the others are read all the same")
    void aBadFileIsLeftOut() throws Exception {
        write("good.conf", "entries = [ { item { material = STONE } } ]");
        write("both.conf", "entries = [ { item { material = STONE }, vanilla = \"minecraft:chests/igloo_chest\" } ]");
        write("stranger.conf", "entries = [ { rarity = mythic, item { material = STONE } } ]");
        write("nothing.conf", "entries = [ { item { material = NOT_A_THING } } ]");
        write("notes.txt", "not a table");

        LootTables tables = LootTables.read(folder);

        assertThat(tables.all()).containsOnlyKeys("good");
        assertThat(tables.problems())
                .hasSize(3)
                .anySatisfy(line -> assertThat(line).contains("both.conf").contains("exactly one"))
                .anySatisfy(line -> assertThat(line).contains("stranger.conf").contains("mythic"))
                .anySatisfy(line -> assertThat(line).contains("nothing.conf"));
        assertThat(LootTables.read(folder.resolve("absent")).all()).isEmpty();
    }

    private void write(String name, String hocon) throws Exception {
        Files.writeString(folder.resolve(name), hocon, StandardCharsets.UTF_8);
    }
}
