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
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class LiveChunkRollbackManager {

    private static final int MAX_BATCH_SIZE = 128;
    private static final int PREPARATION_BUDGET = 4;
    private static final int LIGHTING_BUDGET = 4;

    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Sentinel-Chunk-Preloader");
                thread.setDaemon(true);
                return thread;
            });

    private static Transaction active;
    private static BatchTransaction activeBatch;

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

    private enum BatchStage {
        READING,
        PREPARING,
        COMMITTING,
        LIGHTING,
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
                    level.getServer().getWorldData().getLevelName(),
                    level.dimension(),
                    "chunk"
            );
        }
    }

    private static final class BatchTransaction {

        final ServerLevel level;
        final List<Transaction> entries;
        final Consumer<String> feedback;
        final boolean[] lightingFinished;

        CompletableFuture<List<ChunkSnapshotRecords.Records>> reading;

        BatchStage stage = BatchStage.READING;

        int prepareIndex;
        int lightCursor;
        int completedLighting;

        BatchTransaction(
                ServerLevel level,
                List<Transaction> entries,
                Consumer<String> feedback
        ) {
            this.level = level;
            this.entries = entries;
            this.feedback = feedback;
            this.lightingFinished = new boolean[entries.size()];
        }
    }

    public static boolean busy() {
        return active != null || activeBatch != null;
    }

    /**
     * Original single-chunk rollback entry point.
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
                    "Chunk rollback must start on the server thread."
            );
        }

        if (busy()) {
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

        transaction.reading = CompletableFuture.supplyAsync(() -> {
            try {
                return ChunkSnapshotRecords.read(
                        snapshotDimension,
                        position,
                        transaction.storageInfo
                );
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, IO);

        feedback.accept("Preloading snapshot for chunk " + position);
    }

    /**
     * Start a coordinated multi-chunk rollback.
     *
     * All selected chunks are prepared before any
     * authoritative chunk holder is modified.
     */
    public static void startBatch(
            ServerLevel level,
            Collection<ChunkPos> positions,
            Path snapshotDimension,
            Consumer<String> feedback
    ) {
        MinecraftServer server = level.getServer();

        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "Batch rollback must begin on the server thread."
            );
        }

        if (busy()) {
            throw new IllegalStateException(
                    "Another chunk rollback is already running."
            );
        }

        LinkedHashSet<ChunkPos> unique = new LinkedHashSet<>(positions);

        if (unique.isEmpty()
                || unique.size() > MAX_BATCH_SIZE
                || unique.size() != positions.size()) {

            throw new IllegalArgumentException(
                    "Batch must contain 1 to "
                            + MAX_BATCH_SIZE
                            + " unique chunks."
            );
        }

        Consumer<String> silent = message ->
                Sentinel.LOGGER.debug("Batch rollback: {}", message);

        List<Transaction> entries = new ArrayList<>(unique.size());

        for (ChunkPos position : unique) {
            entries.add(new Transaction(
                    level,
                    position,
                    snapshotDimension,
                    silent
            ));
        }

        BatchTransaction batch = new BatchTransaction(
                level,
                entries,
                feedback
        );

        activeBatch = batch;

        batch.reading = CompletableFuture.supplyAsync(() -> {

            List<ChunkSnapshotRecords.Records> records =
                    new ArrayList<>(entries.size());

            for (Transaction entry : entries) {
                try {
                    records.add(ChunkSnapshotRecords.read(
                            snapshotDimension,
                            entry.position,
                            entry.storageInfo
                    ));
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }

            return records;
        }, IO);

        feedback.accept(
                "Preloading " + entries.size() + " snapshot chunks."
        );
    }

    /**
     * Called once per server tick.
     *
     * The batch and single-chunk pipelines are
     * mutually exclusive.
     */
    public static void tick(MinecraftServer server) {

        if (activeBatch != null) {
            tickBatch(server);
            return;
        }

        Transaction transaction = active;

        if (transaction == null) {
            return;
        }

        try {
            validateServerThread(server, transaction.level);

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

            active = null;
        }
    }

    private static void validateServerThread(
            MinecraftServer server,
            ServerLevel level
    ) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Chunk rollback must execute on the server thread."
            );
        }

        if (server.getLevel(level.dimension()) != level) {
            throw new IllegalStateException(
                    "Target dimension is no longer loaded."
            );
        }
    }

    /**
     * Multi-chunk transaction state machine.
     *
     * Physical restoration is committed in one tick.
     *
     * Client terrain synchronisation happens immediately
     * after physical restoration.
     *
     * Lighting is repaired separately afterward.
     */
    private static void tickBatch(MinecraftServer server) {

        BatchTransaction batch = activeBatch;

        if (batch == null) {
            return;
        }

        try {
            validateServerThread(server, batch.level);

            switch (batch.stage) {

                case READING -> {

                    if (!batch.reading.isDone()) {
                        return;
                    }

                    List<ChunkSnapshotRecords.Records> records =
                            batch.reading.join();

                    if (records.size() != batch.entries.size()) {
                        throw new IllegalStateException(
                                "Snapshot record count mismatch."
                        );
                    }

                    for (int i = 0; i < batch.entries.size(); i++) {

                        Transaction entry = batch.entries.get(i);

                        entry.records = records.get(i);

                        if (entry.records == null
                                || entry.records.terrain() == null) {

                            throw new IllegalStateException(
                                    "Snapshot has no terrain for "
                                            + entry.position
                            );
                        }
                    }

                    batch.stage = BatchStage.PREPARING;

                    batch.feedback.accept(
                            "All snapshot records loaded; "
                                    + "preparing replacements."
                    );
                }

                case PREPARING -> {

                    int budget = PREPARATION_BUDGET;

                    while (budget-- > 0
                            && batch.prepareIndex < batch.entries.size()) {

                        prepareReplacement(
                                batch.entries.get(batch.prepareIndex++)
                        );
                    }

                    if (batch.prepareIndex == batch.entries.size()) {

                        batch.stage = BatchStage.COMMITTING;

                        batch.feedback.accept(
                                "All replacements prepared; "
                                        + "performing synchronized swap."
                        );
                    }
                }

                case COMMITTING -> {

                    /*
                     * Validate every target before modifying
                     * any authoritative chunk holder.
                     */
                    for (Transaction entry : batch.entries) {
                        validateSwapTarget(entry);
                    }

                    /*
                     * PHASE 1: PHYSICAL RESTORATION
                     *
                     * All chunk holder swaps occur in this
                     * server tick without normal world
                     * simulation between them.
                     */
                    for (Transaction entry : batch.entries) {
                        swap(entry);
                    }

                    /*
                     * Every replacement chunk is now
                     * authoritative.
                     *
                     * Restore the remaining physical
                     * world state before publishing terrain.
                     */
                    for (Transaction entry : batch.entries) {
                        restoreEntities(entry);
                    }

                    for (Transaction entry : batch.entries) {
                        restorePoi(entry);
                    }

                    /*
                     * Publish restored terrain immediately.
                     *
                     * Do not wait for lighting.
                     */
                    synchronizeBatchTerrain(batch);

                    batch.feedback.accept(
                            "Physical restoration completed for "
                                    + batch.entries.size()
                                    + " chunks; terrain sent to clients."
                    );

                    /*
                     * PHASE 2: VISUAL RESTORATION
                     *
                     * Lighting continues in later ticks.
                     */
                    batch.stage = BatchStage.LIGHTING;
                }

                case LIGHTING -> {

                    /*
                     * Keep using Sentinel's existing
                     * per-chunk lighting implementation.
                     *
                     * Its work budget is intentionally
                     * independent from physical restoration.
                     */
                    int budget = LIGHTING_BUDGET;
                    int visited = 0;

                    while (budget > 0
                            && visited < batch.entries.size()
                            && batch.completedLighting
                            < batch.entries.size()) {

                        int index = batch.lightCursor;

                        batch.lightCursor =
                                (batch.lightCursor + 1)
                                        % batch.entries.size();

                        visited++;

                        if (batch.lightingFinished[index]) {
                            continue;
                        }

                        budget--;

                        Transaction entry =
                                batch.entries.get(index);

                        if (entry.lightingRestorer.tick()) {

                            batch.lightingFinished[index] = true;
                            batch.completedLighting++;
                        }
                    }

                    if (batch.completedLighting
                            == batch.entries.size()) {

                        batch.stage = BatchStage.SYNCHRONIZING;

                        batch.feedback.accept(
                                "Lighting repaired for all "
                                        + batch.entries.size()
                                        + " chunks; synchronizing light data."
                        );
                    }
                }

                case SYNCHRONIZING -> {

                    /*
                     * Only lighting data needs resending.
                     * Clients already received the terrain.
                     */
                    synchronizeBatchLighting(batch);

                    batch.feedback.accept(
                            "Batch rollback completed: "
                                    + batch.entries.size()
                                    + " chunks."
                    );

                    activeBatch = null;
                }
            }

        } catch (Throwable exception) {

            Sentinel.LOGGER.error(
                    "Batch rollback failed at stage {}",
                    batch.stage,
                    exception
            );

            batch.feedback.accept(
                    "Batch rollback FAILED at "
                            + batch.stage
                            + ": "
                            + exception.getMessage()
                            + ". Already-swapped chunks may require recovery."
            );

            activeBatch = null;
        }
    }

    /**
     * Preflight one chunk's holder before beginning
     * the coordinated commit.
     */
    private static void validateSwapTarget(
            Transaction transaction
    ) {

        ServerChunkCache cache =
                transaction.level.getChunkSource();

        ChunkMapSwapAccessor map =
                (ChunkMapSwapAccessor) (Object) cache.chunkMap;

        long key = transaction.position.toLong();

        ChunkHolder visible =
                map.sentinel$getVisibleChunkMap().get(key);

        ChunkHolder updating =
                map.sentinel$getUpdatingChunkMap().get(key);

        if (visible == null
                || updating == null
                || visible != updating
                || cache.getChunkNow(
                transaction.position.x,
                transaction.position.z
        ) == null) {

            throw new IllegalStateException(
                    "Chunk is not fully loaded: "
                            + transaction.position
            );
        }

        if (transaction.replacement == null) {
            throw new IllegalStateException(
                    "Replacement not prepared: "
                            + transaction.position
            );
        }
    }

    private static void finishReading(Transaction transaction) {

        if (!transaction.reading.isDone()) {
            return;
        }

        transaction.records = transaction.reading.join();

        if (transaction.records == null
                || transaction.records.terrain() == null) {

            throw new IllegalStateException(
                    "Snapshot has no terrain record."
            );
        }

        transaction.stage = Stage.PREPARING;

        transaction.feedback.accept(
                "Snapshot loaded; preparing replacement chunk."
        );
    }

    /**
     * Construct the replacement without modifying
     * the live chunk holder.
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

        if (!proto.getPos().equals(transaction.position)) {
            throw new IllegalStateException(
                    "Snapshot coordinates differ from requested chunk."
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
     * Attach snapshot block-entity NBT to the
     * replacement before installation.
     */
    private static void loadBlockEntityNbt(
            Transaction transaction
    ) {

        ListTag saved =
                transaction.records.terrain().getList(
                        "block_entities",
                        Tag.TAG_COMPOUND
                );

        Set<BlockPos> seen = new HashSet<>();

        int copied = 0;
        int rejected = 0;

        for (int index = 0; index < saved.size(); index++) {

            CompoundTag tag = saved.getCompound(index).copy();

            if (!validBlockEntityCoordinates(tag)) {
                rejected++;
                continue;
            }

            BlockPos position = blockEntityPosition(tag);

            if (!new ChunkPos(position).equals(transaction.position)
                    || !seen.add(position)) {

                rejected++;
                continue;
            }

            transaction.replacement.setBlockEntityNbt(tag);
            copied++;
        }

        Sentinel.LOGGER.info(
                "Prepared block-entity NBT: chunk={}, copied={}, rejected={}",
                transaction.position,
                copied,
                rejected
        );
    }

    private static boolean validBlockEntityCoordinates(
            CompoundTag tag
    ) {
        return tag.contains("x", Tag.TAG_INT)
                && tag.contains("y", Tag.TAG_INT)
                && tag.contains("z", Tag.TAG_INT);
    }

    private static BlockPos blockEntityPosition(
            CompoundTag tag
    ) {
        return new BlockPos(
                tag.getInt("x"),
                tag.getInt("y"),
                tag.getInt("z")
        );
    }

    /**
     * Materialize the replacement chunk's block
     * entities and register their tickers.
     */
    private static void materializeBlockEntities(
            Transaction transaction
    ) {

        LevelChunk replacement = transaction.replacement;

        ListTag saved =
                transaction.records.terrain().getList(
                        "block_entities",
                        Tag.TAG_COMPOUND
                );

        Set<BlockPos> seen = new HashSet<>();

        int restored = 0;
        int failed = 0;

        for (int index = 0; index < saved.size(); index++) {

            CompoundTag tag = saved.getCompound(index);

            if (!validBlockEntityCoordinates(tag)) {
                failed++;
                continue;
            }

            BlockPos position = blockEntityPosition(tag);

            if (!new ChunkPos(position).equals(transaction.position)) {
                failed++;
                continue;
            }

            if (!seen.add(position)) {
                continue;
            }

            BlockEntity entity = replacement.getBlockEntity(
                    position,
                    LevelChunk.EntityCreationType.IMMEDIATE
            );

            if (entity == null) {

                Sentinel.LOGGER.warn(
                        "Unable to materialize block entity at {}",
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
                "Restored block entities: chunk={}, restored={}, failed={}",
                transaction.position,
                restored,
                failed
        );
    }

    /**
     * Replace the authoritative LevelChunk while
     * keeping the existing ChunkHolder object.
     */
    private static void swap(Transaction transaction) {

        ServerLevel level = transaction.level;
        ServerChunkCache cache = level.getChunkSource();

        ChunkMapSwapAccessor chunkMap =
                (ChunkMapSwapAccessor) (Object) cache.chunkMap;

        long key = transaction.position.toLong();

        ChunkHolder visible =
                chunkMap.sentinel$getVisibleChunkMap().get(key);

        ChunkHolder updating =
                chunkMap.sentinel$getUpdatingChunkMap().get(key);

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
                (ChunkHolderSwapAccessor) (Object) visible;

        GenerationChunkHolderSwapAccessor generation =
                (GenerationChunkHolderSwapAccessor) (Object) visible;

        LevelChunk original = cache.getChunkNow(
                transaction.position.x,
                transaction.position.z
        );

        if (original == null) {
            throw new IllegalStateException(
                    "Target chunk is not fully loaded."
            );
        }

        LevelChunk replacement = transaction.replacement;
        transaction.original = original;

        replacement.setFullStatus(original::getFullStatus);

        CompletableFuture<ChunkResult<LevelChunk>> resolved =
                CompletableFuture.completedFuture(
                        ChunkResult.of(replacement)
                );

        CompletableFuture<ChunkResult<ChunkAccess>> generationResolved =
                CompletableFuture.completedFuture(
                        ChunkResult.of((ChunkAccess) replacement)
                );

        int fullIndex = ChunkStatus.FULL.getIndex();

        var generationFutures =
                generation.sentinel$getGenerationFutures();

        var oldGenerationFuture = generationFutures.get(fullIndex);
        var oldFullFuture = holder.sentinel$getFullChunkFuture();
        var oldTickingFuture = holder.sentinel$getTickingChunkFuture();
        var oldEntityTickingFuture =
                holder.sentinel$getEntityTickingChunkFuture();

        generationFutures.set(fullIndex, generationResolved);

        holder.sentinel$setFullChunkFuture(resolved);
        holder.sentinel$setTickingChunkFuture(resolved);
        holder.sentinel$setEntityTickingChunkFuture(resolved);

        ((ServerChunkCacheSwapAccessor) (Object) cache)
                .sentinel$clearCache();

        LevelChunk cached = cache.getChunkNow(
                transaction.position.x,
                transaction.position.z
        );

        LevelChunk authoritative = level.getChunk(
                transaction.position.x,
                transaction.position.z
        );

        if (cached != replacement || authoritative != replacement) {

            generationFutures.set(fullIndex, oldGenerationFuture);

            holder.sentinel$setFullChunkFuture(oldFullFuture);
            holder.sentinel$setTickingChunkFuture(oldTickingFuture);
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
                "Chunk object replaced: chunk={}, original={}, replacement={}",
                transaction.position,
                System.identityHashCode(original),
                System.identityHashCode(replacement)
        );

        transaction.stage = Stage.RESTORING_ENTITIES;

        transaction.feedback.accept(
                "Terrain and block entities restored; restoring entities."
        );
    }

    private static void restoreEntities(
            Transaction transaction
    ) {

        transaction.entityResult = ChunkEntityRestorer.restore(
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
     * Restore POI records and create the lighting
     * restorer for this chunk.
     *
     * The lighting restorer isn't advanced here.
     */
    private static void restorePoi(
            Transaction transaction
    ) {

        transaction.poiResult = ChunkPoiRestorer.restore(
                transaction.level,
                transaction.position,
                transaction.records.poi()
        );

        transaction.lightingRestorer = new ChunkLightingRestorer(
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

    /**
     * PHASE 1 CLIENT SYNCHRONISATION.
     *
     * All selected chunks have physically been
     * restored when this method is called.
     *
     * Send complete replacement terrain immediately,
     * even though lighting may still be incorrect.
     */
    private static void synchronizeBatchTerrain(
            BatchTransaction batch
    ) {

        ServerLevel level = batch.level;

        int viewDistance = level.getServer()
                .getPlayerList()
                .getViewDistance();

        int transmissions = 0;

        for (ServerPlayer player : level.players()) {

            ChunkPos playerChunk = player.chunkPosition();

            for (Transaction entry : batch.entries) {

                ChunkPos target = entry.position;

                if (Math.abs((long) playerChunk.x - target.x)
                        > viewDistance) {
                    continue;
                }

                if (Math.abs((long) playerChunk.z - target.z)
                        > viewDistance) {
                    continue;
                }

                LevelChunk authoritative =
                        level.getChunkSource().getChunkNow(
                                target.x,
                                target.z
                        );

                if (authoritative != entry.replacement) {
                    throw new IllegalStateException(
                            "Batch replacement is not authoritative: "
                                    + target
                    );
                }

                player.connection.send(
                        new ClientboundForgetLevelChunkPacket(target)
                );

                player.connection.send(
                        new ClientboundLevelChunkWithLightPacket(
                                entry.replacement,
                                level.getLightEngine(),
                                null,
                                null
                        )
                );

                transmissions++;
            }
        }

        Sentinel.LOGGER.info(
                "Physical terrain synchronized: chunks={}, transmissions={}",
                batch.entries.size(),
                transmissions
        );
    }

    /**
     * PHASE 2 CLIENT SYNCHRONISATION.
     *
     * Terrain has already been sent.
     *
     * Only send corrected lighting data.
     * Do not forget and reload the client chunks.
     */
    private static void synchronizeBatchLighting(
            BatchTransaction batch
    ) {

        ServerLevel level = batch.level;

        int viewDistance = level.getServer()
                .getPlayerList()
                .getViewDistance();

        int transmissions = 0;

        for (ServerPlayer player : level.players()) {

            ChunkPos playerChunk = player.chunkPosition();

            for (Transaction entry : batch.entries) {

                ChunkPos target = entry.position;

                if (Math.abs((long) playerChunk.x - target.x)
                        > viewDistance) {
                    continue;
                }

                if (Math.abs((long) playerChunk.z - target.z)
                        > viewDistance) {
                    continue;
                }

                player.connection.send(
                        new ClientboundLightUpdatePacket(
                                target,
                                level.getLightEngine(),
                                null,
                                null
                        )
                );

                transmissions++;
            }
        }

        Sentinel.LOGGER.info(
                "Lighting synchronized: chunks={}, transmissions={}",
                batch.entries.size(),
                transmissions
        );
    }

    /**
     * Existing single-chunk finalisation.
     *
     * This path intentionally continues sending
     * the complete chunk after its lighting finishes.
     */
    private static void synchronize(
            Transaction transaction
    ) {

        ServerLevel level = transaction.level;
        ServerChunkCache cache = level.getChunkSource();

        LevelChunk replacement = transaction.replacement;

        LevelChunk cached = cache.getChunkNow(
                transaction.position.x,
                transaction.position.z
        );

        LevelChunk authoritative = level.getChunk(
                transaction.position.x,
                transaction.position.z
        );

        if (cached != replacement || authoritative != replacement) {
            throw new IllegalStateException(
                    "Replacement chunk is no longer authoritative."
            );
        }

        int viewDistance = level.getServer()
                .getPlayerList()
                .getViewDistance();

        for (ServerPlayer player : level.players()) {

            ChunkPos playerChunk = player.chunkPosition();

            if (Math.abs(
                    (long) playerChunk.x - transaction.position.x
            ) > viewDistance) {
                continue;
            }

            if (Math.abs(
                    (long) playerChunk.z - transaction.position.z
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
                "Chunk rollback completed: dimension={}, chunk={}, "
                        + "blockEntities={}, entities={}, poiRecords={}, "
                        + "previousPoiRecords={}, lightChecks={}",
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

        if (active != null && active.reading != null) {
            active.reading.cancel(true);
        }

        active = null;

        if (activeBatch != null && activeBatch.reading != null) {
            activeBatch.reading.cancel(true);
        }

        activeBatch = null;
    }
}