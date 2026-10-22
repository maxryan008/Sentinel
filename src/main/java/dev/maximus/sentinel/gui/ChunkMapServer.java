package dev.maximus.sentinel.gui;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.config.SentinelConfig;
import dev.maximus.sentinel.recovery.WorldPathResolver;
import dev.maximus.sentinel.recovery.chunk.LiveChunkRollbackManager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChunkMapServer {

    private static final ExecutorService INDEX_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(
                        task,
                        "Sentinel-Snapshot-Region-Index"
                );
                thread.setDaemon(true);
                return thread;
            });

    private ChunkMapServer() {
    }

    public static void register() {

        ServerPlayNetworking.registerGlobalReceiver(
                ChunkMapPackets.Request.TYPE,
                (packet, context) -> {
                    ServerPlayer player = context.player();

                    if (!authorized(player)) {
                        return;
                    }

                    try {
                        sendMap(player, packet);
                    } catch (Exception exception) {
                        fail(player, exception);
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                ChunkMapPackets.PlayersRequest.TYPE,
                (packet, context) -> {
                    ServerPlayer player = context.player();

                    if (authorized(player)) {
                        sendPlayers(player);
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                ChunkMapPackets.Restore.TYPE,
                (packet, context) -> {
                    ServerPlayer player = context.player();

                    if (!authorized(player)) {
                        return;
                    }

                    try {
                        restore(player, packet);
                    } catch (Exception exception) {
                        fail(player, exception);
                    }
                }
        );

        ServerTickEvents.END_SERVER_TICK.register(
                ChunkRollbackBatchManager::tick
        );
    }

    private static boolean authorized(ServerPlayer player) {
        return player.hasPermissions(4);
    }

    private static void fail(
            ServerPlayer player,
            Exception exception
    ) {
        Sentinel.LOGGER.error(
                "Chunk map operation failed",
                exception
        );

        player.sendSystemMessage(
                Component.literal(
                        "[Sentinel] " + exception.getMessage()
                )
        );
    }

    private static Path dimensionSnapshot(
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

        BackupManager manager = Sentinel.backupManager();

        if (manager == null) {
            throw new IllegalStateException(
                    "Backup manager unavailable"
            );
        }

        SentinelConfig config = manager.config();

        Path root = config.snapshotsDirectory()
                .toAbsolutePath()
                .normalize();

        Path snapshot = root.resolve(snapshotId).normalize();

        if (!snapshot.startsWith(root)
                || !Files.isDirectory(snapshot)
                || Files.isSymbolicLink(snapshot)) {

            throw new IllegalArgumentException(
                    "Snapshot not found"
            );
        }

        MinecraftServer server = player.getServer();

        Path relative = WorldPathResolver.relativeWorld(server);

        Path world = WorldPathResolver.snapshotWorld(
                snapshot,
                relative
        );

        ResourceLocation dimension =
                player.serverLevel().dimension().location();

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

            target = world
                    .resolve("dimensions")
                    .resolve(dimension.getNamespace())
                    .resolve(dimension.getPath());
        }

        target = target.normalize();

        if (!target.startsWith(world.normalize())) {
            throw new IllegalArgumentException(
                    "Unsafe dimension path"
            );
        }

        if (!Files.isDirectory(target.resolve("region"))) {
            throw new IllegalArgumentException(
                    "Snapshot contains no terrain data for this dimension"
            );
        }

        return target;
    }

    /**
     * Collect current player positions.
     *
     * This never accesses snapshot files.
     */
    private static List<ChunkMapPackets.PlayerMarker> markers(
            ServerPlayer viewer
    ) {

        List<ChunkMapPackets.PlayerMarker> result =
                new ArrayList<>();

        for (ServerPlayer other :
                viewer.serverLevel().players()) {

            int role = other.getUUID().equals(viewer.getUUID())
                    ? 2
                    : other.hasPermissions(4) ? 1 : 0;

            result.add(
                    new ChunkMapPackets.PlayerMarker(
                            other.getGameProfile().getName(),
                            other.getX(),
                            other.getZ(),
                            role
                    )
            );
        }

        return List.copyOf(result);
    }

    private static void sendPlayers(ServerPlayer player) {

        ServerPlayNetworking.send(
                player,
                new ChunkMapPackets.PlayersData(
                        player.serverLevel()
                                .dimension()
                                .location()
                                .toString(),
                        player.getX(),
                        player.getZ(),
                        markers(player)
                )
        );
    }

    /**
     * Index the viewport on the I/O worker.
     *
     * Minecraft world and player state is only
     * accessed on the server thread.
     */
    private static void sendMap(
            ServerPlayer player,
            ChunkMapPackets.Request request
    ) {

        int radius = request.radius();

        if (radius < 1
                || radius > ChunkMapPackets.MAX_MAP_RADIUS) {
            throw new IllegalArgumentException(
                    "Invalid map radius"
            );
        }

        Path dimension = dimensionSnapshot(
                player,
                request.snapshot()
        );

        Path regionDirectory = dimension.resolve("region");

        MinecraftServer server = player.getServer();
        UUID requester = player.getUUID();

        String dimensionId =
                player.serverLevel().dimension()
                        .location().toString();

        int centerX = request.centerX();
        int centerZ = request.centerZ();

        INDEX_EXECUTOR.execute(() -> {

            try {

                int side = radius * 2 + 1;
                boolean[] available = new boolean[side * side];

                int index = 0;

                long minX = (long) centerX - radius;
                long maxX = (long) centerX + radius;

                long minZ = (long) centerZ - radius;
                long maxZ = (long) centerZ + radius;

                for (long z = minZ; z <= maxZ; z++) {
                    for (long x = minX; x <= maxX; x++) {

                        boolean present = false;

                        if (x >= Integer.MIN_VALUE
                                && x <= Integer.MAX_VALUE
                                && z >= Integer.MIN_VALUE
                                && z <= Integer.MAX_VALUE) {

                            present = SnapshotRegionIndex.available(
                                    regionDirectory,
                                    (int) x,
                                    (int) z
                            );
                        }

                        available[index++] = present;
                    }
                }

                server.execute(() -> {

                    ServerPlayer current =
                            server.getPlayerList().getPlayer(requester);

                    if (current == null
                            || !authorized(current)) {
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
                            new ChunkMapPackets.MapData(
                                    request.snapshot(),
                                    dimensionId,
                                    centerX,
                                    centerZ,
                                    radius,
                                    current.getX(),
                                    current.getZ(),
                                    available,
                                    markers(current),
                                    request.open()
                            )
                    );
                });

            } catch (Exception exception) {

                Sentinel.LOGGER.error(
                        "Unable to index snapshot viewport",
                        exception
                );

                server.execute(() -> {

                    ServerPlayer current =
                            server.getPlayerList().getPlayer(requester);

                    if (current != null) {
                        fail(current, exception);
                    }
                });
            }
        });
    }

    /**
     * Validate each restored chunk through the
     * server-side snapshot index.
     *
     * The client cache is never trusted for
     * restoration authorisation.
     */
    private static void restore(
            ServerPlayer player,
            ChunkMapPackets.Restore request
    ) {

        BackupManager manager = Sentinel.backupManager();

        if (manager == null || manager.isRunningBackup()) {
            throw new IllegalStateException(
                    "Backup manager busy or unavailable"
            );
        }

        if (LiveChunkRollbackManager.busy()
                || ChunkRollbackBatchManager.busy()) {
            throw new IllegalStateException(
                    "Another chunk rollback is active"
            );
        }

        ServerLevel level = player.serverLevel();

        if (!level.dimension().location().toString()
                .equals(request.dimension())) {
            throw new IllegalArgumentException(
                    "Dimension changed. Reopen the chunk map."
            );
        }

        if (request.chunks().isEmpty()
                || request.chunks().size()
                > ChunkMapPackets.MAX_BATCH) {
            throw new IllegalArgumentException(
                    "Invalid chunk selection size"
            );
        }

        Path dimension = dimensionSnapshot(
                player,
                request.snapshot()
        );

        Path regionDirectory = dimension.resolve("region");

        Set<ChunkPos> chunks = new LinkedHashSet<>();

        for (ChunkMapPackets.ChunkCoordinate chunk :
                request.chunks()) {

            if (!SnapshotRegionIndex.available(
                    regionDirectory,
                    chunk.x(),
                    chunk.z()
            )) {
                throw new IllegalArgumentException(
                        "Snapshot does not contain chunk ["
                                + chunk.x() + ", "
                                + chunk.z() + "]"
                );
            }

            chunks.add(new ChunkPos(chunk.x(), chunk.z()));
        }

        if (chunks.size() != request.chunks().size()) {
            throw new IllegalArgumentException(
                    "Duplicate chunk coordinates"
            );
        }

        ChunkRollbackBatchManager.start(
                player,
                level,
                dimension,
                chunks
        );
    }
}