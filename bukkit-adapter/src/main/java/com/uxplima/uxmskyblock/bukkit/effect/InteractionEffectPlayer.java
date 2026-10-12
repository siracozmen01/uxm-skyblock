package com.uxplima.uxmskyblock.bukkit.effect;

import java.util.Map;
import java.util.Objects;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.condition.ItemStore;
import com.uxplima.uxmlib.condition.OperandResolver;
import com.uxplima.uxmlib.condition.Wallet;
import com.uxplima.uxmlib.condition.action.ActionContext;
import com.uxplima.uxmlib.condition.action.ActionCostException;
import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmlib.condition.wallet.BridgedWallet;
import com.uxplima.uxmlib.condition.wallet.Economies;
import com.uxplima.uxmlib.content.Operands;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.jspecify.annotations.Nullable;

/**
 * Fires what the operator wrote for an interaction, through the family's action engine.
 *
 * <p>The context is wired all the way rather than partly. A player is the subject and the target
 * audience, so {@code [message]}, {@code [title]}, {@code [actionbar]} and {@code [sound]} have
 * somebody to reach: a context without one renders every line and drops it, quietly. The item store
 * is the player's own inventory, because the engine's default answers "none" to every item question
 * and a {@code [take-item]} against it refuses, quietly and wrongly.
 *
 * <p>{@code [bossbar]} needs a delay to take its bar down again. A node with a scheduler wires one.
 * A node without hears about it the first time such a line runs, which is the engine's own way of
 * saying so and better than a bar that never goes away.
 *
 * <p>An operand is read through the family's standard operands, so {@code {if=%player_level% >= 10}}
 * compares a level rather than the text of the token. {@code [take-money]} and {@code [give-money]}
 * reach the server's economy through Vault when it is there. {@code [console]} and {@code [player]}
 * run their command: left unwired, the first two read every balance as zero and the last two ran
 * nothing, and none of it said so.
 */
public final class InteractionEffectPlayer {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(InteractionEffectPlayer.class.getName());

    private final @Nullable SchedulerPort schedulerPort;

    /** Vault's economy, looked up the first time a line asks for money rather than at startup. */
    private final Wallet wallet = BridgedWallet.ofServer(Economies.vault(), System.getLogger("uxmSkyblock"));

    /**
     * The catalogue a line's {@code @key} parts are read from, in the language of the player it is
     * about. Unwired, the engine refuses such a line, and a line that names its words by key is how
     * an operator writes one text in every language.
     */
    private final com.uxplima.uxmskyblock.bukkit.i18n.Messages messages;

    public InteractionEffectPlayer() {
        this(null);
    }

    public InteractionEffectPlayer(@Nullable SchedulerPort schedulerPort) {
        this(schedulerPort, com.uxplima.uxmskyblock.bukkit.i18n.Messages.bundled());
    }

    public InteractionEffectPlayer(
            @Nullable SchedulerPort schedulerPort, com.uxplima.uxmskyblock.bukkit.i18n.Messages messages) {
        this.schedulerPort = schedulerPort;
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /**
     * Fires one interaction for one player.
     *
     * <p>Everything here reaches the player or their world, so the caller is expected to be on the
     * thread that owns them, which is where all four callers already are.
     */
    public void fire(InteractionEffects effects, String interaction, Player player) {
        fire(effects, interaction, player, Map.of());
    }

    /**
     * Fires one interaction for one player, each {@code %name%} of {@code says} in its lines read as the value
     * given. A value is something the event knows, a number or a name from the operator's own files, and is put
     * in before any other placeholder is read.
     */
    public void fire(InteractionEffects effects, String interaction, Player player, Map<String, String> says) {
        Objects.requireNonNull(effects, "effects must not be null");
        Objects.requireNonNull(interaction, "interaction must not be null");
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(says, "says must not be null");

        ActionList list = effects.of(interaction);
        if (list.actions().isEmpty()) {
            return;
        }
        try {
            list.run(contextFor(player, says));
        } catch (ActionCostException unpaid) {
            // A price nobody can pay is the operator's own gate doing its job. The engine charges
            // every cost before it runs anything, so nothing has happened and nobody is half
            // charged. It is said quietly rather than thrown, because the thing this interaction
            // was about has already happened and must not be undone by a cosmetic line.
            LOGGER.fine(() -> "The " + interaction + " list asked " + player.getName()
                    + " for something they could not pay, so none of it ran: " + unpaid.getMessage());
        } catch (RuntimeException failed) {
            // One line that cannot be carried out is one line. The interaction still happened.
            LOGGER.warning(() -> "The " + interaction + " list could not be carried out for " + player.getName() + ": "
                    + failed.getMessage());
        }
    }

    /** The context an action reads: who it is about, who hears it, and what they are holding. */
    private ActionContext contextFor(Player player, Map<String, String> says) {
        Server server = player.getServer();
        SchedulerPort scheduler = this.schedulerPort;
        ActionContext.Builder builder = ActionContext.builder(resolver(says))
                // The subject, which the engine also makes the target audience.
                .player(player)
                // An island's own interaction reaches the player it happened to. Nothing here is
                // server wide, and an audience left empty renders every line and drops it.
                .broadcast(player)
                .itemStore(ItemStore.inventory())
                .wallet(wallet)
                // Folia runs a console command on the global region, not on the player's.
                .consoleSink(line -> {
                    Runnable dispatch = () -> server.dispatchCommand(server.getConsoleSender(), line);
                    if (scheduler != null) {
                        scheduler.onGlobal(dispatch);
                    } else {
                        dispatch.run();
                    }
                })
                // The caller is on the player's own thread, which is where their command belongs.
                .playerSink(line -> server.dispatchCommand(player, line))
                // A part written as @key reads its words from the catalogue, for this player.
                .words(key -> messages.raw(player, key))
                // The text is painted with this server's theme in the player's language. Unwired,
                // the library's default colours painted it rather than the operator's.
                .style(line -> messages.paint(player, line));
        if (scheduler != null) {
            builder.later(scheduler::asyncAfter);
        }
        return builder.build();
    }

    /** The family's standard operands, after the event's own placeholders are put in. */
    static OperandResolver resolver(Map<String, String> says) {
        OperandResolver standard = Operands.standard();
        if (says.isEmpty()) {
            return standard;
        }
        Map<String, String> held = Map.copyOf(says);
        return OperandResolver.ofBiFunction((player, template) -> standard.resolve(player, fill(template, held)));
    }

    /** {@code template} with each {@code %name%} of {@code says} as its value. */
    static String fill(String template, Map<String, String> says) {
        String filled = template;
        for (Map.Entry<String, String> said : says.entrySet()) {
            filled = filled.replace("%" + said.getKey() + "%", said.getValue());
        }
        return filled;
    }
}
