package com.uxplima.uxmskyblock.core.application.gamemode;

import java.util.concurrent.CompletableFuture;

/**
 * One thing a new island's start does: lay a platform, set a OneBlock island's block, paste a structure.
 *
 * <p>A preset lists the actions its start runs by name, and a game mode brings the providers that
 * answer them. No part of island creation asks which game mode it is making: it runs the list.
 *
 * @param <C> where the action happens, which the server's side describes
 */
public interface CreationActionProvider<C> {

    /** The name a preset's {@code start} list writes for this action, such as {@code uxm:platform}. */
    String actionId();

    /** Does the action at the place the context describes, on the thread that owns that place. */
    void apply(C context);

    /**
     * Does the action and answers once it is done. Most actions are done when {@link #apply} returns and
     * keep this as it is. One that spreads its work over ticks, as a pasted structure does, answers when
     * the last of it stands, so the actions after it and the player's arrival wait for it.
     */
    default CompletableFuture<Void> applyThen(C context) {
        apply(context);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Where the action writes blocks past the place it is handed, once it is done: how far from the island's
     * centre and in which world. An action that writes only around the centre, inside the island the player
     * starts with, answers nothing, which is what this does.
     */
    default java.util.List<CreationReach> reach(C context) {
        return java.util.List.of();
    }
}
