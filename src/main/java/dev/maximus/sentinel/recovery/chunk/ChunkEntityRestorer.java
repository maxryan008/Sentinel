package dev.maximus.sentinel.recovery.chunk;

import dev.maximus.sentinel.Sentinel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChunkEntityRestorer {

    private ChunkEntityRestorer() {
    }

    public record Result(
            int removedFromTarget,
            int removedByUuid,
            int restored,
            int failed
    ) {
    }

    /**
     * Restores the snapshot's non-player entity population.
     *
     * Players are never removed, recreated, or moved.
     *
     * This method must execute on the server thread,
     * after the terrain chunk swap.
     */
    public static Result restore(
            ServerLevel targetLevel,
            ChunkPos targetChunk,
            CompoundTag entityRecord
    ) {

        MinecraftServer server = targetLevel.getServer();

        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "Entity restoration must run on the server thread."
            );
        }

        List<CompoundTag> savedTags =
                readSavedEntities(entityRecord);

        /*
         * Deserialize all replacement entities before
         * deleting anything from the active world.
         *
         * EntityType.loadEntitiesRecursive restores
         * passenger relationships from serialized NBT.
         */
        List<Tag> serialized = new ArrayList<>();

        for (CompoundTag tag : savedTags) {
            serialized.add(tag.copy());
        }

        List<Entity> replacements =
                EntityType.loadEntitiesRecursive(
                        serialized,
                        targetLevel
                ).toList();

        /*
         * Build the UUID set for all replacement entities,
         * including recursively loaded passengers.
         *
         * We must not restore a UUID twice.
         */
        Set<UUID> replacementUuids = new HashSet<>();

        for (Entity root : replacements) {

            for (Entity entity :
                    root.getSelfAndPassengers().toList()) {

                if (entity instanceof Player) {
                    throw new IllegalStateException(
                            "Snapshot entity record contains a player."
                    );
                }

                if (!replacementUuids.add(entity.getUUID())) {
                    throw new IllegalStateException(
                            "Duplicate UUID in snapshot entities: "
                                    + entity.getUUID()
                    );
                }
            }
        }

        /*
         * Locate active entities across every loaded
         * dimension.
         *
         * This supports entities that have moved into
         * another loaded chunk or dimension after
         * the backup was created.
         */
        Map<UUID, Entity> activeByUuid =
                new HashMap<>();

        List<Entity> activeInTarget =
                new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {

            for (Entity entity : level.getAllEntities()) {

                if (entity instanceof Player) {
                    continue;
                }

                if (replacementUuids.contains(
                        entity.getUUID()
                )) {

                    activeByUuid.put(
                            entity.getUUID(),
                            entity
                    );
                }

                if (level == targetLevel
                        && entity.chunkPosition()
                        .equals(targetChunk)) {

                    activeInTarget.add(entity);
                }
            }
        }

        /*
         * Never delete or overwrite a player whose UUID
         * conflicts with an entity in the snapshot.
         */
        for (var player :
                server.getPlayerList().getPlayers()) {

            if (replacementUuids.contains(
                    player.getUUID()
            )) {
                throw new IllegalStateException(
                        "Snapshot entity UUID conflicts with player "
                                + player.getUUID()
                );
            }
        }

        int removedFromTarget = 0;
        int removedByUuid = 0;
        int restored = 0;
        int failed = 0;

        /*
         * First remove non-player entities currently
         * occupying the restored chunk.
         *
         * Entities created after the snapshot should
         * not remain in the rolled-back chunk.
         */
        Set<Entity> removed = new HashSet<>();

        for (Entity entity : activeInTarget) {

            /*
             * Passengers and vehicles can cross chunk
             * boundaries. A complete vehicle hierarchy
             * must be considered before removal.
             *
             * Dismounting prevents a surviving vehicle
             * retaining the removed entity as a passenger.
             */
            entity.stopRiding();

            for (Entity passenger :
                    List.copyOf(entity.getPassengers())) {

                passenger.stopRiding();
            }

            entity.discard();

            removed.add(entity);

            removedFromTarget++;
        }

        /*
         * Now remove matching UUIDs elsewhere.
         *
         * Example:
         * A wolf existed in this chunk at backup time,
         * but has subsequently walked elsewhere.
         *
         * Its current instance must disappear before
         * its saved instance can be spawned.
         */
        for (Entity entity : activeByUuid.values()) {

            if (removed.contains(entity)) {
                continue;
            }

            entity.stopRiding();

            for (Entity passenger :
                    List.copyOf(entity.getPassengers())) {

                passenger.stopRiding();
            }

            entity.discard();

            removed.add(entity);

            removedByUuid++;
        }

        /*
         * Recreate the snapshot entities.
         *
         * Root entities are spawned with their passengers
         * so their saved riding relationships are preserved.
         */
        for (Entity root : replacements) {

            try {

                if (root instanceof Player) {
                    continue;
                }

                /*
                 * All top-level entities in an entity-region
                 * record should belong to the target chunk.
                 */
                if (!root.chunkPosition().equals(
                        targetChunk
                )) {

                    Sentinel.LOGGER.warn(
                            "Skipping snapshot entity {} "
                                    + "outside target chunk {}",
                            root.getUUID(),
                            targetChunk
                    );

                    failed++;
                    continue;
                }

                /*
                 * Check for a surviving loaded UUID conflict.
                 *
                 * This can occur if a mod introduces an entity
                 * between preparation and insertion.
                 */
                boolean conflict = false;

                for (Entity member :
                        root.getSelfAndPassengers().toList()) {

                    for (ServerLevel level :
                            server.getAllLevels()) {

                        if (level.getEntity(
                                member.getUUID()
                        ) != null) {

                            conflict = true;
                            break;
                        }
                    }

                    if (conflict) {
                        break;
                    }
                }

                if (conflict) {

                    Sentinel.LOGGER.warn(
                            "Skipping entity {} because a UUID "
                                    + "is still active.",
                            root.getUUID()
                    );

                    failed++;
                    continue;
                }

                targetLevel.addFreshEntityWithPassengers(root);

                restored += (int) root
                        .getSelfAndPassengers()
                        .count();

            } catch (Exception exception) {

                Sentinel.LOGGER.error(
                        "Failed to restore entity {}",
                        root.getUUID(),
                        exception
                );

                failed++;
            }
        }

        Sentinel.LOGGER.info(
                "Entity rollback in {} chunk {}: "
                        + "removedFromTarget={}, "
                        + "removedByUuid={}, "
                        + "restored={}, failed={}",
                targetLevel.dimension().location(),
                targetChunk,
                removedFromTarget,
                removedByUuid,
                restored,
                failed
        );

        return new Result(
                removedFromTarget,
                removedByUuid,
                restored,
                failed
        );
    }

    /**
     * Reads the vanilla entity-region NBT record.
     *
     * A missing entity record means the snapshot
     * contained no saved entities for this chunk.
     */
    private static List<CompoundTag> readSavedEntities(
            CompoundTag record
    ) {

        List<CompoundTag> result =
                new ArrayList<>();

        if (record == null) {
            return result;
        }

        if (!record.contains(
                "Entities",
                Tag.TAG_LIST
        )) {
            throw new IllegalArgumentException(
                    "Entity record has no Entities list."
            );
        }

        ListTag entities = record.getList(
                "Entities",
                Tag.TAG_COMPOUND
        );

        for (int index = 0;
             index < entities.size();
             index++) {

            CompoundTag tag =
                    entities.getCompound(index);

            if (!tag.hasUUID("UUID")) {

                throw new IllegalArgumentException(
                        "Snapshot entity at index "
                                + index
                                + " has no valid UUID."
                );
            }

            result.add(tag.copy());
        }

        return result;
    }
}