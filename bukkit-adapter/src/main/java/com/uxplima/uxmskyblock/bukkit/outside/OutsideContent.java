package com.uxplima.uxmskyblock.bukkit.outside;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import org.jspecify.annotations.Nullable;

/**
 * Blocks and creatures of somebody else's plugin, which an operator names in a file with the plugin's word in front:
 * {@code itemsadder:<namespace>:<id>}, {@code nexo:<id>} and {@code craftengine:<namespace>:<id>} for a block, and
 * {@code mythicmobs:<id>} for a creature.
 *
 * <p>An optional link and never a dependency: each plugin is reached by name, through reflection, only when it is
 * on the server. A file that names one that is not is read without that line, and a block or creature the plugin
 * cannot make is said once in the console: dirt stands in for a block, and no creature comes for a creature.
 */
public final class OutsideContent {

    /** Whether a name stands for a block or for a creature. */
    public enum Kind {
        BLOCK,
        CREATURE
    }

    /** One plugin this reads: the word an operator writes, the plugin's own name, and what it gives. */
    private record Vendor(String word, String plugin, Kind kind) {}

    private static final Map<String, Vendor> VENDORS = Map.of(
            "itemsadder", new Vendor("itemsadder", "ItemsAdder", Kind.BLOCK),
            "nexo", new Vendor("nexo", "Nexo", Kind.BLOCK),
            "craftengine", new Vendor("craftengine", "CraftEngine", Kind.BLOCK),
            "mythicmobs", new Vendor("mythicmobs", "MythicMobs", Kind.CREATURE));

    /**
     * A name an operator wrote for another plugin's block or creature.
     *
     * @param word the plugin's word, as written in front
     * @param plugin the plugin's own name
     * @param id what the plugin calls the block or creature
     */
    public record Named(String word, String plugin, String id) {

        /** The name as the files keep it: the word, then the plugin's id. */
        public String written() {
            return word + ":" + id;
        }

        /** Whether the plugin is on this server. */
        public boolean present() {
            return Bukkit.getPluginManager().getPlugin(plugin) != null;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(OutsideContent.class.getName());

    /** Names already said in the console, so a failing block is said once and not at every break. */
    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();

    private OutsideContent() {}

    /** The other plugin's block or creature {@code written} names, when its word is one this reads for that kind. */
    public static Optional<Named> named(String written, Kind kind) {
        int colon = written.indexOf(':');
        if (colon <= 0 || colon == written.length() - 1) {
            return Optional.empty();
        }
        Vendor vendor = VENDORS.get(written.substring(0, colon).toLowerCase(Locale.ROOT));
        if (vendor == null || vendor.kind() != kind) {
            return Optional.empty();
        }
        return Optional.of(new Named(vendor.word(), vendor.plugin(), written.substring(colon + 1)));
    }

    /**
     * Sets {@code block} to the other plugin's block {@code written} names, on the block's region.
     *
     * @return whether the plugin laid it; when it did not, the reason is said once and the caller lays its own
     */
    public static boolean place(String written, Block block) {
        Named named = named(written, Kind.BLOCK).orElse(null);
        if (named == null || !named.present()) {
            return false;
        }
        Location at = block.getLocation();
        try {
            boolean placed =
                    switch (named.word()) {
                        case "itemsadder" ->
                            call(named, "dev.lone.itemsadder.api.CustomBlock", "place", named.id(), at) != null;
                        case "nexo" -> {
                            call(named, "com.nexomc.nexo.api.NexoBlocks", "place", named.id(), at);
                            yield true;
                        }
                        case "craftengine" -> craftEngine(named, at);
                        default -> false;
                    };
            if (!placed) {
                tell(written, named.plugin() + " has no block called '" + named.id() + "'", BLOCK_STANDS_IN);
            }
            return placed;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            tell(written, named.plugin() + " could not place it: " + reason(failed), BLOCK_STANDS_IN);
            return false;
        }
    }

    /**
     * Brings the other plugin's creature {@code written} names onto {@code at}, on its region.
     *
     * @return the creature, or nothing when the plugin did not bring it, said once
     */
    public static @Nullable Entity spawn(String written, Location at) {
        Named named = named(written, Kind.CREATURE).orElse(null);
        if (named == null || !named.present()) {
            return null;
        }
        try {
            Object mythic = call(named, "io.lumine.mythic.bukkit.MythicBukkit", "inst");
            Object helper = invoke(mythic, "getAPIHelper");
            Object spawned = invoke(helper, "spawnMythicMob", named.id(), at);
            if (spawned instanceof Entity entity) {
                return entity;
            }
            tell(written, named.plugin() + " has no creature called '" + named.id() + "'", NO_CREATURE);
            return null;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            tell(written, named.plugin() + " could not bring it: " + reason(failed), NO_CREATURE);
            return null;
        }
    }

    /**
     * CraftEngine places a block by its key, with whatever else its version asks for: a flag answered no, and an
     * update option answered with the one that updates everything.
     */
    private static boolean craftEngine(Named named, Location at) throws ReflectiveOperationException {
        Class<?> blocks = type(named, "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
        Class<?> keys = type(named, "net.momirealms.craftengine.core.util.Key");
        Object key = keys.getMethod("of", String.class).invoke(null, named.id());
        for (Method method : blocks.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals("place")
                    || !Modifier.isStatic(method.getModifiers())
                    || types.length < 2
                    || types[0] != Location.class
                    || types[1] != keys) {
                continue;
            }
            Object[] args = new Object[types.length];
            args[0] = at;
            args[1] = key;
            boolean filled = true;
            for (int index = 2; index < types.length; index++) {
                if (types[index] == boolean.class) {
                    args[index] = false;
                } else {
                    Object all = staticField(types[index], "UPDATE_ALL");
                    filled &= all != null;
                    args[index] = all;
                }
            }
            if (filled) {
                Object answered = method.invoke(null, args);
                return !(answered instanceof Boolean done) || done;
            }
        }
        throw new NoSuchMethodException("CraftEngineBlocks has no place it can be called with");
    }

    private static @Nullable Object staticField(Class<?> type, String name) {
        try {
            return type.getField(name).get(null);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** A static method of {@code type} taking {@code args}, matched by count and assignable types. */
    private static @Nullable Object call(Named named, String type, String name, Object... args)
            throws ReflectiveOperationException {
        return find(type(named, type), name, args).invoke(null, args);
    }

    /**
     * A class of the named plugin, read through that plugin's own class loader, so it is found whether or not the
     * server joined the two plugins' class paths.
     */
    private static Class<?> type(Named named, String name) throws ClassNotFoundException {
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(named.plugin());
        ClassLoader loader = plugin == null
                ? OutsideContent.class.getClassLoader()
                : plugin.getClass().getClassLoader();
        return Class.forName(name, true, loader);
    }

    private static @Nullable Object invoke(@Nullable Object target, String name, Object... args)
            throws ReflectiveOperationException {
        if (target == null) {
            throw new NoSuchMethodException(name + " on nothing");
        }
        return find(target.getClass(), name, args).invoke(target, args);
    }

    private static Method find(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals(name) || types.length != args.length) {
                continue;
            }
            boolean fits = true;
            for (int at = 0; at < types.length; at++) {
                fits &= types[at].isInstance(args[at]);
            }
            if (fits) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static String reason(Exception failed) {
        Throwable cause = failed instanceof InvocationTargetException thrown && thrown.getCause() != null
                ? thrown.getCause()
                : failed;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : " " + cause.getMessage());
    }

    private static final String BLOCK_STANDS_IN = "Dirt stands in for it.";
    private static final String NO_CREATURE = "No creature comes in its place.";

    private static void tell(String written, String why, String then) {
        if (TOLD.add(written)) {
            LOGGER.warning(() -> "'" + written + "': " + why + ". " + then);
        }
    }
}
