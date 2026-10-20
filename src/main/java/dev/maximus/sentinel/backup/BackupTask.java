package dev.maximus.sentinel.backup;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.config.SentinelConfig;

import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class BackupTask implements Runnable {
    private static final DateTimeFormatter SNAPSHOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    private final MinecraftServer server;
    private final SentinelConfig config;
    private final String reason;

    public BackupTask(MinecraftServer server, SentinelConfig config, String reason) {
        this.server = server;
        this.config = config;
        this.reason = reason;
    }

    @Override
    public void run() {
        long start = System.currentTimeMillis();

        String snapshotId = SNAPSHOT_FORMAT.format(Instant.now());
        Path source = BackupManager.serverDirectory();
        Path model = this.config.modelDirectory();
        Path snapshot = this.config.snapshotsDirectory().resolve(snapshotId);

        Sentinel.LOGGER.info("Starting Sentinel backup. reason={}, source={}, model={}, snapshot={}",
                this.reason, source, model, snapshot);

        boolean savingDisabled = false;

        try {
            BackupManager.flushServer(this.server);

            if (this.config.disableSavingDuringBackup) {
                BackupManager.disableSaving(this.server);
                savingDisabled = true;
            }

            FileActions.rsyncToModel(source, model, this.config.rsyncExcludes);
            FileActions.hardlinkCopy(model, snapshot);

            long duration = System.currentTimeMillis() - start;

            SnapshotMetadata metadata = new SnapshotMetadata(
                    snapshotId,
                    Instant.now().toString(),
                    this.reason,
                    SharedConstants.getCurrentVersion().getName(),
                    source.toString(),
                    false,
                    duration
            );

            SnapshotMetadata.write(snapshot, metadata);
            BackupCullPolicy.cull(this.config);

            Sentinel.LOGGER.info("Finished Sentinel backup in {} ms: {}", duration, snapshot);
        } finally {
            if (savingDisabled) {
                BackupManager.enableSaving(this.server);
            }
        }
    }
}