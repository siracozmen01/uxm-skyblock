package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.ThemeSource;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * What the configuration wiring does once its files are read: the message catalogue built from the language files and the theme, and the fail-closed check of every value. Split out of {@link ConfigurationWiring}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ConfigurationSupport {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ConfigurationSupport.class.getName());

    private ConfigurationSupport() {}

    /**
     * The catalog is configuration, so it is built here rather than in a later wiring step. The
     * listeners are constructed before the integration layer exists, and they answer a player too.
     */
    static Messages buildMessages(@Nullable CommentedConfigurationNode rootNode, Path dataDir) {
        LanguageConfiguration language = LanguageConfiguration.load(rootNode);
        MessageProvider provider = new MessageProvider(language.defaultLanguage());
        provider.loadBundledDefaults(ConfigurationWiring.class.getClassLoader());

        Path messagesDir = dataDir.resolve("messages");
        if (Files.isDirectory(messagesDir)) {
            try (java.util.stream.Stream<Path> files = Files.list(messagesDir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("messages_") || !name.endsWith(".conf")) {
                        continue;
                    }
                    String locale = name.substring("messages_".length(), name.length() - ".conf".length());
                    try {
                        provider.loadFromFile(locale, file);
                    } catch (IOException e) {
                        LOGGER.warning("Failed loading the message catalog " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.warning("Failed listing the messages folder " + messagesDir + ": " + e.getMessage());
            }
        }
        // The same theme the menus read, so every line and every tile is painted from one file.
        return Messages.of(provider, language, ThemeSource.load(dataDir));
    }

    /** Fail-closed schema and constraint validation of every value {@code wiring} read. */
    static void validate(ConfigurationWiring wiring) {
        // 1. Server node validation
        if (wiring.nodeConfig().nodeId() == null
                || wiring.nodeConfig().nodeId().value().isBlank()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: ServerNodeConfiguration nodeId must not be blank.");
        }
        if (wiring.nodeConfig().worldName() == null
                || wiring.nodeConfig().worldName().isBlank()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: ServerNodeConfiguration worldName must not be blank.");
        }

        // 2. Durability checkpoint interval validation
        if (wiring.playerStateConfig().ambientCheckpointInterval().isNegative()
                || wiring.playerStateConfig().ambientCheckpointInterval().isZero()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: PlayerStateDurabilityConfig ambientCheckpointInterval must be strictly positive: "
                            + wiring.playerStateConfig().ambientCheckpointInterval());
        }

        // 3. Hardware limit bounds validation
        for (com.uxplima.uxmskyblock.core.domain.limit.LimitQuota quota :
                wiring.limitConfig().quotas().values()) {
            if (quota.baseLimit() < 0 || quota.perTierBonus() < 0) {
                throw new IllegalStateException(
                        "Fail-closed configuration error: Limit quota cannot have negative base or tier bonus: "
                                + quota);
            }
        }

        // 4. Performance throttle validation
        if (wiring.performanceConfig().normalBlocksPerTick() <= 0
                || wiring.performanceConfig().normalChunksPerSec() <= 0) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: Performance block/chunk quotas must be strictly positive.");
        }
    }
}
