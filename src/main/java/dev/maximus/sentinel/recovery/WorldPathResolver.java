package dev.maximus.sentinel.recovery;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Resolves and persists the actual loaded world's directory. */
public final class WorldPathResolver {
    private static final Gson GSON = new Gson();
    private WorldPathResolver() {}

    public static Path serverRoot() {
        return Path.of(".").toAbsolutePath().normalize();
    }

    public static Path relativeWorld(MinecraftServer server) {
        Path root = serverRoot();
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return validateRelative(root, world);
    }

    public static Path validateRelative(Path root, Path world) {
        root = root.toAbsolutePath().normalize();
        world = world.toAbsolutePath().normalize();
        if (!world.startsWith(root) || world.equals(root)) {
            throw new IllegalStateException("World must be inside backed-up server root: " + world);
        }
        Path relative = root.relativize(world);
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("World path contains symlink: " + current);
            }
        }
        return relative;
    }

    public static Path snapshotWorld(Path snapshot, Path relative) {
        Path source = snapshot.resolve(relative).normalize();
        if (!source.startsWith(snapshot.normalize()) || !Files.isDirectory(source)
                || !Files.isRegularFile(source.resolve("level.dat"))) {
            throw new IllegalStateException("Snapshot does not contain world: " + source);
        }
        Path current = snapshot;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("Snapshot world contains symlink: " + current);
            }
        }
        return source;
    }

    public static void record(MinecraftServer server, Path recoveryDir) {
        Path root = serverRoot();
        Path relative = relativeWorld(server);
        Path file = recoveryDir.resolve("world-path.json");
        try {
            Files.createDirectories(recoveryDir);
            Path tmp = recoveryDir.resolve("world-path-" + UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, GSON.toJson(new WorldRecord(root.toString(), relative.toString())));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to persist active world path", ex);
        }
    }

    /** Returns null rather than guessing if no verified server path was persisted. */
    public static Path readRecorded(Path recoveryDir, Path expectedRoot) throws IOException {
        Path file = recoveryDir.resolve("world-path.json");
        if (!Files.isRegularFile(file)) return null;
        try (Reader reader = Files.newBufferedReader(file)) {
            WorldRecord record = GSON.fromJson(reader, WorldRecord.class);
            if (record == null || record.serverRoot == null || record.relativeWorld == null
                    || !Path.of(record.serverRoot).toAbsolutePath().normalize().equals(expectedRoot.toAbsolutePath().normalize())) {
                throw new IOException("World-path record belongs to a different server root");
            }
            Path relative = Path.of(record.relativeWorld);
            if (relative.isAbsolute() || relative.normalize().startsWith("..") || relative.normalize().toString().equals(".")) {
                throw new IOException("Unsafe recorded relative world path");
            }
            validateRelative(expectedRoot, expectedRoot.resolve(relative));
            return relative.normalize();
        }
    }

    private record WorldRecord(String serverRoot, String relativeWorld) {}
}
