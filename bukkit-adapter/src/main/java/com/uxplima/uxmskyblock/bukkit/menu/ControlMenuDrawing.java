package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * The island control window drawn: every tile with its live values and numbers, as the operator's menu file lays it out. Split out of {@link IslandControlMenu}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ControlMenuDrawing {

    private final IslandControlMenu host;

    ControlMenuDrawing(IslandControlMenu host) {
        this.host = host;
    }

    void show(Player player, String specId, boolean refresh, Runnable otherwise) {
        show(player, specId, refresh, otherwise, UnaryOperator.identity());
    }

    void show(
            Player player,
            String specId,
            boolean refresh,
            Runnable otherwise,
            UnaryOperator<Map<String, String>> extend) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> activeOpt = host.activeProfileProvider.apply(player.getUniqueId());
        if (refresh && (activeOpt.isEmpty() || host.menuEngine == null)) {
            return;
        }
        if (activeOpt.isEmpty()) {
            host.schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    player.sendMessage(host.messages.render(player, "error.session_not_active"));
                }
            });
            return;
        }
        ProfileId profileId = activeOpt.get();

        host.schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = host.islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                if (refresh) {
                    return;
                }
                host.schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // A window is drawn from an island's values. A command named after one still
                    // answers a player without an island, as the board does, in chat.
                    java.util.function.Consumer<Player> instead = host.noIsland;
                    if ("island-main".equals(specId) && instead != null) {
                        instead.accept(player);
                    } else if ("island-main".equals(specId)) {
                        player.sendMessage(host.messages.render(player, "menu.control.no_island"));
                    } else {
                        otherwise.run();
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            Optional<Island> optIsland = host.islandStoragePort.findIslandById(islandId);
            if (optIsland.isEmpty()) {
                return;
            }

            Island island = optIsland.get();
            IslandBank bank = host.islandBankPort.findBankByIslandId(islandId).orElse(null);
            Map<UpgradeId, Integer> bought = host.upgradeStoragePort.getUpgrades(islandId);
            UnaryOperator<Map<UpgradeId, Integer>> standingOf = host.upgradeStanding;
            Map<UpgradeId, Integer> upgrades = standingOf == null ? bought : standingOf.apply(bought);
            ToIntFunction<IslandId> pagesOf = host.vaultPages;
            int pages = pagesOf == null ? 1 : pagesOf.applyAsInt(islandId);
            Optional<IslandLocation> optLoc = host.locationService.resolveHome(profileId);
            // Only the bank's windows show what a player carries, and reading it may wait on another plugin.
            Function<java.util.UUID, Map<String, String>> carriedOf = host.carried;
            Map<String, String> carrying = carriedOf != null && specId.startsWith(BankCurrencyList.BANK_FILE)
                    ? carriedOf.apply(player.getUniqueId())
                    : Map.of();

            host.schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (refresh) {
                    SkyblockMenuEngine engine = host.menuEngine;
                    if (engine != null && engine.showing(player, specId)) {
                        engine.open(
                                player,
                                specId,
                                extend.apply(IslandControlMenu.withCarried(
                                        liveValues(player, island, bank, upgrades, pages), carrying)));
                    }
                    return;
                }
                // The file is the menu on both editions: the engine draws it as a native form for a
                // Bedrock player, so every window it opens is reachable there too. The form built in
                // code below only answered with hints in chat, and stays for a file that is missing.
                SkyblockMenuEngine engine = host.menuEngine;
                if (engine != null
                        && engine.open(
                                player,
                                specId,
                                extend.apply(IslandControlMenu.withCarried(
                                        liveValues(player, island, bank, upgrades, pages), carrying)))) {
                    return;
                }
                if (!"island-main".equals(specId)) {
                    otherwise.run();
                    return;
                }
                if (host.bedrockFormService != null && host.bedrockFormService.isBedrock(player)) {
                    host.bedrockFormService.openIslandControlForm(
                            player,
                            island,
                            () -> {
                                if (optLoc.isPresent()) {
                                    IslandLocation loc = optLoc.get();
                                    World world = Bukkit.getWorld(host.worldName);
                                    if (world != null) {
                                        Location target = new Location(
                                                world,
                                                loc.spawnX(),
                                                loc.spawnY(),
                                                loc.spawnZ(),
                                                loc.spawnYaw(),
                                                loc.spawnPitch());
                                        var unused = player.teleportAsync(target);
                                        player.sendMessage(host.messages.render(player, "menu.control.home_success"));
                                    } else {
                                        player.sendMessage(
                                                host.messages.render(player, "menu.control.home_world_unloaded"));
                                    }
                                } else {
                                    player.sendMessage(host.messages.render(player, "menu.control.home_missing"));
                                }
                            },
                            () -> host.messages.send(player, "menu.control.warps_hint"),
                            () -> host.messages.send(player, "menu.control.bank_hint"),
                            () -> host.messages.send(player, "menu.control.members_hint"),
                            () -> host.messages.send(player, "menu.control.settings_hint"));
                    return;
                }
                SimpleGui gui = host.buildGui(player, island, bank, upgrades, optLoc);
                gui.open(player);
            });
        });
    }
    /**
     * The live values {@code island-main.conf} may spell as {@code %argument_<name>%}.
     *
     * <p>Every token here is one the server can actually answer. The file that shipped before spelled
     * {@code {bank_level}} and {@code {max_members}}, and neither existed anywhere in the code: it was
     * a dead placeholder in a file nothing read.
     */
    Map<String, String> liveValues(
            Player reader,
            Island island,
            @Nullable IslandBank bank,
            @Nullable Map<UpgradeId, Integer> upgrades,
            int vaultPages) {
        Map<String, String> values = new HashMap<>(IslandControlMenu.numbers(island, bank, upgrades, vaultPages));
        // What the bank holds of each currency the operator lists, so its tile in the bank window reads it.
        if (bank != null) {
            bank.held()
                    .forEach((currency, amount) -> values.put(BankCurrencyList.HELD + currency, Long.toString(amount)));
        }
        UpgradeWords words = host.upgradeWords;
        if (words != null) {
            values.putAll(words.values(reader, upgrades != null ? upgrades : Map.of()));
        }
        // Each flag as flag_<name>, in the reader's words, so a settings tile shows where it stands
        // rather than leaving the player to click and read the answer in chat.
        island.flags().values().forEach((flag, enabled) -> {
            String key = flag.toLowerCase(Locale.ROOT);
            String state = enabled ? "on" : "off";
            values.put(
                    "flag_" + key,
                    host.messages.named(
                            reader,
                            "menu.settings.states",
                            key + "_" + state,
                            host.messages.words(reader, "@menu.settings." + state)));
        });
        return Map.copyOf(values);
    }
}
