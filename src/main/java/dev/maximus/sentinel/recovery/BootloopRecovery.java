package dev.maximus.sentinel.recovery;

import dev.maximus.sentinel.Sentinel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class BootloopRecovery implements PreLaunchEntrypoint {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final DateTimeFormatter SNAPSHOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final String STATE_FILE = "state.json";
    private static final String BOOT_MARKER = "boot-active.json";

    private static final String RECOVERY_ACTIVE = "recovery-active";

    private static RuntimeConfig config;
    private static RecoveryState state;

    private static Path serverDirectory;
    private static Path backupDirectory;
    private static Path recoveryDirectory;
    private static Path worldDirectory;

    private static long tickCount;
    private static long firstTickAt;

    private static boolean enabled;
    private static boolean verifiedHealthy;

    @Override
    public void onPreLaunch() {

        try {
            initialize();
        } catch (Exception exception) {
            System.err.println(
                    "[Sentinel Recovery] Fatal recovery initialization error."
            );

            exception.printStackTrace();

            // Do not let Minecraft start with a partially
            // restored or otherwise uncertain world state.
            Runtime.getRuntime().halt(73);
        }
    }

    private static void initialize() throws Exception {

        serverDirectory = Path.of(".")
                .toAbsolutePath()
                .normalize();

        Path configFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("sentinel.json");

        if (!Files.isRegularFile(configFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(configFile)) {
            config = GSON.fromJson(reader, RuntimeConfig.class);
        }

        if (config == null
                || !config.enabled
                || !config.automaticRecoveryEnabled) {
            return;
        }

        // An integrated client may switch between several saves without
        // restarting the JVM. Prelaunch recovery must not choose a stale save.
        // Manual rollback remains supported on integrated servers.
        if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
            return;
        }

        enabled = true;

        if (config.backupDirectory == null
                || config.backupDirectory.isBlank()) {
            throw new IllegalStateException(
                    "Automatic recovery requires backupDirectory."
            );
        }

        backupDirectory = Path.of(config.backupDirectory)
                .toAbsolutePath()
                .normalize();

        recoveryDirectory = backupDirectory.resolve("recovery");

        if (backupDirectory.startsWith(serverDirectory)
                || serverDirectory.startsWith(backupDirectory)) {
            throw new IllegalStateException(
                    "Backup directory must be separate from server directory."
            );
        }

        Files.createDirectories(recoveryDirectory);

        Path relativeWorld = WorldPathResolver.readRecorded(recoveryDirectory, serverDirectory);
        if (relativeWorld == null) {
            // First start: Minecraft has not opened a world to record yet.
            // Do not invent a path or block a fresh installation.
            System.out.println("[Sentinel Recovery] No recorded world yet; recovery deferred until a world has started.");
            enabled = false;
            return;
        }
        worldDirectory = serverDirectory.resolve(relativeWorld).normalize();

        Path statePath = recoveryDirectory.resolve(STATE_FILE);

        state = readJson(statePath, RecoveryState.class);

        if (state == null) {
            state = new RecoveryState();
        }

        normalizeState();

        Path bootMarker = recoveryDirectory.resolve(BOOT_MARKER);

        BootRecord previousBoot =
                readJson(bootMarker, BootRecord.class);

        long now = System.currentTimeMillis();

        if (previousBoot != null) {

            // An active marker means the previous JVM did not
            // complete a clean Minecraft shutdown.

            recordFailure(now);

            System.out.println(
                    "[Sentinel Recovery] Previous Minecraft instance "
                            + "terminated without a clean shutdown."
            );
        }

        // Persist a marker immediately. If Minecraft crashes
        // before normal server initialization, the next JVM
        // will still recognise the failed launch.

        BootRecord currentBoot = new BootRecord();

        currentBoot.instanceId = UUID.randomUUID().toString();
        currentBoot.startedAt = now;

        writeJson(bootMarker, currentBoot);

        saveState();

        if (state.recoveryActive) {

            // A recovery was previously attempted, but its JVM
            // failed before passing health verification.

            System.out.println(
                    "[Sentinel Recovery] Previous rollback did not "
                            + "pass health verification."
            );

            attemptNextRollback();
            return;
        }

        if (state.failureTimes.size()
                >= Math.max(2, config.recoveryFailureThreshold)) {

            System.out.println(
                    "[Sentinel Recovery] Bootloop detected."
            );

            state.recoveryActive = true;

            saveState();

            attemptNextRollback();
        }
    }

    private static void normalizeState() {

        if (state.failureTimes == null) {
            state.failureTimes = new ArrayList<>();
        }

        if (state.triedSnapshots == null) {
            state.triedSnapshots = new ArrayList<>();
        }

        if (state.rollbackAttempts < 0) {
            state.rollbackAttempts = 0;
        }
    }

    private static void recordFailure(long now) {

        long window = Math.max(
                60,
                config.recoveryFailureWindowSeconds
        ) * 1000L;

        state.failureTimes.removeIf(
                timestamp -> timestamp > now
                        || now - timestamp > window
        );

        if (state.failureTimes.isEmpty()
                && !state.recoveryActive) {
            state.firstFailureAt = now;
        }

        state.failureTimes.add(now);
    }

    private static void attemptNextRollback() throws Exception {

        if (state.rollbackAttempts
                >= Math.max(1, config.recoveryMaxRollbackAttempts)) {

            throw new IllegalStateException(
                    "Automatic recovery exhausted all "
                            + "configured rollback attempts."
            );
        }

        Path snapshotsDirectory =
                backupDirectory.resolve("snapshots");

        if (!Files.isDirectory(snapshotsDirectory)) {
            throw new IllegalStateException(
                    "No Sentinel snapshot directory exists."
            );
        }

        List<Path> snapshots = findEligibleSnapshots(
                snapshotsDirectory
        );

        if (snapshots.isEmpty()) {
            throw new IllegalStateException(
                    "No eligible snapshots remain."
            );
        }

        Path selected = snapshots.getFirst();

        System.out.println(
                "[Sentinel Recovery] Attempting rollback "
                        + (state.rollbackAttempts + 1)
                        + "/"
                        + config.recoveryMaxRollbackAttempts
                        + ": "
                        + selected.getFileName()
        );

        // Before the first rollback, preserve the original
        // failed world in a separate recovery directory.

        if (!state.originalWorldPreserved
                && config.recoveryPreserveFailedWorld) {

            preserveOriginalWorld();
        }

        state.rollbackAttempts++;

        state.triedSnapshots.add(
                selected.getFileName().toString()
        );

        state.currentSnapshot =
                selected.getFileName().toString();

        saveState();

        Files.writeString(
                recoveryDirectory.resolve(RECOVERY_ACTIVE),
                selected.getFileName().toString()
        );

        restoreWorld(selected);

        System.out.println(
                "[Sentinel Recovery] Rollback completed. "
                        + "Continuing Minecraft startup."
        );
    }

    private static List<Path> findEligibleSnapshots(
            Path snapshotsDirectory
    ) throws IOException {

        List<Path> snapshots = new ArrayList<>();

        try (Stream<Path> stream = Files.list(snapshotsDirectory)) {

            stream.filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        String name = path.getFileName().toString();

                        return !state.triedSnapshots.contains(name);
                    })
                    .filter(path -> {
                        String name = path.getFileName().toString();

                        try {
                            LocalDateTime date =
                                    LocalDateTime.parse(
                                            name,
                                            SNAPSHOT_FORMAT
                                    );

                            long createdAt = date.toInstant(
                                    ZoneOffset.UTC
                            ).toEpochMilli();

                            return state.firstFailureAt == 0
                                    || createdAt <= state.firstFailureAt;

                        } catch (Exception exception) {
                            return false;
                        }
                    })
                    .filter(path ->
                            Files.isDirectory(
                                    path.resolve(
                                            serverDirectory.relativize(worldDirectory)
                                    )
                            )
                    )
                    .forEach(snapshots::add);
        }

        snapshots.sort(
                Comparator.comparing(
                        path -> path.getFileName().toString(),
                        Comparator.reverseOrder()
                )
        );

        return snapshots;
    }

    private static void preserveOriginalWorld() throws Exception {

        if (!Files.isDirectory(worldDirectory)) {
            throw new IllegalStateException(
                    "Cannot preserve missing world directory."
            );
        }

        Path preservedDirectory = recoveryDirectory.resolve(
                "preserved"
        );

        Files.createDirectories(preservedDirectory);

        Path destination = preservedDirectory.resolve(
                "failed-world-" + UUID.randomUUID()
        );

        Files.createDirectories(destination);

        System.out.println(
                "[Sentinel Recovery] Preserving failed world: "
                        + destination
        );

        rsync(worldDirectory, destination, false);

        state.originalWorldPreserved = true;

        saveState();
    }

    private static void restoreWorld(Path snapshot) throws Exception {

        Path source = WorldPathResolver.snapshotWorld(
                snapshot, serverDirectory.relativize(worldDirectory)
        );

        if (!Files.isDirectory(source)
                || Files.isSymbolicLink(source)) {

            throw new IllegalStateException(
                    "Snapshot does not contain a safe world directory: "
                            + snapshot
            );
        }

        Files.createDirectories(worldDirectory);

        System.out.println(
                "[Sentinel Recovery] Restoring "
                        + source
                        + " -> "
                        + worldDirectory
        );

        rsync(source, worldDirectory, true);
    }

    private static void rsync(
            Path source,
            Path destination,
            boolean delete
    ) throws Exception {

        List<String> command = new ArrayList<>();

        command.add("rsync");
        command.add("-a");

        if (delete) {
            command.add("--delete");
        }

        command.add("--");

        command.add(source.toString() + "/");
        command.add(destination.toString() + "/");

        Process process = new ProcessBuilder(command)
                .directory(serverDirectory.toFile())
                .inheritIO()
                .start();

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IllegalStateException(
                    "rsync failed with exit code " + exitCode
            );
        }
    }

    private static String readWorldName() throws IOException {

        Path properties = serverDirectory.resolve(
                "server.properties"
        );

        String name = "world";

        if (Files.isRegularFile(properties)) {

            for (String line : Files.readAllLines(properties)) {

                line = line.trim();

                if (line.startsWith("#") || !line.contains("=")) {
                    continue;
                }

                String[] parts = line.split("=", 2);

                if (parts[0].trim().equals("level-name")) {
                    name = parts[1].trim();
                    break;
                }
            }
        }

        if (name.isBlank()
                || name.equals(".")
                || name.equals("..")
                || name.contains("/")
                || name.contains("\\")
                || Path.of(name).isAbsolute()) {

            throw new IllegalStateException(
                    "Invalid level-name: " + name
            );
        }

        return name;
    }

    private static <T> T readJson(
            Path path,
            Class<T> type
    ) throws IOException {

        if (!Files.isRegularFile(path)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, type);
        }
    }

    private static void writeJson(
            Path path,
            Object value
    ) throws IOException {

        Files.createDirectories(path.getParent());

        Path temporary = path.resolveSibling(
                path.getFileName() + "." + UUID.randomUUID() + ".tmp"
        );

        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(value, writer);
        }

        try {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static void saveState() throws IOException {

        writeJson(
                recoveryDirectory.resolve(STATE_FILE),
                state
        );
    }

    /**
     * Called by Sentinel from the main server thread.
     */
    public static void tick() {

        if (!enabled || verifiedHealthy) {
            return;
        }

        tickCount++;

        if (tickCount < 20) {
            return;
        }

        if (firstTickAt == 0) {
            firstTickAt = System.currentTimeMillis();
        }

        long requiredDuration = Math.max(
                60,
                config.recoveryHealthySeconds
        ) * 1000L;

        if (System.currentTimeMillis() - firstTickAt
                < requiredDuration) {
            return;
        }

        verifiedHealthy = true;

        System.out.println(
                "[Sentinel Recovery] Minecraft passed "
                        + "health verification."
        );

        state.recoveryActive = false;
        state.failureTimes.clear();
        state.firstFailureAt = 0;
        state.rollbackAttempts = 0;
        state.triedSnapshots.clear();
        state.currentSnapshot = null;

        // A future bootloop must preserve its own failed world.
        state.originalWorldPreserved = false;

        try {
            saveState();

            Files.deleteIfExists(
                    recoveryDirectory.resolve(RECOVERY_ACTIVE)
            );

        } catch (IOException exception) {

            System.err.println(
                    "[Sentinel Recovery] Failed to persist healthy state."
            );

            exception.printStackTrace();
        }
    }

    /**
     * Called when Minecraft initiates a clean shutdown.
     */
    public static void cleanShutdown() {

        if (!enabled) {
            return;
        }

        try {
            Files.deleteIfExists(
                    recoveryDirectory.resolve(BOOT_MARKER)
            );

        } catch (IOException exception) {
            System.err.println(
                    "[Sentinel Recovery] Failed to clear boot marker."
            );
        }
    }

    public static boolean isRecoveryActive() {
        return enabled
                && state != null
                && state.recoveryActive;
    }

    private static final class RuntimeConfig {

        boolean enabled = true;
        boolean automaticRecoveryEnabled = false;

        String backupDirectory = "";

        int recoveryFailureThreshold = 3;
        int recoveryFailureWindowSeconds = 600;
        int recoveryHealthySeconds = 300;
        int recoveryMaxRollbackAttempts = 5;

        boolean recoveryPreserveFailedWorld = true;
    }

    private static final class RecoveryState {

        List<Long> failureTimes = new ArrayList<>();
        List<String> triedSnapshots = new ArrayList<>();

        long firstFailureAt;

        int rollbackAttempts;

        boolean recoveryActive;
        boolean originalWorldPreserved;

        String currentSnapshot;
    }

    private static final class BootRecord {

        String instanceId;
        long startedAt;
    }
}