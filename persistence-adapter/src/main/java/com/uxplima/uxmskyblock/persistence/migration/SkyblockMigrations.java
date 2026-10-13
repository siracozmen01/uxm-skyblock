package com.uxplima.uxmskyblock.persistence.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Production schema migration registry for Skyblock persistence.
 *
 * <p>Maintains the canonical, ordered list of production migrations across supported
 * V1 SQL targets: SQLite, MariaDB (represented by upstream {@link Dialect#MYSQL}),
 * and PostgreSQL ({@link Dialect#POSTGRES}).
 *
 * <p>Execution, state tracking, and transactions are delegated directly to
 * {@link com.uxplima.uxmlib.storage.migration.MigrationRunner} from {@code uxmlib-storage}.
 *
 * <p>Version numbers are monotonically increasing positive integers (1 to 28).
 * DDL definitions are partitioned across category registries:
 * <ul>
 *   <li>{@link CoreSchemaMigrations}: V1 to V12 (accounts, profiles, sessions, inventories, islands, banks, upgrades, backups, outbox, grid, sagas)</li>
 *   <li>{@link GameplaySchemaMigrations}: V13 to V20 (seasons, social, alliances, temporary access, rewards, warps/bans, vaults, missions)</li>
 *   <li>{@link OperationsSchemaMigrations}: V21 to V28 (spiral pool, anti-abuse, boosters, bankruptcies, game modes, activity/homes, dimensions, cosmetics/recycle)</li>
 * </ul>
 */
public final class SkyblockMigrations {

    /** The latest production schema version. */
    public static final int LATEST_VERSION = 67;

    /** Human-readable description of migration V28. */
    public static final String V28_DESCRIPTION = "create profile cosmetics and island recycle operations";

    /** Human-readable description of migration V29. */
    public static final String V29_DESCRIPTION = "record an inventory purge a reset still owes";

    /** Human-readable description of migration V30. */
    public static final String V30_DESCRIPTION = "record the last upkeep period an island was charged for";

    /** Human-readable description of migration V31. */
    public static final String V31_DESCRIPTION = "let an account's active profile be checked at commit";

    /** Human-readable description of migration V34. */
    public static final String V34_DESCRIPTION = "record what a lifecycle event still owes a player who was away";

    /** Human-readable description of migration V35. */
    public static final String V35_DESCRIPTION = "record which nodes are alive, the world each serves and its load";

    /** Human-readable description of migration V36. */
    public static final String V36_DESCRIPTION = "record the chunks each ChunkBlock island has open, in order";

    /** Human-readable description of migration V37. */
    public static final String V37_DESCRIPTION = "record which islands are AcidIsland islands and their sea level";

    /** Human-readable description of migration V38. */
    public static final String V38_DESCRIPTION =
            "lease the single writer of a game mode instance and of a root a mode owns";

    /** Human-readable description of migration V40. */
    public static final String V40_DESCRIPTION = "record which islands are Poseidon islands";

    /** Human-readable description of migration V41. */
    public static final String V41_DESCRIPTION = "record which islands are StrangerRealms islands";

    /** Human-readable description of migration V42. */
    public static final String V42_DESCRIPTION = "record the Parkour courses and each runner's best time";

    /** Human-readable description of migration V43. */
    public static final String V43_DESCRIPTION = "record which islands are Brix plots";

    /** Human-readable description of migration V44. */
    public static final String V44_DESCRIPTION =
            "record the TradeWinds vessels, their cargo holds and trade, and their standing in each port";

    /** Human-readable description of migration V45. */
    public static final String V45_DESCRIPTION =
            "record where each TradeWinds vessel is bound and the orders placed at the ports' markets";

    /** Human-readable description of migration V46. */
    public static final String V46_DESCRIPTION = "remove the rows a deleted island left behind on SQLite";

    /** Human-readable description of migration V47. */
    public static final String V47_DESCRIPTION = "keep each currency the operator lists in the island bank";

    /** Human-readable description of migration V48. */
    public static final String V48_DESCRIPTION = "order each bank's history by the version each move reached";

    /** Human-readable description of migration V49. */
    public static final String V49_DESCRIPTION = "keep where each island wrote blocks past its border";

    /** Human-readable description of migration V50. */
    public static final String V50_DESCRIPTION = "pay for chunkblock chunks a part at a time and keep chunk points";

    /** Human-readable description of migration V51. */
    public static final String V51_DESCRIPTION = "keep how each island's edge is drawn";

    /** Human-readable description of migration V52. */
    public static final String V52_DESCRIPTION = "keep who did each feed line and what a bank line moved";

    /** Human-readable description of migration V53. */
    public static final String V53_DESCRIPTION = "keep what a player put down in each group of worlds";

    /** Human-readable description of migration V54. */
    public static final String V54_DESCRIPTION = "keep what each oneblock island has been paid";

    /** Human-readable description of migration V55. */
    public static final String V55_DESCRIPTION = "keep which oneblock islands wait before a phase";

    /** Human-readable description of migration V56. */
    public static final String V56_DESCRIPTION = "keep the phase each oneblock island picked to follow a branch";

    /** Human-readable description of migration V57. */
    public static final String V57_DESCRIPTION = "count the blocks each player broke on a oneblock island";

    /** Human-readable description of migration V58. */
    public static final String V58_DESCRIPTION = "keep the push trade left on each tradewinds price";

    /** Human-readable description of migration V59. */
    public static final String V59_DESCRIPTION = "keep the contracts each tradewinds vessel took";

    /** Human-readable description of migration V60. */
    public static final String V60_DESCRIPTION = "keep the events each tradewinds voyage holds";

    /** Human-readable description of migration V61. */
    public static final String V61_DESCRIPTION = "write down every tradewinds plunder at sea";

    /** Human-readable description of migration V62. */
    public static final String V62_DESCRIPTION = "keep how far each boxed island's template land is copied";

    /** Human-readable description of migration V63. */
    public static final String V63_DESCRIPTION = "keep how far each boxed island's box was moved";

    /** Human-readable description of migration V64. */
    public static final String V64_DESCRIPTION = "keep the side each boxed island's box grows toward";

    /** Human-readable description of migration V65. */
    public static final String V65_DESCRIPTION = "keep each runner's best Parkour run as its ghost replays it";

    /** Human-readable description of migration V66. */
    public static final String V66_DESCRIPTION = "keep the split times of each runner's best Parkour run";

    /** Human-readable description of migration V67. */
    public static final String V67_DESCRIPTION = "keep when each runner was last paid each Parkour reward list";

    /** Human-readable description of migration V39. */
    public static final String V39_DESCRIPTION =
            "record which islands are Boxed islands and the advancements each earned";

    /** Human-readable description of migration V33. */
    public static final String V33_DESCRIPTION =
            "record where each OneBlock island's block stands and how often it broke";

    /** Human-readable description of migration V32. */
    public static final String V32_DESCRIPTION = "record each unit a restore puts back";

    /** Human-readable description of migration V27. */
    public static final String V27_DESCRIPTION =
            "create island dimensions table for durable multi-world platform state";

    /** Human-readable description of migration V26. */
    public static final String V26_DESCRIPTION =
            "create enterprise activity events notifications and island homes tables";

    /** Human-readable description of migration V25. */
    public static final String V25_DESCRIPTION = "create game mode instances and canonical gameplay root references";

    /** Human-readable description of migration V24. */
    public static final String V24_DESCRIPTION = "create island bankruptcies table";

    /** Human-readable description of migration V23. */
    public static final String V23_DESCRIPTION = "create island boosters table";

    /** Human-readable description of migration V22. */
    public static final String V22_DESCRIPTION = "create player anti abuse records and island quarantines";

    /** Human-readable description of migration V21. */
    public static final String V21_DESCRIPTION = "create spiral slot pool for coordinate recycling";

    /** Human-readable description of migration V20. */
    public static final String V20_DESCRIPTION = "create island missions and quest progress";

    /** Human-readable description of migration V19. */
    public static final String V19_DESCRIPTION =
            "create island vault pages edit sessions escrow transfers and audit logs";

    /** Human-readable description of migration V18. */
    public static final String V18_DESCRIPTION = "create island warps and island bans";

    /** Human-readable description of migration V1. */
    public static final String V1_DESCRIPTION = "create player accounts profiles and sessions";

    /** Human-readable description of migration V2. */
    public static final String V2_DESCRIPTION = "create profile inventories";

    /** Human-readable description of migration V3. */
    public static final String V3_DESCRIPTION = "create inventory mutation journals";

    /** Human-readable description of migration V4. */
    public static final String V4_DESCRIPTION = "create profile switch operations";

    /** Human-readable description of migration V5. */
    public static final String V5_DESCRIPTION = "create islands and island authority structures";

    /** Human-readable description of migration V6. */
    public static final String V6_DESCRIPTION = "create island banks bank transactions and processed operations";

    /** Human-readable description of migration V7. */
    public static final String V7_DESCRIPTION = "create island upgrades and leaderboard indexes";

    /** Human-readable description of migration V8. */
    public static final String V8_DESCRIPTION = "create backup operations catalog";

    /** Human-readable description of migration V9. */
    public static final String V9_DESCRIPTION = "create outbox events and consumer inbox";

    /** Human-readable description of migration V10. */
    public static final String V10_DESCRIPTION = "create world grid allocations";

    /** Human-readable description of migration V11. */
    public static final String V11_DESCRIPTION = "create economy sagas";

    /** Human-readable description of migration V12. */
    public static final String V12_DESCRIPTION = "create island creation unique constraints";

    /** Human-readable description of migration V13. */
    public static final String V13_DESCRIPTION = "create island seasons snapshots and payouts";

    /** Human-readable description of migration V14. */
    public static final String V14_DESCRIPTION = "create social ratings guestbook subject visits and bookmarks";

    /** Human-readable description of migration V15. */
    public static final String V15_DESCRIPTION = "create island alliances and invites";

    /** Human-readable description of migration V16. */
    public static final String V16_DESCRIPTION = "create temporary access grants and permissions";

    /** Human-readable description of migration V17. */
    public static final String V17_DESCRIPTION = "create reward grants and components";

    private SkyblockMigrations() {}

    /**
     * Returns the ordered immutable list of production migrations for the specified dialect.
     *
     * <p>Explicitly dispatches DDL for supported V1 dialects: SQLite, MariaDB (upstream
     * {@link Dialect#MYSQL}), and PostgreSQL ({@link Dialect#POSTGRES}). Unsupported dialects
     * (such as {@link Dialect#H2} or {@link Dialect#GENERIC}) fail fast with {@link IllegalArgumentException}.
     *
     * @param dialect the target SQL dialect
     * @return ordered list of production migrations (versions 1 to 29)
     * @throws NullPointerException if dialect is null
     * @throws IllegalArgumentException if dialect is unsupported
     */
    public static List<Migration> getMigrations(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        List<Migration> all = new ArrayList<>(LATEST_VERSION);
        all.addAll(CoreSchemaMigrations.migrations(dialect));
        all.addAll(GameplaySchemaMigrations.migrations(dialect));
        all.addAll(OperationsSchemaMigrations.migrations(dialect));
        return List.copyOf(all);
    }
}
