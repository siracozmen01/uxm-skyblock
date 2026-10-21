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

    /** The files as last read, at the start or by an operator's reload. */
    private volatile OneBlockConfiguration config;

    /** Whether the mode ran from the start; a reload does not turn it on or off, a restart does. */
    private final boolean enabledAtStart;

    private final OneBlockService service;
    private final AutoCloseable saving;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener listener;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBlockLines blockLines;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel panel;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws draws;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBranches branches;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPhasesMenu phasesMenu;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBiomes biomes;
    private final com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributions contributions;

    public OneBlockWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile,
            com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPauses.Lines lines) {
        this.config = Objects.requireNonNull(configuration.oneBlockConfig(), "oneBlockConfig must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        this.enabledAtStart = config.enabled();
        this.service = new OneBlockService(
                persistence.oneBlockProgressPort(),
                persistence.oneBlockRewardPort(),
                persistence.oneBlockWaitPort(),
                persistence.oneBlockBranchPort(),
                config.phases(),
                new SplittableRandom());
        service.lookAhead(config.creatureWarning());
        this.contributions = new com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributions(
                persistence.oneBlockContributionPort());
        this.saving = scheduler.repeatAsync(
                () -> {
                    service.flush();
                    contributions.flush();
                },
                config.saveInterval(),
                config.saveInterval());
        this.listener = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener(
                service,
                islands,
                scheduler,
                configuration.messages(),
                configuration.effectsConfig(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()));
        this.draws = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws(
                () -> config.entries(), configuration.messages(), configuration::lootTables);
        listener.useDraws(draws);
        com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPauses pauses =
                new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPauses(
                        () -> config, service, scheduler, configuration.messages(), draws, bedrockPlayer(), lines);
        listener.usePauses(pauses);
        this.branches = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBranches(
                () -> config, service, scheduler, configuration.messages(), java.time.Clock.systemUTC());
        listener.useBranches(branches);
        listener.usePhaseLists(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPhaseLists(
                () -> config, scheduler, org.bukkit.Bukkit::getPlayer));
        listener.useMoments(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockMoments(
                () -> config, scheduler, configuration.messages(), bedrockPlayer(), lines));
        this.panel = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel(
                service, persistence.islandStoragePort(), scheduler, configuration.messages(), activeProfile);
        panel.useConfiguration(() -> config);
        this.phasesMenu =
                new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPhasesMenu(() -> config, configuration.messages());
        panel.usePhasesMenu(phasesMenu);
        var bars = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockProgressBars(
                () -> config, scheduler, configuration.messages());
        panel.useBars(bars);
        listener.useBars(bars);
        this.blockLines = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBlockLines(
                () -> config, configuration.messages(), bedrockPlayer(), lines);
        listener.useBlockLines(blockLines);
        this.biomes = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBiomes(
                () -> config, scheduler, persistence.islandStoragePort()::findIslandById);
        listener.useBiomes(biomes);
        listener.useContributions(contributions);
        panel.useContributions(contributions);
        // Every OneBlock island into memory before players break anything, so no break is a query on
        // the region's thread. A break before it finishes reads its island once.
        scheduler.async(() -> {
            try {
                int count = service.prime();
                contributions.prime();
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

    /** How a phase gives its island its biome. */
    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBiomes biomes() {
        return biomes;
    }

    /** How the operator's pool entries are put into the world. */
    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws draws() {
        return draws;
    }

    /** Whether the operator lets islands be OneBlock islands, which is when the listener is registered. */
    public boolean enabled() {
        return enabledAtStart;
    }

    /**
     * Reads the OneBlock files again, for an operator's reload, and goes on with them: the phases, their blocks,
     * moments, lists, gates, biomes and previews. Each island keeps its count and its picks. What only a restart
     * changes is said, and so is every line that could not be read as written, with its file and line. A module
     * file that will not parse leaves the files in use as they were.
     *
     * @return what the operator should read, empty when everything was read as written
     */
    public java.util.List<String> reload(java.nio.file.Path dataDir) {
        var read = com.uxplima.uxmskyblock.bukkit.config.OneBlockLoadReport.read(dataDir);
        java.util.List<String> said = new java.util.ArrayList<>(read.problems());
        if (read.config().isEmpty()) {
            return said;
        }
        OneBlockConfiguration now = read.config().get();
        OneBlockConfiguration was = config;
        if (now.enabled() != enabledAtStart) {
            said.add("modules/oneblock.conf: enabled changes at the next restart.");
        }
        if (!now.saveInterval().equals(was.saveInterval())) {
            said.add("modules/oneblock.conf: save-interval changes at the next restart.");
        }
        this.config = now;
        service.usePhases(now.phases());
        service.lookAhead(now.creatureWarning());
        return said;
    }

    /**
     * The creation actions OneBlock adds: the one that sets an island's block, while the operator lets
     * islands be OneBlock islands, and none otherwise, so a OneBlock preset is not offered.
     */
    public java.util.List<
                    com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                            com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
            startActions() {
        if (!enabledAtStart) {
            return java.util.List.of();
        }
        return java.util.List.of(
                new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockStart(service, draws, biomes, contributions));
    }

    /**
     * The panel behind {@code /is oneblock} and the OneBlock placeholders, drawn from the operator's menu
     * file, or nothing while the operator does not let islands be OneBlock islands.
     */
    public com.uxplima.uxmskyblock.bukkit.oneblock.@org.jspecify.annotations.Nullable OneBlockPanel panel(
            com.uxplima.uxmskyblock.bukkit.menu.@org.jspecify.annotations.Nullable SkyblockMenuEngine engine) {
        if (!enabledAtStart) {
            return null;
        }
        panel.useMenuEngine(engine);
        branches.useMenuEngine(engine);
        phasesMenu.useMenuEngine(engine);
        return panel;
    }

    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener listener() {
        return listener;
    }

    /** The line over each island's block, which goes with an island that is gone. */
    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockBlockLines blockLines() {
        return blockLines;
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
        var funds = new com.uxplima.uxmskyblock.bukkit.requirement.BankFunds(
                bankPort, bank, access, activeProfile, node, "OneBlock phase");
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
        contributions.flush();
    }
}
