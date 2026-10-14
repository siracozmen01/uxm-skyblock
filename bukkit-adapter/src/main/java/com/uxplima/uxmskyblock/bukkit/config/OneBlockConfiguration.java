package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import com.uxplima.uxmskyblock.core.domain.requirement.RequirementLines;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The OneBlock game mode as the operator wrote it in {@code modules/oneblock.conf} and its phase files.
 *
 * <p>{@code modules/oneblock.conf} lists the phases by name, in order, and each is a file of its own,
 * {@code oneblock/phases/<name>.conf}. Every phase, its icon, its length, what the block turns into and what may
 * appear on it are the file's. A phase still written inside the list, as the files before them were, is read as it
 * stands.
 * A name the server does not know as a block or a creature is left out with a warning, so a typo
 * costs one entry and not the mode; a phase left with no block at all is left out the same way.
 *
 * @param enabled whether islands can be made as OneBlock islands at all
 * @param phases the phases, in order
 * @param saveInterval how often the breaks counted in memory are added to the stored count
 * @param icons each phase's icon, a block or item name, by the phase's name
 * @param entries the pool entries written as more than a name
 * @param moments what happens at exact breaks, by phase, then by how many of the phase's blocks were broken
 * @param lists what each phase runs when it begins, finishes and first finishes, by phase, then by event
 * @param gates what stands before each phase begins, by phase, for a phase that writes anything
 * @param chooseWithin how long an island at the end of a phase that branches has to pick the way on, before the
 *     first is taken
 * @param creatureWarning how many breaks before a hostile creature comes its island is told, nothing for never
 * @param display how a player sees the island's progress: a bar they may switch, and a line over the block
 * @param previews what each phase holds for an island, in the operator's words or a {@code @key} of the catalogue,
 *     shown in the phases menu, by the phase's name, for a phase that writes one
 * @param biomes the biome each phase gives its island when it begins, by the phase's name, for a phase that names one
 */
public record OneBlockConfiguration(
        boolean enabled,
        OneBlockPhases phases,
        java.time.Duration saveInterval,
        Map<String, String> icons,
        OneBlockEntries entries,
        Map<String, Map<Long, OneBlockMoment>> moments,
        Map<String, Map<String, OneBlockLists>> lists,
        Map<String, OneBlockGate> gates,
        Duration chooseWithin,
        int creatureWarning,
        OneBlockDisplay display,
        Map<String, String> previews,
        Map<String, OneBlockBiome> biomes) {

    /** How many breaks ahead a hostile creature is told, when the file names no number. */
    public static final int DEFAULT_CREATURE_WARNING = 5;

    /** How long an island has to pick the way on, when the file names no time. */
    public static final Duration DEFAULT_CHOOSE_WITHIN = Duration.ofSeconds(60);

    /** The icon of a phase whose file names none. */
    public static final String DEFAULT_ICON = "GRASS_BLOCK";

    /**
     * The moments under {@code node}, each keyed by how many of the phase's blocks are broken when it happens, from 0,
     * the phase's first block, to one short of its length. A block or a creature is a name a pool could draw, or an
     * entry of this phase. A moment that cannot be is left out with the reason.
     */
    private static Map<Long, OneBlockMoment> moments(
            String phase,
            long length,
            ConfigurationNode node,
            Map<String, OneBlockEntries.Block> blocks,
            Map<String, OneBlockEntries.Creature> creatures) {
        Map<Long, OneBlockMoment> moments = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String written = String.valueOf(entry.getKey());
            ConfigurationNode value = entry.getValue();
            long at;
            try {
                at = Long.parseLong(written.strip());
            } catch (NumberFormatException notANumber) {
                at = -1;
            }
            if (at < 0 || at >= length) {
                warn(() -> "OneBlock phase '" + phase + "' has a moment at '" + written + "', which is no break"
                        + " from 0 to " + (length - 1) + ". It is left out.");
                continue;
            }
            String block = value.node("block").getString();
            String drawnBlock = block == null
                    ? null
                    : blocks.containsKey(phase + "/" + block)
                            ? phase + "/" + block
                            : OneBlockPhaseReader.drawableBlock(block);
            String creature = value.node("creature").getString();
            String drawnCreature = creature == null
                    ? null
                    : creatures.containsKey(phase + "/" + creature)
                            ? phase + "/" + creature
                            : OneBlockPhaseReader.drawableCreature(creature);
            if ((block != null && drawnBlock == null) || (creature != null && drawnCreature == null)) {
                warn(() -> "OneBlock phase '" + phase + "', moment " + written + ": "
                        + (block != null && drawnBlock == null
                                ? "the block '" + block + "'"
                                : "the creature '" + creature + "'")
                        + " is not one the server or the phase knows. The moment is left out.");
                continue;
            }
            java.time.Duration textTime = OneBlockMoment.TEXT_TIME;
            String writtenTime = value.node("text-time").getString();
            if (writtenTime != null) {
                try {
                    textTime = com.uxplima.uxmlib.common.Durations.parse(writtenTime.strip());
                } catch (RuntimeException unreadable) {
                    warn(() -> "OneBlock phase '" + phase + "', moment " + written + ": text-time '" + writtenTime
                            + "' is no time, so " + OneBlockMoment.TEXT_TIME.toSeconds() + "s is used.");
                }
            }
            try {
                List<String> lines = value.node("actions").getList(String.class, List.of());
                moments.put(
                        at,
                        new OneBlockMoment(
                                drawnBlock,
                                drawnCreature,
                                value.node("loot").getString(),
                                EffectsConfiguration.parseAll("oneblock phase " + phase + ", moment " + written, lines),
                                value.node("text").getString(),
                                textTime));
            } catch (org.spongepowered.configurate.serialize.SerializationException | IllegalArgumentException wrong) {
                warn(() -> "OneBlock phase '" + phase + "', moment " + written + " cannot be: " + wrong.getMessage()
                        + ". It is left out.");
            }
        }
        return moments;
    }

    /** The slots a creature's equipment is written by. */
    public static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "hand", "off-hand");

    /** The name a phase file may have: what the language files key its name by. */
    static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,64}");

    public OneBlockConfiguration(boolean enabled, OneBlockPhases phases, java.time.Duration saveInterval) {
        this(
                enabled,
                phases,
                saveInterval,
                Map.of(),
                OneBlockEntries.NONE,
                Map.of(),
                Map.of(),
                Map.of(),
                DEFAULT_CHOOSE_WITHIN,
                0,
                OneBlockDisplay.SHIPPED,
                Map.of(),
                Map.of());
    }

    /** How often counted breaks are written, when the file names no interval. */
    public static final java.time.Duration DEFAULT_SAVE_INTERVAL = java.time.Duration.ofSeconds(30);

    private static final Logger LOGGER = Logger.getLogger(OneBlockConfiguration.class.getName());

    /** What a load on this thread was told, while {@link #loadReporting} runs it; nothing otherwise. */
    private static final ThreadLocal<List<String>> HEARD = new ThreadLocal<>();

    /** A line of the files that cannot be read as written, kept for the report, or said in the console. */
    static void warn(java.util.function.Supplier<String> said) {
        @org.jspecify.annotations.Nullable List<String> heard = HEARD.get();
        if (heard == null) {
            LOGGER.warning(said);
        } else {
            heard.add(said.get());
        }
    }

    static void warn(String said) {
        warn(() -> said);
    }

    /** The files as read, and every line of them that could not be read as written, in the order met. */
    public record Loaded(OneBlockConfiguration config, List<String> problems) {
        public Loaded {
            Objects.requireNonNull(config, "config must not be null");
            problems = List.copyOf(problems);
        }
    }

    /**
     * The same as {@link #load(ConfigurationNode, java.nio.file.Path)}, with every line it could not read as written
     * kept for the caller to report rather than said in the console.
     */
    public static Loaded loadReporting(
            ConfigurationNode root, java.nio.file.@org.jspecify.annotations.Nullable Path phasesFolder) {
        List<String> heard = new ArrayList<>();
        HEARD.set(heard);
        try {
            return new Loaded(load(root, phasesFolder), heard);
        } finally {
            HEARD.remove();
        }
    }

    public OneBlockConfiguration {
        Objects.requireNonNull(phases, "phases");
        Objects.requireNonNull(saveInterval, "saveInterval");
        if (saveInterval.isNegative() || saveInterval.isZero()) {
            throw new IllegalArgumentException("The save interval must be positive: " + saveInterval);
        }
        icons = Map.copyOf(icons);
        previews = Map.copyOf(previews);
        biomes = Map.copyOf(biomes);
        Objects.requireNonNull(entries, "entries");
        Map<String, Map<Long, OneBlockMoment>> copied = new LinkedHashMap<>();
        moments.forEach((phase, at) -> copied.put(phase, Map.copyOf(at)));
        moments = java.util.Collections.unmodifiableMap(copied);
        Map<String, Map<String, OneBlockLists>> listed = new LinkedHashMap<>();
        lists.forEach((phase, byEvent) -> listed.put(phase, Map.copyOf(byEvent)));
        lists = java.util.Collections.unmodifiableMap(listed);
        gates = Map.copyOf(gates);
        Objects.requireNonNull(chooseWithin, "chooseWithin");
        Objects.requireNonNull(display, "display");
        if (creatureWarning < 0) {
            throw new IllegalArgumentException("A creature is not told after it came: " + creatureWarning);
        }
    }

    /** What stands before the phase {@code phase} begins: nothing, unless its file writes a wait or requirements. */
    public OneBlockGate gateOf(String phase) {
        return gates.getOrDefault(phase, OneBlockGate.OPEN);
    }

    /** What the phase {@code phase} runs on {@code event}, one of {@link OneBlockLists#EVENTS}, if it writes any. */
    public java.util.Optional<OneBlockLists> listsOf(String phase, String event) {
        return java.util.Optional.ofNullable(lists.getOrDefault(phase, Map.of()).get(event))
                .filter(written -> !written.isEmpty());
    }

    /**
     * How often a break of the phase {@code phase} is lucky, when its file writes a lucky break with a chance above
     * nothing, whether or not it writes lists for it.
     */
    public java.util.OptionalDouble luckOf(String phase) {
        OneBlockLists lucky = lists.getOrDefault(phase, Map.of()).get(OneBlockLists.LUCKY);
        return lucky == null || lucky.chance() <= 0
                ? java.util.OptionalDouble.empty()
                : java.util.OptionalDouble.of(lucky.chance());
    }

    /** What happens once {@code broken} blocks of the phase {@code phase} are broken, if anything. */
    public java.util.Optional<OneBlockMoment> momentAt(String phase, long broken) {
        return java.util.Optional.ofNullable(
                moments.getOrDefault(phase, Map.of()).get(broken));
    }

    /** The biome the phase called {@code key} gives its island when it begins, if its file names one. */
    public java.util.Optional<OneBlockBiome> biomeOf(String key) {
        return java.util.Optional.ofNullable(biomes.get(key));
    }

    /** What the phase called {@code key} holds, as its file writes it, if it writes anything. */
    public java.util.Optional<String> previewOf(String key) {
        return java.util.Optional.ofNullable(previews.get(key));
    }

    /** The icon of the phase called {@code key}. */
    public String iconOf(String key) {
        return icons.getOrDefault(key, DEFAULT_ICON);
    }

    /** One phase of plain blocks, for a server whose file holds nothing usable. */
    public static OneBlockConfiguration defaultConfiguration() {
        Map<String, Double> blocks = new LinkedHashMap<>();
        blocks.put(Material.GRASS_BLOCK.name(), 5.0);
        blocks.put(Material.DIRT.name(), 3.0);
        blocks.put(Material.OAK_LOG.name(), 2.0);
        return new OneBlockConfiguration(
                true,
                new OneBlockPhases(
                        List.of(new OneBlockPhase("plains", 500, new WeightedPool(blocks), WeightedPool.empty(), 0)),
                        OneBlockPhases.AfterTheLast.STAY),
                DEFAULT_SAVE_INTERVAL);
    }

    /** The file with its phases written inside the list, and none read from files. */
    public static OneBlockConfiguration load(ConfigurationNode root) {
        return load(root, null);
    }

    /**
     * The file, each phase it names read from {@code phasesFolder}, {@code <name>.conf}. A name with no file, or a
     * file that cannot be read, leaves that phase out with the reason.
     */
    public static OneBlockConfiguration load(
            ConfigurationNode root, java.nio.file.@org.jspecify.annotations.Nullable Path phasesFolder) {
        Objects.requireNonNull(root, "root");
        if (root.virtual() || root.empty()) {
            return defaultConfiguration();
        }
        boolean enabled = root.node("enabled").getBoolean(true);
        OneBlockPhases.AfterTheLast after =
                afterTheLast(root.node("after-last-phase").getString("stay"));
        java.time.Duration saveInterval =
                saveInterval(root.node("save-interval").getString());
        List<OneBlockPhase> phases = new ArrayList<>();
        Map<String, String> icons = new LinkedHashMap<>();
        Map<String, String> previews = new LinkedHashMap<>();
        Map<String, OneBlockBiome> biomes = new LinkedHashMap<>();
        Map<String, OneBlockEntries.Block> blockEntries = new LinkedHashMap<>();
        Map<String, OneBlockEntries.Creature> creatureEntries = new LinkedHashMap<>();
        Map<String, String> tables = new LinkedHashMap<>();
        Map<String, OneBlockGate> gates = new LinkedHashMap<>();
        Map<String, List<String>> next = new LinkedHashMap<>();
        Map<String, Map<Long, OneBlockMoment>> moments = new LinkedHashMap<>();
        Map<String, Map<String, OneBlockLists>> lists = new LinkedHashMap<>();
        // A list, because the phases are gone through in order and a HOCON object keeps none.
        for (ConfigurationNode listed : root.node("phases").childrenList()) {
            ConfigurationNode phase = listed.isMap() ? listed : phaseFile(listed.getString(""), phasesFolder);
            if (phase == null) {
                continue;
            }
            String key = listed.isMap() ? phase.node("name").getString("") : listed.getString("");
            icons.put(key, OneBlockPhaseReader.icon(key, phase.node("icon").getString(DEFAULT_ICON)));
            String preview = phase.node("preview").getString("").strip();
            if (!preview.isEmpty()) {
                previews.put(key, preview);
            }
            OneBlockBiome biome = OneBlockPhaseReader.biome(
                    key,
                    phase.node("biome").getString(""),
                    phase.node("biome-area").getString(""));
            if (biome != null) {
                biomes.put(key, biome);
            }
            Map<String, Double> blocks = OneBlockPhaseReader.blocks(key, phase.node("blocks"), blockEntries);
            if (blocks.isEmpty()) {
                warn(() -> "OneBlock phase '" + key + "' has no block the server knows and is left out.");
                continue;
            }
            try {
                phases.add(new OneBlockPhase(
                        key,
                        phase.node("length").getLong(0),
                        new WeightedPool(blocks),
                        new WeightedPool(OneBlockPhaseReader.creatures(key, phase.node("creatures"), creatureEntries)),
                        phase.node("creature-chance").getDouble(0)));
                lists.put(key, OneBlockPhaseReader.lists(key, phase));
                if (!phase.node("next").virtual()) {
                    List<String> following = new ArrayList<>();
                    for (ConfigurationNode named : phase.node("next").childrenList()) {
                        following.add(named.getString("").strip());
                    }
                    next.put(key, following);
                }
                OneBlockGate gate = OneBlockPhaseReader.gate(key, phase);
                if (!gate.isOpen()) {
                    gates.put(key, gate);
                }
                String table = phase.node("loot").getString("").strip();
                if (!table.isEmpty()) {
                    tables.put(key, table);
                }
                moments.put(
                        key,
                        moments(
                                key,
                                phase.node("length").getLong(0),
                                phase.node("moments"),
                                blockEntries,
                                creatureEntries));
            } catch (IllegalArgumentException unusable) {
                warn(() -> "OneBlock phase '" + key + "' is left out: " + unusable.getMessage());
            }
        }
        if (phases.isEmpty()) {
            warn("modules/oneblock.conf holds no usable phase, so the built in one is used.");
            return new OneBlockConfiguration(enabled, defaultConfiguration().phases(), saveInterval);
        }
        java.util.Set<String> kept = new java.util.HashSet<>();
        phases.forEach(phase -> kept.add(phase.key()));
        Map<String, List<String>> branches = new LinkedHashMap<>();
        next.forEach((from, following) -> {
            List<String> known = new ArrayList<>();
            for (String to : following) {
                if (kept.contains(to)) {
                    known.add(to);
                } else {
                    warn(() -> "OneBlock phase '" + from + "' is followed by '" + to
                            + "', which is no phase in modules/oneblock.conf, and the way is left out.");
                }
            }
            if (kept.contains(from)) {
                branches.put(from, known);
            }
        });
        String within = root.node("choose-within").getString("").strip();
        Duration chooseWithin = within.isEmpty()
                ? DEFAULT_CHOOSE_WITHIN
                : RequirementLines.duration(within).orElseGet(() -> {
                    warn(() -> "choose-within is '" + within + "', which is no time, so "
                            + DEFAULT_CHOOSE_WITHIN.toSeconds() + " seconds is used.");
                    return DEFAULT_CHOOSE_WITHIN;
                });
        int warning = root.node("creature-warning").getInt(DEFAULT_CREATURE_WARNING);
        if (warning < 0) {
            warn(() -> "creature-warning is '" + warning + "', which is below nothing, so " + DEFAULT_CREATURE_WARNING
                    + " is used.");
        }
        int creatureWarning = warning < 0 ? DEFAULT_CREATURE_WARNING : warning;
        return new OneBlockConfiguration(
                enabled,
                new OneBlockPhases(phases, after, branches),
                saveInterval,
                icons,
                new OneBlockEntries(blockEntries, creatureEntries, tables),
                moments,
                lists,
                gates,
                chooseWithin,
                creatureWarning,
                OneBlockPhaseReader.display(root.node("progress")),
                previews,
                biomes);
    }

    /** The phase file of {@code name}, or nothing, said in the console, when there is none to read. */
    private static @org.jspecify.annotations.Nullable ConfigurationNode phaseFile(
            String name, java.nio.file.@org.jspecify.annotations.Nullable Path folder) {
        if (!NAME.matcher(name).matches()) {
            warn(() -> "modules/oneblock.conf lists the phase '" + name
                    + "', which is no name a file can have: 1 to 64 of a-z, 0-9, _ and -. It is left out.");
            return null;
        }
        java.nio.file.Path file = folder == null ? null : folder.resolve(name + ".conf");
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            warn(() -> "modules/oneblock.conf lists the phase '" + name + "', and oneblock/phases/" + name
                    + ".conf is not there. It is left out.");
            return null;
        }
        try {
            return org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                    .path(file)
                    .build()
                    .load();
        } catch (org.spongepowered.configurate.ConfigurateException unreadable) {
            warn(() -> "oneblock/phases/" + name + ".conf cannot be read (" + unreadable.getMessage()
                    + "). The phase is left out.");
            return null;
        }
    }

    private static java.time.Duration saveInterval(@org.jspecify.annotations.Nullable String written) {
        if (written == null || written.isBlank()) {
            return DEFAULT_SAVE_INTERVAL;
        }
        try {
            java.time.Duration parsed = com.uxplima.uxmlib.common.Durations.parse(written.strip());
            if (!parsed.isNegative() && !parsed.isZero()) {
                return parsed;
            }
        } catch (RuntimeException unreadable) {
            // Said below, with the interval used instead.
        }
        warn(() -> "save-interval '" + written + "' is not a positive duration, so " + DEFAULT_SAVE_INTERVAL.toSeconds()
                + "s is used.");
        return DEFAULT_SAVE_INTERVAL;
    }

    private static OneBlockPhases.AfterTheLast afterTheLast(String written) {
        try {
            return OneBlockPhases.AfterTheLast.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            warn(() -> "after-last-phase '" + written + "' is neither 'stay' nor 'repeat', so 'stay' is used.");
            return OneBlockPhases.AfterTheLast.STAY;
        }
    }

    static @org.jspecify.annotations.Nullable String creatureName(String written) {
        NamespacedKey key = NamespacedKey.fromString(written.toLowerCase(Locale.ROOT));
        EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        return type != null && type.isSpawnable() && type.isAlive() ? type.name() : null;
    }
}
