package com.uxplima.uxmskyblock.core.domain.requirement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a requirement line, the one grammar every mode's files write:
 *
 * <pre>
 * [have|take] money 50000 [player|island]
 * [have|take] currency points 30 [player|island]
 * [have|take] item oak_log 96
 * [have|take] points 2
 * [have] level 10           (or: level ladder)
 * [have] permission some.node
 * [have] wait 1h30m
 * </pre>
 *
 * A line that says neither is taken when it names something to hand over, money, a currency, items or points,
 * and had when it names a level, a permission or a wait, which nobody can hand over. Money is written in whole
 * units with cents after a point, and kept in minor units. A line from no purse is the player's.
 */
public final class RequirementLines {

    private static final Pattern DURATION = Pattern.compile("(\\d+)([dhms])");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private RequirementLines() {}

    /** The line {@code written} says, or nothing for one that says none. */
    public static Optional<Requirement> parse(String written) {
        String[] words = SPACES.split(written.strip(), -1);
        if (words.length == 0 || words[0].isEmpty()) {
            return Optional.empty();
        }
        Requirement.Mode said =
                switch (words[0].toLowerCase(Locale.ROOT)) {
                    case "have" -> Requirement.Mode.HAVE;
                    case "take" -> Requirement.Mode.TAKE;
                    default -> null;
                };
        String[] rest = said == null ? words : Arrays.copyOfRange(words, 1, words.length);
        if (rest.length < 2) {
            return Optional.empty();
        }
        try {
            return read(rest, said);
        } catch (IllegalArgumentException | ArithmeticException wrong) {
            return Optional.empty();
        }
    }

    private static Optional<Requirement> read(
            String[] words, Requirement.@org.jspecify.annotations.Nullable Mode said) {
        Requirement.Mode handed = said == null ? Requirement.Mode.TAKE : said;
        return switch (words[0].toLowerCase(Locale.ROOT)) {
            case "money" ->
                words.length <= 3
                        ? source(words, 2).map(from -> new Requirement.Money(minor(words[1]), from, handed))
                        : Optional.empty();
            case "currency" ->
                words.length >= 3 && words.length <= 4
                        ? source(words, 3)
                                .map(from -> new Requirement.Currency(words[1], Long.parseLong(words[2]), from, handed))
                        : Optional.empty();
            case "item", "items" ->
                words.length == 3
                        ? Optional.of(new Requirement.Items(words[1], Long.parseLong(words[2]), handed))
                        : Optional.empty();
            case "points" ->
                words.length == 2
                        ? Optional.of(new Requirement.Points(Long.parseLong(words[1]), handed))
                        : Optional.empty();
            case "level" ->
                words.length == 2 && said != Requirement.Mode.TAKE
                        ? Optional.of(new Requirement.Level(
                                words[1].equalsIgnoreCase("ladder")
                                        ? Requirement.Level.LADDER
                                        : Long.parseLong(words[1])))
                        : Optional.empty();
            case "permission" ->
                words.length == 2 && said != Requirement.Mode.TAKE
                        ? Optional.of(new Requirement.Permission(words[1]))
                        : Optional.empty();
            case "wait" ->
                words.length == 2 && said != Requirement.Mode.TAKE
                        ? duration(words[1]).map(Requirement.Wait::new)
                        : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** Whose purse the word at {@code index} names, the player's when there is none. */
    private static Optional<Requirement.Source> source(String[] words, int index) {
        if (words.length <= index) {
            return Optional.of(Requirement.Source.PLAYER);
        }
        return switch (words[index].toLowerCase(Locale.ROOT)) {
            case "player" -> Optional.of(Requirement.Source.PLAYER);
            case "island", "bank" -> Optional.of(Requirement.Source.ISLAND);
            default -> Optional.empty();
        };
    }

    /** {@code 2500.5} as 250050 minor units. */
    public static long minor(String written) {
        return new BigDecimal(written)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** {@code 1h30m} as ninety minutes; days, hours, minutes and seconds, in any order, each once or more. */
    public static Optional<Duration> duration(String written) {
        String text = written.strip().toLowerCase(Locale.ROOT);
        Matcher parts = DURATION.matcher(text);
        Duration total = Duration.ZERO;
        int end = 0;
        while (parts.find()) {
            if (parts.start() != end) {
                return Optional.empty();
            }
            long amount = Long.parseLong(parts.group(1));
            total = total.plus(
                    switch (parts.group(2)) {
                        case "d" -> Duration.ofDays(amount);
                        case "h" -> Duration.ofHours(amount);
                        case "m" -> Duration.ofMinutes(amount);
                        default -> Duration.ofSeconds(amount);
                    });
            end = parts.end();
        }
        return end == text.length() && end > 0 ? Optional.of(total) : Optional.empty();
    }
}
