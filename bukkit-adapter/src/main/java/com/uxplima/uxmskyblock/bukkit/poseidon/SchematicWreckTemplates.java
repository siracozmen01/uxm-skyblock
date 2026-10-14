package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.block.data.BlockData;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics;
import org.jspecify.annotations.Nullable;

/**
 * Templates a wreck group names by {@code schematic:<name>}: the file schematics/&lt;name&gt;.schem under the plugin's
 * folder, which the operator edits with any tool that writes a Sponge schematic, or with {@code /is schematic save}.
 * Every other key is the server's own template, as before.
 *
 * <p>A file is read once, off the main thread, when the server starts; a key read before then, or whose file cannot
 * be read, lays nothing.
 */
public final class SchematicWreckTemplates implements WreckTemplates {

    private static final Logger LOGGER = Logger.getLogger(SchematicWreckTemplates.class.getName());

    /** The namespace of a key that names a schematic file. */
    public static final String PREFIX = "schematic:";

    private final WreckTemplates server;
    private final Function<String, @Nullable BlockData> parse;
    private final Map<String, WreckTemplate> read = new ConcurrentHashMap<>();

    /**
     * @param server the server's own templates, for every other key
     * @param parse a block state written in a file as the server's block, or null for one it does not know
     */
    public SchematicWreckTemplates(WreckTemplates server, Function<String, @Nullable BlockData> parse) {
        this.server = Objects.requireNonNull(server, "server must not be null");
        this.parse = Objects.requireNonNull(parse, "parse must not be null");
    }

    @Override
    public Optional<WreckTemplate> load(String key) {
        if (!key.startsWith(PREFIX)) {
            return server.load(key);
        }
        return Optional.ofNullable(read.get(key));
    }

    /** A block state as the server's block, or null for one it does not know. */
    public static @Nullable BlockData parseQuietly(String state) {
        try {
            return org.bukkit.Bukkit.createBlockData(state);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Reads the file of each schematic key among {@code keys}. Answers how many were read. */
    public CompletableFuture<Integer> prime(IslandSchematics files, Collection<String> keys) {
        List<CompletableFuture<Boolean>> reads = new ArrayList<>();
        for (String key : keys.stream().distinct().toList()) {
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            String name = key.substring(PREFIX.length());
            if (!IslandSchematics.isName(name)) {
                LOGGER.warning(() -> "modules/poseidon.conf names the template '" + key + "', which is no file name.");
                continue;
            }
            reads.add(files.read(IslandSchematics.pathOf(name)).handle((schematic, failure) -> {
                if (failure != null || schematic == null || schematic.isEmpty()) {
                    LOGGER.log(
                            Level.WARNING,
                            failure,
                            () -> "The template " + key + " has no file " + IslandSchematics.pathOf(name)
                                    + " that can be read. modules/poseidon.conf names it.");
                    return false;
                }
                read.put(key, template(schematic.get(), parse));
                return true;
            }));
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(done ->
                (int) reads.stream().filter(CompletableFuture::join).count());
    }

    /** The blocks of a schematic as a template: air and the blocks the server does not know lay nothing. */
    static WreckTemplate template(Schematic schematic, Function<String, @Nullable BlockData> parse) {
        Map<String, Optional<BlockData>> known = new java.util.HashMap<>();
        List<WreckTemplate.Piece> pieces = new ArrayList<>();
        for (int y = 0; y < schematic.height(); y++) {
            for (int x = 0; x < schematic.width(); x++) {
                for (int z = 0; z < schematic.length(); z++) {
                    String state = schematic.blockAt(x, y, z);
                    if (Schematic.AIR.equals(state)) {
                        continue;
                    }
                    Optional<BlockData> data = known.computeIfAbsent(state, written -> {
                        BlockData parsed = parse.apply(written);
                        if (parsed == null) {
                            LOGGER.warning(() -> "A template holds the block " + written
                                    + ", which this server does not know. It lays nothing.");
                        }
                        return Optional.ofNullable(parsed);
                    });
                    if (data.isPresent()) {
                        pieces.add(new WreckTemplate.Piece(x, y, z, data.get(), null));
                    }
                }
            }
        }
        return new WreckTemplate(schematic.width(), schematic.length(), pieces);
    }
}
