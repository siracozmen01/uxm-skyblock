package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Manages player session coordination, profile switching, distributed authority leases,
 * and node process identity wiring.
 */
public final class AuthorityWiring implements AutoCloseable {

    private final ServerNodeId serverNodeId;
    private final CurrentNodeProcessIdentity nodeProcessIdentity;
    private final SwitchProfileUseCase switchProfileUseCase;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final PlayerSessionListener sessionListener;
    private final com.uxplima.uxmskyblock.core.application.profile.ProfileTypes profileTypes;
    private final com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort carriedStates;

    private AuthorityWiring(
            ServerNodeId serverNodeId,
            CurrentNodeProcessIdentity nodeProcessIdentity,
            SwitchProfileUseCase switchProfileUseCase,
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionListener sessionListener,
            com.uxplima.uxmskyblock.core.application.profile.ProfileTypes profileTypes,
            com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort carriedStates) {
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.nodeProcessIdentity = Objects.requireNonNull(nodeProcessIdentity, "nodeProcessIdentity must not be null");
        this.switchProfileUseCase =
                Objects.requireNonNull(switchProfileUseCase, "switchProfileUseCase must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.sessionListener = Objects.requireNonNull(sessionListener, "sessionListener must not be null");
        this.profileTypes = Objects.requireNonNull(profileTypes, "profileTypes must not be null");
        this.carriedStates = Objects.requireNonNull(carriedStates, "carriedStates must not be null");
    }

    /** What each profile put down in each group of worlds, written with its inventory. */
    public com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort carriedStates() {
        return carriedStates;
    }

    /** Which ruleset a profile plays under, read once per profile and remembered. */
    public com.uxplima.uxmskyblock.core.application.profile.ProfileTypes profileTypes() {
        return profileTypes;
    }

    /**
     * Creates and wires the authority subsystem, linking session providers into the protection listener.
     */
    public static AuthorityWiring create(
            ServerNodeId serverNodeId,
            PlayerStateDurabilityConfig playerStateConfig,
            PersistenceBootstrap persistenceBootstrap,
            SchedulerPort scheduler,
            IslandProtectionListener protectionListener,
            Messages messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        Objects.requireNonNull(playerStateConfig, "playerStateConfig must not be null");
        Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap must not be null");
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(protectionListener, "protectionListener must not be null");

        SwitchProfileUseCase switchProfile = new SwitchProfileUseCase(
                persistenceBootstrap.profileSwitchPort(),
                persistenceBootstrap.inventoryPort(),
                persistenceBootstrap.outboxPort());

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
                serverNodeId,
                persistenceBootstrap.sessionAuthorityPort(),
                persistenceBootstrap.inventoryPort(),
                persistenceBootstrap.handoffFinalizationPort(),
                switchProfile,
                new com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery(
                        persistenceBootstrap.mutationJournalPort(), persistenceBootstrap.inventoryPort()),
                scheduler,
                protectionListener,
                Duration.ofSeconds(5),
                playerStateConfig.ambientCheckpointInterval(),
                messages);

        coordinator.settleTradesWith(new com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery(
                persistenceBootstrap.tradeJournalPort(), persistenceBootstrap.inventoryPort()));
        coordinator.settleCargoWith(new com.uxplima.uxmskyblock.core.application.inventory.CargoJournalRecovery(
                persistenceBootstrap.cargoJournalPort(), persistenceBootstrap.inventoryPort()));

        PlayerSessionListener listener = new PlayerSessionListener(coordinator);
        com.uxplima.uxmskyblock.core.application.profile.ProfileTypes profileTypes =
                new com.uxplima.uxmskyblock.core.application.profile.ProfileTypes(
                        persistenceBootstrap.profileTypePort());
        CurrentNodeProcessIdentity identity = CurrentNodeProcessIdentity.create(serverNodeId.value());

        // Wire node identity, session record and ruleset providers into protection listener.
        // The ruleset was never wired, so every profile read as CLASSIC and the Ironman barrier
        // could not refuse anything: it never saw a profile that was not classic.
        protectionListener.setNodeIdentitySupplier(() -> identity);
        protectionListener.setProfileTypeProvider(profileTypes::of);
        protectionListener.setSessionRecordProvider(uuid -> {
            ActiveSession session = coordinator.getActiveSession(uuid.value());
            if (session == null || session.isFenced()) {
                return Optional.empty();
            }
            return persistenceBootstrap.sessionAuthorityPort().findSession(uuid);
        });

        return new AuthorityWiring(
                serverNodeId,
                identity,
                switchProfile,
                coordinator,
                listener,
                profileTypes,
                persistenceBootstrap.carriedStatePort());
    }

    public ServerNodeId serverNodeId() {
        return serverNodeId;
    }

    public CurrentNodeProcessIdentity nodeProcessIdentity() {
        return nodeProcessIdentity;
    }

    public SwitchProfileUseCase switchProfileUseCase() {
        return switchProfileUseCase;
    }

    public PlayerSessionCoordinator sessionCoordinator() {
        return sessionCoordinator;
    }

    public PlayerSessionListener sessionListener() {
        return sessionListener;
    }

    public Function<UUID, Optional<ProfileId>> activeProfileProvider() {
        return uuid -> sessionCoordinator.activeProfile(uuid);
    }

    @Override
    public void close() {
        sessionCoordinator.shutdown();
    }
}
