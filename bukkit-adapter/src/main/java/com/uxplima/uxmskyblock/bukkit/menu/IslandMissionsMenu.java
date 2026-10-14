package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.ItemNames;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.TradableStacks;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.jspecify.annotations.Nullable;

/**
 * Interactive chest GUI displaying available mission branches, quest tracking,
 * and physical item submissions.
 */
public final class IslandMissionsMenu {

    private static final Logger LOGGER = Logger.getLogger(IslandMissionsMenu.class.getName());

    private final IslandMissionService missionService;
    private final IslandStoragePort islandStoragePort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private @Nullable BedrockFormService bedrockFormService;

    private volatile @Nullable Consumer<Player> wayBack;

    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /** The menu file that lists the missions, and the list it draws a tile from for each one. */
    static final String FILE = "island-mission-list";

    static final String MISSIONS = "skyblock:mission-list";

    /** One mission as a tile acts on it: what it asks for and how far the island had come when it was drawn. */
    record Drawn(MissionDefinition definition, long counted, boolean completed) {}

    public IslandMissionsMenu(
            IslandMissionService missionService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable BedrockFormService bedrockFormService) {
        this.missionService = Objects.requireNonNull(missionService, "missionService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.bedrockFormService = bedrockFormService;
    }

    /**
     * The Bedrock screen is built after this window is, so it arrives here rather than in the
     * constructor. Until it does, a Bedrock player gets the chest window.
     */
    public void setBedrockFormService(@Nullable BedrockFormService bedrockFormService) {
        this.bedrockFormService = bedrockFormService;
    }

    public void open(Player player) {
        open(player, null);
    }

    /**
     * Opens the missions that come back as {@code which} says, every mission when it is null: the daily ones, the
     * weekly ones, or the challenges, which are finished once.
     */
    public void open(Player player, com.uxplima.uxmskyblock.core.domain.mission.@Nullable MissionRepeat which) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt =
                sessionCoordinator != null ? sessionCoordinator.activeProfile(rawUuid) : Optional.empty();

        if (activeOpt.isEmpty()) {
            player.sendMessage(
                    messages.render(player, "error.session_not_active").decoration(TextDecoration.ITALIC, false));
            return;
        }

        ProfileId profileId = activeOpt.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(messages.render(player, "menu.control.no_island")
                                .decoration(TextDecoration.ITALIC, false));
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            // A mission that came back since it was last worked on reads as not started.
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap =
                    missionService.currentProgress(islandId, profileId, java.time.Instant.now());
            // The file keeps no order among its missions, so the window keeps one of its own: the daily ones, the
            // weekly ones, then the challenges, each kind by branch and then by id.
            // Only what the island is offered: a mission of another mode, or one whose chain is not reached yet,
            // is not shown.
            List<MissionDefinition> all =
                    missionService.offeredMissions(islandId, profileId, java.time.Instant.now()).stream()
                            .filter(def -> which == null || def.repeat() == which)
                            .sorted(java.util.Comparator.comparingInt(
                                            (MissionDefinition def) -> kindOrder(def.repeat()))
                                    .thenComparing(MissionDefinition::branch)
                                    .thenComparing(def -> def.id().value()))
                            .toList();

            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                BedrockFormService forms = this.bedrockFormService;
                if (forms != null && forms.isBedrock(player)) {
                    openForm(forms, player, islandId, profileId, progressMap, all);
                    return;
                }
                SkyblockMenuEngine engine = this.menuEngine;
                if (engine != null
                        && engine.open(
                                player,
                                FILE,
                                Map.of(
                                        "island",
                                        islandId.value().toString(),
                                        "profile",
                                        profileId.value().toString()),
                                Map.of(MISSIONS, rows(player, progressMap, all)))) {
                    return;
                }
                SimpleGui gui = buildGui(player, islandId, profileId, progressMap, all);
                gui.open(player);
            });
        });
    }

    /**
     * The same window for a Bedrock player, as a native form rather than a chest they cannot use
     * properly. The buttons are the same missions in the same order, and a mission that takes items
     * submits them from here too.
     */
    private void openForm(
            BedrockFormService forms,
            Player player,
            IslandId islandId,
            ProfileId profileId,
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap,
            List<MissionDefinition> all) {
        List<BedrockFormService.Choice> choices = new ArrayList<>();
        for (MissionDefinition def : all) {
            MissionProgress progress = progressMap.get(def.id());
            long count = progress != null ? progress.progressCount() : 0L;
            boolean completed = progress != null && progress.completed();
            Component status = completed
                    ? messages.renderPlain(player, "menu.missions.status_completed")
                    : messages.renderPlain(
                            player,
                            "menu.missions.status_open",
                            Placeholder.unparsed("count", Long.toString(count)),
                            Placeholder.unparsed("required", def.requiredAmount() + lineCounts(def, progressMap)));
            String label = com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(messages.renderPlain(
                    player,
                    "menu.missions.form_button",
                    Placeholder.unparsed("mission", messages.words(player, def.displayName())),
                    Placeholder.component("status", status)));
            boolean submittable = !completed && def.triggerType() == MissionTriggerType.ITEM_SUBMIT;
            long drawnWith = count;
            choices.add(new BedrockFormService.Choice(label, () -> {
                if (submittable) {
                    handleManualItemSubmission(player, islandId, profileId, def, drawnWith);
                }
            }));
        }
        forms.openChoiceForm(player, "menu.missions.title", "menu.missions.form_body", choices);
    }

    public SimpleGui buildGui(
            Player player,
            IslandId islandId,
            ProfileId profileId,
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap,
            List<MissionDefinition> all) {
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "menu.missions.title")))
                .rows(6)
                .build();
        gui.filler().fill(GuiItem.display(SkyblockTiles.filler()));

        SkyblockTiles tiles = new SkyblockTiles(messages);
        int slot = 10;
        for (MissionDefinition def : all) {
            if (slot > 43) break;
            if (slot % 9 == 8) slot += 2; // skip border columns

            MissionProgress progress = progressMap.get(def.id());
            long count = progress != null ? progress.progressCount() : 0L;
            boolean completed = progress != null && progress.completed();
            boolean submits = !completed && def.triggerType() == MissionTriggerType.ITEM_SUBMIT;

            Material icon = iconOf(def, completed);

            Component status = completed
                    ? messages.renderPlain(player, "menu.missions.status_completed")
                    : messages.renderPlain(
                            player,
                            "menu.missions.status_open",
                            Placeholder.unparsed("count", Long.toString(count)),
                            Placeholder.unparsed("required", def.requiredAmount() + lineCounts(def, progressMap)));

            String branch = def.branch().name();
            ItemStack item = tiles.item(
                    icon,
                    player,
                    "tile:" + colourOf(completed) + " @menu.missions.tile " + factsOf(def, completed),
                    Placeholder.unparsed("entry_mission", messages.words(player, def.displayName())),
                    Placeholder.unparsed("entry_description", messages.words(player, def.description())),
                    Placeholder.unparsed("entry_branch", messages.named(player, "missions.branches", branch, branch)),
                    Placeholder.component("entry_status", status),
                    Placeholder.unparsed(
                            "entry_crystals", Long.toString(def.reward().crystals())),
                    Placeholder.unparsed("entry_currency", money(def.reward().currencyMinorUnits())),
                    Placeholder.unparsed("entry_exp", Long.toString(def.reward().islandExp())));

            GuiItem guiItem = GuiItem.button(item, event -> {
                event.setCancelled(true);
                if (submits) {
                    handleManualItemSubmission(player, islandId, profileId, def, count);
                }
            });

            gui.set(slot++, guiItem);
        }

        Consumer<Player> back = this.wayBack;
        if (back != null) {
            gui.set(49, GuiItem.button(tiles.button(Material.FEATHER, player, "menu.button.back"), event -> {
                event.setCancelled(true);
                back.accept(player);
            }));
        }
        return gui;
    }

    /**
     * Hands this window the engine that reads {@code menus/island-mission-list.conf}, and teaches the
     * engine what a click on a mission does. The window built here stays as the answer to a file that is
     * missing or will not parse.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(MISSIONS);
        engine.action(
                "skyblock:mission-submit",
                ctx -> MenuRow.handle(ctx.context(), Drawn.class)
                        .filter(drawn -> !drawn.completed()
                                && drawn.definition().triggerType() == MissionTriggerType.ITEM_SUBMIT)
                        .ifPresent(drawn -> {
                            Map<String, String> opened = ctx.context().arguments();
                            String island = opened.getOrDefault("island", "");
                            String profile = opened.getOrDefault("profile", "");
                            if (!island.isEmpty() && !profile.isEmpty()) {
                                handleManualItemSubmission(
                                        ctx.player(),
                                        IslandId.of(UUID.fromString(island)),
                                        new ProfileId(UUID.fromString(profile)),
                                        drawn.definition(),
                                        drawn.counted());
                            }
                        }));
    }

    /** One row per mission, in the order the file lists them, with every word its tile asks for. */
    List<MenuRow> rows(
            Player player,
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap,
            List<MissionDefinition> all) {
        List<MenuRow> rows = new ArrayList<>(all.size());
        for (MissionDefinition def : all) {
            MissionProgress progress = progressMap.get(def.id());
            long count = progress != null ? progress.progressCount() : 0L;
            boolean completed = progress != null && progress.completed();
            String branch = def.branch().name();
            Map<String, String> words = new java.util.HashMap<>();
            words.put("material", iconOf(def, completed).name());
            words.put("colour", colourOf(completed));
            words.put("facts", factsOf(def, completed));
            words.put("mission", messages.words(player, def.displayName()));
            words.put("description", messages.words(player, def.description()));
            words.put("branch", messages.named(player, "missions.branches", branch, branch));
            words.put(
                    "repeat",
                    messages.words(
                            player,
                            "@menu.missions.repeats." + def.repeat().name().toLowerCase(Locale.ROOT)));
            words.put(
                    "status",
                    completed
                            ? "<key:menu.missions.status_completed>"
                            : count + " / " + def.requiredAmount() + lineCounts(def, progressMap));
            words.put("crystals", Long.toString(def.reward().crystals()));
            words.put("currency", money(def.reward().currencyMinorUnits()));
            words.put("exp", Long.toString(def.reward().islandExp()));
            rows.add(new MenuRow(words, new Drawn(def, count, completed)));
        }
        return List.copyOf(rows);
    }

    /**
     * Where each line of a mission of several stands, as {@code  (12/64, 3/10)}, after the count of lines finished, or
     * nothing for a mission of one.
     */
    String lineCounts(
            MissionDefinition def,
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap) {
        if (def.triggerType() != MissionTriggerType.LINES) {
            return "";
        }
        List<MissionDefinition> lines = missionService.linesOf(def.id());
        if (lines.isEmpty()) {
            return "";
        }
        List<String> each = new ArrayList<>();
        for (MissionDefinition line : lines) {
            MissionProgress progress = progressMap.get(line.id());
            long count = progress == null ? 0L : Math.min(progress.progressCount(), line.requiredAmount());
            each.add(count + "/" + line.requiredAmount());
        }
        return " (" + String.join(", ", each) + ")";
    }

    /** Where a kind of mission stands in the window: the ones that come back soonest first. */
    static int kindOrder(com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat repeat) {
        return switch (repeat) {
            case DAILY -> 0;
            case WEEKLY -> 1;
            case ONCE -> 2;
        };
    }

    private static Material iconOf(MissionDefinition def, boolean completed) {
        return completed
                ? Material.ENCHANTED_BOOK
                : (def.triggerType() == MissionTriggerType.ITEM_SUBMIT ? Material.CHEST : Material.BOOK);
    }

    private static String colourOf(boolean completed) {
        return completed ? "good" : "0";
    }

    /**
     * The facts a mission's tile lists: a reward it does not pay is a row the tile leaves off, and a
     * mission a click cannot advance says nothing about clicking.
     */
    static String factsOf(MissionDefinition def, boolean completed) {
        StringBuilder facts = new StringBuilder("branch repeat progress");
        if (def.reward().crystals() > 0) {
            facts.append(" crystals");
        }
        if (def.reward().currencyMinorUnits() > 0) {
            facts.append(" currency");
        }
        if (def.reward().islandExp() > 0) {
            facts.append(" exp");
        }
        if (completed || def.triggerType() != MissionTriggerType.ITEM_SUBMIT) {
            facts.append(" -action");
        }
        return facts.toString();
    }

    private static String money(long minorUnits) {
        return String.format(Locale.US, "%.2f", minorUnits / 100.0);
    }

    /**
     * Hands this window the way back to the menu that opened it, so the bottom row reads "Back" rather
     * than leaving Escape as the only way out. Without one the window has no back button at all.
     */
    public void useWayBack(@Nullable Consumer<Player> wayBack) {
        this.wayBack = wayBack;
    }

    /** Package private so the guard against losing a player's items can drive it directly. */
    void handleManualItemSubmission(
            Player player, IslandId islandId, ProfileId profileId, MissionDefinition def, long alreadyCounted) {
        String filter = def.targetFilter();
        Material requiredMat = Material.matchMaterial(filter);
        if (requiredMat == null) {
            messages.send(player, "menu.missions.invalid_requirement", Placeholder.unparsed("filter", filter));
            return;
        }

        int count = TradableStacks.countOf(player, requiredMat);

        if (count <= 0) {
            messages.send(player, "menu.missions.nothing_to_submit", ItemNames.placeholder("item", requiredMat.name()));
            return;
        }

        // How many the mission still wanted when this window was drawn. Reading it again here would
        // be a query on the thread that owns the player, and the answer is already on the tile the
        // player just clicked. What the service actually credits is what it reports back, and
        // anything it did not take is handed straight back below.
        long needed = def.requiredAmount() - alreadyCounted;
        int toTake = (int) Math.min(count, needed);

        if (toTake <= 0) {
            return;
        }

        TradableStacks.take(player, requiredMat, toTake);

        final int taken = toTake;
        schedulerPort.async(() -> {
            long credited;
            try {
                credited = missionService
                        .submitManualItems(islandId, profileId, def.id(), taken, Instant.now())
                        .map(IslandMissionService.MissionSubmission::credited)
                        .orElse(0L);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.SEVERE,
                        "Crediting a manual mission submission failed, returning the items to the player",
                        e);
                credited = 0L;
            }

            long creditedFinal = credited;
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                // Whatever the mission did not take goes back. A window drawn a moment ago can say
                // the mission wants ten when another window has already handed in eight, and a
                // player who loses the other two to a counter that stopped rising has been robbed
                // by their own second window.
                int surplus = (int) (taken - creditedFinal);
                if (surplus > 0) {
                    returnItems(player, requiredMat, surplus);
                }
                if (creditedFinal <= 0) {
                    messages.send(
                            player, "menu.missions.submit_failed", ItemNames.placeholder("item", requiredMat.name()));
                    return;
                }
                messages.send(
                        player,
                        "menu.missions.submitted",
                        Placeholder.unparsed("amount", Long.toString(creditedFinal)),
                        ItemNames.placeholder("item", requiredMat.name()));
                open(player);
            });
        });
    }

    /**
     * Gives back what was taken when the submission was not credited.
     *
     * <p>The items leave the inventory before the progress is written, because the write is off the
     * entity thread and the inventory may not be touched from there. If the write then does nothing,
     * either because it failed or because the mission was already finished by somebody else, the
     * player has paid for nothing. What does not fit goes on the ground at their feet rather than
     * being dropped silently.
     */
    private void returnItems(Player player, Material material, int amount) {
        if (amount <= 0) {
            return;
        }
        TradableStacks.give(player, material, amount);
    }
}
