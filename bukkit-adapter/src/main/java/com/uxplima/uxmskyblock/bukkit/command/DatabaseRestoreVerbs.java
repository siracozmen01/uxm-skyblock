package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Optional;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseDisasterBackupService;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;

/**
 * {@code /isadmin backup database} and its restore: the whole database copied, and put back only after the typed confirmation. Split out of {@link IslandAdminRestoreCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class DatabaseRestoreVerbs {

    private final IslandAdminRestoreCommands host;

    DatabaseRestoreVerbs(IslandAdminRestoreCommands host) {
        this.host = host;
    }

    int executeDatabaseBackup(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        DatabaseDisasterBackupService service = host.databaseBackupServiceProvider.get();
        if (service == null) {
            host.send(sender, "admin.backup_not_configured");
            return Cmd.OK;
        }

        host.schedulerPort.async(() -> {
            StorageBucket bucket = host.backupBucketProvider.get();
            if (bucket == null) {
                host.send(sender, "admin.restore_not_configured");
                return;
            }
            host.send(sender, "admin.database_backup_starting");

            // Reading every table and sending the result somewhere is what this is, so it runs off
            // the thread the command arrived on and nothing here touches the game.
            switch (service.backupDatabase(bucket)) {
                case DatabaseDisasterBackupService.Outcome.Success success ->
                    host.send(
                            sender,
                            "admin.database_backup_success",
                            Placeholder.unparsed("backup", success.backupSetId().toString()),
                            Placeholder.unparsed("dialect", success.dialect().name()),
                            Placeholder.unparsed("bytes", Long.toString(success.bytes())));
                case DatabaseDisasterBackupService.Outcome.Partial partial ->
                    host.send(
                            sender,
                            "admin.backup_partial",
                            Placeholder.unparsed("backup", partial.backupSetId().toString()));
                case DatabaseDisasterBackupService.Outcome.Failure failure ->
                    host.send(sender, "admin.backup_failed", Placeholder.unparsed("reason", failure.reason()));
            }
        });
        return Cmd.OK;
    }
    /** {@code /is admin restore database <backupId> <code>}: and then does it. */
    int executeDatabaseRestoreConfirm(CommandContext<CommandSourceStack> ctx) {
        String code = StringArgumentType.getString(ctx, "code");
        return withDatabaseRestore(ctx, (sender, service, requester, backupSetId) -> {
            StorageBucket bucket = host.backupBucketProvider.get();
            if (bucket == null) {
                host.send(sender, "admin.restore_not_configured");
                return;
            }
            host.send(
                    sender, "admin.database_restore_starting", Placeholder.unparsed("backup", backupSetId.toString()));
            switch (service.restoreDatabase(bucket, backupSetId, requester, code)) {
                case DatabaseDisasterBackupService.RestoreOutcome.Restored done ->
                    host.send(
                            sender,
                            "admin.database_restore_success",
                            Placeholder.unparsed("backup", done.backupSetId().toString()),
                            Placeholder.unparsed("bytes", Long.toString(done.bytes())));
                case DatabaseDisasterBackupService.RestoreOutcome.Refused refused ->
                    host.send(
                            sender, "admin.database_restore_refused", Placeholder.unparsed("reason", refused.reason()));
                case DatabaseDisasterBackupService.RestoreOutcome.CodeIssued ignored ->
                    host.send(sender, "admin.database_restore_refused", Placeholder.unparsed("reason", "unexpected"));
            }
        });
    }
    /** Resolves what both halves of the database restore need, and says why when it cannot. */
    int withDatabaseRestore(
            CommandContext<CommandSourceStack> ctx, IslandAdminRestoreCommands.DatabaseRestoreWork work) {
        CommandSender sender = ctx.getSource().getSender();
        DatabaseDisasterBackupService service = host.databaseBackupServiceProvider.get();
        if (service == null) {
            host.send(sender, "admin.backup_not_configured");
            return Cmd.OK;
        }
        Optional<ProfileId> optRequester = host.requesterOf(sender);
        if (optRequester.isEmpty()) {
            host.send(sender, "admin.database_restore_needs_a_profile");
            return Cmd.OK;
        }
        String raw = StringArgumentType.getString(ctx, "backupId");
        BackupSetId backupSetId;
        try {
            backupSetId = BackupSetId.fromString(raw);
        } catch (RuntimeException notAnId) {
            host.send(sender, "admin.restore_unknown_backup", Placeholder.unparsed("backup", raw));
            return Cmd.OK;
        }
        ProfileId requester = optRequester.get();
        host.schedulerPort.async(() -> work.run(sender, service, requester, backupSetId));
        return Cmd.OK;
    }
}
