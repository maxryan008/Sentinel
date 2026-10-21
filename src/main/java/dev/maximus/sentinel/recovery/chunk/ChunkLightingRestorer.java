package dev.maximus.sentinel.recovery.chunk;

import dev.maximus.sentinel.Sentinel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ChunkLightingRestorer {

    /*
     * Maximum number of block positions scanned
     * in a single server tick.
     */
    private static final int BLOCKS_PER_TICK = 4096;

    /*
     * Allow a limited number of additional update
     * passes after the queued completion marker.
     */
    private static final int MAX_SETTLING_TICKS = 100;

    private final ServerLevel level;
    private final ChunkPos position;

    private final LevelChunk original;
    private final LevelChunk replacement;

    private final ThreadedLevelLightEngine engine;

    private final int minY;
    private final int maxY;
    private final int totalBlocks;

    private enum Stage {
        INITIALIZING,
        SCANNING,
        PROPAGATING,
        WAITING,
        COMPLETE
    }

    private Stage stage = Stage.INITIALIZING;

    private int nextBlock;

    private int changedBlocks;
    private int lightingChecks;
    private int lightSources;
    private int changedSections;
    private int settlingTicks;

    private CompletableFuture<?> completionFuture;

    public ChunkLightingRestorer(
            ServerLevel level,
            ChunkPos position,
            LevelChunk original,
            LevelChunk replacement
    ) {

        this.level = level;
        this.position = position;

        this.original = original;
        this.replacement = replacement;

        this.engine =
                level.getChunkSource().getLightEngine();

        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();

        this.totalBlocks =
                (maxY - minY) * 16 * 16;
    }

    /**
     * Advances lighting restoration on the server thread.
     *
     * Returns true when the scheduled lighting work
     * has reached the completion stage.
     *
     * Does not delete Minecraft's existing light
     * section data.
     */
    public boolean tick() {

        MinecraftServer server = level.getServer();

        if (server == null || !server.isSameThread()) {

            throw new IllegalStateException(
                    "Chunk lighting restoration must run "
                            + "on the Minecraft server thread."
            );
        }

        switch (stage) {

            case INITIALIZING -> {

                initialize();

                stage = Stage.SCANNING;

                return false;
            }

            case SCANNING -> {

                scanBlocks();

                return false;
            }

            case PROPAGATING -> {

                propagate();

                stage = Stage.WAITING;

                return false;
            }

            case WAITING -> {

                return awaitLighting();
            }

            case COMPLETE -> {

                return true;
            }
        }

        return false;
    }

    /**
     * Prepares the replacement chunk's lighting
     * metadata without unloading or resetting the
     * light engine.
     */
    private void initialize() {

        Sentinel.LOGGER.info(
                "Starting lighting reconciliation: "
                        + "dimension={}, chunk={}",
                level.dimension().location(),
                position
        );

        /*
         * Rebuild the replacement chunk's internal
         * skylight source information.
         *
         * This does not clear the light engine's
         * existing block-light or sky-light data.
         */
        replacement.initializeLightSources();

        /*
         * Critical:
         *
         * Minecraft's BlockLightEngine.getEmission()
         * only returns emission when its section is
         * enabled for lighting.
         *
         * Therefore ensure the target chunk is enabled
         * before scheduling source checks.
         */
        engine.setLightEnabled(
                position,
                true
        );

        /*
         * Inform the light engine about changes in
         * section occupancy.
         *
         * Don't reset unchanged sections because
         * doing so can unnecessarily invalidate
         * existing section lighting state.
         */
        LevelChunkSection[] oldSections =
                original.getSections();

        LevelChunkSection[] newSections =
                replacement.getSections();

        if (oldSections.length != newSections.length) {

            throw new IllegalStateException(
                    "Original and replacement chunks have "
                            + "different section counts."
            );
        }

        int minSection =
                replacement.getMinSection();

        for (int index = 0;
             index < newSections.length;
             index++) {

            boolean wasEmpty =
                    oldSections[index].hasOnlyAir();

            boolean isEmpty =
                    newSections[index].hasOnlyAir();

            if (wasEmpty == isEmpty) {
                continue;
            }

            SectionPos sectionPosition =
                    SectionPos.of(
                            position.x,
                            minSection + index,
                            position.z
                    );

            engine.updateSectionStatus(
                    sectionPosition,
                    isEmpty
            );

            changedSections++;
        }

        /*
         * Start processing initialization tasks.
         *
         * These are queued on Minecraft's lighting
         * worker rather than executed immediately.
         */
        engine.tryScheduleUpdate();

        Sentinel.LOGGER.info(
                "Lighting initialized for chunk {}. "
                        + "Changed sections: {}",
                position,
                changedSections
        );
    }

    /**
     * Scans the original and replacement chunks.
     *
     * We reconcile:
     *
     * 1. Changed block states.
     * 2. Existing light sources.
     * 3. Newly restored light sources.
     * 4. Removed light sources.
     * 5. Blocks at chunk boundaries.
     *
     * Light sources are checked even when the
     * block state did not change.
     */
    private void scanBlocks() {

        int processed = 0;

        BlockPos.MutableBlockPos mutable =
                new BlockPos.MutableBlockPos();

        while (nextBlock < totalBlocks
                && processed < BLOCKS_PER_TICK) {

            int index = nextBlock++;

            int localX = index & 15;
            int localZ = (index >> 4) & 15;
            int localY = index >> 8;

            int worldX =
                    position.getMinBlockX() + localX;

            int worldY =
                    minY + localY;

            int worldZ =
                    position.getMinBlockZ() + localZ;

            mutable.set(
                    worldX,
                    worldY,
                    worldZ
            );

            BlockState before =
                    original.getBlockState(mutable);

            BlockState after =
                    replacement.getBlockState(mutable);

            boolean changed =
                    before != after;

            int oldEmission =
                    before.getLightEmission();

            int newEmission =
                    after.getLightEmission();

            boolean wasSource =
                    oldEmission > 0;

            boolean isSource =
                    newEmission > 0;

            /*
             * Any source from either version needs
             * to be reconciled.
             *
             * This is essential for cases where a
             * torch existed in both chunks but
             * its stored lighting became stale.
             */
            boolean needsCheck =
                    changed
                            || wasSource
                            || isSource;

            if (changed) {
                changedBlocks++;
            }

            if (isSource) {
                lightSources++;
            }

            if (needsCheck) {

                check(
                        mutable.immutable()
                );

                /*
                 * Changes at the chunk boundary
                 * can affect neighbouring chunks.
                 */
                if (changed) {

                    checkBoundary(
                            worldX,
                            worldY,
                            worldZ,
                            localX,
                            localZ
                    );
                }
            }

            processed++;
        }

        if (nextBlock < totalBlocks) {
            return;
        }

        Sentinel.LOGGER.info(
                "Lighting scan completed for chunk {}: "
                        + "changedBlocks={}, sources={}, checks={}",
                position,
                changedBlocks,
                lightSources,
                lightingChecks
        );

        stage = Stage.PROPAGATING;
    }

    /**
     * Schedules a lighting check for a block.
     */
    private void check(
            BlockPos blockPos
    ) {

        engine.checkBlock(blockPos);

        lightingChecks++;
    }

    /**
     * Checks neighbouring positions when a changed
     * block lies on a chunk boundary.
     */
    private void checkBoundary(
            int worldX,
            int worldY,
            int worldZ,
            int localX,
            int localZ
    ) {

        if (localX == 0) {

            check(
                    new BlockPos(
                            worldX - 1,
                            worldY,
                            worldZ
                    )
            );
        }

        if (localX == 15) {

            check(
                    new BlockPos(
                            worldX + 1,
                            worldY,
                            worldZ
                    )
            );
        }

        if (localZ == 0) {

            check(
                    new BlockPos(
                            worldX,
                            worldY,
                            worldZ - 1
                    )
            );
        }

        if (localZ == 15) {

            check(
                    new BlockPos(
                            worldX,
                            worldY,
                            worldZ + 1
                    )
            );
        }
    }

    /**
     * Reseeds light sources after all changed-block
     * checks and section-status updates have been
     * queued.
     */
    private void propagate() {

        /*
         * Minecraft's BlockLightEngine implementation
         * calls findBlockLightSources() and queues
         * increases from each emissive block.
         *
         * This ensures the replacement's light sources
         * are considered even if they have not changed.
         */
        engine.propagateLightSources(position);

        /*
         * Kick the lighting worker to process
         * queued checks and source propagation.
         */
        engine.tryScheduleUpdate();

        /*
         * Wait for a POST_UPDATE task on the target
         * chunk's lighting queue.
         *
         * This is a queue completion marker, not
         * a global guarantee that all neighbouring
         * chunks have finished lighting updates.
         */
        completionFuture =
                engine.waitForPendingTasks(
                        position.x,
                        position.z
                );

        engine.tryScheduleUpdate();

        Sentinel.LOGGER.info(
                "Queued lighting propagation for chunk {}: "
                        + "changedBlocks={}, lightSources={}, "
                        + "checks={}",
                position,
                changedBlocks,
                lightSources,
                lightingChecks
        );
    }

    /**
     * Waits for queued lighting work, then performs
     * a final check for outstanding light work.
     */
    private boolean awaitLighting() {

        if (completionFuture == null
                || !completionFuture.isDone()) {

            engine.tryScheduleUpdate();

            return false;
        }

        try {

            completionFuture.join();

        } catch (CompletionException exception) {

            throw new IllegalStateException(
                    "Lighting reconciliation failed "
                            + "for chunk " + position,
                    exception.getCause()
            );
        }

        /*
         * The lighting engine can still have work
         * queued after the initial completion marker,
         * especially when propagation crosses
         * section or chunk boundaries.
         *
         * Ask the worker to continue processing.
         */
        if (engine.hasLightWork()
                && settlingTicks < MAX_SETTLING_TICKS) {

            settlingTicks++;

            engine.tryScheduleUpdate();

            completionFuture =
                    engine.waitForPendingTasks(
                            position.x,
                            position.z
                    );

            engine.tryScheduleUpdate();

            return false;
        }

        if (engine.hasLightWork()) {

            Sentinel.LOGGER.warn(
                    "Chunk {} finished its lighting queue "
                            + "wait with outstanding global light work.",
                    position
            );
        }

        /*
         * Preserve the replacement as the active
         * correctly-lit chunk.
         */
        replacement.setLightCorrect(true);

        replacement.setUnsaved(true);

        stage = Stage.COMPLETE;

        Sentinel.LOGGER.info(
                "Lighting reconciliation completed for chunk {}: "
                        + "changedBlocks={}, sources={}, "
                        + "checks={}, settlingTicks={}",
                position,
                changedBlocks,
                lightSources,
                lightingChecks,
                settlingTicks
        );

        return true;
    }

    public int changedBlocks() {
        return changedBlocks;
    }

    public int lightingChecks() {
        return lightingChecks;
    }

    public int lightSources() {
        return lightSources;
    }

    public boolean isFinished() {
        return stage == Stage.COMPLETE;
    }
}