package dev.maximus.sentinel.mixin.recovery;

import net.minecraft.world.entity.ai.village.poi.PoiManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PoiManager.class)
public interface PoiManagerAccessor {

    /**
     * Mark a POI section dirty.
     *
     * Minecraft's PoiManager implementation
     * also updates the village-distance tracker
     * when this method is called.
     */
    @Invoker("setDirty")
    void sentinel$markSectionDirty(long sectionKey);
}