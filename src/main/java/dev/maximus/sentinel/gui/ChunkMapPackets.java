package dev.maximus.sentinel.gui;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class ChunkMapPackets {

    /**
     * At maximum zoom-out:
     *
     * 660 screen pixels / 1.125 pixels per chunk
     * = approximately 587 visible chunks horizontally.
     *
     * Radius 320 covers the viewport and allows
     * additional margin for 16x16 cell boundaries.
     */
    public static final int MAX_MAP_RADIUS = 320;

    public static final int MAX_GRID = MAX_MAP_RADIUS * 2 + 1;

    /**
     * Maximum number of chunks restored by a single
     * coordinated hot-swap transaction.
     */
    public static final int MAX_BATCH = 128;

    public static final int MAX_PLAYERS = 512;

    private ChunkMapPackets() {
    }

    public record ChunkCoordinate(int x, int z) {
    }

    public record PlayerMarker(
            String name,
            double x,
            double z,
            int role
    ) {
        // 0 = player, 1 = admin, 2 = viewer
    }

    public record Request(
            String snapshot,
            int centerX,
            int centerZ,
            int radius,
            boolean open
    ) implements CustomPacketPayload {

        public static final Type<Request> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(
                        "sentinel", "chunk_map_request"
                )
        );

        public static final StreamCodec<
                RegistryFriendlyByteBuf, Request
                > CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeUtf(packet.snapshot(), 128);
                    buf.writeInt(packet.centerX());
                    buf.writeInt(packet.centerZ());
                    buf.writeVarInt(packet.radius());
                    buf.writeBoolean(packet.open());
                },
                buf -> new Request(
                        buf.readUtf(128),
                        buf.readInt(),
                        buf.readInt(),
                        buf.readVarInt(),
                        buf.readBoolean()
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record MapData(
            String snapshot,
            String dimension,
            int centerX,
            int centerZ,
            int radius,
            double viewerX,
            double viewerZ,
            boolean[] available,
            List<PlayerMarker> players,
            boolean open
    ) implements CustomPacketPayload {

        public static final Type<MapData> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(
                        "sentinel", "chunk_map_data"
                )
        );

        public static final StreamCodec<
                RegistryFriendlyByteBuf, MapData
                > CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeUtf(packet.snapshot(), 128);
                    buf.writeUtf(packet.dimension(), 128);
                    buf.writeInt(packet.centerX());
                    buf.writeInt(packet.centerZ());
                    buf.writeVarInt(packet.radius());
                    buf.writeDouble(packet.viewerX());
                    buf.writeDouble(packet.viewerZ());
                    buf.writeBoolean(packet.open());

                    /*
                     * Pack availability into bits instead of
                     * sending one full byte per chunk.
                     *
                     * At maximum radius this reduces the
                     * grid payload from ~37 KB to ~4.7 KB.
                     */
                    buf.writeVarInt(packet.available().length);

                    int count = packet.available().length;
                    int byteCount = (count + 7) / 8;

                    byte[] packed = new byte[byteCount];

                    for (int i = 0; i < count; i++) {
                        if (packet.available()[i]) {
                            packed[i >> 3] |= (byte) (1 << (i & 7));
                        }
                    }

                    buf.writeBytes(packed);
                    writePlayers(buf, packet.players());
                },
                buf -> {
                    String snapshot = buf.readUtf(128);
                    String dimension = buf.readUtf(128);

                    int centerX = buf.readInt();
                    int centerZ = buf.readInt();
                    int radius = buf.readVarInt();

                    if (radius < 0 || radius > MAX_MAP_RADIUS) {
                        throw new IllegalArgumentException(
                                "Invalid chunk-map radius"
                        );
                    }

                    double viewerX = buf.readDouble();
                    double viewerZ = buf.readDouble();
                    boolean open = buf.readBoolean();

                    int count = buf.readVarInt();
                    int side = radius * 2 + 1;

                    if (count != side * side) {
                        throw new IllegalArgumentException(
                                "Invalid chunk-map grid length"
                        );
                    }

                    byte[] packed = new byte[(count + 7) / 8];
                    buf.readBytes(packed);

                    boolean[] available = new boolean[count];

                    for (int i = 0; i < count; i++) {
                        available[i] =
                                ((packed[i >> 3] >>> (i & 7)) & 1) != 0;
                    }

                    return new MapData(
                            snapshot,
                            dimension,
                            centerX,
                            centerZ,
                            radius,
                            viewerX,
                            viewerZ,
                            available,
                            readPlayers(buf),
                            open
                    );
                }
        );

        public boolean covers(int x, int z) {
            return Math.abs((long) x - centerX) <= radius
                    && Math.abs((long) z - centerZ) <= radius;
        }

        public boolean contains(int x, int z) {
            if (!covers(x, z)) {
                return false;
            }

            int dx = x - centerX;
            int dz = z - centerZ;
            int side = radius * 2 + 1;

            return available[(dz + radius) * side + dx + radius];
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Lightweight player-position request.
     *
     * No snapshot terrain data is transmitted.
     */
    public record PlayersRequest() implements CustomPacketPayload {

        public static final Type<PlayersRequest> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(
                        "sentinel", "chunk_map_players_request"
                )
        );

        public static final StreamCodec<
                RegistryFriendlyByteBuf, PlayersRequest
                > CODEC = StreamCodec.of(
                (buf, packet) -> {
                },
                buf -> new PlayersRequest()
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayersData(
            String dimension,
            double viewerX,
            double viewerZ,
            List<PlayerMarker> players
    ) implements CustomPacketPayload {

        public static final Type<PlayersData> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(
                        "sentinel", "chunk_map_players_data"
                )
        );

        public static final StreamCodec<
                RegistryFriendlyByteBuf, PlayersData
                > CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeUtf(packet.dimension(), 128);
                    buf.writeDouble(packet.viewerX());
                    buf.writeDouble(packet.viewerZ());
                    writePlayers(buf, packet.players());
                },
                buf -> new PlayersData(
                        buf.readUtf(128),
                        buf.readDouble(),
                        buf.readDouble(),
                        readPlayers(buf)
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Restore(
            String snapshot,
            String dimension,
            List<ChunkCoordinate> chunks
    ) implements CustomPacketPayload {

        public static final Type<Restore> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(
                        "sentinel", "chunk_map_restore"
                )
        );

        public static final StreamCodec<
                RegistryFriendlyByteBuf, Restore
                > CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeUtf(packet.snapshot(), 128);
                    buf.writeUtf(packet.dimension(), 128);

                    buf.writeVarInt(packet.chunks().size());

                    for (ChunkCoordinate chunk : packet.chunks()) {
                        buf.writeInt(chunk.x());
                        buf.writeInt(chunk.z());
                    }
                },
                buf -> {
                    String snapshot = buf.readUtf(128);
                    String dimension = buf.readUtf(128);
                    int count = buf.readVarInt();

                    if (count < 1 || count > MAX_BATCH) {
                        throw new IllegalArgumentException(
                                "Invalid chunk batch size"
                        );
                    }

                    List<ChunkCoordinate> chunks =
                            new ArrayList<>(count);

                    for (int i = 0; i < count; i++) {
                        chunks.add(new ChunkCoordinate(
                                buf.readInt(),
                                buf.readInt()
                        ));
                    }

                    return new Restore(
                            snapshot,
                            dimension,
                            List.copyOf(chunks)
                    );
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writePlayers(
            RegistryFriendlyByteBuf buf,
            List<PlayerMarker> players
    ) {
        buf.writeVarInt(players.size());

        for (PlayerMarker player : players) {
            buf.writeUtf(player.name(), 64);
            buf.writeDouble(player.x());
            buf.writeDouble(player.z());
            buf.writeVarInt(player.role());
        }
    }

    private static List<PlayerMarker> readPlayers(
            RegistryFriendlyByteBuf buf
    ) {
        int count = buf.readVarInt();

        if (count < 0 || count > MAX_PLAYERS) {
            throw new IllegalArgumentException(
                    "Invalid player-marker count"
            );
        }

        List<PlayerMarker> players = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            players.add(new PlayerMarker(
                    buf.readUtf(64),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readVarInt()
            ));
        }

        return List.copyOf(players);
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                Request.TYPE, Request.CODEC
        );

        PayloadTypeRegistry.playS2C().register(
                MapData.TYPE, MapData.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                Restore.TYPE, Restore.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                PlayersRequest.TYPE, PlayersRequest.CODEC
        );

        PayloadTypeRegistry.playS2C().register(
                PlayersData.TYPE, PlayersData.CODEC
        );
    }
}