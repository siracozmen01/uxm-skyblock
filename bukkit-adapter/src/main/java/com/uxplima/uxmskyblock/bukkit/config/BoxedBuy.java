package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import com.uxplima.uxmskyblock.core.domain.requirement.RequirementLines;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Growth a Boxed island buys, as {@code buy} of {@code modules/boxed.conf} writes it.
 *
 * @param blocks how far one purchase grows the box
 * @param lines what one purchase asks: money, a currency or items had or taken, an island level or a permission had;
 *     none turns buying off
 * @param most how many times an island may buy, 0 for no end
 */
public record BoxedBuy(int blocks, List<Requirement> lines, int most) {

    /** A block for 2500 of the buyer's money, at most 50 times. */
    public static final BoxedBuy SHIPPED = new BoxedBuy(
            1, List.of(new Requirement.Money(250_000, Requirement.Source.PLAYER, Requirement.Mode.TAKE)), 50);

    /** Buying turned off. */
    public static final BoxedBuy OFF = new BoxedBuy(1, List.of(), 0);

    private static final Logger LOGGER = Logger.getLogger(BoxedBuy.class.getName());

    public BoxedBuy {
        lines = List.copyOf(lines);
        if (blocks < 1 || blocks > 400) {
            throw new IllegalArgumentException("a purchase grows the box from 1 to 400 blocks: " + blocks);
        }
        if (most < 0) {
            throw new IllegalArgumentException("most is how many purchases, 0 for no end: " + most);
        }
        for (Requirement line : lines) {
            if (line instanceof Requirement.Points || line instanceof Requirement.Wait) {
                throw new IllegalArgumentException("a purchase asks no points and no wait");
            }
        }
    }

    /** Whether islands may buy growth at all. */
    public boolean on() {
        return !lines.isEmpty();
    }

    /** The purchase {@code node} writes, or the shipped one; one that cannot be turns buying off, said. */
    static BoxedBuy read(ConfigurationNode node) {
        if (node.virtual()) {
            return SHIPPED;
        }
        try {
            List<Requirement> lines = new ArrayList<>();
            for (ConfigurationNode written : node.node("lines").childrenList()) {
                String line = Objects.requireNonNullElse(written.getString(), "");
                Optional<Requirement> read = RequirementLines.parse(line);
                if (read.isEmpty()) {
                    throw new IllegalArgumentException("the line \"" + line + "\" says nothing this file knows");
                }
                lines.add(read.get());
            }
            return new BoxedBuy(
                    node.node("blocks").getInt(SHIPPED.blocks()),
                    lines,
                    node.node("most").getInt(SHIPPED.most()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/boxed.conf buy: " + e.getMessage() + ". Buying growth is off.");
            return OFF;
        }
    }
}
