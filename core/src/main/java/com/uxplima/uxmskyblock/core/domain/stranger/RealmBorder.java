package com.uxplima.uxmskyblock.core.domain.stranger;

/**
 * How wide the border of the StrangerRealms land is: as wide as it takes to hold the furthest island
 * with room around it, and never narrower or wider than the operator's limits. It grows as islands are
 * made anywhere on the network, and, as an option, by a width for each player on the network.
 *
 * @param margin the room kept beyond the furthest island's edge, in blocks
 * @param minimum the narrowest the border is, from side to side
 * @param maximum the widest the border is, from side to side
 * @param perPlayer how much wider the border is for each player on the network, 0 for a border by islands alone
 */
public record RealmBorder(int margin, int minimum, int maximum, int perPlayer) {

    public static final RealmBorder SHIPPED = new RealmBorder(512, 2048, 29_999_984, 0);

    /** A border by islands alone. */
    public RealmBorder(int margin, int minimum, int maximum) {
        this(margin, minimum, maximum, 0);
    }

    public RealmBorder {
        if (margin < 0 || minimum < 1 || maximum < minimum || perPlayer < 0) {
            throw new IllegalArgumentException(
                    "the margin must not be negative, and the minimum must be above 0 and not above the maximum");
        }
    }

    /** The width, side to side, that holds an island reaching {@code reach} from the centre. */
    public double size(int reach) {
        return size(reach, 0);
    }

    /**
     * The width that holds an island reaching {@code reach} from the centre, wider by {@link #perPlayer()} for each
     * of {@code online} players. Players leaving narrow it again, never below what holds the islands.
     */
    public double size(int reach, int online) {
        long wanted = 2L * ((long) Math.max(0, reach) + margin) + (long) perPlayer * Math.max(0, online);
        return Math.clamp(wanted, minimum, maximum);
    }

    /** Whether the players on the network widen the border at all. */
    public boolean byPlayers() {
        return perPlayer > 0;
    }
}
