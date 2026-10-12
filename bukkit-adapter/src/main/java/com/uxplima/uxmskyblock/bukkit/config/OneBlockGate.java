package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;

/**
 * What stands before a OneBlock phase begins, as its file writes it: the lines that must hold, and a wait. An island
 * at the end of the phase before waits there until the lines hold; then the phase begins, and its block is bedrock
 * with a countdown over it for the wait.
 *
 * @param pause how long the block stays bedrock once the phase begins, nothing for no wait
 * @param requires the lines that must hold before the phase begins, in the requirement grammar of every mode
 */
public record OneBlockGate(Duration pause, List<Requirement> requires) {

    /** Nothing before the phase. */
    public static final OneBlockGate OPEN = new OneBlockGate(Duration.ZERO, List.of());

    public OneBlockGate {
        Objects.requireNonNull(pause, "pause");
        if (pause.isNegative()) {
            throw new IllegalArgumentException("a wait is not below nothing: " + pause);
        }
        requires = List.copyOf(requires);
    }

    /** Whether nothing stands before the phase. */
    public boolean isOpen() {
        return pause.isZero() && requires.isEmpty();
    }
}
