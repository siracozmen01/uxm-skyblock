package com.uxplima.uxmskyblock.bukkit.mission;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionRewardPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Pays a finished mission's reward: its money, crystals and island experience into the island bank, and its commands
 * run by the console.
 *
 * <p>The mission service was built with no reward port, so every reward a mission showed was never handed out. The
 * service calls this once for each finish; it pays off the thread that called it, and a mission rewards booster the
 * island has running makes the amounts more. Commands are the operator's lines, run as written with
 * {@code %player%} and {@code %island%} put in.
 */
public final class MissionPayout implements IslandMissionRewardPort {

    private static final Logger LOGGER = Logger.getLogger(MissionPayout.class.getName());

    /**
     * A player name as a command reads it: letters, digits and underscores, up to sixteen, after at most the dot or
     * the star a Bedrock bridge puts in front. A dash anywhere in front would read as a flag.
     */
    static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[.*]?[A-Za-z0-9_]{1,16}");

    private final IslandBankService bank;
    private final IslandStoragePort islands;
    private final ServerNodeId node;
    private final ToDoubleFunction<IslandId> boost;
    private final SchedulerPort scheduler;
    private final Function<PlayerUuid, Optional<String>> nameOf;
    private final Consumer<String> console;

    /**
     * @param boost the mission rewards booster an island has running, one for none
     * @param nameOf a player's name, for the commands
     * @param console runs a command as the console, on the global thread
     */
    public MissionPayout(
            IslandBankService bank,
            IslandStoragePort islands,
            ServerNodeId node,
            ToDoubleFunction<IslandId> boost,
            SchedulerPort scheduler,
            Function<PlayerUuid, Optional<String>> nameOf,
            Consumer<String> console) {
        this.bank = Objects.requireNonNull(bank, "bank must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.boost = Objects.requireNonNull(boost, "boost must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.nameOf = Objects.requireNonNull(nameOf, "nameOf must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    @Override
    public void dispatchReward(IslandId islandId, ProfileId profileId, MissionDefinition mission) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(mission, "mission must not be null");
        scheduler.async(() -> pay(islandId, profileId, mission));
    }

    /** Pays {@code mission}'s reward to the island, here and now. */
    void pay(IslandId islandId, ProfileId profileId, MissionDefinition mission) {
        Optional<PlayerUuid> player = islands.findIslandById(islandId)
                .flatMap((Island island) -> island.member(profileId))
                .map(IslandMember::playerUuid);
        PlayerUuid actor = player.orElseGet(() -> PlayerUuid.of(profileId.value()));
        MissionReward reward = mission.reward();
        double times = Math.max(1.0, boost.applyAsDouble(islandId));
        String reason = "Mission reward: " + mission.id().value();
        credit(islandId, actor, "PRIMARY", more(reward.currencyMinorUnits(), times), reason);
        credit(islandId, actor, "crystals", more(reward.crystals(), times), reason);
        credit(islandId, actor, "exp", more(reward.islandExp(), times), reason);
        List<String> commands = reward.commands();
        if (commands.isEmpty()) {
            return;
        }
        String name = player.flatMap(nameOf).orElse("");
        // A name goes into a console command only if it is one a command reads as one word.
        boolean speakable = SAFE_NAME.matcher(name).matches();
        scheduler.onGlobal(() -> {
            for (String command : commands) {
                if (command.contains("%player%") && !speakable) {
                    LOGGER.warning(() -> "A mission reward command for island " + islandId.value()
                            + " names its player, whose name a command cannot carry safely, so it was not run: "
                            + command);
                    continue;
                }
                String line = command.replace("%player%", name)
                        .replace("%island%", islandId.value().toString());
                console.accept(line.startsWith("/") ? line.substring(1) : line);
            }
        });
    }

    /** {@code amount} made more by {@code times}, rounded to the nearest whole. */
    static long more(long amount, double times) {
        return Math.round(amount * times);
    }

    private void credit(IslandId islandId, PlayerUuid actor, String currency, long amount, String reason) {
        if (amount <= 0) {
            return;
        }
        BankTransactionOutcome outcome = bank.move(islandId, actor, currency, amount, reason, node);
        if (!IslandBankService.landed(outcome)) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "A mission reward of " + amount + " " + currency.toLowerCase(Locale.ROOT) + " for island "
                            + islandId.value() + " did not land: " + outcome);
        }
    }
}
