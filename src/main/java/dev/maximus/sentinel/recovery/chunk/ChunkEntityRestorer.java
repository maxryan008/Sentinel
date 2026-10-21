package dev.maximus.sentinel.recovery.chunk;

import dev.maximus.sentinel.Sentinel;

import dev.maximus.sentinel.recovery.entity.SentinelEntityGeneration;
import dev.maximus.sentinel.recovery.entity.SentinelEntityGenerationLedger;

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
     * Restores the backed-up non-player entity
     * population for one chunk.
     *
     * Current matching UUIDs in loaded dimensions
     * are removed before their snapshot versions
     * are reconstructed.
     *
     * Every restored entity receives a persistent
     * generation ID that prevents older unloaded
     * copies of the same UUID from being admitted
     * into Minecraft's entity manager.
     *
     * Must run on the server thread.
     */
    public static Result restore(
            ServerLevel targetLevel,
            ChunkPos targetChunk,
            CompoundTag entityRecord
    ) {

        MinecraftServer server =
                targetLevel.getServer();

        if (server == null || !server.isSameThread()) {

            throw new IllegalStateException(
                    "Entity restoration must execute "
                            + "on the server thread."
            );
        }

        List<CompoundTag> savedTags =
                readSavedEntities(entityRecord);

        /*
         * Construct the replacement entities before
         * modifying the active world.
         */
        List<Tag> serialized =
                new ArrayList<>();

        for (CompoundTag tag : savedTags) {

            serialized.add(tag.copy());
        }

        List<Entity> replacements =
                EntityType.loadEntitiesRecursive(
                        serialized,
                        targetLevel
                ).toList();

        /*
         * Gather and validate the UUIDs of all
         * replacement entities and passengers.
         */
        Set<UUID> replacementUuids =
                new HashSet<>();

        for (Entity root : replacements) {

            if (!root.chunkPosition().equals(
                    targetChunk
            )) {

                throw new IllegalStateException(
                        "Snapshot contains entity "
                                + root.getUUID()
                                + " outside the target chunk "
                                + targetChunk
                );
            }

            for (Entity member :
                    root.getSelfAndPassengers().toList()) {

                if (member instanceof Player) {

                    throw new IllegalStateException(
                            "Entity snapshot unexpectedly "
                                    + "contains a player."
                    );
                }

                if (!replacementUuids.add(
                        member.getUUID()
                )) {

                    throw new IllegalStateException(
                            "Duplicate entity UUID in snapshot: "
                                    + member.getUUID()
                    );
                }
            }
        }

        /*
         * Never allow a saved non-player entity to
         * override a currently connected player's UUID.
         */
        for (var player :
                server.getPlayerList().getPlayers()) {

            if (replacementUuids.contains(
                    player.getUUID()
            )) {

                throw new IllegalStateException(
                        "Snapshot entity UUID conflicts "
                                + "with player "
                                + player.getUUID()
                );
            }
        }

        /*
         * Identify active copies across every
         * loaded dimension.
         */
        Map<UUID, Entity> activeByUuid =
                new HashMap<>();

        List<Entity> activeInTarget =
                new ArrayList<>();

        for (ServerLevel level :
                server.getAllLevels()) {

            for (Entity entity :
                    level.getAllEntities()) {

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
         * CRITICAL:
         *
         * Create and persist the new generations
         * before removing any live entities.
         *
         * The entity manager's duplicate-rejection
         * Mixin will subsequently consider only
         * these generations authoritative.
         */
        SentinelEntityGenerationLedger ledger =
                SentinelEntityGenerationLedger.get(server);

        Map<UUID, UUID> newGenerations =
                ledger.claimAll(replacementUuids);

        /*
         * Assign the generations to the newly
         * reconstructed entities.
         *
         * The EntityGenerationMixin will write
         * these values into the entities' NBT
         * when Minecraft saves them.
         */
        for (Entity root : replacements) {

            for (Entity member :
                    root.getSelfAndPassengers().toList()) {

                UUID generation =
                        newGenerations.get(
                                member.getUUID()
                        );

                if (generation == null) {

                    throw new IllegalStateException(
                            "Missing generation for "
                                    + member.getUUID()
                    );
                }

                ((SentinelEntityGeneration) member)
                        .sentinel$setRestorationGeneration(
                                generation
                        );
            }
        }

        int removedFromTarget = 0;
        int removedByUuid = 0;
        int restored = 0;
        int failed = 0;

        Set<Entity> removed =
                new HashSet<>();

        /*
         * Remove entities created or modified
         * inside the target chunk after the backup.
         *
         * Players are not affected.
         */
        for (Entity entity : activeInTarget) {

            entity.stopRiding();

            for (Entity passenger :
                    List.copyOf(
                            entity.getPassengers()
                    )) {

                passenger.stopRiding();
            }

            entity.discard();

            removed.add(entity);

            removedFromTarget++;
        }

        /*
         * Remove matching UUIDs that have moved into
         * other loaded chunks or dimensions.
         */
        for (Entity entity :
                activeByUuid.values()) {

            if (removed.contains(entity)) {
                continue;
            }

            entity.stopRiding();

            for (Entity passenger :
                    List.copyOf(
                            entity.getPassengers()
                    )) {

                passenger.stopRiding();
            }

            entity.discard();

            removed.add(entity);

            removedByUuid++;
        }

        /*
         * Spawn the restored entities.
         *
         * All members of each passenger hierarchy
         * have already received their authoritative
         * generation markers.
         */
        for (Entity root : replacements) {

            try {

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
                            "Skipping restored entity {} "
                                    + "because its UUID is "
                                    + "still active.",
                            root.getUUID()
                    );

                    failed++;
                    continue;
                }

                targetLevel.addFreshEntityWithPassengers(
                        root
                );

                int members =
                        (int) root
                                .getSelfAndPassengers()
                                .count();

                /*
                 * The number of returned entities
                 * is not a definitive admission count.
                 *
                 * Verify the entity manager accepted
                 * the root after insertion.
                 */
                Entity admitted =
                        targetLevel.getEntity(
                                root.getUUID()
                        );

                if (admitted != root) {

                    Sentinel.LOGGER.error(
                            "Restored entity {} was not "
                                    + "accepted by the entity manager.",
                            root.getUUID()
                    );

                    failed++;
                    continue;
                }

                restored += members;

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
                "Entity rollback: dimension={}, chunk={}, "
                        + "removedFromTarget={}, "
                        + "removedByUuid={}, "
                        + "restored={}, failed={}, "
                        + "persistentGenerations={}",
                targetLevel.dimension().location(),
                targetChunk,
                removedFromTarget,
                removedByUuid,
                restored,
                failed,
                newGenerations.size()
        );

        return new Result(
                removedFromTarget,
                removedByUuid,
                restored,
                failed
        );
    }

    /**
     * Reads the vanilla entity-region record.
     */
    private static List<CompoundTag> readSavedEntities(
            CompoundTag record
    ) {

        List<CompoundTag> result =
                new ArrayList<>();

        /*
         * A missing entity record means that no
         * entities were saved in this chunk.
         */
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

        ListTag entities =
                record.getList(
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