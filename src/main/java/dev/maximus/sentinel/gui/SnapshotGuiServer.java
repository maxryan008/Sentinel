package dev.maximus.sentinel.gui;

import com.mojang.brigadier.CommandDispatcher;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.backup.SnapshotLockManager;
import dev.maximus.sentinel.backup.SnapshotMetadata;
import dev.maximus.sentinel.config.SentinelConfig;
import dev.maximus.sentinel.recovery.RollbackManager;
import dev.maximus.sentinel.recovery.WorldPathResolver;
import dev.maximus.sentinel.recovery.chunk.LiveChunkRollbackManager;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SnapshotGuiServer {

    private static final DateTimeFormatter SNAPSHOT_ID_FORMAT =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd_HH-mm-ss"
            );

    private static final ExecutorService DELETE_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {

                Thread thread = new Thread(
                        task,
                        "Sentinel-Snapshot-Deletion"
                );

                thread.setDaemon(true);

                return thread;
            });

    private static final Set<String> DELETING =
            ConcurrentHashMap.newKeySet();

    private SnapshotGuiServer() {
    }

    public static void register() {

        ServerPlayNetworking.registerGlobalReceiver(
                SnapshotGuiPackets.SnapshotAction.TYPE,
                (packet, context) -> {

                    ServerPlayer player = context.player();

                    if (!authorized(player)) {
                        return;
                    }

                    try {

                        switch (packet.action()) {

                            case REFRESH ->
                                    sendList(player);

                            case RESTORE ->
                                    restore(
                                            player,
                                            packet.snapshotId()
                                    );

                            case DELETE ->
                                    delete(
                                            player,
                                            packet.snapshotId()
                                    );

                            case TOGGLE_LOCK ->
                                    toggleLock(
                                            player,
                                            packet.snapshotId()
                                    );
                        }

                    } catch (Exception exception) {

                        Sentinel.LOGGER.error(
                                "Snapshot GUI request failed",
                                exception
                        );

                        error(
                                player,
                                exception.getMessage()
                        );
                    }
                }
        );

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        registerCommand(dispatcher)
        );
    }

    private static void registerCommand(
            CommandDispatcher<CommandSourceStack> dispatcher
    ) {

        dispatcher.register(
                Commands.literal("sentinel")
                        .requires(source -> source.hasPermission(4))

                        .then(
                                Commands.literal("snapshots")
                                        .then(
                                                Commands.literal("gui")
                                                        .executes(context -> {

                                                            CommandSourceStack source =
                                                                    context.getSource();

                                                            ServerPlayer player;

                                                            try {

                                                                player = source.getPlayerOrException();

                                                            } catch (Exception exception) {

                                                                source.sendFailure(
                                                                        Component.literal(
                                                                                "[Sentinel] This command requires a player."
                                                                        )
                                                                );

                                                                return 0;
                                                            }

                                                            sendList(player);

                                                            return 1;
                                                        })
                                        )
                        )
        );
    }

    private static boolean authorized(
            ServerPlayer player
    ) {

        if (player.hasPermissions(4)) {
            return true;
        }

        error(
                player,
                "Operator permission level 4 is required."
        );

        return false;
    }

    /**
     * Sends snapshots belonging to the current world.
     *
     * Includes timestamp, lock status, and short reason.
     */
    public static void sendList(
            ServerPlayer player
    ) {

        if (!authorized(player)) {
            return;
        }

        BackupManager manager =
                Sentinel.backupManager();

        if (manager == null) {

            error(
                    player,
                    "Backup manager is unavailable."
            );

            return;
        }

        SentinelConfig config =
                manager.config();

        Path snapshotsDirectory =
                config.snapshotsDirectory()
                        .toAbsolutePath()
                        .normalize();

        Path relativeWorld =
                WorldPathResolver.relativeWorld(
                        player.getServer()
                );

        List<SnapshotGuiPackets.SnapshotEntry> entries =
                new ArrayList<>();

        if (Files.isDirectory(snapshotsDirectory)) {

            try (var paths =
                         Files.list(snapshotsDirectory)) {

                for (Path snapshot :
                        paths.filter(Files::isDirectory)
                                .filter(path ->
                                        !Files.isSymbolicLink(path)
                                ).toList()) {

                    try {

                        WorldPathResolver.snapshotWorld(
                                snapshot,
                                relativeWorld
                        );

                    } catch (IllegalStateException ignored) {

                        continue;
                    }

                    String id =
                            snapshot.getFileName()
                                    .toString();

                    if (!validId(id)) {
                        continue;
                    }

                    SnapshotMetadata metadata;

                    try {

                        metadata =
                                SnapshotMetadata.read(
                                        snapshot
                                );

                    } catch (Exception exception) {

                        Sentinel.LOGGER.warn(
                                "Could not read snapshot metadata: {}",
                                snapshot,
                                exception
                        );

                        metadata = null;
                    }

                    long timestamp =
                            readTimestamp(
                                    snapshot,
                                    id,
                                    metadata
                            );

                    String reason =
                            normalizeReason(
                                    metadata == null
                                            ? null
                                            : metadata.reason()
                            );

                    boolean locked =
                            SnapshotLockManager.isLocked(
                                    config,
                                    id
                            );

                    entries.add(
                            new SnapshotGuiPackets.SnapshotEntry(
                                    id,
                                    timestamp,
                                    locked,
                                    reason
                            )
                    );
                }

            } catch (IOException exception) {

                throw new IllegalStateException(
                        "Unable to list snapshots",
                        exception
                );
            }
        }

        entries.sort(
                Comparator
                        .comparingLong(
                                SnapshotGuiPackets.SnapshotEntry::
                                        createdAtEpochMillis
                        )
                        .reversed()
                        .thenComparing(
                                SnapshotGuiPackets.SnapshotEntry::id,
                                Comparator.reverseOrder()
                        )
        );

        if (entries.size()
                > SnapshotGuiPackets.MAX_SNAPSHOTS) {

            entries = new ArrayList<>(
                    entries.subList(
                            0,
                            SnapshotGuiPackets.MAX_SNAPSHOTS
                    )
            );
        }

        ServerPlayNetworking.send(
                player,
                new SnapshotGuiPackets.SnapshotList(
                        List.copyOf(entries)
                )
        );
    }

    /**
     * Converts descriptive backup reasons into
     * short, consistent GUI labels.
     *
     * Unknown reasons are categorised as "backup".
     */
    private static String normalizeReason(
            String reason
    ) {

        if (reason == null || reason.isBlank()) {
            return "backup";
        }

        String normalized =
                reason.trim()
                        .toLowerCase(Locale.ROOT);

        if (normalized.equals("auto")
                || normalized.equals("scheduled")
                || normalized.contains("schedule")
                || normalized.contains("automatic")) {

            return "auto";
        }

        if (normalized.equals("manual")
                || normalized.contains("manual")
                || normalized.contains("command")) {

            return "manual";
        }

        return "backup";
    }

    /**
     * Toggle the selected snapshot's lock state.
     */
    private static void toggleLock(
            ServerPlayer player,
            String snapshotId
    ) {

        BackupManager manager =
                Sentinel.backupManager();

        if (manager == null) {

            throw new IllegalStateException(
                    "Backup manager unavailable."
            );
        }

        MinecraftServer server =
                player.getServer();

        if (server == null) {
            return;
        }

        SentinelConfig config =
                manager.config();

        resolveSnapshot(
                server,
                config,
                snapshotId
        );

        if (DELETING.contains(snapshotId)) {

            throw new IllegalStateException(
                    "Snapshot is being deleted."
            );
        }

        boolean locked =
                SnapshotLockManager.isLocked(
                        config,
                        snapshotId
                );

        if (locked) {

            SnapshotLockManager.unlock(
                    config,
                    snapshotId
            );

            player.sendSystemMessage(
                    Component.literal(
                            "[Sentinel] Unlocked snapshot "
                                    + snapshotId
                    )
            );

        } else {

            SnapshotLockManager.lock(
                    config,
                    snapshotId
            );

            player.sendSystemMessage(
                    Component.literal(
                            "[Sentinel] Locked snapshot "
                                    + snapshotId
                    )
            );
        }

        sendList(player);
    }

    private static long readTimestamp(
            Path snapshot,
            String id,
            SnapshotMetadata metadata
    ) {

        if (metadata != null
                && metadata.createdAtUtc() != null) {

            try {

                return Instant.parse(
                        metadata.createdAtUtc()
                ).toEpochMilli();

            } catch (Exception ignored) {
            }
        }

        try {

            return LocalDateTime.parse(
                    id,
                    SNAPSHOT_ID_FORMAT
            ).atZone(
                    ZoneId.systemDefault()
            ).toInstant().toEpochMilli();

        } catch (Exception ignored) {
        }

        try {

            return Files.getLastModifiedTime(
                    snapshot
            ).toMillis();

        } catch (IOException exception) {

            return 0L;
        }
    }

    private static Path resolveSnapshot(
            MinecraftServer server,
            SentinelConfig config,
            String snapshotId
    ) {

        if (!validId(snapshotId)) {

            throw new IllegalArgumentException(
                    "Invalid snapshot ID."
            );
        }

        Path root =
                config.snapshotsDirectory()
                        .toAbsolutePath()
                        .normalize();

        Path snapshot =
                root.resolve(snapshotId)
                        .normalize();

        if (!snapshot.startsWith(root)
                || !Files.isDirectory(snapshot)
                || Files.isSymbolicLink(snapshot)) {

            throw new IllegalArgumentException(
                    "Snapshot no longer exists."
            );
        }

        WorldPathResolver.snapshotWorld(
                snapshot,
                WorldPathResolver.relativeWorld(
                        server
                )
        );

        return snapshot;
    }

    private static boolean validId(
            String snapshotId
    ) {

        return snapshotId != null
                && !snapshotId.equals(".")
                && !snapshotId.equals("..")
                && snapshotId.matches(
                "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
        );
    }

    private static void restore(
            ServerPlayer player,
            String snapshotId
    ) {

        BackupManager manager =
                Sentinel.backupManager();

        if (manager == null) {

            throw new IllegalStateException(
                    "Backup manager unavailable."
            );
        }

        MinecraftServer server =
                player.getServer();

        if (server == null) {
            return;
        }

        SentinelConfig config =
                manager.config();

        resolveSnapshot(
                server,
                config,
                snapshotId
        );

        if (DELETING.contains(snapshotId)) {

            throw new IllegalStateException(
                    "This snapshot is being deleted."
            );
        }

        if (manager.isRunningBackup()
                || LiveChunkRollbackManager.busy()) {

            throw new IllegalStateException(
                    "Wait for the active backup or "
                            + "chunk rollback to finish."
            );
        }

        RollbackManager.restoreAfterShutdown(
                manager,
                snapshotId
        );

        player.sendSystemMessage(
                Component.literal(
                        "[Sentinel] Full-world restore requested: "
                                + snapshotId
                )
        );
    }

    private static void delete(
            ServerPlayer player,
            String snapshotId
    ) {

        BackupManager manager =
                Sentinel.backupManager();

        if (manager == null) {

            throw new IllegalStateException(
                    "Backup manager unavailable."
            );
        }

        MinecraftServer server =
                player.getServer();

        if (server == null) {
            return;
        }

        SentinelConfig config =
                manager.config();

        Path snapshot =
                resolveSnapshot(
                        server,
                        config,
                        snapshotId
                );

        if (manager.isRunningBackup()
                || LiveChunkRollbackManager.busy()) {

            throw new IllegalStateException(
                    "Cannot delete snapshots while "
                            + "backup or rollback is active."
            );
        }

        if (SnapshotLockManager.isLocked(
                config,
                snapshotId
        )) {

            throw new IllegalStateException(
                    "Snapshot is locked. Unlock it first."
            );
        }

        if (!DELETING.add(snapshotId)) {

            throw new IllegalStateException(
                    "Snapshot deletion already running."
            );
        }

        player.sendSystemMessage(
                Component.literal(
                        "[Sentinel] Deleting snapshot "
                                + snapshotId + "..."
                )
        );

        DELETE_EXECUTOR.execute(() -> {

            try {

                if (manager.isRunningBackup()
                        || LiveChunkRollbackManager.busy()) {

                    throw new IllegalStateException(
                            "Another backup or rollback started."
                    );
                }

                if (SnapshotLockManager.isLocked(
                        config,
                        snapshotId
                )) {

                    throw new IllegalStateException(
                            "Snapshot became locked."
                    );
                }

                Files.walkFileTree(
                        snapshot,
                        new SimpleFileVisitor<>() {

                            @Override
                            public FileVisitResult visitFile(
                                    Path file,
                                    BasicFileAttributes attributes
                            ) throws IOException {

                                Files.delete(file);

                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult postVisitDirectory(
                                    Path directory,
                                    IOException exception
                            ) throws IOException {

                                if (exception != null) {
                                    throw exception;
                                }

                                Files.delete(directory);

                                return FileVisitResult.CONTINUE;
                            }
                        }
                );

                server.execute(() -> {

                    player.sendSystemMessage(
                            Component.literal(
                                    "[Sentinel] Deleted snapshot: "
                                            + snapshotId
                            )
                    );

                    SnapshotRegionIndex.clear();

                    if (player.connection != null) {
                        sendList(player);
                    }
                });

            } catch (Exception exception) {

                Sentinel.LOGGER.error(
                        "Failed to delete snapshot {}",
                        snapshotId,
                        exception
                );

                server.execute(() ->
                        error(
                                player,
                                "Snapshot deletion failed: "
                                        + exception.getMessage()
                        )
                );

            } finally {

                DELETING.remove(snapshotId);
            }
        });
    }

    private static void error(
            ServerPlayer player,
            String message
    ) {

        player.sendSystemMessage(
                Component.literal(
                        "[Sentinel] " + message
                )
        );
    }
}