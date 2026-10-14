package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Locale;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;

/**
 * {@code /is top}: the leaderboard of a category or a metric, in its window or in chat, with the reader's own place. Split out of {@link IslandProgressionCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class TopVerbs {

    private final IslandProgressionCommands host;

    TopVerbs(IslandProgressionCommands host) {
        this.host = host;
    }

    int executeTop(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSourceStack src = ctx.getSource();
        String asked = category.trim().toLowerCase(Locale.ROOT);
        com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetrics registered = host.leaderboards.get();
        com.uxplima.uxmskyblock.api.NamespacedId metricId = com.uxplima.uxmskyblock.api.NamespacedId.of(asked);
        if (registered != null
                && com.uxplima.uxmskyblock.core.application.leaderboard.IslandMetricProviders.categoryOf(metricId)
                        .isEmpty()) {
            var provider = registered.provider(metricId);
            if (provider.isPresent()) {
                return executeMetricTop(src, registered, provider.get());
            }
        }
        LeaderboardCategory cat = com.uxplima.uxmskyblock.core.application.leaderboard.IslandMetricProviders.categoryOf(
                        metricId)
                .orElseGet(() -> switch (asked) {
                    case "worth" -> LeaderboardCategory.WORTH;
                    case "bank" -> LeaderboardCategory.BANK;
                    default -> LeaderboardCategory.LEVEL;
                });

        host.schedulerPort.async(() -> {
            var entries = host.islandLeaderboardService.getTop(cat, 10);
            // Where the caller's own island stands. The board prints ten and a server has hundreds,
            // so a player outside the ten used to learn nothing at all from a board about them. The
            // rank is read off the same cached board, not the database; only finding out which
            // island is theirs is a read, and only a player has one.
            IslandProgressionCommands.Rank ownRank = rankOf(src.getSender(), cat);
            // An island nobody named is called whose it is, which is a read, so it is done here.
            java.util.Map<IslandId, Component> unnamed = new java.util.HashMap<>();
            for (LeaderboardEntry entry : entries) {
                if (!entry.named()) {
                    unnamed.put(entry.islandId(), host.labels.of(src.getSender(), entry.islandId()));
                }
            }
            // A window of the board, each island under its owner's head, when the operator kept its file.
            com.uxplima.uxmskyblock.bukkit.menu.TopList window = host.topList.get();
            if (window != null && src.getSender() instanceof Player player) {
                java.util.Map<IslandId, java.util.UUID> owners = new java.util.HashMap<>();
                for (LeaderboardEntry entry : entries) {
                    host.islandLocationService
                            .findIsland(entry.islandId())
                            .ifPresent(island -> owners.put(
                                    entry.islandId(), island.ownerPlayerUuid().value()));
                }
                host.schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // A name by id is the server's cache of who has played, read on the player's thread.
                    java.util.Map<java.util.UUID, String> ownerNames = new java.util.HashMap<>();
                    owners.values().forEach(owner -> {
                        String name = org.bukkit.Bukkit.getOfflinePlayer(owner).getName();
                        if (name != null) {
                            ownerNames.put(owner, name);
                        }
                    });
                    java.util.List<com.uxplima.uxmskyblock.bukkit.menu.TopList.Entry> shown = entries.stream()
                            .map(entry -> {
                                java.util.UUID owner = owners.get(entry.islandId());
                                return new com.uxplima.uxmskyblock.bukkit.menu.TopList.Entry(
                                        entry.rank(),
                                        IslandProgressionCommands.plain(
                                                IslandProgressionCommands.nameOf(entry, unnamed)),
                                        IslandProgressionCommands.plain(scoreOf(player, cat, entry)),
                                        owner,
                                        owner == null ? null : ownerNames.get(owner));
                            })
                            .toList();
                    String board = IslandProgressionCommands.plain(host.messages.renderPlain(
                            player, "leaderboard.category_" + cat.name().toLowerCase(Locale.ROOT)));
                    if (!window.show(
                            player, board, IslandProgressionCommands.plain(rankWords(player, ownRank)), shown)) {
                        topInChat(src, cat, entries, unnamed, ownRank);
                    }
                });
                return;
            }
            host.schedulerPort.onGlobal(() -> topInChat(src, cat, entries, unnamed, ownRank));
        });

        return Cmd.OK;
    }
    /**
     * The board of a metric another plugin registered.
     *
     * <p>The owner is read off the main thread, as each shipped board is. The board's name comes from
     * the reader's language file under {@code leaderboard.metrics}, keyed by the id with its colon
     * written as an underscore, and otherwise from the owner.
     */
    int executeMetricTop(
            CommandSourceStack src,
            com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetrics registered,
            com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider provider) {
        host.schedulerPort.async(() -> {
            var board = registered.ranked(provider.metricId(), 10);
            host.schedulerPort.onGlobal(() -> {
                Audience audience = src.getSender();
                String key = provider.metricId().asString().replace(':', '_');
                String title = host.messages.named(audience, "leaderboard.metrics", key, provider.displayName());
                host.send(audience, "leaderboard.header", Placeholder.unparsed("category", title));
                if (board.isEmpty()) {
                    host.send(audience, "leaderboard.empty");
                    return;
                }
                for (var ranked : board) {
                    host.send(
                            audience,
                            "leaderboard.entry",
                            Placeholder.unparsed("place", Integer.toString(ranked.rank())),
                            Placeholder.unparsed("name", ranked.reading().displayName()),
                            Placeholder.component(
                                    "score",
                                    host.messages.renderPlain(
                                            audience,
                                            "leaderboard.score_value",
                                            Placeholder.unparsed("figure", ranked.formatted()))));
                }
            });
        });
        return Cmd.OK;
    }
    /** A board in chat: a header, a line for each island, and where the reader's own island stands. */
    void topInChat(
            CommandSourceStack src,
            LeaderboardCategory cat,
            java.util.List<LeaderboardEntry> entries,
            java.util.Map<IslandId, Component> unnamed,
            IslandProgressionCommands.Rank ownRank) {
        Audience audience = src.getSender();
        Component categoryName = host.messages.renderPlain(
                audience, "leaderboard.category_" + cat.name().toLowerCase(Locale.ROOT));
        host.send(audience, "leaderboard.header", Placeholder.component("category", categoryName));
        if (entries.isEmpty()) {
            host.send(audience, "leaderboard.empty");
        } else {
            for (LeaderboardEntry entry : entries) {
                host.send(
                        audience,
                        "leaderboard.entry",
                        Placeholder.unparsed("place", Integer.toString(entry.rank())),
                        Placeholder.component("name", IslandProgressionCommands.nameOf(entry, unnamed)),
                        Placeholder.component("score", scoreOf(audience, cat, entry)));
            }
        }
        if (ownRank == IslandProgressionCommands.Rank.NOT_PLACED) {
            host.send(audience, "leaderboard.your_rank_unplaced");
        } else if (ownRank.place() > 0) {
            host.send(
                    audience,
                    "leaderboard.your_rank",
                    Placeholder.unparsed("place", Integer.toString(ownRank.place())));
        }
    }
    /**
     * An island's score in the reader's words. A level is a word, and money is written through the catalogue,
     * where the operator names the currency; the stored entry spells both in English for the API.
     */
    Component scoreOf(Audience reader, LeaderboardCategory cat, LeaderboardEntry entry) {
        return cat == LeaderboardCategory.LEVEL
                ? host.messages.renderPlain(
                        reader,
                        "leaderboard.score_level",
                        Placeholder.unparsed("island_level", Long.toString(entry.score())))
                : host.messages.renderPlain(
                        reader,
                        "leaderboard.score_money",
                        Placeholder.unparsed(
                                "amount", com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(entry.score())));
    }
    /** Where the reader's own island stands, as the leaderboard window says it. */
    Component rankWords(Audience reader, IslandProgressionCommands.Rank rank) {
        if (rank == IslandProgressionCommands.Rank.NOT_PLACED) {
            return host.messages.renderPlain(reader, "menu.leaderboard.rank_unplaced");
        }
        if (rank.place() > 0) {
            return host.messages.renderPlain(
                    reader,
                    "menu.leaderboard.rank_placed",
                    Placeholder.unparsed("place", Integer.toString(rank.place())));
        }
        return host.messages.renderPlain(reader, "menu.leaderboard.rank_none");
    }
    /**
     * Where one sender's island stands on a board, or nothing to say.
     *
     * <p>A console has no island and a player without one has none either, and neither wants a line
     * about a rank. An island the board does not hold is a different answer from no island at all:
     * the board holds as many as the operator's cache capacity, and being past the end of it is
     * worth saying.
     */
    IslandProgressionCommands.Rank rankOf(Audience sender, LeaderboardCategory category) {
        if (!(sender instanceof Player player)) {
            return IslandProgressionCommands.Rank.NOTHING_TO_SAY;
        }
        return host.activeProfile(player)
                .flatMap(host.islandLocationService::findIslandId)
                .map(islandId -> {
                    java.util.OptionalInt place = host.islandLeaderboardService.getRank(category, islandId);
                    return place.isPresent()
                            ? new IslandProgressionCommands.Rank(place.getAsInt())
                            : IslandProgressionCommands.Rank.NOT_PLACED;
                })
                .orElse(IslandProgressionCommands.Rank.NOTHING_TO_SAY);
    }
}
