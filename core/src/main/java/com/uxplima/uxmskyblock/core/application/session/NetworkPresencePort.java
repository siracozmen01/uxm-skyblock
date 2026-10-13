package com.uxplima.uxmskyblock.core.application.session;

/** Who is on the network: players on any of its servers, read from the sessions every server shares. */
public interface NetworkPresencePort {

    /** How many players are on a server of the network now: a session not offline whose lease has not run out. */
    int online();
}
