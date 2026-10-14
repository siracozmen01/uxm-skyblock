package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Optional;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;

/**
 * {@code /isadmin backup} and its restore from a set: one island copied, and put back from the copy an operator names. Split out of {@link IslandAdminRestoreCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class IslandBackupVerbs {

    private final IslandAdminRestoreCommands host;

    IslandBackupVerbs(IslandAdminRestoreCommands host) {
        this.host = host;
    }

    int executeBackup(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        IslandBackupService backupService = host.islandBackupServiceProvider.get();
        if (backupService == null) {
            host.send(sender, "admin.backup_not_configured");
            return Cmd.OK;
        }
        String target = StringArgumentType.getString(ctx, "island");

        host.schedulerPort.async(() -> {
            StorageBucket bucket = host.backupBucketProvider.get();
            if (bucket == null) {
                host.send(sender, "admin.restore_not_configured");
                return;
            }
            Optional<IslandId> optIsland =
                    IslandAdminCommands.resolveIslandId(host.sessionCoordinator, host.islandLocationService, target);
            if (optIsland.isEmpty()) {
                host.send(sender, "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optIsland.get();
            host.send(
                    sender,
                    "admin.backup_starting",
                    Placeholder.unparsed("island", islandId.value().toString()));

            // Reading the database and every chunk inside the island's bounds is what this is, so it
            // runs here on the scheduler and the world adapter hops onto each owning region itself.
            IslandBackupService.BackupOutcome outcome = backupService.backupIsland(
                    islandId, bucket, List.of(DimensionId.OVERWORLD, DimensionId.THE_NETHER, DimensionId.THE_END));

            switch (outcome) {
                case IslandBackupService.BackupOutcome.Success success ->
                    host.send(
                            sender,
                            "admin.backup_success",
                            Placeholder.unparsed("island", islandId.value().toString()),
                            Placeholder.unparsed("backup", success.backupSetId().toString()),
                            Placeholder.unparsed("artifacts", Integer.toString(success.artifacts())));
                case IslandBackupService.BackupOutcome.Partial partial ->
                    host.send(
                            sender,
                            "admin.backup_partial",
                            Placeholder.unparsed("backup", partial.backupSetId().toString()));
                case IslandBackupService.BackupOutcome.Failure failure ->
                    host.send(sender, "admin.backup_failed", Placeholder.unparsed("reason", failure.reason()));
            }
        });
        return Cmd.OK;
    }
    /**
     * Restores one backup set, whichever way the operator named it.
     *
     * <p>Both entry points land here: the one that takes a backup id and the one that takes an
     * island and finds its newest. The manifest is looked for under the plain prefix first and then
     * under the one the catalogue records, because a backup written by an older version used the
     * other shape.
     */
    void restoreFromSet(CommandSender sender, IslandRestoreService rService, String backupIdStr, RestoreMode mode) {
        host.schedulerPort.async(() -> {
            try {
                BackupSetId backupSetId = BackupSetId.fromString(backupIdStr);
                // The bucket is the one the operator named in storage.s3.bucket. It used to be
                // written into this line, so an operator who renamed their bucket wrote backups to
                // one place and restored from another that does not exist: the restore answered
                // "manifest missing" and said nothing about why.
                StorageBucket bucket = host.backupBucketProvider.get();
                if (bucket == null) {
                    host.send(sender, "admin.restore_not_configured");
                    return;
                }
                String rootPrefix = "backups/" + backupSetId;

                Optional<BackupManifest> optManifest = Optional.empty();
                BackupService bService = host.backupServiceProvider.get();
                if (bService != null) {
                    optManifest = bService.loadManifest(bucket, rootPrefix);
                }

                if (optManifest.isEmpty()) {
                    Optional<BackupCatalogRecord> optRecord =
                            rService.catalogPort().findById(backupSetId);
                    if (optRecord.isPresent() && bService != null) {
                        BackupCatalogRecord record = optRecord.get();
                        String customPrefix = "backups/" + record.targetRootTypeId() + "/" + record.targetRootKey()
                                + "/" + backupSetId;
                        optManifest = bService.loadManifest(bucket, customPrefix);
                        if (optManifest.isPresent()) {
                            rootPrefix = customPrefix;
                        }
                    }
                }

                if (optManifest.isEmpty()) {
                    host.send(sender, "admin.restore_manifest_missing", Placeholder.unparsed("backup", backupIdStr));
                    return;
                }

                BackupManifest manifest = optManifest.get();
                IslandRestoreService.RestoreOutcome outcome =
                        rService.executeRestore(manifest, bucket, rootPrefix, true, mode);
                if (outcome instanceof IslandRestoreService.RestoreOutcome.Success success) {
                    host.send(
                            sender,
                            "admin.restore_success",
                            Placeholder.unparsed("backup", backupIdStr),
                            Placeholder.unparsed("artifacts", Integer.toString(success.artifactsRestored())));
                } else if (outcome instanceof IslandRestoreService.RestoreOutcome.Failure failure) {
                    host.send(
                            sender,
                            "admin.restore_failed",
                            Placeholder.unparsed("backup", backupIdStr),
                            Placeholder.unparsed("reason", failure.reason()));
                }
            } catch (Exception e) {
                host.send(
                        sender, "admin.restore_error", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            }
        });
    }
}
