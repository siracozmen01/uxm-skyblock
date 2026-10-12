package com.uxplima.uxmskyblock.core.application.oneblock;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Which OneBlock islands wait before a phase, kept durably so a restart keeps them waiting: until a time, when the
 * phase sets a wait, or until the lines the phase requires hold.
 */
public interface OneBlockWaitPort {

    /**
     * An island waiting before {@code phase}.
     *
     * @param until when the wait ends, or none while it waits for the phase's requirements
     */
    record Waiting(
            IslandId islandId, String phase, @Nullable Instant until) {
        public Waiting {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
        }

        /** Whether the island waits for a time to pass, rather than for the phase's requirements. */
        public boolean timed() {
            return until != null;
        }
    }

    /** Every island waiting, read once when the server starts. */
    List<Waiting> findAll();

    /** The island's wait, if it waits. */
    Optional<Waiting> find(IslandId islandId);

    /** Records the island's wait, over any it had. */
    void set(Waiting waiting);

    /** The island waits no longer. */
    void clear(IslandId islandId);

    /** Keeps nothing, for a server that keeps no waits. */
    OneBlockWaitPort NONE = new OneBlockWaitPort() {
        @Override
        public List<Waiting> findAll() {
            return List.of();
        }

        @Override
        public Optional<Waiting> find(IslandId islandId) {
            return Optional.empty();
        }

        @Override
        public void set(Waiting waiting) {}

        @Override
        public void clear(IslandId islandId) {}
    };
}
