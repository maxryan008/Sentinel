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

import java.util.List;

public final class SentinelCommands {

    private SentinelCommands() {
    }

    public static void register() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        register(dispatcher)
        );
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher
    ) {

        dispatcher.register(
                Commands.literal("sentinel")
                        .requires(source -> source.hasPermission(4))

                        .then(
                                Commands.literal("backup")
                                        .then(
                                                Commands.literal("now")
                                                        .executes(context -> {

                                                            BackupManager manager =
                                                                    requireManager(
                                                                            context.getSource()
                                                                    );

                                                            if (manager == null) {
                                                                return 0;
                                                            }

                                                            manager.requestBackup(
                                                                    "manual command"
                                                            );

                                                            context.getSource()
                                                                    .sendSuccess(
                                                                            () -> Component.literal(
                                                                                    "[Sentinel] Backup queued."
                                                                            ),
                                                                            false
                                                                    );

                                                            return 1;
                                                        })
                                        )
                        )

                        .then(
                                Commands.literal("status")
                                        .executes(context -> {

                                            BackupManager manager =
                                                    requireManager(
                                                            context.getSource()
                                                    );

                                            if (manager == null) {
                                                return 0;
                                            }

                                            context.getSource()
                                                    .sendSuccess(
                                                            () -> Component.literal(
                                                                    manager.statusText()
                                                            ),
                                                            false
                                                    );

                                            return 1;
                                        })
                        )

                        /*
                         * Only list snapshots belonging
                         * to the currently loaded world.
                         */
                        .then(
                                Commands.literal("list")
                                        .executes(context ->
                                                listSnapshots(
                                                        context.getSource()
                                                )
                                        )
                        )

                        .then(
                                Commands.literal("snapshot")

                                        .then(
                                                Commands.literal("lock")
                                                        .then(
                                                                Commands.argument(
                                                                                "snapshot",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context -> {

                                                                            BackupManager manager =
                                                                                    requireManager(
                                                                                            context.getSource()
                                                                                    );

                                                                            if (manager == null) {
                                                                                return 0;
                                                                            }

                                                                            String snapshot =
                                                                                    StringArgumentType.getString(
                                                                                            context,
                                                                                            "snapshot"
                                                                                    );

                                                                            if (!RollbackManager.list(
                                                                                    manager.config(),
                                                                                    context.getSource().getServer()
                                                                            ).contains(snapshot)) {

                                                                                context.getSource().sendFailure(
                                                                                        Component.literal(
                                                                                                "[Sentinel] Snapshot does not "
                                                                                                        + "belong to the current world."
                                                                                        )
                                                                                );

                                                                                return 0;
                                                                            }

                                                                            SnapshotLockManager.lock(
                                                                                    manager.config(),
                                                                                    snapshot
                                                                            );

                                                                            context.getSource().sendSuccess(
                                                                                    () -> Component.literal(
                                                                                            "[Sentinel] Locked snapshot "
                                                                                                    + snapshot
                                                                                    ),
                                                                                    false
                                                                            );

                                                                            return 1;
                                                                        })
                                                        )
                                        )

                                        .then(
                                                Commands.literal("unlock")
                                                        .then(
                                                                Commands.argument(
                                                                                "snapshot",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context -> {

                                                                            BackupManager manager =
                                                                                    requireManager(
                                                                                            context.getSource()
                                                                                    );

                                                                            if (manager == null) {
                                                                                return 0;
                                                                            }

                                                                            String snapshot =
                                                                                    StringArgumentType.getString(
                                                                                            context,
                                                                                            "snapshot"
                                                                                    );

                                                                            if (!RollbackManager.list(
                                                                                    manager.config(),
                                                                                    context.getSource().getServer()
                                                                            ).contains(snapshot)) {

                                                                                context.getSource().sendFailure(
                                                                                        Component.literal(
                                                                                                "[Sentinel] Snapshot does not "
                                                                                                        + "belong to the current world."
                                                                                        )
                                                                                );

                                                                                return 0;
                                                                            }

                                                                            SnapshotLockManager.unlock(
                                                                                    manager.config(),
                                                                                    snapshot
                                                                            );

                                                                            context.getSource().sendSuccess(
                                                                                    () -> Component.literal(
                                                                                            "[Sentinel] Unlocked snapshot "
                                                                                                    + snapshot
                                                                                    ),
                                                                                    false
                                                                            );

                                                                            return 1;
                                                                        })
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("rollback")

                                        /*
                                         * Same filtered listing as /sentinel list.
                                         */
                                        .then(
                                                Commands.literal("list")
                                                        .executes(context ->
                                                                listSnapshots(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )

                                        /*
                                         * Latest snapshot for THIS world,
                                         * not the latest snapshot globally.
                                         */
                                        .then(
                                                Commands.literal("latest")
                                                        .executes(context -> {

                                                            BackupManager manager =
                                                                    requireManager(
                                                                            context.getSource()
                                                                    );

                                                            if (manager == null) {
                                                                return 0;
                                                            }

                                                            String latest =
                                                                    RollbackManager.latest(
                                                                            manager.config(),
                                                                            context.getSource().getServer()
                                                                    );

                                                            if (latest == null) {

                                                                context.getSource()
                                                                        .sendFailure(
                                                                                Component.literal(
                                                                                        "[Sentinel] No snapshots exist "
                                                                                                + "for the current world."
                                                                                )
                                                                        );

                                                                return 0;
                                                            }

                                                            context.getSource()
                                                                    .sendSuccess(
                                                                            () -> Component.literal(
                                                                                    "[Sentinel] Server stopping. "
                                                                                            + "Restoring latest snapshot: "
                                                                                            + latest
                                                                            ),
                                                                            true
                                                                    );

                                                            RollbackManager.restoreAfterShutdown(
                                                                    manager,
                                                                    latest
                                                            );

                                                            return 1;
                                                        })
                                        )

                                        /*
                                         * Explicit snapshot selection is
                                         * also restricted to this world.
                                         */
                                        .then(
                                                Commands.literal("restore")
                                                        .then(
                                                                Commands.argument(
                                                                                "snapshot",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context -> {

                                                                            BackupManager manager =
                                                                                    requireManager(
                                                                                            context.getSource()
                                                                                    );

                                                                            if (manager == null) {
                                                                                return 0;
                                                                            }

                                                                            String snapshot =
                                                                                    StringArgumentType.getString(
                                                                                            context,
                                                                                            "snapshot"
                                                                                    );

                                                                            if (!RollbackManager.list(
                                                                                    manager.config(),
                                                                                    context.getSource().getServer()
                                                                            ).contains(snapshot)) {

                                                                                context.getSource()
                                                                                        .sendFailure(
                                                                                                Component.literal(
                                                                                                        "[Sentinel] Snapshot does not "
                                                                                                                + "belong to the current world."
                                                                                                )
                                                                                        );

                                                                                return 0;
                                                                            }

                                                                            context.getSource()
                                                                                    .sendSuccess(
                                                                                            () -> Component.literal(
                                                                                                    "[Sentinel] Server stopping. "
                                                                                                            + "Restoring snapshot: "
                                                                                                            + snapshot
                                                                                            ),
                                                                                            true
                                                                                    );

                                                                            RollbackManager.restoreAfterShutdown(
                                                                                    manager,
                                                                                    snapshot
                                                                            );

                                                                            return 1;
                                                                        })
                                                        )
                                        )
                        )
        );
    }

    /**
     * Shared world-filtered snapshot listing
     * for both commands.
     */
    private static int listSnapshots(
            CommandSourceStack source
    ) {

        BackupManager manager =
                requireManager(source);

        if (manager == null) {
            return 0;
        }

        List<String> snapshots =
                RollbackManager.listFormatted(
                        manager.config(),
                        source.getServer()
                );

        if (snapshots.isEmpty()) {

            source.sendSuccess(
                    () -> Component.literal(
                            "[Sentinel] No snapshots available "
                                    + "for the current world."
                    ),
                    false
            );

            return 1;
        }

        for (String snapshot : snapshots) {

            source.sendSuccess(
                    () -> Component.literal(
                            "[Sentinel] " + snapshot
                    ),
                    false
            );
        }

        return snapshots.size();
    }

    private static BackupManager requireManager(
            CommandSourceStack source
    ) {

        BackupManager manager =
                Sentinel.backupManager();

        if (manager == null) {

            source.sendFailure(
                    Component.literal(
                            "[Sentinel] Backup manager is not running."
                    )
            );

            return null;
        }

        return manager;
    }
}