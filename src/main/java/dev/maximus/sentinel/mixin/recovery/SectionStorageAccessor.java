package dev.maximus.sentinel.mixin.recovery;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import net.minecraft.world.level.chunk.storage.SectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {

    /**
     * Minecraft's authoritative in-memory
     * section storage.
     *
     * For PoiManager, the values are Optional<PoiSection>.
     *
     * A missing map entry means the section has not
     * been loaded. Optional.empty() means that the
     * section has been loaded and contains no data.
     */
    @Accessor("storage")
    Long2ObjectMap<Optional<?>> sentinel$getStorage();
}