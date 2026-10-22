package dev.maximus.sentinel.gui;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.config.SentinelConfig;
import dev.maximus.sentinel.recovery.WorldPathResolver;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChunkRegionServer {

    private static final ExecutorService INDEX_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {

                Thread thread = new Thread(
                        task,
                        "Sentinel-Chunk-Region-Requests"
                );

                thread.setDaemon(true);

                return thread;
            });

    private ChunkRegionServer() {
    }

    public static void register() {

        ServerPlayNetworking.registerGlobalReceiver(
                ChunkRegionPackets.Request.TYPE,
                (packet, context) -> {

                    ServerPlayer player = context.player();

                    if (!player.hasPermissions(4)) {
                        return;
                    }

                    try {

                        handleRequest(player, packet);

                    } catch (Exception exception) {

                        Sentinel.LOGGER.error(
                                "Chunk region request failed",
                                exception
                        );

                        player.sendSystemMessage(
                                Component.literal(
                                        "[Sentinel] "
                                                + exception.getMessage()
                                )
                        );
                    }
                }
        );
    }

    private static void handleRequest(
            ServerPlayer player,
            ChunkRegionPackets.Request request
    ) {

        if (request.regions().isEmpty()
                || request.regions().size()
                > ChunkRegionPackets.MAX_REGIONS_PER_REQUEST) {

            throw new IllegalArgumentException(
                    "Invalid region request size"
            );
        }

        String dimensionId =
                player.serverLevel()
                        .dimension()
                        .location()
                        .toString();

        if (!dimensionId.equals(request.dimension())) {

            throw new IllegalArgumentException(
                    "Dimension changed. Reopen the map."
            );
        }

        Path regionDirectory =
                resolveRegionDirectory(
                        player,
                        request.snapshot()
                );

        MinecraftServer server = player.getServer();

        if (server == null) {
            return;
        }

        UUID requester = player.getUUID();

        /*
         * Reject duplicate region coordinates.
         */
        Set<ChunkRegionPackets.RegionCoordinate> unique =
                new HashSet<>(request.regions());

        if (unique.size() != request.regions().size()) {

            throw new IllegalArgumentException(
                    "Duplicate region coordinates"
            );
        }

        /*
         * Do not pass mutable Minecraft state
         * to the indexing worker.
         */
        List<ChunkRegionPackets.RegionCoordinate> coordinates =
                List.copyOf(request.regions());

        String snapshot = request.snapshot();

        INDEX_EXECUTOR.execute(() -> {

            try {

                List<ChunkRegionPackets.RegionEntry> entries =
                        new ArrayList<>(coordinates.size());

                for (ChunkRegionPackets.RegionCoordinate region :
                        coordinates) {

                    BitSet availability =
                            SnapshotRegionIndex.regionAvailability(
                                    regionDirectory,
                                    region.x(),
                                    region.z()
                            );

                    entries.add(
                            new ChunkRegionPackets.RegionEntry(
                                    region.x(),
                                    region.z(),
                                    availability
                            )
                    );
                }

                server.execute(() -> {

                    ServerPlayer current =
                            server.getPlayerList()
                                    .getPlayer(requester);

                    if (current == null
                            || !current.hasPermissions(4)) {
                        return;
                    }

                    if (!current.serverLevel()
                            .dimension()
                            .location()
                            .toString()
                            .equals(dimensionId)) {

                        return;
                    }

                    ServerPlayNetworking.send(
                            current,
                            new ChunkRegionPackets.Data(
                                    snapshot,
                                    dimensionId,
                                    List.copyOf(entries)
                            )
                    );
                });

            } catch (Exception exception) {

                Sentinel.LOGGER.error(
                        "Failed to index snapshot regions",
                        exception
                );

                server.execute(() -> {

                    ServerPlayer current =
                            server.getPlayerList()
                                    .getPlayer(requester);

                    if (current != null) {

                        current.sendSystemMessage(
                                Component.literal(
                                        "[Sentinel] Region indexing failed: "
                                                + exception.getMessage()
                                )
                        );
                    }
                });
            }
        });
    }

    /**
     * Locate this snapshot's region directory
     * for the administrator's current dimension.
     */
    private static Path resolveRegionDirectory(
            ServerPlayer player,
            String snapshotId
    ) {

        if (snapshotId == null
                || !snapshotId.matches(
                "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
        )) {

            throw new IllegalArgumentException(
                    "Invalid snapshot ID"
            );
        }

        BackupManager manager =
                Sentinel.backupManager();

        if (manager == null) {

            throw new IllegalStateException(
                    "Backup manager unavailable"
            );
        }

        SentinelConfig config =
                manager.config();

        Path root =
                config.snapshotsDirectory()
                        .toAbsolutePath()
                        .normalize();

        Path snapshot =
                root.resolve(snapshotId)
                        .normalize();

        if (!snapshot.startsWith(root)
                || !Files.isDirectory(snapshot)
                || Files.isSymbolicLink(snapshot)) {

            throw new IllegalArgumentException(
                    "Snapshot not found"
            );
        }

        Path world =
                WorldPathResolver.snapshotWorld(
                        snapshot,
                        WorldPathResolver.relativeWorld(
                                player.getServer()
                        )
                );

        ResourceLocation dimension =
                player.serverLevel()
                        .dimension()
                        .location();

        Path target;

        if (dimension.getNamespace().equals("minecraft")
                && dimension.getPath().equals("overworld")) {

            target = world;

        } else if (dimension.getNamespace().equals("minecraft")
                && dimension.getPath().equals("the_nether")) {

            target = world.resolve("DIM-1");

        } else if (dimension.getNamespace().equals("minecraft")
                && dimension.getPath().equals("the_end")) {

            target = world.resolve("DIM1");

        } else {

            target = world.resolve("dimensions")
                    .resolve(dimension.getNamespace())
                    .resolve(dimension.getPath());
        }

        target = target.normalize();

        if (!target.startsWith(world.normalize())) {

            throw new IllegalArgumentException(
                    "Unsafe dimension path"
            );
        }

        Path regionDirectory = target.resolve("region");

        if (!Files.isDirectory(regionDirectory)) {

            throw new IllegalArgumentException(
                    "Snapshot has no region directory "
                            + "for this dimension"
            );
        }

        return regionDirectory;
    }
}