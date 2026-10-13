package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.condition.action.ActionList;

/**
 * What a OneBlock phase runs when something happens to it, as its file writes it under {@code began},
 * {@code finished}, {@code first-finished} or {@code lucky}: a list run once for the player whose break it was, for
 * commands and rewards, and a list run for every member of the island who is online, for what they all should hear.
 *
 * @param breaker run once, for the breaker
 * @param members run for each member online, the breaker among them
 * @param chance how often a break is lucky, from 0 to 1; only {@code lucky} reads it, and every other event happens
 *     whenever it happens
 */
public record OneBlockLists(ActionList breaker, ActionList members, double chance) {

    /** The events a phase writes lists for. */
    public static final List<String> EVENTS = List.of("began", "finished", "first-finished", "lucky");

    /** The event a break has as often as its chance says. */
    public static final String LUCKY = "lucky";

    public OneBlockLists {
        Objects.requireNonNull(breaker, "breaker");
        Objects.requireNonNull(members, "members");
        if (!(chance >= 0 && chance <= 1)) {
            throw new IllegalArgumentException("a chance is from 0 to 1: " + chance);
        }
    }

    /** Lists that run whenever their event happens. */
    public OneBlockLists(ActionList breaker, ActionList members) {
        this(breaker, members, 1);
    }

    /** Whether the phase wrote nothing for the event, so the shipped fallback in effects.conf runs instead. */
    public boolean isEmpty() {
        return breaker.actions().isEmpty() && members.actions().isEmpty();
    }
}
