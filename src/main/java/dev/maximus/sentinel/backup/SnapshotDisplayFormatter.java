package dev.maximus.sentinel.backup;

import dev.maximus.sentinel.config.SentinelConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SnapshotDisplayFormatter {
    private SnapshotDisplayFormatter() {
    }

    public static String format(SentinelConfig config, Path snapshotPath) {
        String fileName = snapshotPath.getFileName().toString();
        String snapshotId = SnapshotLockManager.cleanSnapshotId(fileName, config);

        boolean locked = SnapshotLockManager.isLocked(config, snapshotId);
        boolean compressed = fileName.endsWith(config.compressionExtension);

        String reason = "unknown";

        if (Files.isDirectory(snapshotPath)) {
            SnapshotMetadata metadata = SnapshotMetadata.read(snapshotPath);

            if (metadata != null && metadata.reason() != null && !metadata.reason().isBlank()) {
                reason = metadata.reason();
            }
        }

        return snapshotId
                + (locked ? " [LOCKED]" : "")
                + (compressed ? " [COMPRESSED]" : "")
                + " - reason: " + reason;
    }
}