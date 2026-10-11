package com.uxplima.uxmskyblock.bukkit.wall;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/walls.conf}: the styles a wall can be drawn in, which of them stands around a ChunkBlock island's
 * open chunks, and how high a wall reaches.
 *
 * @param styles every style, by the name the operator gave it
 * @param chunkBlock the style around a ChunkBlock island's open chunks, blank for no wall
 * @param bottom the height a wall starts at
 * @param top the height a wall ends at, above {@code bottom}
 * @param slice how tall each piece of a wall is: a client draws a piece only while it is near enough
 * @param bedrock how a Bedrock player, whose client draws no wall, is shown where it stands
 * @param island how an island that never chose draws its edge: {@link #BORDER}, {@link #NONE} or a style's id
 */
public record WallConfiguration(
        Map<String, WallStyle> styles,
        String chunkBlock,
        int bottom,
        int top,
        int slice,
        Bedrock bedrock,
        String island) {

    /** An island edged by the square border a client draws, in the colour each player picks. */
    public static final String BORDER = "border";

    /** An island whose edge is not drawn at all. */
    public static final String NONE = "none";

    /**
     * The edge as a Bedrock player sees it: a line of dust along every wall near them, shown to them alone.
     *
     * @param rgb the colour of the dust
     * @param spacing how far apart two specks stand along the edge, in blocks
     * @param reach how far from the player the edge is drawn, in blocks
     */
    public record Bedrock(boolean enabled, int rgb, double spacing, int reach) {

        /** The edge out of the box: the mist's grey, a speck a block, twelve blocks around. */
        public static final Bedrock SHIPPED = new Bedrock(true, 0x8A93A1, 1.0, 12);

        public Bedrock {
            if (spacing < 0.25) {
                throw new IllegalArgumentException("two specks stand at least a quarter block apart");
            }
            if (reach < 1 || reach > 48) {
                throw new IllegalArgumentException("the edge is drawn between 1 and 48 blocks around a player");
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(WallConfiguration.class.getName());

    /** The grey a player sees through, the style a ChunkBlock island is walled in out of the box. */
    static final WallStyle MIST = WallStyle.colour("mist", 0x8A93A1, 22);

    public WallConfiguration {
        styles = Map.copyOf(Objects.requireNonNull(styles, "styles must not be null"));
        chunkBlock = Objects.requireNonNull(chunkBlock, "chunkBlock must not be null")
                .strip();
        if (top <= bottom) {
            throw new IllegalArgumentException("a wall ends above where it starts");
        }
        if (slice < 1) {
            throw new IllegalArgumentException("a piece of wall is at least one block tall");
        }
        Objects.requireNonNull(bedrock, "bedrock must not be null");
        island = Objects.requireNonNull(island, "island must not be null").strip();
        if (!edge(island, styles)) {
            throw new IllegalArgumentException("an island's edge is border, none or a style's id, not " + island);
        }
    }

    /** Walls whose Bedrock edge is the shipped one, and whose islands keep the client's border. */
    public WallConfiguration(Map<String, WallStyle> styles, String chunkBlock, int bottom, int top, int slice) {
        this(styles, chunkBlock, bottom, top, slice, Bedrock.SHIPPED, BORDER);
    }

    /** Whether {@code choice} is a way an island's edge can be drawn among {@code styles}. */
    private static boolean edge(String choice, Map<String, WallStyle> styles) {
        return BORDER.equals(choice) || NONE.equals(choice) || styles.containsKey(choice);
    }

    /** Whether {@code choice} is a way an island's edge can be drawn here. */
    public boolean edge(String choice) {
        return edge(choice, styles);
    }

    /** Every way an island's edge can be drawn: the client's border, each style by its id, and none. */
    public java.util.List<String> edges() {
        java.util.List<String> edges = new java.util.ArrayList<>();
        edges.add(BORDER);
        styles.keySet().stream().sorted().forEach(edges::add);
        edges.add(NONE);
        return java.util.List.copyOf(edges);
    }

    public static WallConfiguration defaultConfiguration() {
        return new WallConfiguration(Map.of(MIST.id(), MIST), MIST.id(), -64, 320, 64);
    }

    /** The style around a ChunkBlock island's open chunks, or nothing when the operator wants no wall. */
    public Optional<WallStyle> chunkBlockStyle() {
        return Optional.ofNullable(styles.get(chunkBlock));
    }

    /** Reads the file. A style the file gets wrong is left out, and the console says so. */
    public static WallConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        ConfigurationNode walls = root.node("walls");
        WallConfiguration shipped = defaultConfiguration();
        Map<String, WallStyle> styles = new LinkedHashMap<>();
        if (walls.node("styles").virtual()) {
            styles.putAll(shipped.styles());
        }
        for (var entry : walls.node("styles").childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).strip();
            styleOf(id, entry.getValue()).ifPresent(style -> styles.put(id, style));
        }
        String chunkBlock =
                walls.node("chunkblock").getString(shipped.chunkBlock()).strip();
        if (!chunkBlock.isEmpty() && !styles.containsKey(chunkBlock)) {
            LOGGER.warning(() -> "modules/walls.conf: chunkblock names the style '" + chunkBlock
                    + "', which the styles do not hold, so a ChunkBlock island stands no wall.");
        }
        int bottom = walls.node("bottom").getInt(shipped.bottom());
        int top = walls.node("top").getInt(shipped.top());
        int slice = walls.node("slice").getInt(shipped.slice());
        Bedrock bedrock = bedrockOf(walls.node("bedrock"));
        String island = walls.node("island").getString(BORDER).strip();
        if (!edge(island, styles)) {
            LOGGER.warning(() -> "modules/walls.conf: island names '" + island
                    + "', which is neither border, none nor a style, so islands keep the client's border.");
        }
        String edge = edge(island, styles) ? island : BORDER;
        try {
            return new WallConfiguration(styles, chunkBlock, bottom, top, slice, bedrock, edge);
        } catch (IllegalArgumentException wrong) {
            LOGGER.warning(() -> "modules/walls.conf: " + wrong.getMessage() + ". The shipped heights are used.");
            return new WallConfiguration(
                    styles, chunkBlock, shipped.bottom(), shipped.top(), shipped.slice(), bedrock, edge);
        }
    }

    /** The Bedrock edge the file writes, the shipped one where it writes none or gets one wrong. */
    static Bedrock bedrockOf(ConfigurationNode node) {
        Bedrock shipped = Bedrock.SHIPPED;
        String written = node.node("colour").getString("#8A93A1");
        Optional<Integer> rgb = rgbOf(written);
        if (rgb.isEmpty()) {
            LOGGER.warning(() -> "modules/walls.conf: the Bedrock edge has the colour '" + written
                    + "', which is no #RRGGBB, so the shipped grey is used.");
        }
        try {
            return new Bedrock(
                    node.node("enabled").getBoolean(shipped.enabled()),
                    rgb.orElse(shipped.rgb()),
                    node.node("spacing").getDouble(shipped.spacing()),
                    node.node("reach").getInt(shipped.reach()));
        } catch (IllegalArgumentException wrong) {
            LOGGER.warning(() -> "modules/walls.conf: " + wrong.getMessage() + ". The shipped Bedrock edge is used.");
            return shipped;
        }
    }

    private static Optional<WallStyle> styleOf(String id, ConfigurationNode node) {
        String kind = node.node("kind").getString("colour").strip().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "colour", "color" -> {
                String written =
                        node.node("colour").getString(node.node("color").getString("#8A93A1"));
                Optional<Integer> rgb = rgbOf(written);
                if (rgb.isEmpty()) {
                    LOGGER.warning(() -> "modules/walls.conf: the style " + id + " has the colour '" + written
                            + "', which is no #RRGGBB, so it is left out.");
                    return Optional.empty();
                }
                return Optional.of(
                        WallStyle.colour(id, rgb.get(), node.node("opacity").getInt(22)));
            }
            case "block" -> {
                String block = node.node("block").getString("").strip();
                if (block.isEmpty()) {
                    LOGGER.warning(() -> "modules/walls.conf: the style " + id + " names no block, so it is left out.");
                    return Optional.empty();
                }
                return Optional.of(WallStyle.block(id, block));
            }
            default -> {
                LOGGER.warning(() -> "modules/walls.conf: the style " + id + " is of the kind '" + kind
                        + "', which is neither colour nor block, so it is left out.");
                return Optional.empty();
            }
        }
    }

    /** The colour of {@code #RRGGBB}, or nothing for anything else. */
    static Optional<Integer> rgbOf(String written) {
        String hex = written.strip();
        if (!hex.matches("#[0-9A-Fa-f]{6}")) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(hex.substring(1), 16));
    }
}
