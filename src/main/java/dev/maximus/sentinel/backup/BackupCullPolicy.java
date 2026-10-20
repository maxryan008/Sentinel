package dev.maximus.sentinel.backup;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.config.SentinelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class BackupCullPolicy {
    private BackupCullPolicy() {
    }

    public static void cull(SentinelConfig config) {
        Path snapshotsDirectory = config.snapshotsDirectory();

        if (Files.notExists(snapshotsDirectory)) {
            return;
        }

        List<Path> snapshots;

        try {
            snapshots = Files.list(snapshotsDirectory)
                    .filter(path -> Files.isDirectory(path) || path.getFileName().toString().endsWith(config.compressionExtension))
                    .filter(path -> !SnapshotLockManager.isLocked(config, SnapshotLockManager.cleanSnapshotId(path.getFileName().toString(), config)))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list snapshots.", exception);
        }

        int deleteCount = snapshots.size() - config.maxBackups;

        if (deleteCount <= 0) {
            return;
        }

        for (int index = 0; index < deleteCount; index++) {
            deleteRecursively(snapshots.get(index));
        }
    }

    private static void deleteRecursively(Path root) {
        Sentinel.LOGGER.info("Deleting old Sentinel snapshot: {}", root);

        try {
            if (Files.isRegularFile(root)) {
                Files.deleteIfExists(root);
                return;
            }

            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete snapshot: " + root, exception);
        }
    }
}