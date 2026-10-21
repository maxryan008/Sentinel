package dev.maximus.sentinel.recovery.entity;

import dev.maximus.sentinel.Sentinel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SentinelEntityGenerationLedger {

    private static final int FORMAT_VERSION = 1;

    private static final Map<
            MinecraftServer,
            SentinelEntityGenerationLedger
            > INSTANCES =
            Collections.synchronizedMap(
                    new WeakHashMap<>()
            );

    private final Path file;

    /**
     * Immutable published generation map.
     *
     * The entity manager normally uses the server thread,
     * but immutable publication also makes lookups safe
     * if a mod invokes serialization from another thread.
     */
    private volatile Map<UUID, UUID> generations =
            Map.of();

    private SentinelEntityGenerationLedger(
            MinecraftServer server
    ) {

        Path world = server.getWorldPath(
                LevelResource.ROOT
        );

        this.file = world
                .resolve("data")
                .resolve("sentinel-entity-generations.dat");

        load();
    }

    public static SentinelEntityGenerationLedger get(
            MinecraftServer server
    ) {

        synchronized (INSTANCES) {

            return INSTANCES.computeIfAbsent(
                    server,
                    SentinelEntityGenerationLedger::new
            );
        }
    }

    /**
     * Returns the currently authoritative generation
     * for an entity UUID.
     *
     * Null means Sentinel has never recorded a rollback
     * generation for this UUID.
     */
    @Nullable
    public UUID generationFor(UUID entityUuid) {

        return generations.get(entityUuid);
    }

    /**
     * Returns true if this entity is allowed to exist.
     *
     * Ordinary entities are unaffected.
     *
     * A Sentinel-tracked UUID must carry the currently
     * authoritative generation.
     */
    public boolean accepts(
            UUID entityUuid,
            @Nullable UUID entityGeneration
    ) {

        UUID expected = generations.get(entityUuid);

        return expected == null
                || expected.equals(entityGeneration);
    }

    /**
     * Assigns new authoritative generations to a
     * collection of entities about to be restored.
     *
     * This method persists all generations together,
     * then publishes them to the live lookup map.
     *
     * If persistence fails, it throws before the
     * caller should remove or spawn any entities.
     */
    public synchronized Map<UUID, UUID> claimAll(
            Set<UUID> entityUuids
    ) {

        if (entityUuids.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UUID> next =
                new HashMap<>(generations);

        Map<UUID, UUID> claims =
                new HashMap<>();

        for (UUID entityUuid : entityUuids) {

            UUID newGeneration =
                    UUID.randomUUID();

            next.put(
                    entityUuid,
                    newGeneration
            );

            claims.put(
                    entityUuid,
                    newGeneration
            );
        }

        /*
         * Durably write before making the new
         * generations visible to entity loading.
         */
        write(next);

        generations = Map.copyOf(next);

        Sentinel.LOGGER.info(
                "Committed {} entity restoration generations; "
                        + "ledger now tracks {} UUIDs",
                claims.size(),
                generations.size()
        );

        return Map.copyOf(claims);
    }

    private void load() {

        if (!Files.exists(file)) {

            generations = Map.of();
            return;
        }

        try {

            CompoundTag root =
                    NbtIo.readCompressed(
                            file,
                            NbtAccounter.unlimitedHeap()
                    );

            int version =
                    root.getInt("FormatVersion");

            if (version != FORMAT_VERSION) {

                throw new IOException(
                        "Unsupported Sentinel entity ledger "
                                + "version: " + version
                );
            }

            ListTag entries =
                    root.getList(
                            "Entries",
                            Tag.TAG_COMPOUND
                    );

            Map<UUID, UUID> loaded =
                    new HashMap<>();

            for (int index = 0;
                 index < entries.size();
                 index++) {

                CompoundTag entry =
                        entries.getCompound(index);

                if (!entry.hasUUID("Entity")
                        || !entry.hasUUID("Generation")) {

                    throw new IOException(
                            "Malformed entity generation entry "
                                    + index
                    );
                }

                UUID entityUuid =
                        entry.getUUID("Entity");

                UUID generation =
                        entry.getUUID("Generation");

                if (loaded.put(
                        entityUuid,
                        generation
                ) != null) {

                    throw new IOException(
                            "Duplicate entity UUID in ledger: "
                                    + entityUuid
                    );
                }
            }

            generations = Map.copyOf(loaded);

            Sentinel.LOGGER.info(
                    "Loaded {} persistent entity generations",
                    generations.size()
            );

        } catch (Exception exception) {

            /*
             * Do not silently treat a corrupt ledger
             * as empty.
             *
             * Doing that could allow old copies of
             * restored entities to load again.
             */
            throw new IllegalStateException(
                    "Unable to read Sentinel entity ledger: "
                            + file,
                    exception
            );
        }
    }

    private void write(
            Map<UUID, UUID> next
    ) {

        Path temporary =
                file.resolveSibling(
                        file.getFileName() + ".tmp"
                );

        try {

            Files.createDirectories(
                    file.getParent()
            );

            CompoundTag root =
                    new CompoundTag();

            root.putInt(
                    "FormatVersion",
                    FORMAT_VERSION
            );

            ListTag entries =
                    new ListTag();

            for (Map.Entry<UUID, UUID> entry :
                    next.entrySet()) {

                CompoundTag saved =
                        new CompoundTag();

                saved.putUUID(
                        "Entity",
                        entry.getKey()
                );

                saved.putUUID(
                        "Generation",
                        entry.getValue()
                );

                entries.add(saved);
            }

            root.put(
                    "Entries",
                    entries
            );

            NbtIo.writeCompressed(
                    root,
                    temporary
            );

            /*
             * Prefer an atomic replacement.
             *
             * Some filesystems do not support
             * atomic moves, so retain a fallback.
             */
            try {

                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );

            } catch (AtomicMoveNotSupportedException ignored) {

                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException exception) {

            throw new IllegalStateException(
                    "Unable to persist entity generations "
                            + "to " + file,
                    exception
            );

        } finally {

            try {

                Files.deleteIfExists(temporary);

            } catch (IOException ignored) {
            }
        }
    }
}