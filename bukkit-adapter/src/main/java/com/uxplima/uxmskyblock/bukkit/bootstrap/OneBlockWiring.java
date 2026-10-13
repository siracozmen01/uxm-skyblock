package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * The OneBlock game mode's running parts: the service that counts breaks, and the schedule that writes
 * the count down.
 */
public final class OneBlockWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(OneBlockWiring.class.getName());

    private final OneBlockConfiguration config;
    private final OneBlockService service;
    private final AutoCloseable saving;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener listener;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel panel;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws draws;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBranches branches;

    public OneBlockWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile) {
        this.config = Objects.requireNonNull(configuration.oneBlockConfig(), "oneBlockConfig must not be null");
        this.service = new OneBlockService(
                persistence.oneBlockProgressPort(),
                persistence.oneBlockRewardPort(),
                persistence.oneBlockWaitPort(),
                persistence.oneBlockBranchPort(),
                config.phases(),
                new SplittableRandom());
        service.lookAhead(config.creatureWarning());
        this.saving = scheduler.repeatAsync(service::flush, config.saveInterval(), config.saveInterval());
        this.listener = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener(
                service,
                islands,
                scheduler,
                configuration.messages(),
                configuration.effectsConfig(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()));
        this.draws = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws(
                config::entries, configuration.messages(), configuration::lootTables);
        listener.useDraws(draws);
        com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPauses pauses =
                new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPauses(
                        () -> config, service, scheduler, configuration.messages(), draws, bedrockPlayer());
        listener.usePauses(pauses);
        this.branches = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBranches(
                () -> config, service, scheduler, configuration.messages(), java.time.Clock.systemUTC());
        listener.useBranches(branches);
        listener.usePhaseLists(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPhaseLists(
                () -> config, scheduler, org.bukkit.Bukkit::getPlayer));
        listener.useMoments(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockMoments(
                () -> config, scheduler, configuration.messages(), bedrockPlayer()));
        this.panel = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel(
                service, persistence.islandStoragePort(), scheduler, configuration.messages(), activeProfile);
        panel.useConfiguration(() -> config);
        // Every OneBlock island into memory before players break anything, so no break is a query on
        // the region's thread. A break before it finishes reads its island once.
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " OneBlock islands are in memory.");
                // A wait that was counting down before the restart goes on where it stood.
                for (var waiting : service.timedWaits()) {
                    pauses.resume(
                            waiting,
                            island -> persistence
                                    .islandStoragePort()
                                    .findLocationByIslandId(island)
                                    .map(found -> found.worldName()));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The OneBlock islands could not be read ahead; each is read when needed.", e);
            }
        });
    }

    /** Whether a player plays on Bedrock, asked of Floodgate once the server is up. */
    private static java.util.function.Predicate<org.bukkit.entity.Player> bedrockPlayer() {
        java.util.function.Predicate<java.util.UUID> bedrock = ChunkBlockWiring.bedrockPlayers();
        return player -> bedrock.test(player.getUniqueId());
    }

    public OneBlockService service() {
        return service;
    }

    /** How the operator's pool entries are put into the world. */
    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws draws() {
        return draws;
    }

    /** Whether the operator lets islands be OneBlock islands, which is when the listener is registered. */
    public boolean enabled() {
        return config.enabled();
    }

    /**
     * The creation actions OneBlock adds: the one that sets an island's block, while the operator lets
     * islands be OneBlock islands, and none otherwise, so a OneBlock preset is not offered.
     */
    public java.util.List<
                    com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                            com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
            startActions() {
        if (!config.enabled()) {
            return java.util.List.of();
        }
        return java.util.List.of(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockStart(service, draws));
    }

    /**
     * The panel behind {@code /is oneblock} and the OneBlock placeholders, drawn from the operator's menu
     * file, or nothing while the operator does not let islands be OneBlock islands.
     */
    public com.uxplima.uxmskyblock.bukkit.oneblock.@org.jspecify.annotations.Nullable OneBlockPanel panel(
            com.uxplima.uxmskyblock.bukkit.menu.@org.jspecify.annotations.Nullable SkyblockMenuEngine engine) {
        if (!config.enabled()) {
            return null;
        }
        panel.useMenuEngine(engine);
        branches.useMenuEngine(engine);
        return panel;
    }

    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener listener() {
        return listener;
    }

    /**
     * Has the listener ask each phase's requirement lines before it begins: the breaker's purse, the island's bank,
     * which only a member whose role may spend from it pays out of, and the island's level.
     */
    public void useGates(
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.i18n.Messages messages,
            com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurse purse,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankPort bankPort,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankService bank,
            com.uxplima.uxmskyblock.core.application.island.IslandAccessService access,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            java.util.function.BiFunction<
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId,
                            com.uxplima.uxmskyblock.core.domain.identity.ProfileId,
                            Long>
                    levelOf,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node) {
        var funds = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockGates.IslandFunds() {
            @Override
            public long held(
                    com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId,
                    com.uxplima.uxmskyblock.core.domain.requirement.Requirement line) {
                return bankPort.findBankByIslandId(islandId)
                        .map(held -> line
                                        instanceof
                                        com.uxplima.uxmskyblock.core.domain.requirement.Requirement.Currency currency
                                ? held.heldOf(com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.columnOf(
                                        currency.id()))
                                : held.primaryBalanceMinorUnits())
                        .orElse(0L);
            }

            @Override
            public boolean take(
                    com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId,
                    org.bukkit.entity.Player payer,
                    com.uxplima.uxmskyblock.core.domain.requirement.Requirement line) {
                return moved(islandId, payer, line, -1);
            }

            @Override
            public void giveBack(
                    com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId,
                    org.bukkit.entity.Player payer,
                    com.uxplima.uxmskyblock.core.domain.requirement.Requirement line) {
                if (!moved(islandId, payer, line, 1)) {
                    LOGGER.warning(() ->
                            "Giving back what OneBlock took from the bank of island " + islandId + " failed: " + line);
                }
            }

            private boolean moved(
                    com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId,
                    org.bukkit.entity.Player payer,
                    com.uxplima.uxmskyblock.core.domain.requirement.Requirement line,
                    long sign) {
                String column;
                long amount;
                if (line instanceof com.uxplima.uxmskyblock.core.domain.requirement.Requirement.Currency currency) {
                    column = com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.columnOf(currency.id());
                    amount = currency.amount();
                } else if (line instanceof com.uxplima.uxmskyblock.core.domain.requirement.Requirement.Money money) {
                    column = com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.PRIMARY_COLUMN;
                    amount = money.minor();
                } else {
                    return false;
                }
                return bank.move(
                                islandId,
                                new com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid(payer.getUniqueId()),
                                column,
                                sign * amount,
                                "OneBlock phase",
                                node)
                        instanceof com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.Success;
            }

            @Override
            public boolean mayTake(
                    com.uxplima.uxmskyblock.core.domain.island.Island island, org.bukkit.entity.Player payer) {
                return activeProfile
                        .apply(payer.getUniqueId())
                        .map(profile -> access.canWithdrawBank(island, profile))
                        .orElse(false);
            }
        };
        listener.useGates(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockGates(
                () -> config,
                service,
                scheduler,
                messages,
                purse,
                funds,
                (island, player) -> activeProfile
                        .apply(player.getUniqueId())
                        .map(profile -> levelOf.apply(island.id(), profile))
                        .orElse(0L)));
    }

    /** Stops the schedule and writes what is still only counted, before the database closes. */
    @Override
    public void close() {
        try {
            saving.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Stopping the OneBlock save schedule failed.", e);
        }
        service.flush();
    }
}
