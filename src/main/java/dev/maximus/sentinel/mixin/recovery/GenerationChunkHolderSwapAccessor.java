package dev.maximus.sentinel.mixin.recovery;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;

import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderSwapAccessor {

    /**
     * Minecraft stores chunk-generation futures here,
     * indexed by ChunkStatus.getIndex().
     *
     * The FULL entry must be replaced alongside the
     * ChunkHolder's accessibility/ticking futures.
     */
    @Accessor("futures")
    AtomicReferenceArray<
            CompletableFuture<ChunkResult<ChunkAccess>>
            > sentinel$getGenerationFutures();
}