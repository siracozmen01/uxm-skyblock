package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Interactive double-confirmation GUI for island reset and deletion requests.
 */
public final class IslandResetConfirmationMenu {

    private final IslandRecycleService recycleService;
    private final IslandStoragePort islandStoragePort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private volatile @Nullable BedrockFormService bedrockFormService;
    private final Messages messages;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /** The menu file that asks, and what each viewer with it open would confirm or call off. */
    static final String FILE = "island-reset";

    private final Map<UUID, Pending> pending = new java.util.concurrent.ConcurrentHashMap<>();

    /** One open question: whose reset it is, and what confirming it does. */
    private record Pending(ProfileId profileId, Runnable onConfirm) {}

    public IslandResetConfirmationMenu(
            IslandRecycleService recycleService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            @Nullable BedrockFormService bedrockFormService,
            Messages messages) {
        this.recycleService = Objects.requireNonNull(recycleService, "recycleService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.bedrockFormService = bedrockFormService;
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public IslandResetConfirmationMenu(
            IslandRecycleService recycleService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages) {
        this(recycleService, islandStoragePort, sessionCoordinator, schedulerPort, null, messages);
    }

    public void setBedrockFormService(@Nullable BedrockFormService bedrockFormService) {
        this.bedrockFormService = bedrockFormService;
    }

    /**
     * Hands this window the engine that reads {@code menus/island-reset.conf}, and teaches the engine
     * what confirming and calling off do. The window built here stays as the answer to a file that is
     * missing or will not parse.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine == null) {
            return;
        }
        engine.action("skyblock:reset-confirm", ctx -> {
            Pending asked = pending.remove(ctx.player().getUniqueId());
            ctx.player().closeInventory();
            if (asked != null) {
                asked.onConfirm().run();
            }
        });
        engine.action("skyblock:reset-cancel", ctx -> {
            Pending asked = pending.remove(ctx.player().getUniqueId());
            ctx.player().closeInventory();
            if (asked != null) {
                recycleService.cancelResetChallenge(asked.profileId());
                messages.send(ctx.player(), "menu.reset.cancelled");
            }
        });
    }

    /**
     * Opens the confirmation for {@code player}.
     *
     * <p>Two buttons, erase and keep. The window showed a code as well, for a player to type in chat
     * instead, and a player who had the window open had no use for it.
     *
     * @param onConfirm what confirming does. It is the command's own confirmation, so a reset from
     *     here is held to the same rules as a typed one: the allowance, the double click guard and
     *     the inventory the operator asked to have emptied.
     */
    public void open(Player player, Runnable onConfirm) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt =
                sessionCoordinator != null ? sessionCoordinator.activeProfile(rawUuid) : Optional.empty();

        if (activeOpt.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }

        ProfileId profileId = activeOpt.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        messages.send(player, "error.no_island");
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (bedrockFormService != null && bedrockFormService.isBedrock(player)) {
                    bedrockFormService.openConfirmationModal(
                            player,
                            legacy(messages.renderPlain(player, "menu.reset.title")),
                            legacy(messages.renderPlain(player, "menu.reset.form_body")),
                            legacy(messages.renderPlain(player, "menu.reset.confirm.title")),
                            legacy(messages.renderPlain(player, "menu.reset.cancel.title")),
                            onConfirm,
                            () -> {
                                recycleService.cancelResetChallenge(profileId);
                                messages.send(player, "menu.reset.cancelled");
                            });
                    return;
                }
                SkyblockMenuEngine engine = this.menuEngine;
                if (engine != null) {
                    pending.put(rawUuid, new Pending(profileId, onConfirm));
                    if (engine.open(player, FILE, Map.of())) {
                        return;
                    }
                    pending.remove(rawUuid);
                }
                SimpleGui gui = buildGui(player, profileId, islandId, onConfirm);
                gui.open(player);
            });
        });
    }

    /** A Floodgate form takes a string, so a component has to be flattened for it. */
    private static String legacy(net.kyori.adventure.text.Component component) {
        return com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(component);
    }

    public SimpleGui buildGui(Player player, ProfileId profileId, IslandId islandId, Runnable onConfirm) {
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "menu.reset.title")))
                .rows(3)
                .build();
        gui.filler().fill(GuiItem.display(SkyblockTiles.filler()));

        SkyblockTiles tiles = new SkyblockTiles(messages);
        gui.set(11, GuiItem.button(tiles.item(Material.RED_CONCRETE, player, "tile:bad @menu.reset.confirm"), event -> {
            player.closeInventory();
            onConfirm.run();
        }));
        gui.set(
                15,
                GuiItem.button(tiles.item(Material.GREEN_CONCRETE, player, "tile:good @menu.reset.cancel"), event -> {
                    player.closeInventory();
                    recycleService.cancelResetChallenge(profileId);
                    messages.send(player, "menu.reset.cancelled");
                }));
        return gui;
    }
}
