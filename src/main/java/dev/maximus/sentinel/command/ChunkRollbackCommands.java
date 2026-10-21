package dev.maximus.sentinel.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.backup.BackupManager;
import dev.maximus.sentinel.config.SentinelConfig;
import dev.maximus.sentinel.recovery.RollbackManager;
import dev.maximus.sentinel.recovery.WorldPathResolver;
import dev.maximus.sentinel.recovery.chunk.LiveChunkRollbackManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChunkRollbackCommands {
    private ChunkRollbackCommands() {}
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> register(dispatcher));
    }
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sentinel").requires(s -> s.hasPermission(4))
            .then(Commands.literal("rollback")
                .then(Commands.literal("chunk")
                    .then(Commands.literal("here")
                        .then(Commands.literal("latest").executes(c -> run(c.getSource(), null, null, "latest")))
                        .then(Commands.argument("snapshot", StringArgumentType.word()).executes(c ->
                            run(c.getSource(), null, null, StringArgumentType.getString(c, "snapshot")))))
                    .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                            .then(Commands.literal("latest").executes(c -> run(c.getSource(),
                                IntegerArgumentType.getInteger(c,"chunkX"), IntegerArgumentType.getInteger(c,"chunkZ"), "latest")))
                            .then(Commands.argument("snapshot", StringArgumentType.word()).executes(c -> run(c.getSource(),
                                IntegerArgumentType.getInteger(c,"chunkX"), IntegerArgumentType.getInteger(c,"chunkZ"),
                                StringArgumentType.getString(c,"snapshot")))))))));
    }
    private static int run(CommandSourceStack source, Integer x, Integer z, String snapshotName) {
        try {
            BackupManager manager = Sentinel.backupManager();
            if (manager == null) throw new IllegalStateException("Backup manager unavailable");
            if (manager.isRunningBackup()) throw new IllegalStateException("Backup currently running");
            if (LiveChunkRollbackManager.busy()) throw new IllegalStateException("Hot swap already running");
            SentinelConfig config = manager.config();
            String selected = snapshotName.equals("latest") ? RollbackManager.latest(config) : snapshotName;
            if (selected == null || !selected.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                throw new IllegalArgumentException("Missing or invalid snapshot name");
            Path root = config.snapshotsDirectory().toAbsolutePath().normalize();
            Path snapshot = root.resolve(selected).normalize();
            if (!snapshot.startsWith(root) || !Files.isDirectory(snapshot) || Files.isSymbolicLink(snapshot))
                throw new IllegalArgumentException("Snapshot not found");
            Path relative = WorldPathResolver.relativeWorld(source.getServer());
            Path world = WorldPathResolver.snapshotWorld(snapshot, relative);
            ServerLevel level = source.getLevel();
            ChunkPos pos = x == null ? new ChunkPos(BlockPos.containing(source.getPosition())) : new ChunkPos(x,z);
            ResourceLocation dimension = level.dimension().location();
            Path target;
            if (dimension.getNamespace().equals("minecraft") && dimension.getPath().equals("overworld")) target = world;
            else if (dimension.getNamespace().equals("minecraft") && dimension.getPath().equals("the_nether")) target = world.resolve("DIM-1");
            else if (dimension.getNamespace().equals("minecraft") && dimension.getPath().equals("the_end")) target = world.resolve("DIM1");
            else target = world.resolve("dimensions").resolve(dimension.getNamespace()).resolve(dimension.getPath());
            Path dimensionSnapshot = target.normalize();
            if (!dimensionSnapshot.startsWith(world.normalize())) throw new IllegalArgumentException("Unsafe dimension");
            LiveChunkRollbackManager.start(level, pos, dimensionSnapshot, msg ->
                source.sendSuccess(() -> Component.literal("[Sentinel] " + msg), false));
            return 1;
        } catch (Exception e) {
            Sentinel.LOGGER.error("Unable to start experimental chunk hot swap", e);
            source.sendFailure(Component.literal("[Sentinel] " + e.getMessage()));
            return 0;
        }
    }
}
