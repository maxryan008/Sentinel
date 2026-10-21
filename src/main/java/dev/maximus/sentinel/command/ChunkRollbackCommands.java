package dev.maximus.sentinel.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.config.SentinelConfig;
import dev.maximus.sentinel.recovery.RollbackManager;
import dev.maximus.sentinel.recovery.WorldPathResolver;
import dev.maximus.sentinel.recovery.chunk.LiveChunkRollbackManager;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.level.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ChunkRollbackCommands {

    private ChunkRollbackCommands() {
    }

    public static void register() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registries, environment) ->
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
                                Commands.literal("rollback")

                                        .then(
                                                Commands.literal("chunk")

                                                        .then(
                                                                Commands.literal("here")

                                                                        .then(
                                                                                Commands.literal("latest")
                                                                                        .executes(context ->
                                                                                                run(
                                                                                                        context.getSource(),
                                                                                                        null,
                                                                                                        null,
                                                                                                        "latest"
                                                                                                )
                                                                                        )
                                                                        )

                                                                        .then(
                                                                                Commands.argument(
                                                                                                "snapshot",
                                                                                                StringArgumentType.word()
                                                                                        )
                                                                                        .executes(context ->
                                                                                                run(
                                                                                                        context.getSource(),
                                                                                                        null,
                                                                                                        null,
                                                                                                        StringArgumentType.getString(
                                                                                                                context,
                                                                                                                "snapshot"
                                                                                                        )
                                                                                                )
                                                                                        )
                                                                        )
                                                        )

                                                        .then(
                                                                Commands.argument(
                                                                                "chunkX",
                                                                                IntegerArgumentType.integer()
                                                                        )

                                                                        .then(
                                                                                Commands.argument(
                                                                                                "chunkZ",
                                                                                                IntegerArgumentType.integer()
                                                                                        )

                                                                                        .then(
                                                                                                Commands.literal("latest")
                                                                                                        .executes(context ->
                                                                                                                run(
                                                                                                                        context.getSource(),
                                                                                                                        IntegerArgumentType.getInteger(
                                                                                                                                context,
                                                                                                                                "chunkX"
                                                                                                                        ),
                                                                                                                        IntegerArgumentType.getInteger(
                                                                                                                                context,
                                                                                                                                "chunkZ"
                                                                                                                        ),
                                                                                                                        "latest"
                                                                                                                )
                                                                                                        )
                                                                                        )

                                                                                        .then(
                                                                                                Commands.argument(
                                                                                                                "snapshot",
                                                                                                                StringArgumentType.word()
                                                                                                        )
                                                                                                        .executes(context ->
                                                                                                                run(
                                                                                                                        context.getSource(),
                                                                                                                        IntegerArgumentType.getInteger(
                                                                                                                                context,
                                                                                                                                "chunkX"
                                                                                                                        ),
                                                                                                                        IntegerArgumentType.getInteger(
                                                                                                                                context,
                                                                                                                                "chunkZ"
                                                                                                                        ),
                                                                                                                        StringArgumentType.getString(
                                                                                                                                context,
                                                                                                                                "snapshot"
                                                                                                                        )
                                                                                                                )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                        )
        );
    }

    private static int run(
            CommandSourceStack source,
            Integer x,
            Integer z,
            String snapshotName
    ) {

        try {

            BackupManager manager =
                    Sentinel.backupManager();

            if (manager == null) {

                throw new IllegalStateException(
                        "Backup manager unavailable"
                );
            }

            if (manager.isRunningBackup()) {

                throw new IllegalStateException(
                        "Backup currently running"
                );
            }

            if (LiveChunkRollbackManager.busy()) {

                throw new IllegalStateException(
                        "Hot swap already running"
                );
            }

            SentinelConfig config =
                    manager.config();

            /*
             * Select the latest snapshot belonging
             * to this world, not the newest snapshot
             * anywhere in the backup directory.
             */
            String selected =
                    snapshotName.equals("latest")
                            ? RollbackManager.latest(
                            config,
                            source.getServer()
                    )
                            : snapshotName;

            if (selected == null) {

                throw new IllegalArgumentException(
                        "No snapshots available "
                                + "for the current world"
                );
            }

            if (!selected.matches(
                    "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
            )) {

                throw new IllegalArgumentException(
                        "Invalid snapshot name"
                );
            }

            /*
             * Explicit selections must also belong
             * to the current world.
             */
            if (!RollbackManager.list(
                    config,
                    source.getServer()
            ).contains(selected)) {

                throw new IllegalArgumentException(
                        "Snapshot does not belong "
                                + "to the current world"
                );
            }

            Path root =
                    config.snapshotsDirectory()
                            .toAbsolutePath()
                            .normalize();

            Path snapshot =
                    root.resolve(selected)
                            .normalize();

            if (!snapshot.startsWith(root)
                    || !Files.isDirectory(snapshot)
                    || Files.isSymbolicLink(snapshot)) {

                throw new IllegalArgumentException(
                        "Snapshot not found"
                );
            }

            Path relative =
                    WorldPathResolver.relativeWorld(
                            source.getServer()
                    );

            Path world =
                    WorldPathResolver.snapshotWorld(
                            snapshot,
                            relative
                    );

            ServerLevel level =
                    source.getLevel();

            ChunkPos position =
                    x == null
                            ? new ChunkPos(
                            BlockPos.containing(
                                    source.getPosition()
                            )
                    )
                            : new ChunkPos(x, z);

            ResourceLocation dimension =
                    level.dimension().location();

            Path target;

            if (dimension.getNamespace().equals("minecraft")
                    && dimension.getPath().equals("overworld")) {

                target = world;

            } else if (
                    dimension.getNamespace().equals("minecraft")
                            && dimension.getPath().equals("the_nether")
            ) {

                target = world.resolve("DIM-1");

            } else if (
                    dimension.getNamespace().equals("minecraft")
                            && dimension.getPath().equals("the_end")
            ) {

                target = world.resolve("DIM1");

            } else {

                target = world
                        .resolve("dimensions")
                        .resolve(dimension.getNamespace())
                        .resolve(dimension.getPath());
            }

            Path dimensionSnapshot =
                    target.normalize();

            if (!dimensionSnapshot.startsWith(
                    world.normalize()
            )) {

                throw new IllegalArgumentException(
                        "Unsafe dimension path"
                );
            }

            /*
             * A snapshot can contain the correct
             * world without containing this dimension.
             */
            if (!Files.isDirectory(dimensionSnapshot)) {

                throw new IllegalArgumentException(
                        "Snapshot does not contain dimension "
                                + dimension
                );
            }

            LiveChunkRollbackManager.start(
                    level,
                    position,
                    dimensionSnapshot,
                    message ->
                            source.sendSuccess(
                                    () -> Component.literal(
                                            "[Sentinel] " + message
                                    ),
                                    false
                            )
            );

            return 1;

        } catch (Exception exception) {

            Sentinel.LOGGER.error(
                    "Unable to start chunk rollback",
                    exception
            );

            source.sendFailure(
                    Component.literal(
                            "[Sentinel] "
                                    + exception.getMessage()
                    )
            );

            return 0;
        }
    }
}