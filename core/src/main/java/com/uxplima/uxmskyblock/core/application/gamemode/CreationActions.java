package com.uxplima.uxmskyblock.core.application.gamemode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The creation actions a server knows, each answered by exactly one provider.
 *
 * <p>Two providers for one name would leave it to chance which one builds a player's island, so the
 * second registration fails while the server starts rather than on some player's first island. A start
 * that names an action nobody provides fails before any of its actions has run, so no island is left
 * half built.
 *
 * @param <C> where the actions happen
 */
public final class CreationActions<C> {

    private final Map<String, CreationActionProvider<C>> providers = new ConcurrentHashMap<>();

    /**
     * Adds a provider.
     *
     * @throws IllegalStateException when another provider already answers its name
     */
    public void register(CreationActionProvider<C> provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        String name = key(provider.actionId());
        CreationActionProvider<C> earlier = providers.putIfAbsent(name, provider);
        if (earlier != null) {
            throw new IllegalStateException("The creation action '" + name + "' is provided twice, by "
                    + earlier.getClass().getName() + " and by "
                    + provider.getClass().getName());
        }
    }

    /** Whether every action the list names has a provider. */
    public boolean knowsAll(List<String> actionIds) {
        Objects.requireNonNull(actionIds, "actionIds must not be null");
        for (String actionId : actionIds) {
            if (!providers.containsKey(key(actionId))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs the actions in the order the list names them.
     *
     * @throws IllegalArgumentException when an action has no provider, before any action has run
     */
    public void run(List<String> actionIds, C context) {
        Objects.requireNonNull(context, "context must not be null");
        for (CreationActionProvider<C> provider : resolve(actionIds)) {
            provider.apply(context);
        }
    }

    /**
     * Runs the actions in the order the list names them, each once the one before it is done, and answers
     * when the last is done. An action done at once is followed at once, on the same thread. One that
     * finishes later, on whatever thread finished it, is followed through {@code resume}, which hands the
     * next action back to the thread that owns the place.
     *
     * @throws IllegalArgumentException when an action has no provider, before any action has run
     */
    public CompletableFuture<Void> runThen(List<String> actionIds, C context, Executor resume) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(resume, "resume must not be null");
        return from(resolve(actionIds), 0, context, resume);
    }

    private CompletableFuture<Void> from(
            List<CreationActionProvider<C>> resolved, int index, C context, Executor resume) {
        for (int i = index; i < resolved.size(); i++) {
            CompletableFuture<Void> step = applied(resolved.get(i), context);
            if (!step.isDone()) {
                int next = i + 1;
                if (next == resolved.size()) {
                    // Nothing follows it, so nothing is handed back.
                    return step;
                }
                return step.thenComposeAsync(done -> from(resolved, next, context, resume), resume);
            }
            if (step.isCompletedExceptionally()) {
                return step;
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static <C> CompletableFuture<Void> applied(CreationActionProvider<C> provider, C context) {
        try {
            CompletableFuture<Void> step = provider.applyThen(context);
            return step == null ? CompletableFuture.completedFuture(null) : step;
        } catch (RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    /**
     * Where the named actions write blocks past the place they are handed, as each says. An action nobody
     * provides reaches nowhere, so a start that cannot run still answers.
     */
    public List<CreationReach> reachOf(List<String> actionIds, C context) {
        Objects.requireNonNull(actionIds, "actionIds must not be null");
        Objects.requireNonNull(context, "context must not be null");
        List<CreationReach> reach = new ArrayList<>();
        for (String actionId : actionIds) {
            CreationActionProvider<C> provider = providers.get(key(actionId));
            if (provider != null) {
                reach.addAll(provider.reach(context));
            }
        }
        return List.copyOf(reach);
    }

    private List<CreationActionProvider<C>> resolve(List<String> actionIds) {
        Objects.requireNonNull(actionIds, "actionIds must not be null");
        List<CreationActionProvider<C>> resolved = new ArrayList<>(actionIds.size());
        for (String actionId : actionIds) {
            CreationActionProvider<C> provider = providers.get(key(actionId));
            if (provider == null) {
                throw new IllegalArgumentException("No provider answers the creation action '" + actionId + "'");
            }
            resolved.add(provider);
        }
        return resolved;
    }

    private static String key(String actionId) {
        Objects.requireNonNull(actionId, "actionId must not be null");
        String key = actionId.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A creation action needs a name");
        }
        return key;
    }
}
