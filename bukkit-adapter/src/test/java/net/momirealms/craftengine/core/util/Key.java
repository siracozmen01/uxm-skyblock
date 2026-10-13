package net.momirealms.craftengine.core.util;

/** A stand in for CraftEngine's key, written for the tests. */
public record Key(String value) {

    public static Key of(String value) {
        return new Key(value);
    }
}
