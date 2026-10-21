package dev.maximus.sentinel.mixin.recovery;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
public interface RegionFileStorageInvoker {

    /**
     * Creates a RegionFileStorage instance.
     *
     * The constructor is package-private in Minecraft 1.21.1,
     * so Sentinel accesses it through a Mixin constructor invoker.
     *
     * @param storageInfo Region storage metadata.
     * @param directory   Directory containing .mca region files.
     * @param sync        Whether region writes should be synchronous.
     * @return A new RegionFileStorage instance.
     */
    @Invoker("<init>")
    static RegionFileStorage sentinel$create(
            RegionStorageInfo storageInfo,
            Path directory,
            boolean sync
    ) {
        throw new AssertionError(
                "RegionFileStorage constructor invoker was not applied."
        );
    }

    /**
     * Invokes RegionFileStorage's protected write method.
     *
     * A null CompoundTag removes the corresponding chunk record.
     *
     * @param position Target chunk coordinates.
     * @param data     Complete serialized chunk NBT, or null.
     */
    @Invoker("write")
    void sentinel$write(
            ChunkPos position,
            CompoundTag data
    ) throws IOException;
}