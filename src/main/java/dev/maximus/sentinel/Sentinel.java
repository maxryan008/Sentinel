package dev.maximus.sentinel;

import dev.maximus.sentinel.backup.BackupManager;

import dev.maximus.sentinel.command.SentinelCommands;
import dev.maximus.sentinel.command.ChunkRollbackCommands;

import dev.maximus.sentinel.config.SentinelConfig;

import dev.maximus.sentinel.recovery.BootloopRecovery;
import dev.maximus.sentinel.recovery.RestoreScriptGenerator;
import dev.maximus.sentinel.recovery.RollbackManager;
import dev.maximus.sentinel.recovery.WorldPathResolver;

import dev.maximus.sentinel.recovery.chunk.LiveChunkRollbackManager;

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

    /*
     * Fabric requires a public constructor.
     */
    public Sentinel() {
    }

    @Override
    public void onInitialize() {

        SentinelCommands.register();
        ChunkRollbackCommands.register();

        /*
         * Initialise the backup manager whenever a
         * dedicated or integrated server starts.
         */
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {

            SentinelConfig config =
                    SentinelConfig.loadOrCreate();

            backupManager = new BackupManager(
                    server,
                    config
            );

            backupManager.validateBeforeServerStart();

            if (
                    config.enabled
                            && config.backupDirectory != null
                            && !config.backupDirectory.isBlank()
            ) {

                WorldPathResolver.record(
                        server,
                        config.recoveryDirectory()
                );
            }

            backupManager.start();

            if (config.generateManualRollbackScripts) {

                RestoreScriptGenerator.generate(
                        config,
                        BackupManager.serverDirectory()
                );
            }

            LOGGER.info(
                    "Sentinel backup manager started."
            );
        });

        /*
         * Advance the in-memory swap and normal
         * Sentinel maintenance on the server thread.
         */
        ServerTickEvents.END_SERVER_TICK.register(server -> {

            BootloopRecovery.tick();

            LiveChunkRollbackManager.tick(server);

            /*
             * Don't start scheduled backups while a
             * chunk swap is actively running.
             *
             * This does not stop backup operations that
             * began before the swap.
             */
            if (
                    backupManager != null
                            && !LiveChunkRollbackManager.busy()
            ) {

                backupManager.tick();
            }
        });

        /*
         * Server shutdown.
         */
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {

            LiveChunkRollbackManager.shutdown();

            if (backupManager != null) {

                backupManager.shutdown();
                backupManager = null;
            }

            LOGGER.info(
                    "Sentinel server shutdown cleanup completed."
            );
        });

        /*
         * Preserve existing integrated-server rollback
         * and bootloop-recovery handling.
         */
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {

            try {

                RollbackManager.onServerStopped(server);

            } finally {

                BootloopRecovery.cleanShutdown();
            }
        });

        LOGGER.info(
                "Sentinel initialised."
        );
    }

    public static BackupManager backupManager() {
        return backupManager;
    }
}