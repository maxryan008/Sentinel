package dev.maximus.sentinel.mixin.recovery;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public interface ChunkHolderSwapAccessor {

    @Accessor("fullChunkFuture")
    CompletableFuture<ChunkResult<LevelChunk>>
    sentinel$getFullChunkFuture();

    @Accessor("fullChunkFuture")
    void sentinel$setFullChunkFuture(
            CompletableFuture<ChunkResult<LevelChunk>> future
    );

    @Accessor("tickingChunkFuture")
    CompletableFuture<ChunkResult<LevelChunk>>
    sentinel$getTickingChunkFuture();

    @Accessor("tickingChunkFuture")
    void sentinel$setTickingChunkFuture(
            CompletableFuture<ChunkResult<LevelChunk>> future
    );

    @Accessor("entityTickingChunkFuture")
    CompletableFuture<ChunkResult<LevelChunk>>
    sentinel$getEntityTickingChunkFuture();

    @Accessor("entityTickingChunkFuture")
    void sentinel$setEntityTickingChunkFuture(
            CompletableFuture<ChunkResult<LevelChunk>> future
    );
}