package dev.maximus.sentinel.mixin.recovery;

import dev.maximus.sentinel.recovery.entity.SentinelEntityGeneration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(Entity.class)
public abstract class EntityGenerationMixin
        implements SentinelEntityGeneration {

    @Unique
    @Nullable
    private UUID sentinel$restorationGeneration;

    @Override
    @Nullable
    public UUID sentinel$getRestorationGeneration() {

        return sentinel$restorationGeneration;
    }

    @Override
    public void sentinel$setRestorationGeneration(
            UUID generation
    ) {

        sentinel$restorationGeneration = generation;
    }

    /**
     * Minecraft calls saveWithoutId() while
     * serializing an entity's persistent NBT.
     *
     * The generation becomes part of the same
     * serialized entity record as its normal UUID.
     */
    @Inject(
            method = "saveWithoutId",
            at = @At("RETURN")
    )
    private void sentinel$writeGeneration(
            CompoundTag tag,
            CallbackInfoReturnable<CompoundTag> cir
    ) {

        if (sentinel$restorationGeneration == null) {
            return;
        }

        CompoundTag result = cir.getReturnValue();

        if (result != null) {

            result.putUUID(
                    NBT_KEY,
                    sentinel$restorationGeneration
            );
        }
    }

    /**
     * Recover the persisted generation when Minecraft
     * deserializes an entity.
     *
     * Old entity records have no generation, which is
     * how Sentinel recognises stale pre-rollback copies.
     */
    @Inject(
            method = "load",
            at = @At("TAIL")
    )
    private void sentinel$readGeneration(
            CompoundTag tag,
            CallbackInfo ci
    ) {

        if (tag.hasUUID(NBT_KEY)) {

            sentinel$restorationGeneration =
                    tag.getUUID(NBT_KEY);

        } else {

            sentinel$restorationGeneration = null;
        }
    }
}