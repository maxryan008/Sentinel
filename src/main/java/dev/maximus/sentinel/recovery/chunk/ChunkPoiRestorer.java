package dev.maximus.sentinel.recovery.chunk;

import com.mojang.serialization.DataResult;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.mixin.recovery.PoiManagerAccessor;
import dev.maximus.sentinel.mixin.recovery.SectionStorageAccessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import net.minecraft.SharedConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import net.minecraft.resources.RegistryOps;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ChunkPoiRestorer {

    private ChunkPoiRestorer() {
    }

    public record Result(
            int restoredSections,
            int clearedSections,
            int restoredRecords,
            int previousRecords
    ) {
    }

    /**
     * Restores one chunk column of POI data.
     *
     * This must run on the Minecraft server thread.
     *
     * The snapshot is decoded and validated before
     * the active SectionStorage cache is modified.
     */
    public static Result restore(
            ServerLevel level,
            ChunkPos position,
            CompoundTag snapshotPoi
    ) {

        MinecraftServer server = level.getServer();

        if (server == null || !server.isSameThread()) {

            throw new IllegalStateException(
                    "POI restoration must run on "
                            + "the Minecraft server thread."
            );
        }

        PoiManager manager = level.getPoiManager();

        PoiManagerAccessor managerAccessor =
                (PoiManagerAccessor) (Object) manager;

        SectionStorageAccessor storageAccessor =
                (SectionStorageAccessor) (Object) manager;

        /*
         * Decode all saved POI sections before
         * changing Minecraft's active cache.
         */
        Prepared prepared = prepare(
                level,
                position,
                snapshotPoi,
                managerAccessor
        );

        Long2ObjectMap<Optional<?>> storage =
                storageAccessor.sentinel$getStorage();

        int previousRecords = 0;

        /*
         * Inspect the currently active POI state.
         *
         * getInChunk() loads the chunk's POI column
         * if it has not already been loaded.
         */
        List<PoiRecord> currentRecords =
                manager.getInChunk(
                        holder -> true,
                        position,
                        PoiManager.Occupancy.ANY
                ).toList();

        previousRecords = currentRecords.size();

        /*
         * Preserve the existing map entries so we
         * can restore them if the installation of
         * the new POI sections throws.
         *
         * This is a limited in-memory rollback,
         * not a full transaction across disk writes.
         */
        Map<Long, Optional<?>> previous =
                new HashMap<>();

        for (long key : prepared.keys()) {

            previous.put(
                    key,
                    storage.get(key)
            );
        }

        int installed = 0;

        try {

            /*
             * Install the complete replacement column.
             *
             * Never merge old and new records:
             * the snapshot is authoritative.
             */
            for (long key : prepared.keys()) {

                PoiSection section =
                        prepared.sections().get(key);

                storage.put(
                        key,
                        Optional.of(section)
                );

                installed++;
            }

            /*
             * All new sections are now installed.
             *
             * Calling PoiManager.setDirty() updates
             * the section save queue and the village
             * distance tracker.
             */
            for (long key : prepared.keys()) {

                managerAccessor
                        .sentinel$markSectionDirty(key);
            }

        } catch (RuntimeException exception) {

            /*
             * Restore prior cache entries if
             * installation fails.
             */
            for (long key : prepared.keys()) {

                Optional<?> old =
                        previous.get(key);

                if (old == null) {
                    storage.remove(key);
                } else {
                    storage.put(key, old);
                }

                if (old != null && old.isPresent()) {

                    managerAccessor
                            .sentinel$markSectionDirty(key);
                }
            }

            throw new IllegalStateException(
                    "Failed to install POI snapshot "
                            + "for chunk " + position,
                    exception
            );
        }

        /*
         * Verify that POI queries now use the
         * replacement sections.
         */
        long visibleRecords =
                manager.getInChunk(
                        holder -> true,
                        position,
                        PoiManager.Occupancy.ANY
                ).count();

        if (visibleRecords != prepared.recordCount()) {

            throw new IllegalStateException(
                    "POI restoration verification failed: "
                            + "expected "
                            + prepared.recordCount()
                            + " records, found "
                            + visibleRecords
            );
        }

        Result result = new Result(
                prepared.restoredSectionCount(),
                prepared.emptySectionCount(),
                prepared.recordCount(),
                previousRecords
        );

        Sentinel.LOGGER.info(
                "POI rollback completed: "
                        + "dimension={}, chunk={}, "
                        + "sections={}, emptySections={}, "
                        + "previousRecords={}, restoredRecords={}",
                level.dimension().location(),
                position,
                result.restoredSections(),
                result.clearedSections(),
                result.previousRecords(),
                result.restoredRecords()
        );

        return result;
    }

    /**
     * Fully prepared replacement POI column.
     */
    private record Prepared(
            List<Long> keys,
            Map<Long, PoiSection> sections,
            int restoredSectionCount,
            int emptySectionCount,
            int recordCount
    ) {
    }

    /**
     * Decode the snapshot into detached POI sections.
     *
     * The codecs preserve:
     *
     * - POI positions
     * - Registry-backed POI types
     * - free_tickets occupancy state
     * - Valid section state
     */
    private static Prepared prepare(
            ServerLevel level,
            ChunkPos position,
            CompoundTag snapshotPoi,
            PoiManagerAccessor accessor
    ) {

        /*
         * A null record means the snapshot did not
         * contain a POI column.
         *
         * Treat it as an empty column.
         */
        CompoundTag sectionsTag =
                new CompoundTag();

        if (snapshotPoi != null) {

            /*
             * The codec below expects the current
             * Minecraft data format.
             *
             * Older snapshots require the POI
             * DataFixer before decoding.
             */
            int currentVersion =
                    SharedConstants.getCurrentVersion()
                            .getDataVersion()
                            .getVersion();

            int savedVersion =
                    snapshotPoi.getInt("DataVersion");

            if (savedVersion != currentVersion) {

                throw new IllegalArgumentException(
                        "POI snapshot DataVersion "
                                + savedVersion
                                + " differs from current version "
                                + currentVersion
                                + ". DataFixer conversion is required."
                );
            }

            if (!snapshotPoi.contains(
                    "Sections",
                    Tag.TAG_COMPOUND
            )) {

                throw new IllegalArgumentException(
                        "POI snapshot has no Sections compound."
                );
            }

            sectionsTag =
                    snapshotPoi.getCompound("Sections");
        }

        RegistryOps<Tag> registryOps =
                level.registryAccess()
                        .createSerializationContext(
                                NbtOps.INSTANCE
                        );

        int minSection = level.getMinSection();
        int maxSection = level.getMaxSection();

        /*
         * Validate section keys.
         *
         * A record for another height range must
         * not be silently installed.
         */
        for (String sectionName :
                sectionsTag.getAllKeys()) {

            int sectionY;

            try {

                sectionY =
                        Integer.parseInt(sectionName);

            } catch (NumberFormatException exception) {

                throw new IllegalArgumentException(
                        "Invalid POI section key: "
                                + sectionName,
                        exception
                );
            }

            if (sectionY < minSection
                    || sectionY >= maxSection) {

                throw new IllegalArgumentException(
                        "POI section "
                                + sectionY
                                + " is outside the dimension's "
                                + "stored section range."
                );
            }

            if (!sectionsTag.contains(
                    sectionName,
                    Tag.TAG_COMPOUND
            )) {

                throw new IllegalArgumentException(
                        "POI section "
                                + sectionName
                                + " is not a compound."
                );
            }
        }

        List<Long> keys = new ArrayList<>();

        Map<Long, PoiSection> sections =
                new HashMap<>();

        int populatedSections = 0;
        int emptySections = 0;
        int recordCount = 0;

        for (int sectionY = minSection;
             sectionY < maxSection;
             sectionY++) {

            long key = SectionPos.asLong(
                    position.x,
                    sectionY,
                    position.z
            );

            keys.add(key);

            PoiSection section;

            String sectionName =
                    Integer.toString(sectionY);

            if (sectionsTag.contains(
                    sectionName,
                    Tag.TAG_COMPOUND
            )) {

                CompoundTag sectionNbt =
                        sectionsTag.getCompound(
                                sectionName
                        );

                validateSectionNbt(
                        position,
                        sectionY,
                        sectionNbt
                );

                DataResult<PoiSection> parsed =
                        PoiSection.codec(
                                () -> accessor
                                        .sentinel$markSectionDirty(key)
                        ).parse(
                                registryOps,
                                sectionNbt
                        );

                final int expectedSectionY = sectionY;
                section = parsed.result().orElseThrow(
                        () -> new IllegalArgumentException(
                                "Unable to decode POI section "
                                        + expectedSectionY
                                        + " in chunk "
                                        + position
                        )
                );

                populatedSections++;

            } else {

                /*
                 * Missing snapshot section:
                 *
                 * Create an empty, valid PoiSection.
                 *
                 * This actively removes any POIs
                 * introduced after the backup.
                 */
                section = new PoiSection(
                        () -> accessor
                                .sentinel$markSectionDirty(key)
                );

                emptySections++;
            }

            /*
             * Validate decoded records against the
             * expected chunk and section coordinates.
             */
            List<PoiRecord> records =
                    section.getRecords(
                            holder -> true,
                            PoiManager.Occupancy.ANY
                    ).toList();

            for (PoiRecord record : records) {

                BlockPos recordPos =
                        record.getPos();

                if (!new ChunkPos(recordPos).equals(
                        position
                )) {

                    throw new IllegalArgumentException(
                            "POI record "
                                    + recordPos
                                    + " belongs to another chunk."
                    );
                }

                if (SectionPos.blockToSectionCoord(
                        recordPos.getY()
                ) != sectionY) {

                    throw new IllegalArgumentException(
                            "POI record "
                                    + recordPos
                                    + " belongs to another section."
                    );
                }

                PoiType type =
                        record.getPoiType().value();

                int freeTickets =
                        record.getFreeTickets();

                if (freeTickets < 0
                        || freeTickets > type.maxTickets()) {

                    throw new IllegalArgumentException(
                            "Invalid POI ticket count at "
                                    + recordPos
                                    + ": "
                                    + freeTickets
                    );
                }
            }

            recordCount += records.size();

            sections.put(
                    key,
                    section
            );
        }

        return new Prepared(
                keys,
                sections,
                populatedSections,
                emptySections,
                recordCount
        );
    }

    /**
     * Validate the serialized POI records before
     * passing them through Minecraft's codec.
     *
     * PoiSection.codec() contains a fallback for
     * malformed sections. Sentinel should reject
     * malformed snapshot data instead of silently
     * installing an empty fallback section.
     */
    private static void validateSectionNbt(
            ChunkPos position,
            int sectionY,
            CompoundTag sectionNbt
    ) {

        if (!sectionNbt.contains(
                "Records",
                Tag.TAG_LIST
        )) {

            throw new IllegalArgumentException(
                    "POI section "
                            + sectionY
                            + " has no Records list."
            );
        }

        var records =
                sectionNbt.getList(
                        "Records",
                        Tag.TAG_COMPOUND
                );

        Set<BlockPos> seen =
                new HashSet<>();

        for (int index = 0;
             index < records.size();
             index++) {

            CompoundTag record =
                    records.getCompound(index);

            if (!record.contains(
                    "pos",
                    Tag.TAG_INT_ARRAY
            )) {

                throw new IllegalArgumentException(
                        "Invalid POI position in section "
                                + sectionY
                );
            }

            int[] coordinates =
                    record.getIntArray("pos");

            if (coordinates.length != 3) {

                throw new IllegalArgumentException(
                        "POI position must contain "
                                + "exactly three coordinates."
                );
            }

            BlockPos blockPos = new BlockPos(
                    coordinates[0],
                    coordinates[1],
                    coordinates[2]
            );

            if (!new ChunkPos(blockPos).equals(
                    position
            )) {

                throw new IllegalArgumentException(
                        "POI "
                                + blockPos
                                + " belongs to another chunk."
                );
            }

            if (SectionPos.blockToSectionCoord(
                    blockPos.getY()
            ) != sectionY) {

                throw new IllegalArgumentException(
                        "POI "
                                + blockPos
                                + " belongs to another section."
                );
            }

            if (!seen.add(blockPos)) {

                throw new IllegalArgumentException(
                        "Duplicate POI position: "
                                + blockPos
                );
            }

            if (!record.contains(
                    "type",
                    Tag.TAG_STRING
            )) {

                throw new IllegalArgumentException(
                        "POI at "
                                + blockPos
                                + " has no type."
                );
            }
        }
    }
}