package dev.maximus.sentinel.recovery.chunk;

import dev.maximus.sentinel.mixin.recovery.RegionFileStorageInvoker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChunkSnapshotRecords {

    private ChunkSnapshotRecords() {
    }

    public record Records(
            CompoundTag terrain,
            CompoundTag entities,
            CompoundTag poi
    ) {
    }

    /**
     * Reads the complete saved NBT records for one chunk.
     */
    public static Records read(
            Path dimension,
            ChunkPos position,
            RegionStorageInfo info
    ) throws IOException {

        CompoundTag terrain = readOne(
                dimension.resolve("region"),
                position,
                info
        );

        if (terrain == null) {
            throw new IOException(
                    "Snapshot has no terrain record for " + position
            );
        }

        if (terrain.getInt("xPos") != position.x
                || terrain.getInt("zPos") != position.z) {

            throw new IOException(
                    "Snapshot terrain record has incorrect coordinates."
            );
        }

        if (!terrain.contains("sections")
                || !terrain.contains("Status")) {

            throw new IOException(
                    "Snapshot terrain record is incomplete."
            );
        }

        CompoundTag entities = readOne(
                dimension.resolve("entities"),
                position,
                info
        );

        if (entities != null) {

            int[] coordinates =
                    entities.getIntArray("Position");

            if (coordinates.length != 2
                    || coordinates[0] != position.x
                    || coordinates[1] != position.z) {

                throw new IOException(
                        "Snapshot entity record has incorrect coordinates."
                );
            }
        }

        CompoundTag poi = readOne(
                dimension.resolve("poi"),
                position,
                info
        );

        return new Records(
                terrain,
                entities,
                poi
        );
    }

    private static CompoundTag readOne(
            Path directory,
            ChunkPos position,
            RegionStorageInfo info
    ) throws IOException {

        if (!Files.isDirectory(directory)) {
            return null;
        }

        try (RegionFileStorage storage =
                     RegionFileStorageInvoker.sentinel$create(
                             info,
                             directory,
                             false
                     )) {

            CompoundTag tag = storage.read(position);

            return tag == null ? null : tag.copy();
        }
    }

    /**
     * Writes the complete saved chunk records.
     *
     * A null entity or POI record removes that chunk's
     * existing record from the corresponding region file.
     */
    public static void write(
            Path dimension,
            ChunkPos position,
            RegionStorageInfo info,
            Records records
    ) throws IOException {

        writeOne(
                dimension.resolve("region"),
                position,
                info,
                records.terrain()
        );

        writeOne(
                dimension.resolve("entities"),
                position,
                info,
                records.entities()
        );

        writeOne(
                dimension.resolve("poi"),
                position,
                info,
                records.poi()
        );
    }

    private static void writeOne(
            Path directory,
            ChunkPos position,
            RegionStorageInfo info,
            CompoundTag tag
    ) throws IOException {

        Files.createDirectories(directory);

        try (RegionFileStorage storage =
                     RegionFileStorageInvoker.sentinel$create(
                             info,
                             directory,
                             true
                     )) {

            /*
             * Mixin adds RegionFileStorageInvoker to
             * RegionFileStorage at runtime.
             *
             * The intermediate Object cast allows this
             * to compile against unmodified Minecraft.
             */
            RegionFileStorageInvoker invoker =
                    (RegionFileStorageInvoker) (Object) storage;

            invoker.sentinel$write(
                    position,
                    tag == null ? null : tag.copy()
            );

            storage.flush();
        }
    }
}