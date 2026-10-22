package dev.maximus.sentinel.gui;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class ChunkRegionPackets {

    public static final int REGION_SIZE = 32;
    public static final int REGION_CHUNKS = 1024;
    public static final int REGION_BYTES = 128;

    public static final int MAX_REGIONS_PER_REQUEST = 32;

    private ChunkRegionPackets() {
    }

    public record RegionCoordinate(int x, int z) {
    }

    /**
     * One complete 32x32 availability region.
     *
     * Bit index:
     *
     * localZ * 32 + localX
     */
    public record RegionEntry(
            int regionX,
            int regionZ,
            BitSet available
    ) {
        public RegionEntry {
            available = (BitSet) available.clone();
        }

        public boolean contains(int chunkX, int chunkZ) {

            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);

            return available.get(localZ * 32 + localX);
        }
    }

    /**
     * Client -> server.
     *
     * Requests complete regions not currently
     * available in the client's local cache.
     */
    public record Request(
            String snapshot,
            String dimension,
            List<RegionCoordinate> regions
    ) implements CustomPacketPayload {

        public static final Type<Request> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                "sentinel",
                                "chunk_regions_request"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                Request
                > CODEC = StreamCodec.of(
                Request::write,
                Request::read
        );

        private static void write(
                RegistryFriendlyByteBuf buffer,
                Request packet
        ) {

            buffer.writeUtf(packet.snapshot(), 128);
            buffer.writeUtf(packet.dimension(), 128);

            buffer.writeVarInt(packet.regions().size());

            for (RegionCoordinate region : packet.regions()) {

                buffer.writeInt(region.x());
                buffer.writeInt(region.z());
            }
        }

        private static Request read(
                RegistryFriendlyByteBuf buffer
        ) {

            String snapshot = buffer.readUtf(128);
            String dimension = buffer.readUtf(128);

            int count = buffer.readVarInt();

            if (count < 1 || count > MAX_REGIONS_PER_REQUEST) {

                throw new IllegalArgumentException(
                        "Invalid region request size: " + count
                );
            }

            List<RegionCoordinate> regions =
                    new ArrayList<>(count);

            for (int i = 0; i < count; i++) {

                regions.add(
                        new RegionCoordinate(
                                buffer.readInt(),
                                buffer.readInt()
                        )
                );
            }

            return new Request(
                    snapshot,
                    dimension,
                    List.copyOf(regions)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server -> client.
     *
     * Every returned region is complete.
     *
     * The 1024 availability flags are packed
     * into exactly 128 bytes.
     */
    public record Data(
            String snapshot,
            String dimension,
            List<RegionEntry> regions
    ) implements CustomPacketPayload {

        public static final Type<Data> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                "sentinel",
                                "chunk_regions_data"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                Data
                > CODEC = StreamCodec.of(
                Data::write,
                Data::read
        );

        private static void write(
                RegistryFriendlyByteBuf buffer,
                Data packet
        ) {

            buffer.writeUtf(packet.snapshot(), 128);
            buffer.writeUtf(packet.dimension(), 128);

            buffer.writeVarInt(packet.regions().size());

            for (RegionEntry region : packet.regions()) {

                buffer.writeInt(region.regionX());
                buffer.writeInt(region.regionZ());

                byte[] packed = new byte[REGION_BYTES];

                byte[] actual =
                        region.available().toByteArray();

                System.arraycopy(
                        actual,
                        0,
                        packed,
                        0,
                        Math.min(actual.length, packed.length)
                );

                buffer.writeBytes(packed);
            }
        }

        private static Data read(
                RegistryFriendlyByteBuf buffer
        ) {

            String snapshot = buffer.readUtf(128);
            String dimension = buffer.readUtf(128);

            int count = buffer.readVarInt();

            if (count < 0 || count > MAX_REGIONS_PER_REQUEST) {

                throw new IllegalArgumentException(
                        "Invalid region response size: " + count
                );
            }

            List<RegionEntry> regions =
                    new ArrayList<>(count);

            for (int i = 0; i < count; i++) {

                int regionX = buffer.readInt();
                int regionZ = buffer.readInt();

                byte[] packed = new byte[REGION_BYTES];

                buffer.readBytes(packed);

                regions.add(
                        new RegionEntry(
                                regionX,
                                regionZ,
                                BitSet.valueOf(packed)
                        )
                );
            }

            return new Data(
                    snapshot,
                    dimension,
                    List.copyOf(regions)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {

        PayloadTypeRegistry.playC2S().register(
                Request.TYPE,
                Request.CODEC
        );

        PayloadTypeRegistry.playS2C().register(
                Data.TYPE,
                Data.CODEC
        );
    }
}