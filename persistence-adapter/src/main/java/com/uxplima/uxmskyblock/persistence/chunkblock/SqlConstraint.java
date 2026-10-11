package com.uxplima.uxmskyblock.persistence.chunkblock;

import java.sql.SQLException;

/** Whether a statement failed because a row it wrote was taken first. */
final class SqlConstraint {

    private SqlConstraint() {}

    /**
     * Class 23 is an integrity constraint. The SQLite driver names no class and says SQLITE_CONSTRAINT, code 19,
     * instead.
     */
    static boolean violated(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
    }
}
