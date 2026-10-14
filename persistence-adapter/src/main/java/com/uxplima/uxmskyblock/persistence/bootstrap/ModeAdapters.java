package com.uxplima.uxmskyblock.persistence.bootstrap;

import com.uxplima.uxmlib.storage.sql.Database;

/**
 * The adapters of the game modes and of what only a mode reads: OneBlock, ChunkBlock, AcidIsland, Boxed, Poseidon,
 * StrangerRealms, Parkour, Brix, TradeWinds, the island edges, the visit fee and the mode standings. Each is built on
 * the database alone, once {@link PersistenceBootstrap}, which extends this, has run the migrations. Split out of it
 * so neither file is over the size the standards draw a line at; every caller still asks the bootstrap.
 */
abstract class ModeAdapters {

    private final com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockProgressAdapter oneBlockProgressAdapter;
    private final com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockRewardAdapter oneBlockRewardAdapter;
    private final com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockWaitAdapter oneBlockWaitAdapter;
    private final com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockBranchAdapter oneBlockBranchAdapter;
    private final com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockContributionAdapter
            oneBlockContributionAdapter;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketPricesAdapter marketPricesAdapter;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlContractsAdapter contractsAdapter;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyageEventsAdapter voyageEventsAdapter;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlPlunderAdapter plunderAdapter;
    private final com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkTerritoryAdapter chunkTerritoryAdapter;
    private final com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkPaymentAdapter chunkPaymentAdapter;
    private final com.uxplima.uxmskyblock.persistence.boundary.SqlIslandEdgeAdapter islandEdgeAdapter;
    private final com.uxplima.uxmskyblock.persistence.acid.SqlAcidIslandsAdapter acidIslandsAdapter;
    private final com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedIslandsAdapter boxedIslandsAdapter;
    private final com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedLandAdapter boxedLandAdapter;
    private final com.uxplima.uxmskyblock.persistence.boxed.SqlBoxOffsetsAdapter boxOffsetsAdapter;
    private final com.uxplima.uxmskyblock.persistence.boxed.SqlBoxSidesAdapter boxSidesAdapter;
    private final com.uxplima.uxmskyblock.persistence.poseidon.SqlPoseidonIslandsAdapter poseidonIslandsAdapter;
    private final com.uxplima.uxmskyblock.persistence.stranger.SqlStrangerRealmsAdapter strangerRealmsAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourGhostsAdapter parkourGhostsAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourSplitsAdapter parkourSplitsAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourRewardsAdapter parkourRewardsAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourTagsAdapter parkourTagsAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourDirectoryAdapter parkourDirectoryAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourSeasonsAdapter parkourSeasonsAdapter;
    private final com.uxplima.uxmskyblock.persistence.visit.SqlVisitFeeAdapter visitFeeAdapter;
    private final com.uxplima.uxmskyblock.persistence.parkour.SqlParkourAdapter parkourAdapter;
    private final com.uxplima.uxmskyblock.persistence.leaderboard.SqlModeStandingsAdapter modeStandingsAdapter;
    private final com.uxplima.uxmskyblock.persistence.brix.SqlBrixPlotsAdapter brixPlotsAdapter;
    private final com.uxplima.uxmskyblock.persistence.brix.SqlBrixSkiesAdapter brixSkiesAdapter;
    private final com.uxplima.uxmskyblock.persistence.brix.SqlBrixContestsAdapter brixContestsAdapter;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter vesselsAdapter;
    private final com.uxplima.uxmskyblock.persistence.inventory.VesselCargoJournalAdapter cargoJournal;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyagesAdapter voyages;
    private final com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketOrdersAdapter marketOrders;

    ModeAdapters(Database database) {
        this.oneBlockProgressAdapter =
                new com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockProgressAdapter(database.dataSource());
        this.oneBlockRewardAdapter =
                new com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockRewardAdapter(database.dataSource());
        this.oneBlockWaitAdapter =
                new com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockWaitAdapter(database.dataSource());
        this.oneBlockBranchAdapter =
                new com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockBranchAdapter(database.dataSource());
        this.oneBlockContributionAdapter =
                new com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockContributionAdapter(database.dataSource());
        this.marketPricesAdapter =
                new com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketPricesAdapter(database.dataSource());
        this.contractsAdapter = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlContractsAdapter(database);
        this.voyageEventsAdapter = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyageEventsAdapter(database);
        this.plunderAdapter = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlPlunderAdapter(database);
        this.chunkTerritoryAdapter =
                new com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkTerritoryAdapter(database.dataSource());
        this.chunkPaymentAdapter = new com.uxplima.uxmskyblock.persistence.chunkblock.SqlChunkPaymentAdapter(database);
        this.islandEdgeAdapter = new com.uxplima.uxmskyblock.persistence.boundary.SqlIslandEdgeAdapter(database);
        this.acidIslandsAdapter =
                new com.uxplima.uxmskyblock.persistence.acid.SqlAcidIslandsAdapter(database.dataSource());
        this.boxedIslandsAdapter =
                new com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedIslandsAdapter(database.dataSource());
        this.boxedLandAdapter =
                new com.uxplima.uxmskyblock.persistence.boxed.SqlBoxedLandAdapter(database.dataSource());
        this.boxOffsetsAdapter =
                new com.uxplima.uxmskyblock.persistence.boxed.SqlBoxOffsetsAdapter(database.dataSource());
        this.boxSidesAdapter = new com.uxplima.uxmskyblock.persistence.boxed.SqlBoxSidesAdapter(database.dataSource());
        this.poseidonIslandsAdapter =
                new com.uxplima.uxmskyblock.persistence.poseidon.SqlPoseidonIslandsAdapter(database.dataSource());
        this.strangerRealmsAdapter =
                new com.uxplima.uxmskyblock.persistence.stranger.SqlStrangerRealmsAdapter(database.dataSource());
        this.parkourGhostsAdapter =
                new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourGhostsAdapter(database.dataSource());
        this.parkourSplitsAdapter =
                new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourSplitsAdapter(database.dataSource());
        this.parkourRewardsAdapter =
                new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourRewardsAdapter(database.dataSource());
        this.parkourTagsAdapter =
                new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourTagsAdapter(database.dataSource());
        this.parkourDirectoryAdapter =
                new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourDirectoryAdapter(database.dataSource());
        this.parkourSeasonsAdapter =
                new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourSeasonsAdapter(database.dataSource());
        this.visitFeeAdapter = new com.uxplima.uxmskyblock.persistence.visit.SqlVisitFeeAdapter(database.dataSource());
        this.parkourAdapter = new com.uxplima.uxmskyblock.persistence.parkour.SqlParkourAdapter(database.dataSource());
        this.modeStandingsAdapter =
                new com.uxplima.uxmskyblock.persistence.leaderboard.SqlModeStandingsAdapter(database.dataSource());
        this.brixPlotsAdapter = new com.uxplima.uxmskyblock.persistence.brix.SqlBrixPlotsAdapter(database.dataSource());
        this.brixSkiesAdapter = new com.uxplima.uxmskyblock.persistence.brix.SqlBrixSkiesAdapter(database.dataSource());
        this.brixContestsAdapter =
                new com.uxplima.uxmskyblock.persistence.brix.SqlBrixContestsAdapter(database.dataSource());
        this.vesselsAdapter =
                new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter(database.dataSource());
        this.cargoJournal = new com.uxplima.uxmskyblock.persistence.inventory.VesselCargoJournalAdapter(database);
        this.voyages = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlVoyagesAdapter(database);
        this.marketOrders = new com.uxplima.uxmskyblock.persistence.tradewinds.SqlMarketOrdersAdapter(database);
    }
    /** Where each OneBlock island's block stands and how often it has been broken. */
    public com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort oneBlockProgressPort() {
        return oneBlockProgressAdapter;
    }
    /** What each OneBlock island has been paid, so a reward is paid once in each pass through the phases. */
    public com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort oneBlockRewardPort() {
        return oneBlockRewardAdapter;
    }
    /** Which OneBlock islands wait before a phase, for a time or for its requirements. */
    public com.uxplima.uxmskyblock.core.application.oneblock.OneBlockWaitPort oneBlockWaitPort() {
        return oneBlockWaitAdapter;
    }
    /** The phase each OneBlock island picked to follow one that branches. */
    public com.uxplima.uxmskyblock.core.application.oneblock.OneBlockBranchPort oneBlockBranchPort() {
        return oneBlockBranchAdapter;
    }
    /** How many blocks each player broke on each OneBlock island. */
    public com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributionPort oneBlockContributionPort() {
        return oneBlockContributionAdapter;
    }
    /** The push trade left on each TradeWinds price. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.MarketPricesPort marketPricesPort() {
        return marketPricesAdapter;
    }
    /** The contracts each TradeWinds vessel took. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort contractsPort() {
        return contractsAdapter;
    }
    /** The events each TradeWinds voyage holds. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort voyageEventsPort() {
        return voyageEventsAdapter;
    }
    /** Every TradeWinds plunder at sea. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort plunderPort() {
        return plunderAdapter;
    }
    /** The chunks each ChunkBlock island has open in the overworld. */
    public com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort chunkTerritoryPort() {
        return chunkTerritoryAdapter;
    }
    /** What has been paid toward each closed chunk of a ChunkBlock island, and each island's chunk points. */
    public com.uxplima.uxmskyblock.core.application.chunkblock.ChunkPaymentPort chunkPaymentPort() {
        return chunkPaymentAdapter;
    }
    /** How each island chose to have its edge drawn. */
    public com.uxplima.uxmskyblock.core.application.boundary.IslandEdgePort islandEdgePort() {
        return islandEdgeAdapter;
    }
    /** Which islands are AcidIsland islands, and where their seas stand. */
    public com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort acidIslandsPort() {
        return acidIslandsAdapter;
    }
    /** Which islands are Boxed islands, and the advancements each has earned. */
    public com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort boxedIslandsPort() {
        return boxedIslandsAdapter;
    }
    /** How far each Boxed island played on the template's land has it copied. */
    public com.uxplima.uxmskyblock.core.application.boxed.BoxedLandPort boxedLandPort() {
        return boxedLandAdapter;
    }
    /** How far each moved Boxed box lies from its island's centre. */
    public com.uxplima.uxmskyblock.core.application.boxed.BoxOffsetsPort boxOffsetsPort() {
        return boxOffsetsAdapter;
    }
    /** The side each Boxed island's box grows toward. */
    public com.uxplima.uxmskyblock.core.application.boxed.BoxSidesPort boxSidesPort() {
        return boxSidesAdapter;
    }
    /** Which islands are Poseidon islands. */
    public com.uxplima.uxmskyblock.core.application.poseidon.PoseidonIslandsPort poseidonIslandsPort() {
        return poseidonIslandsAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort strangerRealmsPort() {
        return strangerRealmsAdapter;
    }
    /** Each runner's best run on each course, as its ghost replays it. */
    public com.uxplima.uxmskyblock.core.application.parkour.ParkourGhostsPort parkourGhostsPort() {
        return parkourGhostsAdapter;
    }
    /** The split times of each runner's best run on each course. */
    public com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort parkourSplitsPort() {
        return parkourSplitsAdapter;
    }
    /** When each runner was last paid each reward list of each course. */
    public com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort parkourRewardsPort() {
        return parkourRewardsAdapter;
    }
    /** Each course's tags and the stars its runners give it. */
    public com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort parkourTagsPort() {
        return parkourTagsAdapter;
    }
    /** The courses as the directory lists them. */
    public com.uxplima.uxmskyblock.core.application.parkour.ParkourDirectoryPort parkourDirectoryPort() {
        return parkourDirectoryAdapter;
    }
    /** Each runner's best time on each course in each season. */
    public com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonsPort parkourSeasonsPort() {
        return parkourSeasonsAdapter;
    }
    /** The fee each island charges a visitor. */
    public com.uxplima.uxmskyblock.core.application.visit.VisitFeeStoragePort visitFeeStoragePort() {
        return visitFeeAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.parkour.ParkourPort parkourPort() {
        return parkourAdapter;
    }
    /** Where the islands of each mode stand, for the boards. */
    public com.uxplima.uxmskyblock.core.application.leaderboard.ModeStandingsPort modeStandingsPort() {
        return modeStandingsAdapter;
    }
    /** Which islands are Brix plots. */
    public com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort brixPlotsPort() {
        return brixPlotsAdapter;
    }
    /** The time and weather each Brix plot's team set. */
    public com.uxplima.uxmskyblock.core.application.brix.BrixSkiesPort brixSkiesPort() {
        return brixSkiesAdapter;
    }
    /** The Brix build contests, their entries, votes and payouts. */
    public com.uxplima.uxmskyblock.core.application.brix.BrixContestsPort brixContestsPort() {
        return brixContestsAdapter;
    }
    /** Which islands are TradeWinds vessels. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort vesselsPort() {
        return vesselsAdapter;
    }
    /** The journal of moves between a player's inventory and a vessel's cargo hold. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort cargoJournalPort() {
        return cargoJournal;
    }
    /** Where each TradeWinds vessel is bound. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.VoyagesPort voyagesPort() {
        return voyages;
    }
    /** The orders placed at the TradeWinds ports' markets. */
    public com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort marketOrdersPort() {
        return marketOrders;
    }
}
