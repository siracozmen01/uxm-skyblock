package com.uxplima.uxmskyblock.persistence.migration;

import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Operations schema migrations (V21 - V33).
 * Covers spiral slot pool coordinate recycling, player anti-abuse records,
 * island quarantines, island boosters, island bankruptcies, game mode instances,
 * primary gameplay root references, enterprise activity events, notifications,
 * island homes, island dimensions, profile cosmetics, and island recycle operations.
 */
final class OperationsSchemaMigrations {

    private OperationsSchemaMigrations() {}

    static List<Migration> migrations(Dialect dialect) {
        List<Migration> list = new ArrayList<>(8);
        list.addAll(OperationsSchemaMigrationsV21ToV24.migrations(dialect));
        list.addAll(OperationsSchemaMigrationsV25ToV28.migrations(dialect));
        list.add(OperationsSchemaMigrationsV29.migration(dialect));
        list.add(OperationsSchemaMigrationsV30.migration(dialect));
        list.add(OperationsSchemaMigrationsV31.migration(dialect));
        list.add(OperationsSchemaMigrationsV32.migration(dialect));
        list.add(GameModeSchemaMigrationsV33.migration(dialect));
        list.add(LifecycleSchemaMigrationsV34.migration(dialect));
        list.add(ClusterSchemaMigrationsV35.migration(dialect));
        list.add(GameModeSchemaMigrationsV36.migration(dialect));
        list.add(GameModeSchemaMigrationsV37.migration(dialect));
        list.add(GameModeSchemaMigrationsV38.migration(dialect));
        list.add(GameModeSchemaMigrationsV39.migration(dialect));
        list.add(GameModeSchemaMigrationsV40.migration(dialect));
        list.add(GameModeSchemaMigrationsV41.migration(dialect));
        list.add(GameModeSchemaMigrationsV42.migration(dialect));
        list.add(GameModeSchemaMigrationsV43.migration(dialect));
        list.add(GameModeSchemaMigrationsV44.migration(dialect));
        list.add(GameModeSchemaMigrationsV45.migration(dialect));
        list.add(OperationsSchemaMigrationsV46.migration(dialect));
        list.add(BankSchemaMigrationsV47.migration(dialect));
        list.add(BankSchemaMigrationsV48.migration(dialect));
        list.add(RecycleSchemaMigrationsV49.migration(dialect));
        list.add(ChunkBlockSchemaMigrationsV50.migration(dialect));
        list.add(IslandEdgeSchemaMigrationsV51.migration(dialect));
        list.add(ActivityFeedSchemaMigrationsV52.migration(dialect));
        list.add(CarriedStateSchemaMigrationsV53.migration(dialect));
        list.add(GameModeSchemaMigrationsV54.migration(dialect));
        list.add(GameModeSchemaMigrationsV55.migration(dialect));
        list.add(GameModeSchemaMigrationsV56.migration(dialect));
        list.add(GameModeSchemaMigrationsV57.migration(dialect));
        list.add(GameModeSchemaMigrationsV58.migration(dialect));
        list.add(GameModeSchemaMigrationsV59.migration(dialect));
        list.add(GameModeSchemaMigrationsV60.migration(dialect));
        list.add(GameModeSchemaMigrationsV61.migration(dialect));
        list.add(GameModeSchemaMigrationsV62.migration(dialect));
        list.add(GameModeSchemaMigrationsV63.migration(dialect));
        list.add(GameModeSchemaMigrationsV64.migration(dialect));
        return List.copyOf(list);
    }
}
