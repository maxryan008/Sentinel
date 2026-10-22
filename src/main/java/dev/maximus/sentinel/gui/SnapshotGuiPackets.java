package dev.maximus.sentinel.gui;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class SnapshotGuiPackets {

    public static final int MAX_SNAPSHOTS = 512;

    private SnapshotGuiPackets() {
    }

    public static void register() {

        PayloadTypeRegistry.playS2C().register(
                SnapshotList.TYPE,
                SnapshotList.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                SnapshotAction.TYPE,
                SnapshotAction.CODEC
        );
    }

    public record SnapshotEntry(
            String id,
            long createdAtEpochMillis,
            boolean locked,
            String reason
    ) {
    }

    public record SnapshotList(
            List<SnapshotEntry> entries
    ) implements CustomPacketPayload {

        public static final Type<SnapshotList> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                "sentinel",
                                "snapshot_list"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                SnapshotList
                > CODEC = StreamCodec.of(
                SnapshotList::write,
                SnapshotList::read
        );

        private static void write(
                RegistryFriendlyByteBuf buffer,
                SnapshotList packet
        ) {

            buffer.writeVarInt(
                    packet.entries.size()
            );

            for (SnapshotEntry entry : packet.entries) {

                buffer.writeUtf(entry.id(), 128);

                buffer.writeLong(
                        entry.createdAtEpochMillis()
                );

                buffer.writeBoolean(
                        entry.locked()
                );

                buffer.writeUtf(
                        entry.reason(),
                        16
                );
            }
        }

        private static SnapshotList read(
                RegistryFriendlyByteBuf buffer
        ) {

            int count = buffer.readVarInt();

            if (count < 0 || count > MAX_SNAPSHOTS) {

                throw new IllegalArgumentException(
                        "Invalid snapshot count: " + count
                );
            }

            List<SnapshotEntry> entries =
                    new ArrayList<>(count);

            for (int index = 0;
                 index < count;
                 index++) {

                entries.add(
                        new SnapshotEntry(
                                buffer.readUtf(128),
                                buffer.readLong(),
                                buffer.readBoolean(),
                                buffer.readUtf(16)
                        )
                );
            }

            return new SnapshotList(
                    List.copyOf(entries)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SnapshotAction(
            Action action,
            String snapshotId
    ) implements CustomPacketPayload {

        public enum Action {
            REFRESH,
            RESTORE,
            DELETE,
            TOGGLE_LOCK
        }

        public static final Type<SnapshotAction> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                "sentinel",
                                "snapshot_action"
                        )
                );

        public static final StreamCodec<
                RegistryFriendlyByteBuf,
                SnapshotAction
                > CODEC = StreamCodec.of(
                SnapshotAction::write,
                SnapshotAction::read
        );

        private static void write(
                RegistryFriendlyByteBuf buffer,
                SnapshotAction packet
        ) {

            buffer.writeVarInt(
                    packet.action().ordinal()
            );

            buffer.writeUtf(
                    packet.snapshotId(),
                    128
            );
        }

        private static SnapshotAction read(
                RegistryFriendlyByteBuf buffer
        ) {

            int ordinal = buffer.readVarInt();

            Action[] actions = Action.values();

            if (ordinal < 0 || ordinal >= actions.length) {

                throw new IllegalArgumentException(
                        "Unknown snapshot action: " + ordinal
                );
            }

            return new SnapshotAction(
                    actions[ordinal],
                    buffer.readUtf(128)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}