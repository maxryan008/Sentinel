package dev.maximus.sentinel.mixin.recovery;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheSwapAccessor {

    @Accessor("lastChunk")
    ChunkAccess[] sentinel$getLastChunk();

    @Accessor("lastChunkPos")
    long[] sentinel$getLastChunkPos();

    @Invoker("clearCache")
    void sentinel$clearCache();
}