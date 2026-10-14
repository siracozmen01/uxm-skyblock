package com.uxplima.uxmskyblock.core.application.visit;

/** A visit fee below zero, or above the most the operator lets an island charge. */
public final class VisitFeeOutOfRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long most;

    public VisitFeeOutOfRangeException(long asked, long most) {
        super("A visit fee of " + asked + " is outside 0 to " + most);
        this.most = most;
    }

    /** The most an island may charge, in minor units. */
    public long most() {
        return most;
    }
}
