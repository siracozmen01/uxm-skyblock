package com.uxplima.uxmskyblock.bukkit.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;

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
 * handed in by whoever pays; one paid from the island comes out of its bank.
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

    /** The line {@code written} says, or nothing for one that says none. */
    public static Optional<ChunkRequirement> parse(String written) {
        String[] words = written.strip().split("\\s+");
        if (words.length < 2) {
            return Optional.empty();
        }
        try {
            return switch (words[0].toLowerCase(Locale.ROOT)) {
                case "money" ->
                    words.length <= 3
                            ? source(words, 2).map(from -> new ChunkRequirement.Money(minor(words[1]), from))
                            : Optional.empty();
                case "currency" ->
                    words.length >= 3 && words.length <= 4
                            ? source(words, 3)
                                    .map(from -> new ChunkRequirement.Currency(
                                            words[1].toLowerCase(Locale.ROOT), Long.parseLong(words[2]), from))
                            : Optional.empty();
                case "item", "items" ->
                    words.length == 3
                            ? Optional.of(new ChunkRequirement.Items(words[1], Long.parseLong(words[2])))
                            : Optional.empty();
                case "points" ->
                    words.length == 2
                            ? Optional.of(new ChunkRequirement.Points(Long.parseLong(words[1])))
                            : Optional.empty();
                case "level" ->
                    words.length == 2
                            ? Optional.of(new ChunkRequirement.Level(
                                    words[1].equalsIgnoreCase("ladder")
                                            ? ChunkRequirement.Level.LADDER
                                            : Long.parseLong(words[1])))
                            : Optional.empty();
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException | ArithmeticException wrong) {
            return Optional.empty();
        }
    }

    /** Whose purse the line at {@code index} names, the player's when it names none. */
    private static Optional<ChunkRequirement.Source> source(String[] words, int index) {
        if (words.length <= index) {
            return Optional.of(ChunkRequirement.Source.PLAYER);
        }
        return switch (words[index].toLowerCase(Locale.ROOT)) {
            case "player" -> Optional.of(ChunkRequirement.Source.PLAYER);
            case "island", "bank" -> Optional.of(ChunkRequirement.Source.ISLAND);
            default -> Optional.empty();
        };
    }

    /** {@code 2500.5} as 250050 minor units. */
    static long minor(String written) {
        return new BigDecimal(written)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
