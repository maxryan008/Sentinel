package dev.maximus.sentinel.config;

import dev.maximus.sentinel.Sentinel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SentinelConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public String backupDirectory = "";
    public int intervalSeconds = 300;
    public int maxBackups = 128;

    public boolean failServerStartWithoutBackupDirectory = true;
    public boolean failIfRunningInsideBackupDirectory = true;
    public boolean disableSavingDuringBackup = true;

    public boolean runInitialBackupAfterStartup = false;
    public int initialBackupDelaySeconds = 60;

    public boolean broadcastBackupStart = true;
    public boolean broadcastBackupFinish = true;
    public boolean broadcastBackupFailures = true;
    public boolean broadcastToOpsOnly = true;

    public boolean generateManualRollbackScripts = true;
    public boolean createPreRollbackSafetyBackup = true;

    public boolean compressOldSnapshots = false;
    public int compressSnapshotsOlderThanDays = 7;
    public String compressionCommand = "tar";
    public String compressionExtension = ".tar.gz";

    public List<String> rsyncExcludes = new ArrayList<>(List.of(
            "backups/**",
            "logs/latest.log",
            "crash-reports/**",
            "session.lock",
            "manual_rollback/**"
    ));

    // Automatic bootloop recovery
    public boolean automaticRecoveryEnabled = false;

    // Number of consecutive failed launches before recovery begins.
    public int recoveryFailureThreshold = 3;

    // Failures must occur within this window.
    public int recoveryFailureWindowSeconds = 600;

    // Server must tick successfully for this long to be considered healthy.
    public int recoveryHealthySeconds = 300;

    // Maximum number of progressively older snapshots to try.
    public int recoveryMaxRollbackAttempts = 5;

    // Preserve the original world before the first automatic restore.
    public boolean recoveryPreserveFailedWorld = true;

    public static SentinelConfig loadOrCreate() {
        Path path = configPath();

        try {
            Files.createDirectories(path.getParent());

            if (Files.notExists(path)) {
                SentinelConfig config = new SentinelConfig();
                config.save();
                Sentinel.LOGGER.warn("Created default Sentinel config at {}. Set backupDirectory before using.", path);
                return config;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                SentinelConfig config = GSON.fromJson(reader, SentinelConfig.class);

                if (config == null) {
                    config = new SentinelConfig();
                }

                config.sanitize();
                config.save();
                return config;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Sentinel config.", exception);
        }
    }

    public void save() {
        Path path = configPath();

        try {
            Files.createDirectories(path.getParent());

            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save Sentinel config.", exception);
        }
    }

    public Path resolveBackupDirectory() {
        return Path.of(this.backupDirectory).toAbsolutePath().normalize();
    }

    public Path modelDirectory() {
        return this.resolveBackupDirectory().resolve("_model");
    }

    public Path snapshotsDirectory() {
        return this.resolveBackupDirectory().resolve("snapshots");
    }

    public Path locksDirectory() {
        return this.resolveBackupDirectory().resolve("locks");
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("sentinel.json");
    }

    private void sanitize() {
        if (this.intervalSeconds < 30) {
            this.intervalSeconds = 30;
        }

        if (this.maxBackups < 1) {
            this.maxBackups = 1;
        }

        if (this.initialBackupDelaySeconds < 0) {
            this.initialBackupDelaySeconds = 0;
        }

        if (this.compressSnapshotsOlderThanDays < 1) {
            this.compressSnapshotsOlderThanDays = 1;
        }

        if (this.rsyncExcludes == null) {
            this.rsyncExcludes = new ArrayList<>();
        }

        if (recoveryFailureThreshold < 2) {
            recoveryFailureThreshold = 2;
        }

        if (recoveryFailureWindowSeconds < 60) {
            recoveryFailureWindowSeconds = 60;
        }

        if (recoveryHealthySeconds < 60) {
            recoveryHealthySeconds = 60;
        }

        if (recoveryMaxRollbackAttempts < 1) {
            recoveryMaxRollbackAttempts = 1;
        }
    }

    public Path recoveryDirectory() {
        return resolveBackupDirectory().resolve("recovery");
    }
}