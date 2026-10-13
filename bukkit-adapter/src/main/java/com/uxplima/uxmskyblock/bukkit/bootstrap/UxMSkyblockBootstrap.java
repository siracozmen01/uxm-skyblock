package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import com.uxplima.uxmskyblock.bukkit.acid.SulfurSeaPack;
import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * What the plugin does before the server reads its data packs: it hands over the pack that carries its sulfur sea
 * biome, written in the colours {@code modules/acidisland.conf} gives, so the biome is there when the worlds load.
 */
public final class UxMSkyblockBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            Path data = context.getDataDirectory();
            try {
                Path pack = data.resolve(".datapack").resolve("sulfur-sea");
                SulfurSeaPack.write(pack, lookIn(data.resolve("modules").resolve("acidisland.conf")));
                event.registrar()
                        .discoverPack(pack, SulfurSeaPack.ID, configure -> configure.autoEnableOnServerStart(true));
            } catch (IOException | RuntimeException e) {
                context.getLogger()
                        .warn(
                                "The sulfur sea biome could not be handed to the server; AcidIsland seas keep the"
                                        + " biome their world made.",
                                e);
            }
        });
    }

    /** The look {@code file} writes, or the shipped one when there is no file yet. */
    static AcidIslandConfiguration.Look lookIn(Path file) throws ConfigurateException {
        if (!Files.isRegularFile(file)) {
            return AcidIslandConfiguration.Look.SHIPPED;
        }
        return AcidIslandConfiguration.look(
                HoconConfigurationLoader.builder().path(file).build().load().node("sea", "look"));
    }
}
