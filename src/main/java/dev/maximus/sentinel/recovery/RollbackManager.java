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
    private static final AtomicReference<Pending> PENDING = new AtomicReference<>();
    private RollbackManager() {}

    public static List<String> list(SentinelConfig config) {
        try (Stream<Path> stream = Files.list(config.snapshotsDirectory())) {
            return stream.filter(Files::isDirectory).filter(p -> !Files.isSymbolicLink(p))
                    .map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).toList();
        } catch (java.nio.file.NoSuchFileException ex) { return List.of(); }
        catch (Exception ex) { throw new IllegalStateException("Cannot list snapshots", ex); }
    }

    public static String latest(SentinelConfig config) {
        List<String> snapshots = list(config);
        return snapshots.isEmpty() ? null : snapshots.getFirst();
    }

    public static void restoreAfterShutdown(BackupManager manager, String snapshotId) {
        if (snapshotId == null || !snapshotId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid snapshot ID");
        MinecraftServer server = manager.server();
        SentinelConfig config = manager.config();
        Path snapshot = config.snapshotsDirectory().resolve(snapshotId).normalize();
        Path relative = WorldPathResolver.relativeWorld(server);
        WorldPathResolver.snapshotWorld(snapshot, relative);
        if (manager.isRunningBackup()) throw new IllegalStateException("Backup running");
        if (PENDING.get() != null) throw new IllegalStateException("Rollback pending");
        manager.broadcastToAdmins("[Sentinel] Rollback requested: " + snapshotId);
        if (!config.createPreRollbackSafetyBackup) {
            launch(server, config, snapshotId, relative);
            return;
        }
        if (!manager.requestBackup("pre rollback safety backup", () -> {
            try { launch(server, config, snapshotId, relative); }
            catch (Exception ex) { Sentinel.LOGGER.error("Rollback not launched", ex); }
        }, throwable -> Sentinel.LOGGER.error("Safety backup failed; rollback cancelled", throwable))) {
            throw new IllegalStateException("Could not queue safety backup");
        }
    }

    private static void launch(MinecraftServer server, SentinelConfig config, String id, Path relative) {
        Path snapshot = config.snapshotsDirectory().resolve(id).normalize();
        WorldPathResolver.snapshotWorld(snapshot, relative);
        if (server.isDedicatedServer()) {
            Path script = BackupManager.serverDirectory().resolve("manual_rollback/rollback.sh");
            RestoreScriptGenerator.generate(config, BackupManager.serverDirectory());
            try {
                new ProcessBuilder("bash", script.toString(), "restore-world-after-pid", id,
                        Long.toString(ProcessHandle.current().pid()), relative.toString())
                        .directory(BackupManager.serverDirectory().toFile()).inheritIO().start();
            } catch (Exception ex) { throw new IllegalStateException("Cannot launch rollback", ex); }
        } else {
            Pending pending = new Pending(server, snapshot, relative);
            if (!PENDING.compareAndSet(null, pending)) throw new IllegalStateException("Rollback already pending");
        }
        server.halt(false);
    }

    /** Runs after integrated-server world storage has closed, not when the client JVM exits. */
    public static void onServerStopped(MinecraftServer server) {
        Pending pending = PENDING.get();
        if (pending == null || pending.server != server || !PENDING.compareAndSet(pending, null)) return;
        try {
            WorldRestorer.restore(BackupManager.serverDirectory(), pending.snapshot, pending.relativeWorld);
            Sentinel.LOGGER.info("Integrated world restored from {}", pending.snapshot.getFileName());
        } catch (Exception ex) {
            throw new IllegalStateException("Integrated rollback failed; do not reopen this world", ex);
        }
    }

    public static List<String> listFormatted(SentinelConfig config) {
        try (Stream<Path> stream = Files.list(config.snapshotsDirectory())) {
            return stream.filter(Files::isDirectory).map(p -> SnapshotDisplayFormatter.format(config, p))
                    .sorted(Comparator.reverseOrder()).toList();
        } catch (java.nio.file.NoSuchFileException ex) { return List.of(); }
        catch (Exception ex) { throw new IllegalStateException("Cannot list snapshots", ex); }
    }

    private record Pending(MinecraftServer server, Path snapshot, Path relativeWorld) {}
}
