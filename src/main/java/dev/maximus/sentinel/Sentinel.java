package dev.maximus.sentinel;

import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.command.SentinelCommands;
import dev.maximus.sentinel.config.SentinelConfig;
import dev.maximus.sentinel.recovery.BootloopRecovery;
import dev.maximus.sentinel.recovery.RestoreScriptGenerator;
import dev.maximus.sentinel.recovery.WorldPathResolver;
import dev.maximus.sentinel.recovery.RollbackManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Sentinel implements ModInitializer {

    public static final String MOD_ID = "sentinel";

    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    private static BackupManager backupManager;

    @Override
    public void onInitialize() {

        SentinelCommands.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {

            SentinelConfig config = SentinelConfig.loadOrCreate();

            backupManager = new BackupManager(server, config);

            backupManager.validateBeforeServerStart();
            if (config.enabled && config.backupDirectory != null && !config.backupDirectory.isBlank()) {
                WorldPathResolver.record(server, config.recoveryDirectory());
            }
            backupManager.start();

            if (config.generateManualRollbackScripts) {
                RestoreScriptGenerator.generate(
                        config,
                        BackupManager.serverDirectory()
                );
            }

            LOGGER.info("Sentinel backup manager started.");
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {

            // Recovery health certification must continue even
            // while ordinary scheduled backups are paused.

            BootloopRecovery.tick();

            if (backupManager != null) {
                backupManager.tick();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {

            if (backupManager != null) {
                backupManager.shutdown();
                backupManager = null;
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {

            // Only clear the boot marker after the server
            // has completed its normal shutdown lifecycle.

            try { RollbackManager.onServerStopped(server); } finally { BootloopRecovery.cleanShutdown(); }
        });
    }

    public static BackupManager backupManager() {
        return backupManager;
    }
}