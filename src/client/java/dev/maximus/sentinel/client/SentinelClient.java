package dev.maximus.sentinel.client;

import dev.maximus.sentinel.client.gui.ChunkMapScreen;
import dev.maximus.sentinel.client.gui.SnapshotGuiScreen;

import dev.maximus.sentinel.gui.ChunkMapPackets;
import dev.maximus.sentinel.gui.ChunkRegionPackets;
import dev.maximus.sentinel.gui.SnapshotGuiPackets;

import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class SentinelClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        /*
         * Existing snapshot management GUI.
         */
        ClientPlayNetworking.registerGlobalReceiver(
                SnapshotGuiPackets.SnapshotList.TYPE,
                (packet, context) -> {

                    context.client().execute(() -> {

                        if (context.client().screen
                                instanceof SnapshotGuiScreen screen) {

                            screen.updateSnapshots(
                                    packet.entries()
                            );

                            return;
                        }

                        context.client().setScreen(
                                new SnapshotGuiScreen(
                                        packet.entries()
                                )
                        );
                    });
                }
        );

        /*
         * Chunk availability and initial map opening.
         *
         * Subsequent responses merge into the
         * open screen's client-side cache.
         */
        ClientPlayNetworking.registerGlobalReceiver(
                ChunkMapPackets.MapData.TYPE,
                (packet, context) -> {

                    context.client().execute(() -> {

                        if (context.client().screen
                                instanceof ChunkMapScreen screen) {

                            screen.updateMap(packet);
                            return;
                        }

                        if (!packet.open()) {
                            return;
                        }

                        if (!(context.client().screen
                                instanceof SnapshotGuiScreen previous)) {
                            return;
                        }

                        context.client().setScreen(
                                new ChunkMapScreen(
                                        packet.snapshot(),
                                        previous,
                                        packet
                                )
                        );
                    });
                }
        );

        /*
         * Lightweight player-marker updates.
         *
         * These never replace cached terrain data.
         */
        ClientPlayNetworking.registerGlobalReceiver(
                ChunkMapPackets.PlayersData.TYPE,
                (packet, context) -> {

                    context.client().execute(() -> {

                        if (context.client().screen
                                instanceof ChunkMapScreen screen) {

                            screen.updatePlayers(packet);
                        }
                    });
                }
        );

        /*
         * Complete 32x32 region availability updates.
         *
         * These are merged into the open chunk map's
         * bounded region cache.
         */
        ClientPlayNetworking.registerGlobalReceiver(
                ChunkRegionPackets.Data.TYPE,
                (packet, context) -> {

                    context.client().execute(() -> {

                        if (context.client().screen
                                instanceof ChunkMapScreen screen) {

                            screen.updateRegions(packet);
                        }
                    });
                }
        );
    }
}