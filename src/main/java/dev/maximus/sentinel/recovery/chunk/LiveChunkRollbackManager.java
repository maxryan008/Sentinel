package dev.maximus.sentinel.recovery.chunk;

import dev.maximus.sentinel.Sentinel;

import dev.maximus.sentinel.mixin.recovery.ChunkHolderSwapAccessor;
import dev.maximus.sentinel.mixin.recovery.ChunkMapSwapAccessor;
import dev.maximus.sentinel.mixin.recovery.GenerationChunkHolderSwapAccessor;
import dev.maximus.sentinel.mixin.recovery.ServerChunkCacheSwapAccessor;

import net.minecraft.core.BlockPos;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;

import java.util.HashSet;
import java.util.Set;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.function.Consumer;

public final class LiveChunkRollbackManager {

    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(task -> {

                Thread thread = new Thread(
                        task,
                        "Sentinel-Chunk-Preloader"
                );

                thread.setDaemon(true);

                return thread;
            });

    private static Transaction active;

    private LiveChunkRollbackManager() {
    }

    private enum Stage {
        READING,
        PREPARING,
        SWAPPING,
        RESTORING_ENTITIES,
        REBUILDING_LIGHT,
        SYNCHRONIZING
    }

    private static final class Transaction {

        final ServerLevel level;
        final ChunkPos position;

        final Path snapshotDimension;
        final Consumer<String> feedback;

        final RegionStorageInfo storageInfo;

        CompletableFuture<ChunkSnapshotRecords.Records> reading;

        ChunkSnapshotRecords.Records records;

        LevelChunk original;
        LevelChunk replacement;

        ChunkLightingRestorer lightingRestorer;

        ChunkEntityRestorer.Result entityResult;

        Stage stage = Stage.READING;

        int restoredBlockEntities;

        Transaction(
                ServerLevel level,
                ChunkPos position,
                Path snapshotDimension,
                Consumer<String> feedback
        ) {

            this.level = level;
            this.position = position;
            this.snapshotDimension = snapshotDimension;
            this.feedback = feedback;

            this.storageInfo = new RegionStorageInfo(
                    level.getServer()
                            .getWorldData()
                            .getLevelName(),
                    level.dimension(),
                    "chunk"
            );
        }
    }

    public static boolean busy() {
        return active != null;
    }

    /**
     * Starts an in-memory chunk rollback.
     *
     * Snapshot IO happens asynchronously.
     * Minecraft world operations run on the server thread.
     */
    public static void start(
            ServerLevel level,
            ChunkPos position,
            Path snapshotDimension,
            Consumer<String> feedback
    ) {

        MinecraftServer server = level.getServer();

        if (server == null || !server.isSameThread()) {

            throw new IllegalStateException(
                    "Chunk swap must start on the server thread."
            );
        }

        if (active != null) {

            throw new IllegalStateException(
                    "Another chunk swap is already running."
            );
        }

        Transaction transaction = new Transaction(
                level,
                position,
                snapshotDimension,
                feedback
        );

        active = transaction;

        transaction.reading = CompletableFuture.supplyAsync(
                () -> {

                    try {

                        return ChunkSnapshotRecords.read(
                                snapshotDimension,
                                position,
                                transaction.storageInfo
                        );

                    } catch (Exception exception) {

                        throw new CompletionException(exception);
                    }
                },
                IO
        );

        feedback.accept(
                "Preloading snapshot for chunk "
                        + position
        );
    }

    /**
     * Advances the active transaction.
     *
     * Called by Sentinel from END_SERVER_TICK.
     */
    public static void tick(MinecraftServer server) {

        Transaction transaction = active;

        if (transaction == null) {
            return;
        }

        try {

            if (!server.isSameThread()) {
                throw new IllegalStateException(
                        "Chunk swap must tick on the server thread."
                );
            }

            if (server.getLevel(
                    transaction.level.dimension()
            ) != transaction.level) {

                throw new IllegalStateException(
                        "Target dimension is no longer loaded."
                );
            }

            switch (transaction.stage) {

                case READING ->
                        finishReading(transaction);

                case PREPARING ->
                        prepareReplacement(transaction);

                case SWAPPING ->
                        swap(transaction);

                case RESTORING_ENTITIES ->
                        restoreEntities(transaction);

                case REBUILDING_LIGHT ->
                        rebuildLighting(transaction);

                case SYNCHRONIZING ->
                        synchronize(transaction);
            }

        } catch (Throwable exception) {

            Sentinel.LOGGER.error(
                    "In-memory chunk swap failed at stage {}",
                    transaction.stage,
                    exception
            );

            transaction.feedback.accept(
                    "Chunk rollback FAILED at "
                            + transaction.stage
                            + ": "
                            + exception.getMessage()
            );

            /*
             * This does not reverse an already committed
             * chunk swap or entity restoration.
             */
            active = null;
        }
    }

    private static void finishReading(
            Transaction transaction
    ) {

        if (!transaction.reading.isDone()) {
            return;
        }

        transaction.records =
                transaction.reading.join();

        if (transaction.records.terrain() == null) {

            throw new IllegalStateException(
                    "Snapshot has no terrain record."
            );
        }

        transaction.stage = Stage.PREPARING;

        transaction.feedback.accept(
                "Snapshot loaded; constructing replacement chunk."
        );
    }

    /**
     * Deserializes the terrain and prepares saved
     * block-entity NBT.
     */
    private static void prepareReplacement(
            Transaction transaction
    ) {

        CompoundTag terrain =
                transaction.records.terrain().copy();

        ProtoChunk proto = ChunkSerializer.read(
                transaction.level,
                transaction.level.getPoiManager(),
                transaction.storageInfo,
                transaction.position,
                terrain
        );

        if (proto == null) {

            throw new IllegalStateException(
                    "ChunkSerializer returned null."
            );
        }

        if (!proto.getPos().equals(
                transaction.position
        )) {

            throw new IllegalStateException(
                    "Deserialized chunk coordinates differ."
            );
        }

        transaction.replacement = new LevelChunk(
                transaction.level,
                proto,
                null
        );

        loadBlockEntityNbt(transaction);

        transaction.stage = Stage.SWAPPING;

        transaction.feedback.accept(
                "Replacement constructed; swapping active chunk."
        );
    }

    /**
     * Places complete block-entity NBT into the
     * replacement chunk before activation.
     */
    private static void loadBlockEntityNbt(
            Transaction transaction
    ) {

        LevelChunk replacement =
                transaction.replacement;

        CompoundTag terrain =
                transaction.records.terrain();

        ListTag saved = terrain.getList(
                "block_entities",
                Tag.TAG_COMPOUND
        );

        Set<BlockPos> seen = new HashSet<>();

        int copied = 0;
        int rejected = 0;

        for (int i = 0; i < saved.size(); i++) {

            CompoundTag tag =
                    saved.getCompound(i).copy();

            if (!tag.contains("x", Tag.TAG_INT)
                    || !tag.contains("y", Tag.TAG_INT)
                    || !tag.contains("z", Tag.TAG_INT)) {

                rejected++;
                continue;
            }

            BlockPos position = new BlockPos(
                    tag.getInt("x"),
                    tag.getInt("y"),
                    tag.getInt("z")
            );

            if (!new ChunkPos(position).equals(
                    transaction.position
            )) {

                rejected++;
                continue;
            }

            if (!seen.add(position)) {

                rejected++;
                continue;
            }

            replacement.setBlockEntityNbt(tag);

            copied++;
        }

        Sentinel.LOGGER.info(
                "Prepared block-entity NBT for chunk {}: "
                        + "copied={}, rejected={}",
                transaction.position,
                copied,
                rejected
        );
    }

    /**
     * Instantiates the restored block entities
     * from their saved NBT.
     */
    private static void materializeBlockEntities(
            Transaction transaction
    ) {

        LevelChunk replacement =
                transaction.replacement;

        ListTag saved =
                transaction.records.terrain().getList(
                        "block_entities",
                        Tag.TAG_COMPOUND
                );

        Set<BlockPos> seen = new HashSet<>();

        int restored = 0;
        int failed = 0;

        for (int i = 0; i < saved.size(); i++) {

            CompoundTag tag =
                    saved.getCompound(i);

            if (!tag.contains("x", Tag.TAG_INT)
                    || !tag.contains("y", Tag.TAG_INT)
                    || !tag.contains("z", Tag.TAG_INT)) {

                failed++;
                continue;
            }

            BlockPos position = new BlockPos(
                    tag.getInt("x"),
                    tag.getInt("y"),
                    tag.getInt("z")
            );

            if (!new ChunkPos(position).equals(
                    transaction.position
            )) {

                failed++;
                continue;
            }

            if (!seen.add(position)) {
                continue;
            }

            BlockEntity entity =
                    replacement.getBlockEntity(
                            position,
                            LevelChunk.EntityCreationType.IMMEDIATE
                    );

            if (entity == null) {

                Sentinel.LOGGER.warn(
                        "Failed to materialize block entity "
                                + "at {} in {}",
                        position,
                        transaction.position
                );

                failed++;
                continue;
            }

            entity.setChanged();

            restored++;
        }

        transaction.restoredBlockEntities = restored;

        replacement.registerAllBlockEntitiesAfterLevelLoad();

        replacement.setUnsaved(true);

        Sentinel.LOGGER.info(
                "Restored block entities in chunk {}: "
                        + "restored={}, failed={}",
                transaction.position,
                restored,
                failed
        );
    }

    /**
     * Replaces the active LevelChunk object.
     *
     * Both the FULL generation future and the
     * accessible/ticking futures are updated.
     */
    private static void swap(
            Transaction transaction
    ) {

        ServerLevel level = transaction.level;

        ServerChunkCache cache =
                level.getChunkSource();

        ChunkMapSwapAccessor chunkMap =
                (ChunkMapSwapAccessor) (Object)
                        cache.chunkMap;

        long key = transaction.position.toLong();

        ChunkHolder visible =
                chunkMap.sentinel$getVisibleChunkMap()
                        .get(key);

        ChunkHolder updating =
                chunkMap.sentinel$getUpdatingChunkMap()
                        .get(key);

        if (visible == null || updating == null) {

            throw new IllegalStateException(
                    "Target chunk has no active holder."
            );
        }

        if (visible != updating) {

            throw new IllegalStateException(
                    "Visible and updating holders disagree."
            );
        }

        ChunkHolderSwapAccessor holder =
                (ChunkHolderSwapAccessor) (Object)
                        visible;

        GenerationChunkHolderSwapAccessor generation =
                (GenerationChunkHolderSwapAccessor) (Object)
                        visible;

        LevelChunk original =
                cache.getChunkNow(
                        transaction.position.x,
                        transaction.position.z
                );

        if (original == null) {

            throw new IllegalStateException(
                    "Target chunk is not fully loaded."
            );
        }

        LevelChunk replacement =
                transaction.replacement;

        transaction.original = original;

        replacement.setFullStatus(
                original::getFullStatus
        );

        CompletableFuture<ChunkResult<LevelChunk>> resolved =
                CompletableFuture.completedFuture(
                        ChunkResult.of(replacement)
                );

        CompletableFuture<ChunkResult<ChunkAccess>> generationResolved =
                CompletableFuture.completedFuture(
                        ChunkResult.of(
                                (ChunkAccess) replacement
                        )
                );

        int fullIndex =
                ChunkStatus.FULL.getIndex();

        var generationFutures =
                generation.sentinel$getGenerationFutures();

        var oldGenerationFuture =
                generationFutures.get(fullIndex);

        var oldFullFuture =
                holder.sentinel$getFullChunkFuture();

        var oldTickingFuture =
                holder.sentinel$getTickingChunkFuture();

        var oldEntityTickingFuture =
                holder.sentinel$getEntityTickingChunkFuture();

        /*
         * Change the generation and ticking results.
         */
        generationFutures.set(
                fullIndex,
                generationResolved
        );

        holder.sentinel$setFullChunkFuture(
                resolved
        );

        holder.sentinel$setTickingChunkFuture(
                resolved
        );

        holder.sentinel$setEntityTickingChunkFuture(
                resolved
        );

        ((ServerChunkCacheSwapAccessor) (Object) cache)
                .sentinel$clearCache();

        /*
         * Verify server-side authoritative lookups
         * before removing the old runtime registrations.
         */
        LevelChunk cached =
                cache.getChunkNow(
                        transaction.position.x,
                        transaction.position.z
                );

        LevelChunk authoritative =
                level.getChunk(
                        transaction.position.x,
                        transaction.position.z
                );

        if (cached != replacement
                || authoritative != replacement) {

            generationFutures.set(
                    fullIndex,
                    oldGenerationFuture
            );

            holder.sentinel$setFullChunkFuture(
                    oldFullFuture
            );

            holder.sentinel$setTickingChunkFuture(
                    oldTickingFuture
            );

            holder.sentinel$setEntityTickingChunkFuture(
                    oldEntityTickingFuture
            );

            ((ServerChunkCacheSwapAccessor) (Object) cache)
                    .sentinel$clearCache();

            throw new IllegalStateException(
                    "Chunk lookup verification failed. "
                            + "Original holder futures restored."
            );
        }

        /*
         * The replacement now appears in both
         * primary server lookup paths.
         */
        original.unregisterTickContainerFromLevel(level);

        original.clearAllBlockEntities();

        original.setLoaded(false);

        replacement.setLoaded(true);

        replacement.registerTickContainerInLevel(level);

        materializeBlockEntities(transaction);

        replacement.setUnsaved(true);

        Sentinel.LOGGER.info(
                "Chunk object replaced: chunk={}, "
                        + "original={}, replacement={}, "
                        + "blockEntities={}",
                transaction.position,
                System.identityHashCode(original),
                System.identityHashCode(replacement),
                transaction.restoredBlockEntities
        );

        transaction.stage = Stage.RESTORING_ENTITIES;

        transaction.feedback.accept(
                "Terrain and block entities restored; "
                        + "reconciling non-player entities."
        );
    }

    /**
     * Restores non-player entities from the snapshot.
     *
     * Matching UUIDs are removed from loaded dimensions
     * before the snapshot versions are recreated.
     */
    private static void restoreEntities(
            Transaction transaction
    ) {

        transaction.entityResult =
                ChunkEntityRestorer.restore(
                        transaction.level,
                        transaction.position,
                        transaction.records.entities()
                );

        transaction.stage = Stage.REBUILDING_LIGHT;

        transaction.lightingRestorer =
                new ChunkLightingRestorer(
                        transaction.level,
                        transaction.position,
                        transaction.original,
                        transaction.replacement
                );

        transaction.feedback.accept(
                "Entities restored: "
                        + transaction.entityResult.restored()
                        + ". Rebuilding lighting."
        );
    }

    /**
     * Recalculates lighting where the original and
     * replacement block states differ.
     */
    private static void rebuildLighting(
            Transaction transaction
    ) {

        if (!transaction.lightingRestorer.tick()) {
            return;
        }

        transaction.stage = Stage.SYNCHRONIZING;

        transaction.feedback.accept(
                "Lighting scan completed; synchronizing clients."
        );
    }

    /**
     * Sends the restored chunk to nearby players
     * after entity reconciliation and lighting updates.
     */
    private static void synchronize(
            Transaction transaction
    ) {

        ServerLevel level =
                transaction.level;

        ServerChunkCache cache =
                level.getChunkSource();

        LevelChunk replacement =
                transaction.replacement;

        LevelChunk cached =
                cache.getChunkNow(
                        transaction.position.x,
                        transaction.position.z
                );

        LevelChunk authoritative =
                level.getChunk(
                        transaction.position.x,
                        transaction.position.z
                );

        if (cached != replacement
                || authoritative != replacement) {

            throw new IllegalStateException(
                    "Replacement chunk is no longer authoritative."
            );
        }

        int viewDistance =
                level.getServer()
                        .getPlayerList()
                        .getViewDistance();

        for (ServerPlayer player : level.players()) {

            ChunkPos playerChunk =
                    player.chunkPosition();

            if (Math.abs(
                    playerChunk.x - transaction.position.x
            ) > viewDistance) {
                continue;
            }

            if (Math.abs(
                    playerChunk.z - transaction.position.z
            ) > viewDistance) {
                continue;
            }

            player.connection.send(
                    new ClientboundForgetLevelChunkPacket(
                            transaction.position
                    )
            );

            player.connection.send(
                    new ClientboundLevelChunkWithLightPacket(
                            replacement,
                            level.getLightEngine(),
                            null,
                            null
                    )
            );
        }

        ChunkEntityRestorer.Result entities =
                transaction.entityResult;

        transaction.feedback.accept(
                "Chunk rollback completed for "
                        + transaction.position
                        + ". Block entities: "
                        + transaction.restoredBlockEntities
                        + ", entities restored: "
                        + entities.restored()
                        + ", entities removed from target: "
                        + entities.removedFromTarget()
                        + ", entities removed by UUID: "
                        + entities.removedByUuid()
                        + ", entity failures: "
                        + entities.failed()
                        + ", lighting changes queued: "
                        + transaction.lightingRestorer.changedBlocks()
                        + ". Unloaded-entity UUID reconciliation "
                        + "and POI restoration remain incomplete."
        );

        Sentinel.LOGGER.info(
                "Chunk rollback finished: "
                        + "dimension={}, chunk={}, "
                        + "blockEntities={}, entities={}, "
                        + "uuidRelocations={}, lightingChanges={}",
                level.dimension().location(),
                transaction.position,
                transaction.restoredBlockEntities,
                entities.restored(),
                entities.removedByUuid(),
                transaction.lightingRestorer.changedBlocks()
        );

        active = null;
    }

    /**
     * Cancels pending snapshot preparation.
     *
     * Does not reverse a committed runtime swap.
     */
    public static void shutdown() {

        if (active != null
                && active.reading != null) {

            active.reading.cancel(true);
        }

        active = null;
    }
}