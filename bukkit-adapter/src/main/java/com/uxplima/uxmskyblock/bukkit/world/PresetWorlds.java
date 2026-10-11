package com.uxplima.uxmskyblock.bukkit.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.Bukkit;

import com.uxplima.uxmlib.common.ServerVersion;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;

/**
 * Which kinds of island this server can make, by whether the world each is made in is loaded.
 *
 * <p>Boxed and StrangerRealms islands are made on land, in a world of their own. A Folia server runs one world with
 * its Nether and its End and cannot open another, so on Folia those kinds can never be made. They were offered in
 * the window all the same, and a player who picked one was told the world was missing. A kind whose world is not
 * loaded is now not offered, on any server, and the console says once which kinds are off and why.
 */
public final class PresetWorlds {

    private final String islandWorld;
    private final Predicate<String> loaded;
    private final boolean folia;

    /**
     * @param islandWorld the server's own island world, where a kind that names none is made
     * @param loaded whether a world of that name is loaded
     * @param folia whether this server is Folia, which opens no world but its own
     */
    public PresetWorlds(String islandWorld, Predicate<String> loaded, boolean folia) {
        this.islandWorld = Objects.requireNonNull(islandWorld, "islandWorld must not be null");
        this.loaded = Objects.requireNonNull(loaded, "loaded must not be null");
        this.folia = folia;
    }

    /** The kinds of this server, asking it for its worlds. */
    public static PresetWorlds ofServer(String islandWorld) {
        return new PresetWorlds(islandWorld, name -> Bukkit.getWorld(name) != null, ServerVersion.isFolia());
    }

    /** Whether islands of {@code preset} can be made here: its world is loaded. */
    public boolean playable(StarterPreset preset) {
        return loaded.test(preset.worldOr(islandWorld));
    }

    /** The kinds of {@code presets} that can be made here, in their order. */
    public List<StarterPreset> playable(List<StarterPreset> presets) {
        return presets.stream().filter(this::playable).toList();
    }

    /** One line for the console for each kind that cannot be made here, saying why and what to do. */
    public List<String> notes(List<StarterPreset> presets) {
        List<String> notes = new ArrayList<>();
        for (StarterPreset preset : presets) {
            if (playable(preset)) {
                continue;
            }
            String world = preset.worldOr(islandWorld);
            notes.add("Islands of the kind '" + preset.id() + "' are not offered: they are made in the world '" + world
                    + "', which this server has not loaded. "
                    + (folia
                            ? "A Folia server runs one world with its Nether and End and cannot open another, so"
                                    + " this kind stays off here. A network plays it on a Paper or Leaf server."
                            : "Make that world, or name a loaded one as world for this kind in"
                                    + " modules/presets.conf, and restart."));
        }
        return List.copyOf(notes);
    }
}
