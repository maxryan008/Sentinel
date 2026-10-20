package dev.maximus.sentinel.backup;

import dev.maximus.sentinel.config.SentinelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SnapshotLockManager {
    private SnapshotLockManager() {
    }

    public static void lock(SentinelConfig config, String snapshotId) {
        setLocked(config, snapshotId, true);
    }

    public static void unlock(SentinelConfig config, String snapshotId) {
        setLocked(config, snapshotId, false);
    }

    public static boolean isLocked(SentinelConfig config, String snapshotId) {
        return Files.exists(lockPath(config, snapshotId));
    }

    public static void setLocked(SentinelConfig config, String snapshotId, boolean locked) {
        try {
            Files.createDirectories(config.locksDirectory());

            Path lockPath = lockPath(config, snapshotId);

            if (locked) {
                Files.writeString(lockPath, "locked\n");
            } else {
                Files.deleteIfExists(lockPath);
            }

            Path snapshotDirectory = config.snapshotsDirectory().resolve(snapshotId);
            if (Files.isDirectory(snapshotDirectory)) {
                Path localLock = snapshotDirectory.resolve(".sentinel_locked");

                if (locked) {
                    Files.writeString(localLock, "locked\n");
                } else {
                    Files.deleteIfExists(localLock);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to change snapshot lock state.", exception);
        }
    }

    public static String cleanSnapshotId(String fileName, SentinelConfig config) {
        if (fileName.endsWith(config.compressionExtension)) {
            return fileName.substring(0, fileName.length() - config.compressionExtension.length());
        }

        return fileName;
    }

    private static Path lockPath(SentinelConfig config, String snapshotId) {
        if (!snapshotId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || snapshotId.equals("..")) {
            throw new IllegalArgumentException("Invalid snapshot ID: " + snapshotId);
        }
        return config.locksDirectory().resolve(snapshotId + ".lock");
    }
}