package dev.maximus.sentinel.backup;

import dev.maximus.sentinel.Sentinel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FileActions {
    private FileActions() {
    }

    public static void rsyncToModel(Path source, Path model, List<String> excludes) {
        try {
            Files.createDirectories(model);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create model directory: " + model, exception);
        }

        List<String> command = new ArrayList<>();
        command.add("rsync");
        command.add("-ra");
        command.add("--delete");

        if (excludes != null) {
            for (String exclude : excludes) {
                if (exclude != null && !exclude.isBlank()) {
                    command.add("--exclude=" + exclude);
                }
            }
        }

        command.add(ensureTrailingSlash(source));
        command.add(ensureTrailingSlash(model));

        run(command, source);
    }

    public static void hardlinkCopy(Path model, Path snapshot) {
        if (Files.exists(snapshot)) {
            throw new IllegalStateException("Snapshot already exists: " + snapshot);
        }

        try {
            Files.createDirectories(snapshot.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create snapshot parent directory.", exception);
        }

        run(List.of("cp", "-al", model.toString(), snapshot.toString()), model.getParent());
    }

    public static void run(List<String> command, Path workingDirectory) {
        Sentinel.LOGGER.info("Running command: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.inheritIO();

        try {
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IllegalStateException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run command: " + String.join(" ", command), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running command: " + String.join(" ", command), exception);
        }
    }

    private static String ensureTrailingSlash(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return value.endsWith("/") ? value : value + "/";
    }
}