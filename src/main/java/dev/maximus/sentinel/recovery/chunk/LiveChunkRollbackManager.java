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
        RESTORING_POI,
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
        ChunkPoiRestorer.Result poiResult;

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

    public static void start(
            ServerLevel level,
            ChunkPos position,
            Path snapshotDimension,
            Consumer<String> feedback
    ) {

        MinecraftServer server = level.getServer();

        if (server == null || !server.isSameThread()) {

            throw new IllegalStateException(
                    "Chunk rollback must start on "
                            + "the server thread."
            );
        }

        if (active != null) {

            throw new IllegalStateException(
                    "Another chunk rollback is already running."
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
     * Called from Sentinel's END_SERVER_TICK callback.
     */
    public static void tick(MinecraftServer server) {

        Transaction transaction = active;

        if (transaction == null) {
            return;
        }

        try {

            if (!server.isSameThread()) {

                throw new IllegalStateException(
                        "Chunk rollback must tick on "
                                + "the server thread."
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

                case RESTORING_POI ->
                        restorePoi(transaction);

                case REBUILDING_LIGHT ->
                        rebuildLighting(transaction);

                case SYNCHRONIZING ->
                        synchronize(transaction);
            }

        } catch (Throwable exception) {

            Sentinel.LOGGER.error(
                    "Chunk rollback failed at stage {}",
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
             * A failure after SWAPPING is not
             * automatically reversed.
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
                "Snapshot loaded; preparing replacement chunk."
        );
    }

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
                    "Snapshot chunk coordinates differ "
                            + "from the requested chunk."
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
     * Copies the complete saved block-entity NBT
     * into the replacement LevelChunk.
     */
    private static void loadBlockEntityNbt(
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

        int copied = 0;
        int rejected = 0;

        for (int index = 0;
             index < saved.size();
             index++) {

            CompoundTag tag =
                    saved.getCompound(index).copy();

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
                "Prepared block-entity NBT: "
                        + "chunk={}, copied={}, rejected={}",
                transaction.position,
                copied,
                rejected
        );
    }

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

        for (int index = 0;
             index < saved.size();
             index++) {

            CompoundTag tag =
                    saved.getCompound(index);

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
                        "Unable to materialize block entity "
                                + "at {}",
                        position
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
                "Restored block entities: "
                        + "chunk={}, restored={}, failed={}",
                transaction.position,
                restored,
                failed
        );
    }

    /**
     * Replace the authoritative LevelChunk while
     * preserving the active ChunkHolder.
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
                    "Chunk lookup verification failed."
            );
        }

        original.unregisterTickContainerFromLevel(level);
        original.clearAllBlockEntities();
        original.setLoaded(false);

        replacement.setLoaded(true);
        replacement.registerTickContainerInLevel(level);

        materializeBlockEntities(transaction);

        replacement.setUnsaved(true);

        Sentinel.LOGGER.info(
                "Chunk object replaced: "
                        + "chunk={}, original={}, replacement={}",
                transaction.position,
                System.identityHashCode(original),
                System.identityHashCode(replacement)
        );

        transaction.stage = Stage.RESTORING_ENTITIES;

        transaction.feedback.accept(
                "Terrain and block entities restored; "
                        + "restoring entities."
        );
    }

    private static void restoreEntities(
            Transaction transaction
    ) {

        transaction.entityResult =
                ChunkEntityRestorer.restore(
                        transaction.level,
                        transaction.position,
                        transaction.records.entities()
                );

        transaction.stage = Stage.RESTORING_POI;

        transaction.feedback.accept(
                "Entities restored; restoring POI data."
        );
    }

    /**
     * Restores the authoritative POI column.
     *
     * This stage runs after terrain replacement
     * and before lighting/client synchronisation.
     */
    private static void restorePoi(
            Transaction transaction
    ) {

        transaction.poiResult =
                ChunkPoiRestorer.restore(
                        transaction.level,
                        transaction.position,
                        transaction.records.poi()
                );

        transaction.lightingRestorer =
                new ChunkLightingRestorer(
                        transaction.level,
                        transaction.position,
                        transaction.original,
                        transaction.replacement
                );

        transaction.stage = Stage.REBUILDING_LIGHT;

        transaction.feedback.accept(
                "POIs restored: "
                        + transaction.poiResult.restoredRecords()
                        + " records. Rebuilding lighting."
        );
    }

    private static void rebuildLighting(
            Transaction transaction
    ) {

        if (!transaction.lightingRestorer.tick()) {
            return;
        }

        transaction.stage = Stage.SYNCHRONIZING;

        transaction.feedback.accept(
                "Lighting rebuilt; synchronizing clients."
        );
    }

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

        ChunkPoiRestorer.Result pois =
                transaction.poiResult;

        transaction.feedback.accept(
                "Chunk rollback completed for "
                        + transaction.position
                        + ". Block entities: "
                        + transaction.restoredBlockEntities
                        + ", entities restored: "
                        + entities.restored()
                        + ", POI records restored: "
                        + pois.restoredRecords()
                        + ", old POI records: "
                        + pois.previousRecords()
                        + ", lighting checks: "
                        + transaction.lightingRestorer.lightingChecks()
        );

        Sentinel.LOGGER.info(
                "Chunk rollback completed: "
                        + "dimension={}, chunk={}, "
                        + "blockEntities={}, entities={}, "
                        + "poiRecords={}, previousPoiRecords={}, "
                        + "lightChecks={}",
                level.dimension().location(),
                transaction.position,
                transaction.restoredBlockEntities,
                entities.restored(),
                pois.restoredRecords(),
                pois.previousRecords(),
                transaction.lightingRestorer.lightingChecks()
        );

        active = null;
    }

    public static void shutdown() {

        if (active != null
                && active.reading != null) {

            active.reading.cancel(true);
        }

        active = null;
    }
}