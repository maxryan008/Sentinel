package dev.maximus.sentinel.command;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.backup.SnapshotLockManager;
import dev.maximus.sentinel.recovery.RollbackManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class SentinelCommands {
    private SentinelCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sentinel")
                .requires(source -> source.hasPermission(4))

                .then(Commands.literal("backup")
                        .then(Commands.literal("now")
                                .executes(context -> {
                                    BackupManager manager = requireManager(context.getSource());

                                    if (manager == null) {
                                        return 0;
                                    }

                                    manager.requestBackup("manual command");
                                    context.getSource().sendSuccess(() -> Component.literal("[Sentinel] Backup queued."), false);
                                    return 1;
                                })))

                .then(Commands.literal("status")
                        .executes(context -> {
                            BackupManager manager = requireManager(context.getSource());

                            if (manager == null) {
                                return 0;
                            }

                            context.getSource().sendSuccess(() -> Component.literal(manager.statusText()), false);
                            return 1;
                        }))

                .then(Commands.literal("list")
                        .executes(context -> {
                            BackupManager manager = requireManager(context.getSource());

                            if (manager == null) {
                                return 0;
                            }

                            for (String snapshot : RollbackManager.listFormatted(manager.config())) {
                                context.getSource().sendSuccess(() -> Component.literal("[Sentinel] " + snapshot), false);
                            }

                            return 1;
                        }))

                .then(Commands.literal("snapshot")
                        .then(Commands.literal("lock")
                                .then(Commands.argument("snapshot", StringArgumentType.word())
                                        .executes(context -> {
                                            BackupManager manager = requireManager(context.getSource());

                                            if (manager == null) {
                                                return 0;
                                            }

                                            String snapshot = StringArgumentType.getString(context, "snapshot");
                                            SnapshotLockManager.lock(manager.config(), snapshot);

                                            context.getSource().sendSuccess(() -> Component.literal("[Sentinel] Locked snapshot " + snapshot), false);
                                            return 1;
                                        })))

                        .then(Commands.literal("unlock")
                                .then(Commands.argument("snapshot", StringArgumentType.word())
                                        .executes(context -> {
                                            BackupManager manager = requireManager(context.getSource());

                                            if (manager == null) {
                                                return 0;
                                            }

                                            String snapshot = StringArgumentType.getString(context, "snapshot");
                                            SnapshotLockManager.unlock(manager.config(), snapshot);

                                            context.getSource().sendSuccess(() -> Component.literal("[Sentinel] Unlocked snapshot " + snapshot), false);
                                            return 1;
                                        }))))

                .then(Commands.literal("rollback")
                        .then(Commands.literal("list")
                                .executes(context -> {
                                    BackupManager manager = requireManager(context.getSource());

                                    if (manager == null) {
                                        return 0;
                                    }

                                    for (String snapshot : RollbackManager.listFormatted(manager.config())) {
                                        context.getSource().sendSuccess(() -> Component.literal("[Sentinel] " + snapshot), false);
                                    }

                                    return 1;
                                }))

                        .then(Commands.literal("latest")
                                .executes(context -> {
                                    BackupManager manager = requireManager(context.getSource());

                                    if (manager == null) {
                                        return 0;
                                    }

                                    String latest = RollbackManager.latest(manager.config());

                                    if (latest == null) {
                                        context.getSource().sendFailure(Component.literal("[Sentinel] No rollback snapshots exist."));
                                        return 0;
                                    }

                                    context.getSource().sendSuccess(
                                            () -> Component.literal("[Sentinel] Server stopping. Restoring latest snapshot: " + latest),
                                            true
                                    );

                                    RollbackManager.restoreAfterShutdown(manager, latest);
                                    return 1;
                                }))

                        .then(Commands.literal("restore")
                                .then(Commands.argument("snapshot", StringArgumentType.word())
                                        .executes(context -> {
                                            BackupManager manager = requireManager(context.getSource());

                                            if (manager == null) {
                                                return 0;
                                            }

                                            String snapshot = StringArgumentType.getString(context, "snapshot");

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("[Sentinel] Server stopping. Restoring snapshot: " + snapshot),
                                                    true
                                            );

                                            RollbackManager.restoreAfterShutdown(manager, snapshot);
                                            return 1;
                                        })))));
    }

    private static BackupManager requireManager(CommandSourceStack source) {
        BackupManager manager = Sentinel.backupManager();

        if (manager == null) {
            source.sendFailure(Component.literal("[Sentinel] Backup manager is not running."));
            return null;
        }

        return manager;
    }
}