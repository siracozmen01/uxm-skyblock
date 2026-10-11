package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * Interactive Chest GUI providing one-stop island management, bank stats, upgrades,
 * navigation, and permission control.
 */
public final class IslandControlMenu {

    private final IslandStoragePort islandStoragePort;
    private final IslandBankPort islandBankPort;
    private final IslandUpgradeStoragePort upgradeStoragePort;
    private final IslandLocationService locationService;
    private final SchedulerPort schedulerPort;
    private final String worldName;
    private final Function<UUID, Optional<ProfileId>> activeProfileProvider;
    private final @Nullable BedrockFormService bedrockFormService;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    private volatile @Nullable ToIntFunction<IslandId> vaultPages;
    private volatile @Nullable UnaryOperator<Map<UpgradeId, Integer>> upgradeStanding;
    private volatile @Nullable UpgradeWords upgradeWords;
    private final Messages messages;

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            @Nullable BedrockFormService bedrockFormService,
            Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.upgradeStoragePort = Objects.requireNonNull(upgradeStoragePort, "upgradeStoragePort must not be null");
        this.locationService = Objects.requireNonNull(locationService, "locationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.activeProfileProvider =
                Objects.requireNonNull(activeProfileProvider, "activeProfileProvider must not be null");
        this.bedrockFormService = bedrockFormService;
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                activeProfileProvider,
                null,
                messages);
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable BedrockFormService bedrockFormService,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty(),
                bedrockFormService,
                messages);
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                sessionCoordinator,
                null,
                messages);
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                (PlayerSessionCoordinator) null,
                messages);
    }

    /**
     * Hands this menu the engine that reads {@code menus/island-main.conf}.
     *
     * <p>When the operator's file is there, the file decides the layout. The Java built window below
     * stays as the answer to a file that is missing or will not parse, so a typo in a menu file
     * leaves a player with a working menu rather than with nothing.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine menuEngine) {
        this.menuEngine = menuEngine;
    }

    /**
     * How many vault pages an island has, read where the other values are read, off the player's
     * thread.
     *
     * <p>The vault tile showed the upgrade tier as the pages unlocked, so an island with its one base
     * page read "Pages unlocked: 0".
     */
    public void useVaultPages(@Nullable ToIntFunction<IslandId> vaultPages) {
        this.vaultPages = vaultPages;
    }

    /**
     * The tier each upgrade stands on, given what the island bought, so a free base tier reads as
     * held here the way the upgrade list reads it.
     */
    public void useUpgradeStanding(@Nullable UnaryOperator<Map<UpgradeId, Integer>> standing) {
        this.upgradeStanding = standing;
    }

    /**
     * What each upgrade does now, what its next tier would do and what that costs, so the upgrades window
     * says what is bought before it is bought.
     */
    public void useUpgradeWords(@Nullable UpgradeWords upgradeWords) {
        this.upgradeWords = upgradeWords;
    }

    /**
     * The live values {@code island-main.conf} may spell as {@code %argument_<name>%}.
     *
     * <p>Every token here is one the server can actually answer. The file that shipped before spelled
     * {@code {bank_level}} and {@code {max_members}}, and neither existed anywhere in the code: it was
     * a dead placeholder in a file nothing read.
     */
    private Map<String, String> liveValues(
            Player reader,
            Island island,
            @Nullable IslandBank bank,
            @Nullable Map<UpgradeId, Integer> upgrades,
            int vaultPages) {
        Map<String, String> values = new HashMap<>(numbers(island, bank, upgrades, vaultPages));
        // What the bank holds of each currency the operator lists, so its tile in the bank window reads it.
        if (bank != null) {
            bank.held()
                    .forEach((currency, amount) -> values.put(BankCurrencyList.HELD + currency, Long.toString(amount)));
        }
        UpgradeWords words = this.upgradeWords;
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
                    messages.named(
                            reader,
                            "menu.settings.states",
                            key + "_" + state,
                            messages.words(reader, "@menu.settings." + state)));
        });
        return Map.copyOf(values);
    }

    private static Map<String, String> withCarried(Map<String, String> values, Map<String, String> carrying) {
        if (carrying.isEmpty()) {
            return values;
        }
        Map<String, String> both = new HashMap<>(values);
        both.putAll(carrying);
        return Map.copyOf(both);
    }

    private static Map<String, String> numbers(
            Island island, @Nullable IslandBank bank, @Nullable Map<UpgradeId, Integer> upgrades, int vaultPages) {
        long minorBalance = bank != null ? bank.primaryBalanceMinorUnits() : 0L;
        Map<UpgradeId, Integer> tiers = upgrades != null ? upgrades : Map.of();
        IslandBounds bounds = island.bounds();
        return Map.ofEntries(
                Map.entry("island", island.id().value().toString()),
                Map.entry("balance", String.format(Locale.US, "%.2f", (double) minorBalance / 100.0)),
                Map.entry("crystals", String.valueOf(bank != null ? bank.crystalsBalance() : 0L)),
                Map.entry("exp", String.valueOf(bank != null ? bank.expBalance() : 0L)),
                Map.entry("member_count", String.valueOf(island.members().size())),
                Map.entry("radius", String.valueOf(bounds.radius())),
                Map.entry("center_x", String.valueOf(bounds.centerX())),
                Map.entry("center_z", String.valueOf(bounds.centerZ())),
                Map.entry("size_tier", String.valueOf(tiers.getOrDefault(UpgradeId.SIZE, 0))),
                Map.entry("members_tier", String.valueOf(tiers.getOrDefault(UpgradeId.MEMBERS, 0))),
                Map.entry("warps_tier", String.valueOf(tiers.getOrDefault(UpgradeId.WARPS, 0))),
                Map.entry("spawner_tier", String.valueOf(tiers.getOrDefault(UpgradeId.SPAWNER_RATES, 0))),
                Map.entry("generator_tier", String.valueOf(tiers.getOrDefault(UpgradeId.ORE_GENERATOR, 0))),
                Map.entry("vault_tier", String.valueOf(tiers.getOrDefault(UpgradeId.VAULT_PAGES, 0))),
                Map.entry("vault_pages", String.valueOf(vaultPages)),
                Map.entry("crop_tier", String.valueOf(tiers.getOrDefault(UpgradeId.CROP_GROWTH, 0))));
    }

    /** The upgrades window, opened from this menu and drawn from the values it gathers. */
    public static final String UPGRADES = "island-upgrades";

    /** The settings window, opened from this menu and drawn from the values it gathers. */
    public static final String SETTINGS = "island-settings";

    public void open(Player player) {
        show(player, "island-main", false, () -> {});
    }

    /** What the main menu does for a player with no island, on their thread; nothing set tells them in chat. */
    private volatile java.util.function.@Nullable Consumer<Player> noIsland;

    /** Hands the main menu what it does for a player with no island, as the operator chose. */
    public void useNoIsland(java.util.function.@Nullable Consumer<Player> noIsland) {
        this.noIsland = noIsland;
    }

    /** Whether the operator kept the window {@code specId}, so a command named after it opens it. */
    public boolean hasWindow(String specId) {
        SkyblockMenuEngine engine = this.menuEngine;
        return engine != null && engine.has(specId);
    }

    /**
     * Opens the window {@code specId} with the island's values, as its tile in this menu does, so a
     * command named after a window opens it. When the window cannot be opened, {@code otherwise} runs on
     * the player's thread, so the command still answers in chat.
     */
    public void openWindow(Player player, String specId, Runnable otherwise) {
        show(player, specId, false, Objects.requireNonNull(otherwise, "otherwise must not be null"));
    }

    /**
     * Opens {@code specId} again with the island's values as they stand now, when the player still has
     * it up. A window opened from this menu reads the values this menu gathered, so a tier bought from
     * one would otherwise show the tier it had before.
     */
    public void refresh(Player player, String specId) {
        refresh(player, specId, UnaryOperator.identity());
    }

    /**
     * The same, for a window opened with values of its own beside the island's: {@code extend} adds them to the
     * island's values as they stand now.
     */
    public void refresh(Player player, String specId, UnaryOperator<Map<String, String>> extend) {
        show(player, specId, true, () -> {}, Objects.requireNonNull(extend, "extend must not be null"));
    }

    /** What a player carries of each currency the bank keeps, read off their thread, for the bank's windows. */
    private volatile @Nullable Function<java.util.UUID, Map<String, String>> carried;

    /** Hands the bank's windows what a player carries of each currency, as {@code carried_<id>}. */
    public void useCarried(@Nullable Function<java.util.UUID, Map<String, String>> carried) {
        this.carried = carried;
    }

    private void show(Player player, String specId, boolean refresh, Runnable otherwise) {
        show(player, specId, refresh, otherwise, UnaryOperator.identity());
    }

    private void show(
            Player player,
            String specId,
            boolean refresh,
            Runnable otherwise,
            UnaryOperator<Map<String, String>> extend) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> activeOpt = activeProfileProvider.apply(player.getUniqueId());
        if (refresh && (activeOpt.isEmpty() || menuEngine == null)) {
            return;
        }
        if (activeOpt.isEmpty()) {
            schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    player.sendMessage(messages.render(player, "error.session_not_active"));
                }
            });
            return;
        }
        ProfileId profileId = activeOpt.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                if (refresh) {
                    return;
                }
                schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // A window is drawn from an island's values. A command named after one still
                    // answers a player without an island, as the board does, in chat.
                    java.util.function.Consumer<Player> instead = this.noIsland;
                    if ("island-main".equals(specId) && instead != null) {
                        instead.accept(player);
                    } else if ("island-main".equals(specId)) {
                        player.sendMessage(messages.render(player, "menu.control.no_island"));
                    } else {
                        otherwise.run();
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
            if (optIsland.isEmpty()) {
                return;
            }

            Island island = optIsland.get();
            IslandBank bank = islandBankPort.findBankByIslandId(islandId).orElse(null);
            Map<UpgradeId, Integer> bought = upgradeStoragePort.getUpgrades(islandId);
            UnaryOperator<Map<UpgradeId, Integer>> standingOf = this.upgradeStanding;
            Map<UpgradeId, Integer> upgrades = standingOf == null ? bought : standingOf.apply(bought);
            ToIntFunction<IslandId> pagesOf = this.vaultPages;
            int pages = pagesOf == null ? 1 : pagesOf.applyAsInt(islandId);
            Optional<IslandLocation> optLoc = locationService.resolveHome(profileId);
            // Only the bank's windows show what a player carries, and reading it may wait on another plugin.
            Function<java.util.UUID, Map<String, String>> carriedOf = this.carried;
            Map<String, String> carrying = carriedOf != null && specId.startsWith(BankCurrencyList.BANK_FILE)
                    ? carriedOf.apply(player.getUniqueId())
                    : Map.of();

            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (refresh) {
                    SkyblockMenuEngine engine = this.menuEngine;
                    if (engine != null && engine.showing(player, specId)) {
                        engine.open(
                                player,
                                specId,
                                extend.apply(withCarried(liveValues(player, island, bank, upgrades, pages), carrying)));
                    }
                    return;
                }
                // The file is the menu on both editions: the engine draws it as a native form for a
                // Bedrock player, so every window it opens is reachable there too. The form built in
                // code below only answered with hints in chat, and stays for a file that is missing.
                SkyblockMenuEngine engine = this.menuEngine;
                if (engine != null
                        && engine.open(
                                player,
                                specId,
                                extend.apply(
                                        withCarried(liveValues(player, island, bank, upgrades, pages), carrying)))) {
                    return;
                }
                if (!"island-main".equals(specId)) {
                    otherwise.run();
                    return;
                }
                if (bedrockFormService != null && bedrockFormService.isBedrock(player)) {
                    bedrockFormService.openIslandControlForm(
                            player,
                            island,
                            () -> {
                                if (optLoc.isPresent()) {
                                    IslandLocation loc = optLoc.get();
                                    World world = Bukkit.getWorld(worldName);
                                    if (world != null) {
                                        Location target = new Location(
                                                world,
                                                loc.spawnX(),
                                                loc.spawnY(),
                                                loc.spawnZ(),
                                                loc.spawnYaw(),
                                                loc.spawnPitch());
                                        var unused = player.teleportAsync(target);
                                        player.sendMessage(messages.render(player, "menu.control.home_success"));
                                    } else {
                                        player.sendMessage(messages.render(player, "menu.control.home_world_unloaded"));
                                    }
                                } else {
                                    player.sendMessage(messages.render(player, "menu.control.home_missing"));
                                }
                            },
                            () -> messages.send(player, "menu.control.warps_hint"),
                            () -> messages.send(player, "menu.control.bank_hint"),
                            () -> messages.send(player, "menu.control.members_hint"),
                            () -> messages.send(player, "menu.control.settings_hint"));
                    return;
                }
                SimpleGui gui = buildGui(player, island, bank, upgrades, optLoc);
                gui.open(player);
            });
        });
    }

    public SimpleGui buildGui(
            Player player,
            Island island,
            @Nullable IslandBank bank,
            Map<UpgradeId, Integer> upgrades,
            Optional<IslandLocation> optLoc) {

        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "menu.control.title")))
                .rows(4)
                .build();
        gui.filler().fill(GuiItem.display(SkyblockTiles.filler()));

        Map<String, String> values = new HashMap<>(liveValues(player, island, bank, upgrades, 1));
        values.put("owner", island.ownerPlayerUuid().value().toString().substring(0, 8));
        TagResolver[] live = SkyblockTiles.arguments(values);
        SkyblockTiles tiles = new SkyblockTiles(messages);

        gui.set(
                10,
                GuiItem.display(tiles.item(
                        Material.GRASS_BLOCK,
                        player,
                        "tile:4 @menu.control.overview owner members centre radius",
                        live)));
        gui.set(
                11,
                hint(
                        tiles.item(Material.GOLD_INGOT, player, "tile:money @menu.control.bank balance crystals", live),
                        player,
                        "menu.control.bank_hint"));
        gui.set(
                12,
                hint(
                        tiles.item(Material.NETHER_STAR, player, "tile:6 @menu.control.upgrades size spawner", live),
                        player,
                        "menu.control.upgrades_hint"));
        gui.set(
                13,
                hint(
                        tiles.item(Material.OAK_SAPLING, player, "tile:3 @menu.control.biome", live),
                        player,
                        "menu.control.biome_hint"));
        gui.set(
                14,
                hint(
                        tiles.item(Material.PLAYER_HEAD, player, "tile:1 @menu.control.members count", live),
                        player,
                        "menu.control.members_hint"));
        gui.set(
                15,
                hint(
                        tiles.item(Material.REDSTONE_TORCH, player, "tile:2 @menu.control.settings", live),
                        player,
                        "menu.control.settings_hint"));
        gui.set(16, GuiItem.button(tiles.item(Material.COMPASS, player, "tile:4 @menu.control.home", live), e -> {
            player.closeInventory();
            if (optLoc.isPresent()) {
                IslandLocation loc = optLoc.get();
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Location target = new Location(
                            world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch());
                    var unused = player.teleportAsync(target);
                    messages.send(player, "menu.control.home_success");
                } else {
                    messages.send(player, "menu.control.home_world_unloaded");
                }
            } else {
                messages.send(player, "menu.control.home_missing");
            }
        }));
        return gui;
    }

    /** A tile whose click closes the window and names, in chat, the command that does the job. */
    private GuiItem hint(ItemStack tile, Player player, String key) {
        return GuiItem.button(tile, e -> {
            player.closeInventory();
            messages.send(player, key);
        });
    }
}
