package dev.maximus.sentinel.mixin.recovery;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.LightEngine;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(LightEngine.class)
public abstract class LightEngineCacheMixin {

    @Shadow
    @Final
    private long[] lastChunkPos;

    @Shadow
    @Final
    private LightChunk[] lastChunk;

    /**
     * Clear cached chunk references before lighting updates.
     *
     * Sentinel replaces the LevelChunk object in memory,
     * but Minecraft's lighting engine can retain references
     * to the previous chunk.
     *
     * This ensures lighting calculations use the current
     * authoritative LevelChunk.
     */
    @Inject(
            method = "runLightUpdates",
            at = @At("HEAD")
    )
    private void sentinel$clearCachedChunks(
            CallbackInfoReturnable<Integer> cir
    ) {
        Arrays.fill(
                lastChunkPos,
                ChunkPos.INVALID_CHUNK_POS
        );

        Arrays.fill(
                lastChunk,
                null
        );
    }
}