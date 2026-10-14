package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockPhaseFiles;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * How one shipped file reaches the data folder: unpacked when missing, brought up to date with a backup when the release added keys, and read as HOCON. Split out of {@link ConfigurationLoader}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ConfigurationFiles {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ConfigurationFiles.class.getName());

    private ConfigurationFiles() {}

    static <T> T loadConfig(
            JavaPlugin plugin,
            Path dataDir,
            String configName,
            ConfigurationLoader.ConfigParser<T> parser,
            T defaultVal) {
        Path moduleFile = dataDir.resolve("modules").resolve(configName);
        Path rootFile = dataDir.resolve(configName);

        Path targetFile;
        if (Files.exists(moduleFile)) {
            targetFile = moduleFile;
        } else if (Files.exists(rootFile)) {
            targetFile = rootFile;
        } else {
            unpackResource(plugin, "modules/" + configName, moduleFile);
            if (!Files.exists(moduleFile)) {
                unpackResource(plugin, configName, moduleFile);
            }
            targetFile = moduleFile;
        }
        if (!bringUpToDate(plugin, dataDir, targetFile, "modules/" + configName)) {
            bringUpToDate(plugin, dataDir, targetFile, configName);
        }

        if (Files.exists(targetFile)) {
            try {
                CommentedConfigurationNode node = HoconConfigurationLoader.builder()
                        .path(targetFile)
                        .build()
                        .load();
                return parser.parse(node);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse configuration from " + targetFile, e);
            }
        }
        return defaultVal;
    }
    /**
     * Adds to the operator's {@code file} what this release ships in {@code resource} and the file lacks,
     * and says so in one line. See {@link PluginSettings#bringUpToDate}.
     *
     * <p>A file that cannot be brought up to date still loads as it is: the server starts with the keys
     * the operator has, and the line says which file and why.
     */
    static boolean bringUpToDate(JavaPlugin plugin, Path dataDir, Path file, String resource) {
        try {
            if (PluginSettings.bringUpToDate(
                    dataDir, file, resource, plugin.getClass().getClassLoader())) {
                LOGGER.info("Added what this release brings to " + dataDir.relativize(file)
                        + ". The file as it was is beside it as " + file.getFileName() + ".bak.");
                return true;
            }
        } catch (RuntimeException failed) {
            LOGGER.warning("Could not bring " + file + " up to date with this release: " + failed.getMessage()
                    + ". It loads as it is.");
        }
        return false;
    }
    /**
     * Writes a shipped default next to the server, once, and never over an operator's edit.
     *
     * <p>A failure here is not fatal: the subsystem falls back to its own defaults and the server
     * starts. It is not silent either. An operator whose disk was full or whose folder was read only
     * would otherwise edit a file the plugin never wrote and wonder why nothing changed.
     */
    static void unpackResource(JavaPlugin plugin, String resourcePath, Path targetPath) {
        if (Files.exists(targetPath)) {
            return;
        }
        try {
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in != null) {
                    Files.copy(in, targetPath);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Could not write the shipped default " + resourcePath + " to " + targetPath + ": "
                    + e.getMessage() + ". The subsystem will use its built in defaults instead.");
        }
    }

    static @Nullable CommentedConfigurationNode loadHocon(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return HoconConfigurationLoader.builder().path(file).build().load();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load configuration file: " + file, e);
        }
    }
    /**
     * Moves phases still written inside the OneBlock file into a file each, then ships the phase files a fresh
     * server lacks. Moved first, so a phase the operator wrote is never shadowed by the shipped file of its name.
     */
    static void oneBlockPhaseFiles(JavaPlugin plugin, Path dataDir, Path phasesFolder) {
        for (Path file :
                List.of(dataDir.resolve("modules").resolve("oneblock.conf"), dataDir.resolve("oneblock.conf"))) {
            if (!Files.exists(file)) {
                continue;
            }
            try {
                OneBlockPhaseFiles.moveIntoFiles(file, phasesFolder);
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.warning("The OneBlock phases written in " + file + " could not be moved into files ("
                        + e.getMessage() + "). They are read from the list as they stand.");
            }
        }
        for (String phase : OneBlockPhaseFiles.SHIPPED) {
            unpackResource(plugin, "oneblock/phases/" + phase + ".conf", phasesFolder.resolve(phase + ".conf"));
        }
    }
}
