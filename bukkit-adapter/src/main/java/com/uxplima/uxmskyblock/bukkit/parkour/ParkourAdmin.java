package com.uxplima.uxmskyblock.bukkit.parkour;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * {@code /isadmin parkour resettimes}: forgets the runs on a course, of every runner or of one, so the board starts
 * again and the next finish is a first.
 */
public final class ParkourAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "parkour";

    private final ParkourService service;
    private final Function<String, Optional<UUID>> players;
    private volatile com.uxplima.uxmskyblock.core.application.parkour.@org.jspecify.annotations.Nullable ParkourSeasonService
            seasons;

    /** @param players the player a name stands for, among those the server has seen */
    public ParkourAdmin(ParkourService service, Function<String, Optional<UUID>> players) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.players = Objects.requireNonNull(players, "players must not be null");
    }

    /** Forgets the season times with the runs, so a reset course starts every board again. */
    public ParkourAdmin seasons(com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService boards) {
        this.seasons = Objects.requireNonNull(boards, "boards must not be null");
        return this;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("resettimes", CatalogPermissions.ADMIN_PARKOUR_RESETTIMES)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .executes(ctx -> reset(admin, ctx, null))
                                .then(Cmd.argument("runner", StringArgumentType.word())
                                        .executes(ctx ->
                                                reset(admin, ctx, StringArgumentType.getString(ctx, "runner"))))));
    }

    private int reset(
            ModeAdminCommands admin,
            CommandContext<CommandSourceStack> ctx,
            @org.jspecify.annotations.Nullable String runnerName) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, course -> {
            if (!service.isCourse(course)) {
                admin.send(source.getSender(), "isadmin.parkour.not_course", Placeholder.unparsed("target", target));
                return;
            }
            Optional<PlayerUuid> runner = Optional.empty();
            if (runnerName != null) {
                Optional<UUID> found = players.apply(runnerName);
                if (found.isEmpty()) {
                    admin.send(
                            source.getSender(),
                            "isadmin.parkour.unknown_runner",
                            Placeholder.unparsed("runner", runnerName));
                    return;
                }
                runner = Optional.of(PlayerUuid.of(found.get()));
            }
            int cleared = service.clear(course, runner);
            com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService boards = seasons;
            if (boards != null) {
                boards.clear(course, runner);
            }
            admin.send(
                    source.getSender(),
                    runnerName == null ? "isadmin.parkour.reset" : "isadmin.parkour.reset_runner",
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed("runner", runnerName == null ? "" : runnerName),
                    Placeholder.unparsed("count", Integer.toString(cleared)));
        });
        return Cmd.OK;
    }
}
