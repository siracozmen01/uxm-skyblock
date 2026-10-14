package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Sign warps, {@code warps.sign} in {@code modules/warps.conf}: a sign whose first line is the header and whose second
 * is a name makes a warp of that name where it stands.
 *
 * @param header the first line that marks a warp sign, read in any case
 */
public record SignWarpsConfiguration(boolean enabled, String header) {

    public SignWarpsConfiguration {
        Objects.requireNonNull(header, "header must not be null");
        header = header.strip();
    }

    public static SignWarpsConfiguration defaultConfiguration() {
        return new SignWarpsConfiguration(true, "[islandwarp]");
    }

    public static SignWarpsConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        SignWarpsConfiguration shipped = defaultConfiguration();
        ConfigurationNode sign = root.node("warps", "sign");
        String header = sign.node("header").getString(shipped.header());
        return new SignWarpsConfiguration(
                sign.node("enabled").getBoolean(shipped.enabled()), header.isBlank() ? shipped.header() : header);
    }

    /** Whether {@code firstLine}, as written, marks a warp sign. */
    public boolean marks(String firstLine) {
        return enabled && firstLine.strip().equalsIgnoreCase(header);
    }
}
