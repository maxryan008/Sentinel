package dev.maximus.sentinel.recovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** World-only restoration; callers must ensure no running world is open. */
public final class WorldRestorer {
    private WorldRestorer() {}

    public static void restore(Path serverRoot, Path snapshot, Path relativeWorld) throws IOException, InterruptedException {
        Path destination = serverRoot.resolve(relativeWorld).normalize();
        WorldPathResolver.validateRelative(serverRoot, destination);
        Path source = WorldPathResolver.snapshotWorld(snapshot, relativeWorld);
        if (Files.exists(destination) && !Files.isDirectory(destination)) {
            throw new IOException("Destination is not a directory: " + destination);
        }
        Files.createDirectories(destination);
        run(List.of("rsync", "-a", "--delete", "--", source + "/", destination + "/"), serverRoot);
    }

    public static void preserve(Path serverRoot, Path destination, Path world) throws IOException, InterruptedException {
        if (!Files.isDirectory(world)) throw new IOException("World missing: " + world);
        Files.createDirectories(destination);
        run(List.of("rsync", "-a", "--", world + "/", destination + "/"), serverRoot);
    }

    private static void run(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).inheritIO().start();
        int code;
        try { code = process.waitFor(); }
        catch (InterruptedException ex) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw ex; }
        if (code != 0) throw new IOException("Restore command failed with exit " + code);
    }
}
