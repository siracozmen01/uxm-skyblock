package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.ActivityWindow;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.message.MessagePayload;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is activity}: what has happened on this island lately.
 *
 * <p>The feed, its table and its service have been here since the enterprise foundation work and
 * nothing ever read one. A log nobody can read is a table that only grows.
 */
public final class IslandActivityCommands {

    private static final int DEFAULT_LIMIT = 10;

    /**
     * The most lines the window is handed at once; it turns them into pages itself. More than a month of lines on a
     * busy island is more than anyone pages through, and the filters narrow to the rest.
     */
    private static final int WINDOW_LINES = 500;

    private final Supplier<@Nullable ActivityFeedService> activityServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandActivityCommands(
            Supplier<@Nullable ActivityFeedService> activityServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.activityServiceProvider =
                Objects.requireNonNull(activityServiceProvider, "activityServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** The name the operator gives a currency the bank keeps, by its id, written as the bank file writes it. */
    private java.util.function.Function<String, Optional<String>> currencyNames = id -> Optional.empty();

    /** Hands this command where the names of the bank's currencies are read. */
    public void useCurrencyNames(java.util.function.Function<String, Optional<String>> names) {
        this.currencyNames = Objects.requireNonNull(names, "names must not be null");
    }

    /** The window the feed is shown in, rather than lines in chat. */
    private java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable ActivityWindow> window =
            () -> null;

    /** Hands this command the window the feed is shown in. */
    public void useWindow(
            java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable ActivityWindow> window) {
        this.window = Objects.requireNonNull(window, "window must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("activity").executes(this::executeActivity);
    }

    private int executeActivity(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        show(player, ActivityWindow.View.LINES);
        return Cmd.OK;
    }

    /**
     * Reads the player's island feed and shows it: in its window under the filters the player set, or in chat when
     * the operator removed the window.
     */
    public void show(Player player, ActivityWindow.View view) {
        ActivityFeedService service = activityServiceProvider.get();
        if (service == null) {
            send(player, "activity.disabled");
            return;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ActivityWindow shown = window.get();
        if (shown != null) {
            shown.onChange(this::show);
        }

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            String island = optIsland.get().value().toString();
            Instant now = Instant.now();
            if (shown != null) {
                ActivityQuery query = shown.filterOf(player.getUniqueId()).query(island, now);
                if (view == ActivityWindow.View.TOTALS) {
                    List<ActivityWindow.Total> totals = service.totals(query).stream()
                            .map(total -> new ActivityWindow.Total(
                                    nameOf(total.player()),
                                    total.type() == ActivityEventType.BANK_DEPOSIT,
                                    amountOf(player, total.currency(), total.minorUnits()),
                                    total.lines()))
                            .toList();
                    schedulerPort.onEntity(playerUuid, () -> {
                        if (!shown.showTotals(player, totals, IslandActivityCommands::nameOf)) {
                            send(player, "activity.no_window");
                        }
                    });
                    return;
                }
                List<ActivityEvent> events =
                        service.page(query, 0, WINDOW_LINES).events();
                List<UUID> members = membersOf(service, island, query);
                schedulerPort.onEntity(playerUuid, () -> {
                    List<ActivityWindow.Line> lines = events.stream()
                            .map(event -> {
                                String ago =
                                        DurationText.coarse(messages, player, Duration.between(event.createdAt(), now));
                                // The line says how long ago itself, as it does in chat.
                                return new ActivityWindow.Line(
                                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                                .plainText()
                                                .serialize(lineOf(player, event, ago))
                                                .strip(),
                                        ago);
                            })
                            .toList();
                    if (!shown.showLines(player, lines, members, IslandActivityCommands::nameOf)) {
                        sayInChat(player, events, now);
                    }
                });
                return;
            }
            List<ActivityEvent> events = service.getRecentActivities(island, DEFAULT_LIMIT);
            schedulerPort.onEntity(playerUuid, () -> sayInChat(player, events, now));
        });
    }

    /** The feed as lines in chat, as it was before it had a window. */
    private void sayInChat(Player player, List<ActivityEvent> events, Instant now) {
        send(player, "activity.header");
        if (events.isEmpty()) {
            send(player, "activity.empty");
            return;
        }
        for (ActivityEvent event : events) {
            sendOne(player, event, DurationText.coarse(messages, player, Duration.between(event.createdAt(), now)));
        }
    }

    /**
     * The members the island's feed names, in the order they last appear, for the member filter to move through.
     * The member the filter shows stays in the list even when their lines have aged out of it.
     */
    private static List<UUID> membersOf(ActivityFeedService service, String island, ActivityQuery query) {
        java.util.LinkedHashSet<UUID> known = new java.util.LinkedHashSet<>();
        for (ActivityEvent event :
                service.page(ActivityQuery.all(island), 0, WINDOW_LINES).events()) {
            if (event.actorPlayer() != null) {
                known.add(event.actorPlayer());
            }
        }
        if (query.player() != null) {
            known.add(query.player());
        }
        return List.copyOf(known);
    }

    /** The name a player goes by, or the start of their id when the server never saw them. */
    private static String nameOf(UUID player) {
        String name = org.bukkit.Bukkit.getOfflinePlayer(player).getName();
        return name != null ? name : player.toString().substring(0, 8);
    }

    /**
     * An amount the bank moved, written for the reader: the server's money in its units, an operator currency whole
     * and by the name the operator gives it.
     */
    private String amountOf(Player reader, String currency, long minorUnits) {
        if (ActivityAmount.PRIMARY.equals(currency)) {
            return java.math.BigDecimal.valueOf(minorUnits, 2)
                    .stripTrailingZeros()
                    .toPlainString();
        }
        return minorUnits + " " + readable(reader, "currency", currency);
    }

    /**
     * One stored value in this viewer's language.
     *
     * <p>A value stored as {@code @key}, such as a mission's name, is read from the catalogue. A row
     * written before roles, warp categories and presets were stored that way holds the bare id, such
     * as {@code GENERAL} or {@code classic}, and is read the same way by what it names.
     */
    private String readable(Player player, String name, String value) {
        if (value.startsWith("@")) {
            return messages.words(player, value);
        }
        return switch (name) {
            // A currency the bank keeps is stored by its id, and named as the operator names it today.
            case "currency" ->
                currencyNames
                        .apply(value)
                        .map(written -> messages.words(player, written))
                        .orElse(value);
            case "role" -> messages.named(player, "roles", value, value);
            case "category" -> messages.named(player, "warp.categories", value, value);
            case "preset" ->
                messages.has("presets." + value + ".name")
                        ? messages.words(player, "@presets." + value + ".name")
                        : value;
            default -> value;
        };
    }

    /**
     * Writes one line of the feed out in the reader's own language.
     *
     * <p>What was stored is the name of a message and the values it has holes for, never the
     * sentence, so a player who reads Turkish reads Turkish and an operator who rewrites a line
     * rewrites the old entries with it.
     *
     * <p>An entry naming a message the catalogue does not have falls back to the plain line, so a
     * row written before a language file was edited still reads.
     */
    private void sendOne(Player player, ActivityEvent event, String ago) {
        Component line = lineOf(player, event, ago);
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                player.sendMessage(line);
            }
        });
    }

    /** One line of the feed in the reader's language, with {@code ago} where the line asks how long ago. */
    private Component lineOf(Player player, ActivityEvent event, String ago) {
        java.util.Map<String, String> values = MessagePayload.unpack(event.payloadData());
        String key = event.payloadTypeId();
        if (messages.has(key)) {
            java.util.List<TagResolver> resolvers = new java.util.ArrayList<>(values.size() + 1);
            for (java.util.Map.Entry<String, String> value : values.entrySet()) {
                resolvers.add(Placeholder.unparsed(value.getKey(), readable(player, value.getKey(), value.getValue())));
            }
            resolvers.add(Placeholder.unparsed("ago", ago));
            return messages.render(player, key, resolvers.toArray(new TagResolver[0]));
        }
        return messages.render(
                player,
                "activity.entry",
                Placeholder.unparsed("type", event.eventType().name()),
                Placeholder.unparsed("text", values.getOrDefault("body", event.payloadData())),
                Placeholder.unparsed("ago", ago));
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
