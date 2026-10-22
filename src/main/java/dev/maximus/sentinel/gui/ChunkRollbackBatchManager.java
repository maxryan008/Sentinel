package dev.maximus.sentinel.gui;

import dev.maximus.sentinel.recovery.chunk.LiveChunkRollbackManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * Delegates GUI rollback batches to the coordinated
 * multi-chunk hot-swap transaction.
 *
 * The transaction itself is managed by
 * LiveChunkRollbackManager.
 */
public final class ChunkRollbackBatchManager {

    private ChunkRollbackBatchManager() {
    }

    public static boolean busy() {
        return LiveChunkRollbackManager.busy();
    }

    public static void start(
            ServerPlayer owner,
            ServerLevel level,
            Path snapshotDimension,
            Collection<ChunkPos> chunks
    ) {

        UUID ownerId = owner.getUUID();
        MinecraftServer server = owner.getServer();

        LiveChunkRollbackManager.startBatch(
                level,
                chunks,
                snapshotDimension,
                message -> {

                    ServerPlayer current =
                            server.getPlayerList().getPlayer(ownerId);

                    if (current != null) {
                        current.sendSystemMessage(
                                Component.literal(
                                        "[Sentinel] " + message
                                )
                        );
                    }
                }
        );
    }

    /**
     * Retained for the existing Fabric event
     * registration in ChunkMapServer.
     *
     * LiveChunkRollbackManager is already ticked
     * from Sentinel.java, so ticking the batch
     * separately would cause duplicate updates.
     */
    public static void tick(MinecraftServer server) {
    }
}