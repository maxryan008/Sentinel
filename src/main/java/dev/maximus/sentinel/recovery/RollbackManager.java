package dev.maximus.sentinel.recovery;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.backup.SnapshotDisplayFormatter;
import dev.maximus.sentinel.config.SentinelConfig;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Comparator;
import java.util.List;

import java.util.concurrent.atomic.AtomicReference;

import java.util.stream.Stream;

public final class RollbackManager {

    private static final AtomicReference<Pending> PENDING =
            new AtomicReference<>();

    private RollbackManager() {
    }

    /**
     * Lists all snapshot directories.
     *
     * Retained for internal operations that
     * genuinely require the complete snapshot list.
     *
     * Do not use this method for commands that
     * operate on the currently loaded world.
     */
    public static List<String> list(
            SentinelConfig config
    ) {

        try (Stream<Path> stream =
                     Files.list(config.snapshotsDirectory())) {

            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();

        } catch (java.nio.file.NoSuchFileException exception) {

            return List.of();

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Cannot list snapshots",
                    exception
            );
        }
    }

    /**
     * Lists snapshots containing the currently
     * loaded world's directory and level.dat.
     *
     * Snapshots belonging to other world paths
     * are excluded.
     */
    public static List<String> list(
            SentinelConfig config,
            MinecraftServer server
    ) {

        Path relativeWorld =
                WorldPathResolver.relativeWorld(server);

        try (Stream<Path> stream =
                     Files.list(config.snapshotsDirectory())) {

            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> containsWorld(
                            path,
                            relativeWorld
                    ))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();

        } catch (java.nio.file.NoSuchFileException exception) {

            return List.of();

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Cannot list snapshots for current world",
                    exception
            );
        }
    }

    /**
     * Returns the newest snapshot across all
     * snapshot directories.
     *
     * Retained for compatibility with existing
     * internal recovery functionality.
     */
    public static String latest(
            SentinelConfig config
    ) {

        List<String> snapshots = list(config);

        return snapshots.isEmpty()
                ? null
                : snapshots.getFirst();
    }

    /**
     * Returns the newest snapshot belonging to
     * the current world.
     */
    public static String latest(
            SentinelConfig config,
            MinecraftServer server
    ) {

        List<String> snapshots =
                list(config, server);

        return snapshots.isEmpty()
                ? null
                : snapshots.getFirst();
    }

    /**
     * Existing unfiltered formatted listing.
     *
     * Retained for callers that need to inspect
     * snapshots from every world.
     */
    public static List<String> listFormatted(
            SentinelConfig config
    ) {

        try (Stream<Path> stream =
                     Files.list(config.snapshotsDirectory())) {

            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            Comparator.reverseOrder()
                    ))
                    .map(path -> SnapshotDisplayFormatter.format(
                            config,
                            path
                    ))
                    .toList();

        } catch (java.nio.file.NoSuchFileException exception) {

            return List.of();

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Cannot list formatted snapshots",
                    exception
            );
        }
    }

    /**
     * Formats only snapshots belonging to
     * the currently loaded world.
     */
    public static List<String> listFormatted(
            SentinelConfig config,
            MinecraftServer server
    ) {

        Path relativeWorld =
                WorldPathResolver.relativeWorld(server);

        try (Stream<Path> stream =
                     Files.list(config.snapshotsDirectory())) {

            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> containsWorld(
                            path,
                            relativeWorld
                    ))
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            Comparator.reverseOrder()
                    ))
                    .map(path -> SnapshotDisplayFormatter.format(
                            config,
                            path
                    ))
                    .toList();

        } catch (java.nio.file.NoSuchFileException exception) {

            return List.of();

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Cannot list formatted snapshots "
                            + "for current world",
                    exception
            );
        }
    }

    /**
     * Returns whether this snapshot contains
     * the currently loaded world.
     *
     * Uses Sentinel's existing safe world-path
     * validation rather than duplicating
     * its filesystem checks.
     */
    public static boolean containsWorld(
            Path snapshot,
            Path relativeWorld
    ) {

        if (!Files.isDirectory(snapshot)
                || Files.isSymbolicLink(snapshot)) {

            return false;
        }

        try {

            WorldPathResolver.snapshotWorld(
                    snapshot,
                    relativeWorld
            );

            return true;

        } catch (IllegalStateException exception) {

            return false;
        }
    }

    /**
     * Begins a full-world rollback after
     * validating that the selected snapshot
     * contains the currently loaded world.
     */
    public static void restoreAfterShutdown(
            BackupManager manager,
            String snapshotId
    ) {

        if (snapshotId == null
                || !snapshotId.matches(
                "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
        )) {

            throw new IllegalArgumentException(
                    "Invalid snapshot ID"
            );
        }

        MinecraftServer server = manager.server();

        SentinelConfig config = manager.config();

        Path snapshot =
                config.snapshotsDirectory()
                        .resolve(snapshotId)
                        .normalize();

        Path relative =
                WorldPathResolver.relativeWorld(server);

        /*
         * Reject snapshots that do not contain
         * the current world.
         */
        WorldPathResolver.snapshotWorld(
                snapshot,
                relative
        );

        if (manager.isRunningBackup()) {

            throw new IllegalStateException(
                    "Backup running"
            );
        }

        if (PENDING.get() != null) {

            throw new IllegalStateException(
                    "Rollback pending"
            );
        }

        manager.broadcastToAdmins(
                "[Sentinel] Rollback requested: "
                        + snapshotId
        );

        if (!config.createPreRollbackSafetyBackup) {

            launch(
                    server,
                    config,
                    snapshotId,
                    relative
            );

            return;
        }

        if (!manager.requestBackup(
                "pre rollback safety backup",
                () -> {

                    try {

                        launch(
                                server,
                                config,
                                snapshotId,
                                relative
                        );

                    } catch (Exception exception) {

                        Sentinel.LOGGER.error(
                                "Rollback not launched",
                                exception
                        );
                    }
                },
                throwable ->
                        Sentinel.LOGGER.error(
                                "Safety backup failed; "
                                        + "rollback cancelled",
                                throwable
                        )
        )) {

            throw new IllegalStateException(
                    "Could not queue safety backup"
            );
        }
    }

    private static void launch(
            MinecraftServer server,
            SentinelConfig config,
            String id,
            Path relative
    ) {

        Path snapshot =
                config.snapshotsDirectory()
                        .resolve(id)
                        .normalize();

        WorldPathResolver.snapshotWorld(
                snapshot,
                relative
        );

        if (server.isDedicatedServer()) {

            Path script =
                    BackupManager.serverDirectory()
                            .resolve(
                                    "manual_rollback/rollback.sh"
                            );

            RestoreScriptGenerator.generate(
                    config,
                    BackupManager.serverDirectory()
            );

            try {

                new ProcessBuilder(
                        "bash",
                        script.toString(),
                        "restore-world-after-pid",
                        id,
                        Long.toString(
                                ProcessHandle.current().pid()
                        ),
                        relative.toString()
                )
                        .directory(
                                BackupManager.serverDirectory()
                                        .toFile()
                        )
                        .inheritIO()
                        .start();

            } catch (Exception exception) {

                throw new IllegalStateException(
                        "Cannot launch rollback",
                        exception
                );
            }

        } else {

            Pending pending = new Pending(
                    server,
                    snapshot,
                    relative
            );

            if (!PENDING.compareAndSet(
                    null,
                    pending
            )) {

                throw new IllegalStateException(
                        "Rollback already pending"
                );
            }
        }

        server.halt(false);
    }

    /**
     * Runs after integrated-server world storage
     * has closed.
     */
    public static void onServerStopped(
            MinecraftServer server
    ) {

        Pending pending =
                PENDING.get();

        if (pending == null
                || pending.server != server
                || !PENDING.compareAndSet(
                pending,
                null
        )) {

            return;
        }

        try {

            WorldRestorer.restore(
                    BackupManager.serverDirectory(),
                    pending.snapshot,
                    pending.relativeWorld
            );

            Sentinel.LOGGER.info(
                    "Integrated world restored from {}",
                    pending.snapshot.getFileName()
            );

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Integrated rollback failed; "
                            + "do not reopen this world",
                    exception
            );
        }
    }

    private record Pending(
            MinecraftServer server,
            Path snapshot,
            Path relativeWorld
    ) {
    }
}