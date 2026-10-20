package dev.maximus.sentinel.backup;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.config.SentinelConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class SnapshotCompressor {
    private SnapshotCompressor() {
    }

    public static void compressOldSnapshots(SentinelConfig config) {
        try {
            if (Files.notExists(config.snapshotsDirectory())) {
                return;
            }

            Instant cutoff = Instant.now().minus(Duration.ofDays(config.compressSnapshotsOlderThanDays));

            List<Path> snapshots = Files.list(config.snapshotsDirectory())
                    .filter(Files::isDirectory)
                    .filter(path -> !SnapshotLockManager.isLocked(config, path.getFileName().toString()))
                    .filter(path -> isOlderThan(path, cutoff))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path snapshot : snapshots) {
                compressSnapshot(config, snapshot);
            }
        } catch (Exception exception) {
            Sentinel.LOGGER.error("Failed to compress old Sentinel snapshots.", exception);
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (Exception exception) {
            return false;
        }
    }

    private static void compressSnapshot(SentinelConfig config, Path snapshot) {
        String fileName = snapshot.getFileName().toString();
        Path archive = config.snapshotsDirectory().resolve(fileName + config.compressionExtension);

        if (Files.exists(archive)) {
            return;
        }

        Sentinel.LOGGER.info("Compressing Sentinel snapshot: {}", snapshot);

        FileActions.run(List.of(
                config.compressionCommand,
                "-czf",
                archive.toString(),
                "-C",
                config.snapshotsDirectory().toString(),
                fileName
        ), config.snapshotsDirectory());

        deleteDirectory(snapshot);
    }

    private static void deleteDirectory(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to delete compressed snapshot directory.", exception);
        }
    }
}