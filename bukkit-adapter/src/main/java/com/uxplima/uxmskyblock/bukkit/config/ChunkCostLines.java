package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import com.uxplima.uxmskyblock.core.domain.requirement.RequirementLines;

/**
 * The lines of what a ChunkBlock chunk costs, as {@code modules/chunkblock.conf} writes them:
 *
 * <pre>
 * money 50000 [player|island]
 * currency points 30 [player|island]
 * item oak_log 96
 * points 2
 * level 10            (or: level ladder)
 * </pre>
 *
 * Money is written in whole units, cents after a point, and kept in minor units. A line paid from the player is
 * handed in by whoever pays; one paid from the island comes out of its bank. The lines are those of
 * {@link RequirementLines}, so a line may say {@code take} in front.
 */
public final class ChunkCostLines {

    private static final Logger LOGGER = Logger.getLogger(ChunkCostLines.class.getName());

    private ChunkCostLines() {}

    /** Every line that reads, in order. One that does not is left out, and the console says where. */
    public static List<ChunkRequirement> read(String where, List<String> written) {
        List<ChunkRequirement> lines = new ArrayList<>();
        for (String line : written) {
            Optional<ChunkRequirement> read = parse(line);
            if (read.isPresent()) {
                lines.add(read.get());
            } else {
                LOGGER.warning(() -> "modules/chunkblock.conf: the line \"" + line + "\" under " + where
                        + " is no cost line, so it is left out.");
            }
        }
        return List.copyOf(lines);
    }

    /**
     * The line {@code written} says, or nothing for one that says none. The line is read by the grammar every mode
     * shares; a chunk is paid for, so a line to hand over reads as its price and a level as the level it waits
     * for. A line only to have, a permission or a wait is no price.
     */
    public static Optional<ChunkRequirement> parse(String written) {
        return RequirementLines.parse(written).flatMap(ChunkCostLines::price);
    }

    private static Optional<ChunkRequirement> price(Requirement line) {
        if (line instanceof Requirement.Level level) {
            return Optional.of(new ChunkRequirement.Level(level.level()));
        }
        if (line.mode() != Requirement.Mode.TAKE) {
            return Optional.empty();
        }
        return switch (line) {
            case Requirement.Money money ->
                Optional.of(new ChunkRequirement.Money(money.minor(), source(money.from())));
            case Requirement.Currency currency ->
                Optional.of(new ChunkRequirement.Currency(currency.id(), currency.amount(), source(currency.from())));
            case Requirement.Items items -> Optional.of(new ChunkRequirement.Items(items.material(), items.amount()));
            case Requirement.Points points -> Optional.of(new ChunkRequirement.Points(points.amount()));
            default -> Optional.empty();
        };
    }

    private static ChunkRequirement.Source source(Requirement.Source from) {
        return from == Requirement.Source.ISLAND ? ChunkRequirement.Source.ISLAND : ChunkRequirement.Source.PLAYER;
    }
}
