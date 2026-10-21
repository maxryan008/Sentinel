package dev.maximus.sentinel.recovery.entity;

import java.util.UUID;

/**
 * Implemented by Minecraft entities through a Mixin.
 *
 * A generation identifies the currently authoritative
 * incarnation of an entity UUID following a Sentinel
 * rollback.
 *
 * The generation is persisted in the entity's NBT.
 */
public interface SentinelEntityGeneration {

    String NBT_KEY = "SentinelRestorationGeneration";

    /**
     * Returns the generation saved with this entity,
     * or null for entities that have never been
     * restored by Sentinel.
     */
    UUID sentinel$getRestorationGeneration();

    /**
     * Assigns the generation to be saved with
     * this entity.
     */
    void sentinel$setRestorationGeneration(UUID generation);
}