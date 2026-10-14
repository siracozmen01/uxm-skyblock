package com.uxplima.uxmskyblock.persistence.chunkblock;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Where a ChunkBlock web is kept: the overworld's in the tables it always had, a Nether's or an End's in the dimension
 * tables, with the dimension in the key. The dimension is written last in each statement, so the statements of the
 * overworld read as they always did.
 *
 * @param claims the table of the opened chunks
 * @param payments the table of what is paid toward a closed chunk
 * @param dimension the dimension's key, or null for the overworld
 */
record ChunkScope(String claims, String payments, @Nullable String dimension) {

    /** The overworld's web. */
    static final ChunkScope OVERWORLD =
            new ChunkScope("chunkblock_territory_claims", "chunkblock_chunk_payments", null);

    ChunkScope {
        Objects.requireNonNull(claims, "claims must not be null");
        Objects.requireNonNull(payments, "payments must not be null");
    }

    /** The web of one dimension other than the overworld. */
    static ChunkScope of(String dimension) {
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("a dimension has a key");
        }
        return new ChunkScope("chunkblock_dimension_claims", "chunkblock_dimension_payments", dimension);
    }

    /** What a WHERE clause adds, last. */
    String and() {
        return dimension == null ? "" : " AND dimension = ?";
    }

    /** What a WHERE clause is when the dimension is all it asks. */
    String where() {
        return dimension == null ? "" : " WHERE dimension = ?";
    }

    /** What a column list adds, last. */
    String column() {
        return dimension == null ? "" : ", dimension";
    }

    /** What a list of values adds, last. */
    String value() {
        return dimension == null ? "" : ", ?";
    }

    /** Binds the dimension at {@code index}, the place after the statement's own, when there is one. */
    void bind(PreparedStatement ps, int index) throws SQLException {
        if (dimension != null) {
            ps.setString(index, dimension);
        }
    }
}
