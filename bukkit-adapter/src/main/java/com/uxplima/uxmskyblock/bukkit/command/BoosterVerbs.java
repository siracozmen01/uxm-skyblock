package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * {@code /isadmin booster}: a booster applied to an island, and what the operator is told it did. Split out of {@link IslandMechanicsCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class BoosterVerbs {

    private final IslandMechanicsCommands host;

    BoosterVerbs(IslandMechanicsCommands host) {
        this.host = host;
    }

    int executeAdminApplyBooster(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        IslandBoosterService boosterService = host.boosterServiceProvider.get();
        if (boosterService == null) {
            host.send(sender, "booster.disabled");
            return Cmd.OK;
        }
        if (!sender.hasPermission("uxmskyblock.admin.booster")) {
            host.send(sender, "booster.no_permission");
            return Cmd.OK;
        }

        String catStr = StringArgumentType.getString(ctx, "category");
        Optional<BoosterCategory> optCategory = BoosterCategory.parse(catStr);
        if (optCategory.isEmpty()) {
            host.send(
                    sender,
                    "booster.invalid_category",
                    Placeholder.unparsed("category", catStr),
                    Placeholder.unparsed("categories", IslandMechanicsCommands.availableBoosterCategories()));
            return Cmd.OK;
        }

        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        String durStr = StringArgumentType.getString(ctx, "duration");
        Duration duration = IslandMechanicsCommands.parseDurationString(durStr);
        if (duration.isZero() || duration.isNegative()) {
            host.send(sender, "booster.invalid_duration", Placeholder.unparsed("duration", durStr));
            return Cmd.OK;
        }

        if (!(sender instanceof Player player)) {
            host.send(sender, "booster.console_needs_island");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId boosterProfile = optProfile.get();
        // Read on the thread that owns this player, because the feed line is written off it.
        String playerName = player.getName();
        host.schedulerPort.async(() -> {
            Optional<IslandId> optIsland = host.islandLocationService.findIslandId(boosterProfile);
            if (optIsland.isEmpty()) {
                host.send(player, "error.no_island");
                return;
            }
            BoosterApplyResult result = boosterService.applyBooster(
                    optIsland.get(), optCategory.get(), multiplier, duration, Instant.now());
            if (IslandMechanicsCommands.isApplied(result)) {
                host.activityLog.recordForMembers(
                        optIsland.get(),
                        boosterProfile,
                        ActivityEventType.BOOSTER_ACTIVATED,
                        "activity.booster_activated",
                        java.util.Map.of(
                                "player",
                                playerName,
                                "category",
                                optCategory.get().name(),
                                "multiplier",
                                String.format(java.util.Locale.ROOT, "%.2f", multiplier)));
            }
            reportBoosterResult(player, optCategory.get(), result);
        });
        return Cmd.OK;
    }
    /**
     * Every outcome of applying a booster says what happened. The old line reported the result
     * class name inside a green sentence, so a rejected booster and a disabled category both read
     * as a success with a Java class name at the end of them.
     */
    void reportBoosterResult(Player player, BoosterCategory category, BoosterApplyResult result) {
        TagResolver categoryName = Placeholder.unparsed(
                "category", host.messages.named(player, "booster.categories", category.name(), category.displayName()));
        switch (result) {
            case BoosterApplyResult.Success success ->
                host.send(
                        player,
                        "booster.applied",
                        categoryName,
                        IslandMechanicsCommands.multiplierOf(success.effectiveMultiplier()),
                        Placeholder.unparsed(
                                "duration", DurationText.of(host.messages, player, success.remainingDuration())));
            case BoosterApplyResult.DurationExtended extended ->
                host.send(
                        player,
                        extended.capped() ? "booster.duration_extended_capped" : "booster.duration_extended",
                        categoryName,
                        IslandMechanicsCommands.multiplierOf(extended.effectiveMultiplier()),
                        Placeholder.unparsed(
                                "duration", DurationText.of(host.messages, player, extended.totalDuration())));
            case BoosterApplyResult.MultiplierStacked stacked ->
                host.send(
                        player,
                        stacked.capped() ? "booster.multiplier_stacked_capped" : "booster.multiplier_stacked",
                        categoryName,
                        IslandMechanicsCommands.multiplierOf(stacked.effectiveMultiplier()),
                        Placeholder.unparsed(
                                "duration", DurationText.of(host.messages, player, stacked.remainingDuration())));
            case BoosterApplyResult.Replaced replaced ->
                host.send(
                        player,
                        "booster.replaced",
                        categoryName,
                        IslandMechanicsCommands.multiplierOf(
                                replaced.newBooster().multiplier()));
            case BoosterApplyResult.RejectedLowerTier rejected ->
                host.send(
                        player,
                        "booster.rejected_lower_tier",
                        categoryName,
                        Placeholder.unparsed(
                                "current", IslandMechanicsCommands.formatMultiplier(rejected.currentMultiplier())),
                        Placeholder.unparsed(
                                "attempted", IslandMechanicsCommands.formatMultiplier(rejected.attemptedMultiplier())));
            case BoosterApplyResult.CategoryDisabled disabled ->
                host.send(
                        player,
                        "booster.category_disabled",
                        Placeholder.unparsed(
                                "category",
                                host.messages.named(
                                        player,
                                        "booster.categories",
                                        disabled.category().name(),
                                        disabled.category().displayName())));
        }
    }
}
