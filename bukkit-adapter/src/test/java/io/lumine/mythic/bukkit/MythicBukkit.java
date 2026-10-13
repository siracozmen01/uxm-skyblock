package io.lumine.mythic.bukkit;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import org.jspecify.annotations.Nullable;

/** A stand in for MythicMobs' entry point, written for the tests: it knows one creature, a zombie. */
public final class MythicBukkit {

    private static final MythicBukkit INSTANCE = new MythicBukkit();

    private MythicBukkit() {}

    public static MythicBukkit inst() {
        return INSTANCE;
    }

    public Helper getAPIHelper() {
        return new Helper();
    }

    /** What MythicMobs' API helper does here: spawn its creature by name. */
    public static final class Helper {

        public @Nullable Entity spawnMythicMob(String name, Location location) {
            if (!name.equals("SkeletonKing")) {
                return null;
            }
            return location.getWorld().spawnEntity(location, EntityType.ZOMBIE);
        }
    }
}
