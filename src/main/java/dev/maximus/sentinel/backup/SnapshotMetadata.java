package dev.maximus.sentinel.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public record SnapshotMetadata(
        String snapshotId,
        String createdAtUtc,
        String reason,
        String minecraftVersion,
        String serverDirectory,
        boolean compressed,
        long backupDurationMs
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String FILE_NAME = "snapshot.json";

    public static void write(Path snapshotDirectory, SnapshotMetadata metadata) {
        try {
            Files.createDirectories(snapshotDirectory);

            try (Writer writer = Files.newBufferedWriter(snapshotDirectory.resolve(FILE_NAME))) {
                GSON.toJson(metadata, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write snapshot metadata.", exception);
        }
    }

    public static SnapshotMetadata read(Path snapshotDirectory) {
        Path path = snapshotDirectory.resolve(FILE_NAME);

        if (Files.notExists(path)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, SnapshotMetadata.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read snapshot metadata.", exception);
        }
    }
}