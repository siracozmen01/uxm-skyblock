package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHoldListener;
import com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHolds;
import com.uxplima.uxmskyblock.bukkit.tradewinds.Crew;
import com.uxplima.uxmskyblock.bukkit.tradewinds.Harbour;
import com.uxplima.uxmskyblock.bukkit.tradewinds.HoldGoods;
import com.uxplima.uxmskyblock.bukkit.tradewinds.IslandBankMarket;
import com.uxplima.uxmskyblock.bukkit.tradewinds.VesselStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoTransfer;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The TradeWinds game mode, while the operator lets islands be trading vessels. */
public final class TradeWindsWiring {

    private static final Logger LOGGER = Logger.getLogger(TradeWindsWiring.class.getName());

    private final TradeWindsConfiguration config;
    private final VesselService service;
    private final SchedulerPort scheduler;
    private final CargoHolds holds;
    private final PortMarket market;
    private final Harbour harbour;
    private @org.jspecify.annotations.Nullable AutoCloseable recovery;
    private final CargoHoldListener listener;
    private final com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener holdStill;

    public TradeWindsWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            com.uxplima.uxmskyblock.core.application.bank.IslandBankService bank) {
        this.config = Objects.requireNonNull(configuration.tradeWindsConfig(), "tradeWindsConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new VesselService(persistence.vesselsPort());
        com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator coordinator = authority.sessionCoordinator();
        java.time.Clock clock = java.time.Clock.systemUTC();
        VesselLease lease = new VesselLease(persistence.rootAuthorityPort(), authority.serverNodeId(), clock);
        HoldGoods goods = new HoldGoods();
        this.market = new PortMarket(
                persistence.vesselsPort(),
                persistence.voyagesPort(),
                persistence.marketOrdersPort(),
                lease,
                new IslandBankMarket(bank, persistence.islandAuthorityPort(), authority.serverNodeId(), clock),
                goods,
                config.standing(),
                config.ranks(),
                authority.serverNodeId(),
                clock);
        this.holds = new CargoHolds(
                service,
                persistence.vesselsPort(),
                new VesselLease(persistence.rootAuthorityPort(), authority.serverNodeId(), java.time.Clock.systemUTC()),
                new CargoTransfer(persistence.cargoJournalPort(), authority.serverNodeId()),
                islands::findIslandAt,
                sessions(coordinator),
                scheduler,
                configuration.messages(),
                com.uxplima.uxmskyblock.bukkit.creative.SealedInventory::holds,
                market::rank);
        this.harbour = new Harbour(
                market,
                config,
                persistence.vesselsPort(),
                goods,
                new Crew(service, islands::findIslandAt, sessions(coordinator)),
                scheduler,
                configuration.messages(),
                clock);
        harbour.useEffects(
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()),
                configuration.effectsConfig());
        this.listener = new CargoHoldListener(holds);
        this.holdStill = new com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener(holds::moving);
        start();
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " TradeWinds vessels are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The TradeWinds vessels could not be read ahead.", e);
            }
        });
    }

    /** Whose session a player plays under here, and how one is taken off the server. */
    private static CargoHolds.Sessions sessions(
            com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator coordinator) {
        return new CargoHolds.Sessions() {
            @Override
            public com.uxplima.uxmskyblock.bukkit.session.@org.jspecify.annotations.Nullable ActiveSession session(
                    java.util.UUID player) {
                com.uxplima.uxmskyblock.bukkit.session.ActiveSession session = coordinator.getActiveSession(player);
                return session == null || session.isFenced() || !coordinator.inPlay(player) ? null : session;
            }

            @Override
            public void fence(java.util.UUID player, String why) {
                coordinator.selfFencePlayer(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(player), why);
            }
        };
    }

    /** The ports' markets. */
    public PortMarket market() {
        return market;
    }

    /** Voyages and trade, as the crew asks for them. */
    public Harbour harbour() {
        return harbour;
    }

    /**
     * Carries on, once a minute, the orders a crash or a busy bank left open on vessels whose bank this
     * node writes. Started with the plugin, while TradeWinds is enabled.
     */
    public void start() {
        if (!config.enabled() || recovery != null) {
            return;
        }
        recovery = scheduler.repeatAsync(
                () -> {
                    try {
                        int settled = market.recoverAll();
                        if (settled > 0) {
                            LOGGER.info(() -> settled + " TradeWinds market orders were carried on and settled.");
                        }
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.WARNING, "The TradeWinds market orders could not be carried on now.", e);
                    }
                },
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofMinutes(1));
    }

    /** Stops carrying orders on. */
    public void close() {
        AutoCloseable running = recovery;
        recovery = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "The TradeWinds order recovery did not stop cleanly.", e);
            }
        }
    }

    /** The cargo holds of vessels. */
    public CargoHolds holds() {
        return holds;
    }

    /** The events of cargo hold windows. */
    public CargoHoldListener listener() {
        return listener;
    }

    /** Holds an inventory still while a move into or out of a hold is carried out. */
    public com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener holdStill() {
        return holdStill;
    }

    public VesselService service() {
        return service;
    }

    public TradeWindsConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that launches an island as a vessel, while TradeWinds is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new VesselStart(service, scheduler, config.sea())) : List.of();
    }
}
