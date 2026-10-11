package com.uxplima.uxmskyblock.persistence.recycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint;

/** What every engine must do with the squares an island wrote in. */
final class IslandFootprintScenario {

    private IslandFootprintScenario() {}

    static void run(Database database) {
        SqlIslandFootprintAdapter footprints = new SqlIslandFootprintAdapter(database);
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());

        footprints.record(island, new IslandFootprint("skyblock", 100, 200, 0));
        footprints.record(island, new IslandFootprint("skyblock", 100, 200, 48));
        footprints.record(island, new IslandFootprint("skyblock", 100, 200, 30));
        footprints.record(island, new IslandFootprint("skyblock_nether", 100, 200, 63));
        footprints.record(other, new IslandFootprint("skyblock", 9000, 0, 12));

        assertThat(footprints.of(island))
                .describedAs("one square per world and centre, as wide as the widest written, never narrowed")
                .containsExactlyInAnyOrder(
                        new IslandFootprint("skyblock", 100, 200, 48),
                        new IslandFootprint("skyblock_nether", 100, 200, 63));

        footprints.forget(island);

        assertThat(footprints.of(island)).isEmpty();
        assertThat(footprints.of(other))
                .describedAs("another island's squares are kept")
                .containsExactly(new IslandFootprint("skyblock", 9000, 0, 12));
    }
}
