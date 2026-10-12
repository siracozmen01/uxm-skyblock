package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.CarriedParts;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;

/**
 * A player crossing between two groups of worlds and back, on one engine: what they put down is kept for the
 * group they left, what they kept in the other is written as their inventory, and a write taken before the
 * crossing never lands over it.
 */
@SuppressWarnings("NullAway") // a record built with no effects and no logout place, as ProfileInventoryRecord allows
final class CarriedStateCrossingScene {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

    private CarriedStateCrossingScene() {}

    static void crossesOn(Database database) {
        try (database) {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            PlayerSessionAuthorityAdapter sessions = new PlayerSessionAuthorityAdapter(database);
            PlayerProfileInventoryAdapter inventories = new PlayerProfileInventoryAdapter(database);
            SqlCarriedStateAdapter carried = new SqlCarriedStateAdapter(database);
            PlayerUuid ada = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            SessionAuthorityOutcome taken = sessions.ensureSession(ada, profile, NODE);
            assertThat(taken.isSuccess()).isTrue();
            long epoch = ((SessionAuthorityOutcome.Success) taken).epoch();

            assertThat(carried.carriedGroup(profile)).isEmpty();
            assertThat(carried.carryFirst(profile, "main")).isEqualTo("main");
            assertThat(carried.carryFirst(profile, "boxed"))
                    .describedAs("the first group stays the one carried for")
                    .isEqualTo("main");

            ProfileInventoryRecord diamonds = write(inventories, ada, profile, epoch, "diamond x64", 30);
            CarriedStatePort.Crossed intoBoxed = carried.cross(
                    crossing(ada, profile, epoch, "main", "boxed", diamonds, List.of("story/mine_diamond|diamond")));

            assertThat(intoBoxed).isInstanceOf(CarriedStatePort.Crossed.Done.class);
            CarriedStatePort.Crossed.Done done = (CarriedStatePort.Crossed.Done) intoBoxed;
            assertThat(done.carried().inventoryNbt())
                    .describedAs("nothing walks into Boxed")
                    .isEmpty();
            assertThat(done.carried().experiencePoints()).isZero();
            assertThat(done.carried().version()).isEqualTo(diamonds.version() + 1);
            assertThat(done.advancements()).isEmpty();
            assertThat(carried.carriedGroup(profile)).hasValue("boxed");
            ProfileInventoryRecord stored = inventories.loadInventory(profile).orElseThrow();
            assertThat(stored.version()).isEqualTo(diamonds.version() + 1);
            assertThat(stored.inventoryNbt()).isEmpty();

            assertThat(inventories
                            .checkpointInventory(ada, profile, NODE, epoch, diamonds.version(), diamonds)
                            .isSuccess())
                    .describedAs("a write of the diamonds taken before the crossing never lands after it")
                    .isFalse();

            ProfileInventoryRecord logs = write(inventories, ada, profile, epoch, "oak_log x3", 7);
            assertThat(carried.cross(crossing(ada, profile, epoch, "boxed", "main", diamonds, List.of())))
                    .describedAs("a crossing from an old version is refused")
                    .isInstanceOf(CarriedStatePort.Crossed.Refused.class);
            assertThat(carried.cross(crossing(ada, profile, epoch, "main", "boxed", logs, List.of())))
                    .describedAs("so is one from a group the profile does not carry for")
                    .isInstanceOf(CarriedStatePort.Crossed.Refused.class);
            assertThat(carried.cross(crossing(ada, profile, epoch + 1, "boxed", "main", logs, List.of())))
                    .describedAs("and one from a session this node does not hold")
                    .isInstanceOf(CarriedStatePort.Crossed.Refused.class);
            assertThat(carried.carriedGroup(profile)).hasValue("boxed");
            assertThat(text(inventories.loadInventory(profile).orElseThrow())).isEqualTo("oak_log x3");

            CarriedStatePort.Crossed back =
                    carried.cross(crossing(ada, profile, epoch, "boxed", "main", logs, List.of("story/root|crafting")));

            CarriedStatePort.Crossed.Done home = (CarriedStatePort.Crossed.Done) back;
            assertThat(text(home.carried())).isEqualTo("diamond x64");
            assertThat(home.carried().experiencePoints()).isEqualTo(30);
            assertThat(home.advancements()).containsExactly("story/mine_diamond|diamond");
            assertThat(text(inventories.loadInventory(profile).orElseThrow())).isEqualTo("diamond x64");
            assertThat(carried.carriedGroup(profile)).hasValue("main");
            assertThat(keptGroups(database, profile))
                    .describedAs("what is carried is the inventory, and only the group left is kept beside it")
                    .containsExactly("boxed");

            CarriedStatePort.Crossed.Done again =
                    (CarriedStatePort.Crossed.Done) carried.cross(new CarriedStatePort.Crossing(
                            ada,
                            profile,
                            NODE,
                            epoch,
                            "main",
                            "boxed",
                            home.carried(),
                            List.of(),
                            new CarriedParts(false, true, true, true, true, true, true)));
            assertThat(text(again.carried()))
                    .describedAs("a part no group keeps apart is carried across")
                    .isEqualTo("diamond x64");
            assertThat(again.carried().experiencePoints())
                    .describedAs("and the logs' experience is Boxed's own")
                    .isEqualTo(7);
            assertThat(again.advancements()).containsExactly("story/root|crafting");
        }
    }

    private static CarriedStatePort.Crossing crossing(
            PlayerUuid player,
            ProfileId profile,
            long epoch,
            String from,
            String to,
            ProfileInventoryRecord leaving,
            List<String> advancements) {
        return new CarriedStatePort.Crossing(
                player, profile, NODE, epoch, from, to, leaving, advancements, CarriedParts.ALL);
    }

    private static ProfileInventoryRecord write(
            PlayerProfileInventoryAdapter inventories,
            PlayerUuid player,
            ProfileId profile,
            long epoch,
            String contents,
            int experience) {
        long version = inventories.loadInventory(profile).orElseThrow().version();
        ProfileInventoryRecord state = new ProfileInventoryRecord(
                profile,
                version,
                contents.getBytes(StandardCharsets.UTF_8),
                new byte[0],
                experience,
                12.0,
                9,
                1.0f,
                null,
                null,
                null,
                null,
                null,
                "SURVIVAL",
                false);
        assertThat(inventories
                        .checkpointInventory(player, profile, NODE, epoch, version, state)
                        .isSuccess())
                .isTrue();
        return inventories.loadInventory(profile).orElseThrow();
    }

    private static List<String> keptGroups(Database database, ProfileId profile) {
        try (java.sql.Connection conn = database.connection();
                java.sql.PreparedStatement ps =
                        conn.prepareStatement("SELECT state_group FROM carried_states WHERE profile_id = ?")) {
            ps.setString(1, profile.value().toString());
            List<String> groups = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(rs.getString(1));
                }
            }
            return groups;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(ProfileInventoryRecord record) {
        return new String(record.inventoryNbt(), StandardCharsets.UTF_8);
    }
}
