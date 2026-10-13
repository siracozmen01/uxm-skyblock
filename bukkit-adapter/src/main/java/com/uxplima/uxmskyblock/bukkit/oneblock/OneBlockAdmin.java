package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.World;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;

/**
 * {@code /isadmin oneblock}: set an island's phase or count, add to it, read where it stands, and lay its block
 * again. A change of count lays the block again, drawn from the phase the island stands in now.
 */
public final class OneBlockAdmin implements ModeAdminCommands.Mode {

    static final String KEY = "oneblock";

    private final OneBlockService service;
    private final OneBlockDraws draws;
    private final @org.jspecify.annotations.Nullable OneBlockBiomes biomes;

    public OneBlockAdmin(OneBlockService service) {
        this(service, OneBlockDraws.PLAIN, null);
    }

    /** With the biome of the phase an operator moves an island into given to it, when its file names one. */
    public OneBlockAdmin(
            OneBlockService service, OneBlockDraws draws, @org.jspecify.annotations.Nullable OneBlockBiomes biomes) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.draws = Objects.requireNonNull(draws, "draws must not be null");
        this.biomes = biomes;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin) {
        return Cmd.literal(KEY)
                .then(ModeAdminCommands.verb("setphase", CatalogPermissions.ADMIN_ONEBLOCK_SETPHASE)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(Cmd.argument("phase", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            service.phases().phases().stream()
                                                    .map(OneBlockPhase::key)
                                                    .filter(key -> key.startsWith(builder.getRemainingLowerCase()))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> setPhase(admin, ctx)))))
                .then(ModeAdminCommands.verb("setcount", CatalogPermissions.ADMIN_ONEBLOCK_SETCOUNT)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(Cmd.argument("count", LongArgumentType.longArg(0))
                                        .executes(ctx -> change(
                                                admin,
                                                ctx,
                                                island -> service.setBroken(
                                                        island, LongArgumentType.getLong(ctx, "count")))))))
                .then(ModeAdminCommands.verb("addcount", CatalogPermissions.ADMIN_ONEBLOCK_ADDCOUNT)
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .then(Cmd.argument("count", LongArgumentType.longArg())
                                        .executes(ctx -> change(
                                                admin,
                                                ctx,
                                                island -> service.addBroken(
                                                        island, LongArgumentType.getLong(ctx, "count")))))))
                .then(ModeAdminCommands.verb("check", CatalogPermissions.ADMIN_ONEBLOCK_CHECK)
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(ctx -> check(admin, ctx))))
                .then(ModeAdminCommands.verb("respawn", CatalogPermissions.ADMIN_ONEBLOCK_RESPAWN)
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(ctx -> respawn(admin, ctx))));
    }

    private int setPhase(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        String phase = StringArgumentType.getString(ctx, "phase");
        java.util.OptionalLong start = service.phases().startOf(phase);
        if (start.isEmpty()) {
            admin.send(
                    ctx.getSource().getSender(),
                    "isadmin.oneblock.unknown_phase",
                    Placeholder.unparsed("phase", phase),
                    Placeholder.unparsed(
                            "phases",
                            String.join(
                                    ", ",
                                    service.phases().phases().stream()
                                            .map(OneBlockPhase::key)
                                            .toList())));
            return Cmd.OK;
        }
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> {
            Optional<OneBlockPhases> way = service.phasesOf(island);
            java.util.OptionalLong along = way.map(read -> read.startOf(phase)).orElse(java.util.OptionalLong.empty());
            if (way.isPresent() && along.isEmpty()) {
                // A phase on a way the island did not pick: it cannot stand there.
                admin.send(
                        source.getSender(),
                        "isadmin.oneblock.off_way",
                        Placeholder.unparsed("phase", phase),
                        Placeholder.unparsed("target", target));
                return;
            }
            changed(admin, source, target, island, service.setBroken(island, along.orElse(start.getAsLong())));
        });
        return Cmd.OK;
    }

    private int change(
            ModeAdminCommands admin,
            CommandContext<CommandSourceStack> ctx,
            java.util.function.Function<IslandId, Optional<OneBlockService.Changed>> how) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> changed(admin, source, target, island, how.apply(island)));
        return Cmd.OK;
    }

    /** Tells the sender what an operator's change did, and lays the block again from the phase it stands in now. */
    private void changed(
            ModeAdminCommands admin,
            CommandSourceStack source,
            String target,
            IslandId island,
            Optional<OneBlockService.Changed> changed) {
        if (changed.isEmpty()) {
            notOneBlock(admin, source.getSender(), target);
            return;
        }
        OneBlockPhases way = service.phasesOf(island).orElse(service.phases());
        OneBlockPhases.Position now = way.positionAt(changed.get().now());
        admin.send(
                source.getSender(),
                "isadmin.oneblock.set",
                Placeholder.unparsed("target", target),
                Placeholder.unparsed("was", Long.toString(changed.get().was())),
                Placeholder.unparsed("now", Long.toString(changed.get().now())),
                Placeholder.unparsed("phase", title(admin, source.getSender(), now.phase())));
        layAgain(admin, source.getSender(), target, island, false);
        OneBlockBiomes painted = biomes;
        Optional<String> world = admin.locations().findLocation(island).map(found -> found.worldName());
        World loaded = world.map(Bukkit::getWorld).orElse(null);
        if (painted != null
                && loaded != null
                && !way.positionAt(changed.get().was())
                        .phase()
                        .key()
                        .equals(now.phase().key())) {
            service.island(island)
                    .ifPresent(read -> painted.paint(
                            island, loaded, read.x(), read.z(), now.phase().key()));
        }
    }

    private int check(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> {
            Optional<OneBlockProgressPort.OneBlockIsland> read = service.island(island);
            if (read.isEmpty()) {
                notOneBlock(admin, source.getSender(), target);
                return;
            }
            long broken = read.get().blocksBroken();
            OneBlockPhases way = service.phasesOf(island).orElse(service.phases());
            OneBlockPhases.Position at = way.positionAt(broken);
            admin.send(
                    source.getSender(),
                    "isadmin.oneblock.check",
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed("phase", title(admin, source.getSender(), at.phase())),
                    Placeholder.unparsed("phase_number", Integer.toString(at.index() + 1)),
                    Placeholder.unparsed(
                            "phase_count", Integer.toString(way.phases().size())),
                    Placeholder.unparsed("phase_blocks", Long.toString(at.intoPhase())),
                    Placeholder.unparsed("phase_length", Long.toString(at.phaseLength())),
                    Placeholder.unparsed("blocks_broken", Long.toString(broken)),
                    Placeholder.unparsed(
                            "at",
                            read.get().x() + " " + read.get().y() + " "
                                    + read.get().z()));
        });
        return Cmd.OK;
    }

    private int respawn(ModeAdminCommands admin, CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        admin.island(source, target, island -> layAgain(admin, source.getSender(), target, island, true));
        return Cmd.OK;
    }

    /** Lays the island's block again on its region, from the phase it stands in; {@code say} tells the sender. */
    private void layAgain(ModeAdminCommands admin, Audience sender, String target, IslandId island, boolean say) {
        Optional<OneBlockProgressPort.OneBlockIsland> read = service.island(island);
        Optional<String> block = service.blockNow(island);
        String phase = service.position(island).map(at -> at.phase().key()).orElse("");
        Optional<String> world = admin.locations().findLocation(island).map(found -> found.worldName());
        if (read.isEmpty() || block.isEmpty() || world.isEmpty()) {
            if (say) {
                notOneBlock(admin, sender, target);
            }
            return;
        }
        int x = read.get().x();
        int y = read.get().y();
        int z = read.get().z();
        admin.scheduler().onRegion(world.get(), x >> 4, z >> 4, () -> {
            World loaded = Bukkit.getWorld(world.get());
            if (loaded == null) {
                return;
            }
            OneBlockListener.lay(draws, loaded, x, y, z, block.get());
            var unused = draws.fill(loaded, x, y, z, block.get(), phase);
            if (say) {
                admin.send(
                        sender,
                        "isadmin.oneblock.respawned",
                        Placeholder.unparsed("target", target),
                        Placeholder.unparsed("block", draws.named(block.get())));
            }
        });
    }

    private static void notOneBlock(ModeAdminCommands admin, Audience sender, String target) {
        admin.send(sender, "isadmin.oneblock.not_oneblock", Placeholder.unparsed("target", target));
    }

    private static String title(ModeAdminCommands admin, Audience reader, OneBlockPhase phase) {
        return admin.messages().named(reader, "oneblock.phases", phase.key(), phase.key());
    }
}
