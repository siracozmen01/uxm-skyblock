package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseDisasterBackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * The two administrative commands that cannot be undone: restore and delete.
 *
 * <p>They sat among the ones that can, so freezing an island and erasing one read the same on the
 * page. These two write over a world and the other four do not.
 *
 * <p>A restore never rewinds money. Which tables it does put back is the caller's choice among the
 * three {@link RestoreMode} values, and none of the three writes a bank.
 */
public final class IslandAdminRestoreCommands {

    /** {@code /isadmin backup} and its restore from a set: one island copied, and put back from the copy an operator names. */
    private final IslandBackupVerbs islandBackups = new IslandBackupVerbs(this);

    /** {@code /isadmin backup database} and its restore: the whole database copied, and put back only after the typed confirmation. */
    private final DatabaseRestoreVerbs databaseVerbs = new DatabaseRestoreVerbs(this);

    private final Supplier<@Nullable IslandRestoreService> restoreServiceProvider;
    final Supplier<@Nullable BackupService> backupServiceProvider;
    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    final Supplier<@Nullable StorageBucket> backupBucketProvider;
    final Supplier<@Nullable IslandBackupService> islandBackupServiceProvider;

    /** The whole database backup the persistence specification publishes and nothing ever took. */
    volatile Supplier<@Nullable DatabaseDisasterBackupService> databaseBackupServiceProvider = () -> null;

    final IslandLocationService islandLocationService;
    final @Nullable PlayerSessionCoordinator sessionCoordinator;
    final SchedulerPort schedulerPort;
    private final Messages messages;

    public IslandAdminRestoreCommands(
            Supplier<@Nullable IslandRestoreService> restoreServiceProvider,
            Supplier<@Nullable BackupService> backupServiceProvider,
            Supplier<@Nullable IslandRecycleService> recycleServiceProvider,
            Supplier<@Nullable StorageBucket> backupBucketProvider,
            Supplier<@Nullable IslandBackupService> islandBackupServiceProvider,
            IslandLocationService islandLocationService,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages) {
        this.restoreServiceProvider =
                Objects.requireNonNull(restoreServiceProvider, "restoreServiceProvider must not be null");
        this.backupServiceProvider =
                Objects.requireNonNull(backupServiceProvider, "backupServiceProvider must not be null");
        this.recycleServiceProvider =
                Objects.requireNonNull(recycleServiceProvider, "recycleServiceProvider must not be null");
        this.backupBucketProvider =
                Objects.requireNonNull(backupBucketProvider, "backupBucketProvider must not be null");
        this.islandBackupServiceProvider =
                Objects.requireNonNull(islandBackupServiceProvider, "islandBackupServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /**
     * {@code /is admin rollback <island> [mode]}: put one island back from its latest backup.
     *
     * <p>Four documents publish this command and nothing answered it. {@code /is admin restore}
     * takes a backup set id, which an operator looking at a griefed island does not have; what they
     * have is the island. This finds its newest finished backup and restores that.
     *
     * <p>It restores one island and never a database. That is not a detail of the implementation:
     * the persistence specification says a whole database is never restored as a side effect of a
     * root rollback, and this reaches the same IslandRestoreService the backup id form does.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRollback() {
        return Cmd.literal("rollback")
                .requires(src -> src.getSender().hasPermission("uxmskyblock.admin.restore")
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                .then(Cmd.argument("island", StringArgumentType.word())
                        .executes(ctx -> executeRollback(ctx, RestoreMode.safeDefault()))
                        .then(Cmd.argument("mode", StringArgumentType.word()).executes(this::executeRollbackWithMode)));
    }

    private int executeRollbackWithMode(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "mode");
        for (RestoreMode mode : RestoreMode.values()) {
            if (mode.name().equalsIgnoreCase(raw)) {
                return executeRollback(ctx, mode);
            }
        }
        send(
                ctx.getSource().getSender(),
                "admin.restore_unknown_mode",
                Placeholder.unparsed("mode", raw),
                Placeholder.unparsed("modes", availableRestoreModes()));
        return Cmd.OK;
    }

    private int executeRollback(CommandContext<CommandSourceStack> ctx, RestoreMode mode) {
        CommandSender sender = ctx.getSource().getSender();
        IslandRestoreService rService = restoreServiceProvider.get();
        if (rService == null) {
            send(sender, "admin.restore_not_configured");
            return Cmd.OK;
        }
        String target = StringArgumentType.getString(ctx, "island");

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland =
                    IslandAdminCommands.resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optIsland.isEmpty()) {
                send(sender, "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }
            Optional<BackupCatalogRecord> latest = newestFinishedBackup(rService, optIsland.get());
            if (latest.isEmpty()) {
                send(
                        sender,
                        "admin.rollback_no_backup",
                        Placeholder.unparsed("island", optIsland.get().value().toString()));
                return;
            }
            send(
                    sender,
                    "admin.rollback_starting",
                    Placeholder.unparsed("island", optIsland.get().value().toString()),
                    Placeholder.unparsed("backup", latest.get().backupSetId().toString()),
                    Placeholder.unparsed("mode", mode.name()));
            islandBackups.restoreFromSet(
                    sender, rService, latest.get().backupSetId().toString(), mode);
        });
        return Cmd.OK;
    }

    /** The newest backup of this island that finished, because a half written one restores nothing. */
    private static Optional<BackupCatalogRecord> newestFinishedBackup(
            IslandRestoreService restoreService, IslandId islandId) {
        return restoreService
                .catalogPort()
                .findByRoot("ISLAND", islandId.value().toString())
                .stream()
                .filter(record -> record.state() == BackupLifecycleState.AVAILABLE)
                .max(Comparator.comparing(BackupCatalogRecord::createdAt));
    }

    /**
     * {@code /is admin backup <island>}: make the backup a restore puts back.
     *
     * <p>Everything on the reading side was here and nothing ever wrote one, so an administrator
     * could restore backups that could not exist. {@code /is admin rollback} looks for the newest
     * finished backup of an island and, until this command, never found one.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildBackup() {
        return Cmd.literal("backup")
                .requires(src -> src.getSender().hasPermission("uxmskyblock.admin.restore")
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                // The specification publishes two kinds of backup. This is the second one, and
                // until now nothing could take it: the port, its adapter and the word in the
                // catalog table were all written and no command drove any of them.
                .then(Cmd.literal("database").executes(databaseVerbs::executeDatabaseBackup))
                .then(Cmd.argument("island", StringArgumentType.word()).executes(islandBackups::executeBackup));
    }

    /** Tells this command group what takes a whole database backup. */
    public void useDatabaseBackup(Supplier<@Nullable DatabaseDisasterBackupService> provider) {
        this.databaseBackupServiceProvider = Objects.requireNonNull(provider, "provider must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRestore() {
        return Cmd.literal("restore")
                .requires(src -> src.getSender().hasPermission("uxmskyblock.admin.restore")
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                // Putting the whole database back is its own verb. It is not an island restore
                // with a flag turned on, and the island restore refuses a database backup set.
                .then(Cmd.literal("database")
                        .then(Cmd.argument("backupId", StringArgumentType.word())
                                .executes(this::executeDatabaseRestoreRequest)
                                .then(Cmd.argument("code", StringArgumentType.word())
                                        .executes(databaseVerbs::executeDatabaseRestoreConfirm))))
                .then(Cmd.argument("backupId", StringArgumentType.word())
                        .executes(ctx -> executeAdminRestore(ctx, RestoreMode.safeDefault()))
                        .then(Cmd.argument("mode", StringArgumentType.word())
                                .executes(this::executeAdminRestoreWithMode)));
    }

    /**
     * {@code /is admin restore database <backupId>}: asks, and gives back a code.
     *
     * <p>Putting one of these back replaces every island, every bank and every inventory on the
     * server at once. An island reset asks for four digits before it erases one island, so this
     * asks for four before it erases all of them.
     */
    private int executeDatabaseRestoreRequest(CommandContext<CommandSourceStack> ctx) {
        return databaseVerbs.withDatabaseRestore(ctx, (sender, service, requester, backupSetId) -> {
            DatabaseDisasterBackupService.RestoreOutcome outcome = service.requestRestore(requester, backupSetId);
            if (outcome instanceof DatabaseDisasterBackupService.RestoreOutcome.CodeIssued issued) {
                send(
                        sender,
                        "admin.database_restore_confirm",
                        Placeholder.unparsed("backup", backupSetId.toString()),
                        Placeholder.unparsed("code", issued.code()));
            }
        });
    }

    @FunctionalInterface
    interface DatabaseRestoreWork {
        void run(
                CommandSender sender,
                DatabaseDisasterBackupService service,
                ProfileId requester,
                BackupSetId backupSetId);
    }

    /**
     * Who is asking, as a profile.
     *
     * <p>The code is held against the asker, so the asker has to be somebody the plugin can name.
     * A console has no profile and so cannot hold a code; it is not shut out of anything it could
     * do before, because before this nothing could do it at all.
     */
    Optional<ProfileId> requesterOf(CommandSender sender) {
        if (sessionCoordinator == null || !(sender instanceof org.bukkit.entity.Player player)) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    public int executeAdminRestoreWithMode(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "mode");
        for (RestoreMode mode : RestoreMode.values()) {
            if (mode.name().equalsIgnoreCase(raw)) {
                return executeAdminRestore(ctx, mode);
            }
        }
        send(
                ctx.getSource().getSender(),
                "admin.restore_unknown_mode",
                Placeholder.unparsed("mode", raw),
                Placeholder.unparsed("modes", availableRestoreModes()));
        return Cmd.OK;
    }

    private static String availableRestoreModes() {
        return java.util.Arrays.stream(RestoreMode.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public int executeAdminRestore(CommandContext<CommandSourceStack> ctx, RestoreMode mode) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("uxmskyblock.admin.restore")
                && !sender.hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                && !sender.isOp()) {
            send(sender, "admin.restore_no_permission");
            return Cmd.OK;
        }

        IslandRestoreService rService = restoreServiceProvider.get();
        if (rService == null) {
            send(sender, "admin.restore_not_configured");
            return Cmd.OK;
        }

        String backupIdStr = StringArgumentType.getString(ctx, "backupId");
        send(
                sender,
                "admin.restore_starting",
                Placeholder.unparsed("backup", backupIdStr),
                Placeholder.unparsed("mode", mode.name()));

        islandBackups.restoreFromSet(sender, rService, backupIdStr, mode);

        return Cmd.OK;
    }

    public int executeAdminDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(src.getSender(), "admin.recycle_disabled");
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland =
                    IslandAdminCommands.resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optIsland.isEmpty()) {
                send(src.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optIsland.get();
            send(
                    src.getSender(),
                    "admin.delete_starting",
                    Placeholder.unparsed("island", islandId.value().toString()));
            // An admin bypass skips the ownership and challenge checks, so the requester is only ever
            // read when the bypass is off. It used to be a fresh random UUID: a profile that has
            // never existed, in the one field that says who asked for the island to be erased. The
            // admin's own profile is the true answer, and the island's owner is the honest fallback
            // when the sender is the console and has none.
            ProfileId requester = adminProfile(src.getSender()).orElse(islandOwnerOrPlaceholder(islandId));
            var unusedAdminReset = recycleService
                    .executeReset(requester, islandId, null, true)
                    .thenAccept(result -> {
                        schedulerPort.onGlobal(() -> {
                            if (result instanceof IslandRecycleService.RecycleResult.Success) {
                                send(
                                        src.getSender(),
                                        "admin.delete_success",
                                        Placeholder.unparsed(
                                                "island", islandId.value().toString()));
                            } else {
                                send(
                                        src.getSender(),
                                        "admin.delete_failed",
                                        Placeholder.unparsed(
                                                "island", islandId.value().toString()));
                            }
                        });
                    });
        });
        return Cmd.OK;
    }

    void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    void send(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }

    /** The profile of the staff member who typed the command, when a player typed it. */
    private Optional<ProfileId> adminProfile(CommandSender sender) {
        if (sessionCoordinator == null || !(sender instanceof Player player)) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    /**
     * The island's own owner, for a console delete that has no staff profile behind it.
     *
     * <p>Naming the owner is not naming the actor, and it is still better than a UUID that belongs
     * to nobody: every profile this returns is one that exists.
     */
    private ProfileId islandOwnerOrPlaceholder(IslandId islandId) {
        return islandLocationService.findOwnerProfileId(islandId).orElseGet(() -> new ProfileId(islandId.value()));
    }
}
