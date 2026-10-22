package dev.maximus.sentinel.gui;

import dev.maximus.sentinel.Sentinel;

import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SnapshotRegionIndex {

    /*
     * 2,048 cached region headers, including missing ones.
     *
     * Each region represents 1,024 chunks.
     * The BitSet payload itself is only 128 bytes.
     */
    private static final int MAX_REGIONS = 2048;

    private static final Object LOCK = new Object();

    private static final LinkedHashMap<Key, BitSet> CACHE =
            new LinkedHashMap<>(256, 0.75f, true);

    private SnapshotRegionIndex() {
    }

    private record Key(
            Path regionDirectory,
            int regionX,
            int regionZ
    ) {
    }

    /**
     * Returns whether the snapshot has a terrain
     * record for this chunk.
     *
     * Cache misses read the Anvil location table.
     */
    public static boolean available(
            Path regionDirectory,
            int chunkX,
            int chunkZ
    ) {

        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);

        Path directory =
                regionDirectory.toAbsolutePath().normalize();

        Key key = new Key(
                directory,
                regionX,
                regionZ
        );

        BitSet bits;

        synchronized (LOCK) {

            bits = CACHE.get(key);

            if (bits == null) {

                bits = readHeader(
                        directory,
                        regionX,
                        regionZ
                );

                CACHE.put(key, bits);

                if (CACHE.size() > MAX_REGIONS) {

                    Key eldest =
                            CACHE.keySet().iterator().next();

                    CACHE.remove(eldest);
                }
            }
        }

        int localX = Math.floorMod(chunkX, 32);
        int localZ = Math.floorMod(chunkZ, 32);

        return bits.get(localZ * 32 + localX);
    }

    /**
     * Invalidates cached regions belonging to
     * a snapshot dimension.
     *
     * Call when a snapshot is deleted or replaced.
     */
    public static void invalidate(
            Path regionDirectory
    ) {

        Path directory =
                regionDirectory.toAbsolutePath().normalize();

        synchronized (LOCK) {
            CACHE.keySet().removeIf(
                    key -> key.regionDirectory()
                            .equals(directory)
            );
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }

    public static int cachedRegionCount() {
        synchronized (LOCK) {
            return CACHE.size();
        }
    }

    private static BitSet readHeader(
            Path directory,
            int regionX,
            int regionZ
    ) {

        BitSet bits = new BitSet(1024);

        Path file = directory.resolve(
                "r." + regionX + "." + regionZ + ".mca"
        );

        if (!Files.isRegularFile(file)
                || Files.isSymbolicLink(file)) {
            return bits;
        }

        try (RandomAccessFile input =
                     new RandomAccessFile(file.toFile(), "r")) {

            long length = input.length();

            if (length < 8192) {
                return bits;
            }

            for (int i = 0; i < 1024; i++) {

                int location = input.readInt();

                int offset = (location >>> 8) & 0xFFFFFF;
                int sectors = location & 0xFF;

                if (offset >= 2
                        && sectors > 0
                        && (long) offset * 4096L < length) {

                    bits.set(i);
                }
            }

        } catch (IOException exception) {

            /*
             * Don't make an I/O failure indistinguishable
             * from a region that legitimately doesn't exist.
             */
            Sentinel.LOGGER.error(
                    "Unable to read snapshot region {}",
                    file,
                    exception
            );

            throw new IllegalStateException(
                    "Unable to index snapshot region " + file,
                    exception
            );
        }

        return bits;
    }

    /**
     * Obtain the complete 1024-bit availability map
     * for one Minecraft region.
     *
     * Returns a defensive copy so callers cannot
     * mutate the authoritative cache.
     */
    public static BitSet regionAvailability(
            Path regionDirectory,
            int regionX,
            int regionZ
    ) {

        Path directory =
                regionDirectory.toAbsolutePath().normalize();

        Key key = new Key(
                directory,
                regionX,
                regionZ
        );

        synchronized (LOCK) {

            BitSet bits = CACHE.get(key);

            if (bits == null) {

                bits = readHeader(
                        directory,
                        regionX,
                        regionZ
                );

                CACHE.put(key, bits);

                if (CACHE.size() > MAX_REGIONS) {

                    Key eldest =
                            CACHE.keySet().iterator().next();

                    CACHE.remove(eldest);
                }
            }

            return (BitSet) bits.clone();
        }
    }
}