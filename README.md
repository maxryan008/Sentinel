# Sentinel

**Minecraft Server Maintenance, Backup & Recovery**

Sentinel is a server maintenance and recovery mod for Minecraft Fabric 1.21.1, designed to protect server data, simplify administration, and provide reliable recovery tools.

Originally developed as an independent reimplementation of the BTFU backup system, Sentinel aims to evolve into a comprehensive server management solution, combining automated backups, disaster recovery, health monitoring, diagnostics, and world maintenance.

Sentinel currently provides continuous incremental backups using `rsync` and hardlinked snapshots, together with manual restoration and optional automatic bootloop recovery. When repeated unclean starts are detected, Sentinel can restore progressively older snapshots before world loading and check whether the recovered server remains responsive.

The long-term goal is to make Minecraft servers more resilient by detecting failures, identifying their causes, and recovering from problems with minimal administrator intervention and data loss.

## Features

### Backup Management

- Incremental backups using `rsync`
- Space-efficient hardlinked snapshots
- Automatic scheduled backups
- Manual on-demand backups
- Configurable backup intervals
- Configurable snapshot retention
- Automatic removal of older snapshots
- Snapshot locking to prevent deletion
- Snapshot metadata and history
- Optional compression of older snapshots
- Configurable file and directory exclusions
- Administrator backup notifications

### Recovery & Restoration

- **Optional automatic bootloop recovery** without a separately installed supervisor or Python script
- Detect repeated unclean server starts across JVM restarts
- Configurable bootloop threshold and detection window
- Restore progressively older snapshots if a previous recovery attempt fails
- Preserve the original failed world before automatic rollback (configurable)
- Keep recovery attempt state outside the Minecraft server directory
- Verify recovery after sustained successful server ticks
- Configurable limit on automatic rollback attempts
- Compatibility with timestamp-named snapshots from earlier backup versions, when the expected world data is present
- Manual snapshot rollback
- Restore the latest available snapshot
- Restore a specific snapshot
- Automated rollback workflows
- Pre-rollback safety backups
- Offline recovery scripts
- Recovery support when Minecraft cannot start
- Linux and Windows recovery scripts

### Safety Features

- Backup directory validation
- Protection against recursive backup configurations
- Optional prevention of startup without a configured backup directory
- World saving controls during backups
- Snapshot locking
- Safety backups before restoration
- Restoration after server shutdown
- Early-start automatic world restoration before Minecraft loads the world
- Scheduled backups paused while automatic recovery verification is in progress

### Platform Support

- Minecraft 1.21.1
- Fabric Loader
- Java 21
- Linux-first architecture
- Experimental Windows recovery support

---

# Requirements

## Minecraft

- Minecraft 1.21.1
- Fabric Loader
- Fabric API

## Java

Java 21 or newer is required.

## Linux

Sentinel uses standard system utilities for incremental backups and snapshot management.

Install the required packages:

```bash
sudo apt install rsync coreutils tar
```

These provide:

| Utility | Purpose |
|---|---|
| `rsync` | Incremental file synchronization |
| `cp` | Hardlinked snapshot creation |
| `tar` | Optional snapshot compression |

Linux is the recommended platform.

## Windows

Sentinel includes Windows emergency rollback scripts.

However, the primary backup implementation currently relies on Unix utilities and is designed for Linux environments.

Native Windows backup support and hardlink handling are not yet fully implemented.

---

# Installation

1. Install Fabric Loader for Minecraft 1.21.1.
2. Install Fabric API.
3. Place the Sentinel JAR in the server's `mods` directory.
4. Start the server once to generate the configuration.
5. Configure the backup directory.
6. Restart the server.
7. Optionally enable automatic bootloop recovery after validating your backups and restart setup.

Example:

```text
server/
├── mods/
│   ├── fabric-api.jar
│   └── sentinel.jar
├── config/
│   └── sentinel.json
└── world/
```

Sentinel generates its configuration at:

```text
config/sentinel.json
```

**Important:** The backup directory must be configured before Sentinel can perform backups.

By default, Sentinel prevents server startup if no backup directory has been configured.

---

# Configuration

Sentinel supports configurable backup schedules, retention policies, recovery behaviour, and administrator notifications.

Example configuration:

```json
{
  "enabled": true,
  "backupDirectory": "/home/user/backups/sentinel",
  "intervalSeconds": 300,
  "maxBackups": 128,

  "failServerStartWithoutBackupDirectory": true,
  "failIfRunningInsideBackupDirectory": true,
  "disableSavingDuringBackup": true,

  "runInitialBackupAfterStartup": false,
  "initialBackupDelaySeconds": 60,

  "broadcastBackupStart": true,
  "broadcastBackupFinish": true,
  "broadcastBackupFailures": true,
  "broadcastToOpsOnly": true,

  "generateManualRollbackScripts": true,
  "createPreRollbackSafetyBackup": true,

  "compressOldSnapshots": false,
  "compressSnapshotsOlderThanDays": 7,
  "compressionCommand": "tar",
  "compressionExtension": ".tar.gz",

  "automaticRecoveryEnabled": false,
  "recoveryFailureThreshold": 3,
  "recoveryFailureWindowSeconds": 600,
  "recoveryHealthySeconds": 300,
  "recoveryMaxRollbackAttempts": 5,
  "recoveryPreserveFailedWorld": true,

  "rsyncExcludes": [
    "backups/**",
    "logs/latest.log",
    "crash-reports/**",
    "session.lock",
    "manual_rollback/**"
  ]
}
```

## Configuration Options

| Option | Description |
|---|---|
| `enabled` | Enables or disables Sentinel's backup system |
| `backupDirectory` | Directory used to store backups |
| `intervalSeconds` | Time between automatic backups |
| `maxBackups` | Maximum number of retained snapshots |
| `failServerStartWithoutBackupDirectory` | Prevents startup without a configured backup directory |
| `failIfRunningInsideBackupDirectory` | Prevents unsafe backup directory configurations |
| `disableSavingDuringBackup` | Temporarily disables world saving during backup operations |
| `runInitialBackupAfterStartup` | Schedules an initial backup after startup |
| `initialBackupDelaySeconds` | Delay before the initial backup |
| `broadcastBackupStart` | Announces backup starts |
| `broadcastBackupFinish` | Announces successful backups |
| `broadcastBackupFailures` | Announces failed backups |
| `broadcastToOpsOnly` | Restricts announcements to server operators |
| `generateManualRollbackScripts` | Generates offline recovery scripts |
| `createPreRollbackSafetyBackup` | Creates a safety backup before rollback |
| `compressOldSnapshots` | Enables compression of older snapshots |
| `compressSnapshotsOlderThanDays` | Age threshold for compression |
| `compressionCommand` | Command used for compression |
| `compressionExtension` | Extension for compressed archives |
| `rsyncExcludes` | Paths excluded from backups |
| `automaticRecoveryEnabled` | Enables early-start bootloop detection and recovery (default: `false`) |
| `recoveryFailureThreshold` | Count of consecutive unclean launches that triggers rollback (default: `3`) |
| `recoveryFailureWindowSeconds` | Time window for counting previous failed launches (default: `600`) |
| `recoveryHealthySeconds` | Duration of successful server ticking required to clear recovery state (default: `300`) |
| `recoveryMaxRollbackAttempts` | Maximum number of progressively older snapshots to try (default: `5`) |
| `recoveryPreserveFailedWorld` | Copies the failed world before the first automatic restore (default: `true`) |

---

# Backup Directory

Sentinel requires a backup directory outside the Minecraft server directory.

Recommended:

```text
/home/user/backups/sentinel
```

Or, preferably, a separate storage device:

```text
/mnt/storage/sentinel-backups
```

Avoid placing backups inside the server directory:

```text
server/backups
```

Doing so could cause recursive backups, unnecessary disk usage, and unsafe restoration behaviour.

Sentinel validates the configured backup directory to prevent unsafe configurations.

For additional protection, storing backups on a separate physical drive is recommended.

---

# Backup Structure

Sentinel organises backups into snapshots, maintaining a synchronized model directory and a collection of historical recovery points.

Example:

```text
sentinel-backups/
├── _model/
│
├── snapshots/
│   ├── 2026-05-24_14-17-26/
│   ├── 2026-05-24_14-20-40/
│   └── 2026-05-24_14-25-12/
│
├── locks/
│
├── recovery/
│   ├── state.json
│   ├── boot-active.json
│   ├── recovery-active            # While validating a restored world
│   └── preserved/                # Copies of failed worlds
│
└── compressed/
```

### Model Directory

The `_model` directory maintains a synchronized copy of the Minecraft server files.

Sentinel updates this directory incrementally using `rsync`.

### Snapshots

Each snapshot represents the server's backed-up state at a particular point in time.

Snapshots use timestamps as directory names.

### Locks

The `locks` directory stores information about snapshots protected from automatic deletion or compression.

### Compressed Archives

Older snapshots can optionally be compressed into archives for long-term storage.

---

# Incremental Backup System

Sentinel uses a combination of `rsync` and filesystem hardlinks to create space-efficient snapshots.

Unlike conventional full backups, hardlinked snapshots share unchanged file data.

## Step 1: Synchronize Server Files

Sentinel synchronizes the server directory into its model directory.

```bash
rsync -ra --delete
```

Only changed or newly created files need to be transferred.

Files removed from the source directory are also removed from the model.

## Step 2: Create a Snapshot

Sentinel creates a hardlinked copy of the synchronized model.

```bash
cp -al
```

Hardlinks allow multiple snapshots to reference the same underlying file data.

This means unchanged files do not need to be duplicated for every backup.

## Example

Consider a Minecraft server containing 4.5 GB of data.

```text
Snapshot 1: 4.5 GB
Snapshot 2: 200 MB additional storage
Snapshot 3: 150 MB additional storage
Snapshot 4: 90 MB additional storage
```

Although each snapshot appears to contain a complete server backup, unchanged files share physical storage.

This allows Sentinel to maintain frequent recovery points without the storage requirements of repeatedly copying the entire server.

**Note:** Hardlinked snapshots generally require the model and snapshot directories to reside on the same filesystem.

---

# Automatic Backups

Sentinel automatically creates backups at configurable intervals.

By default:

```json
"intervalSeconds": 300
```

This schedules a backup approximately every five minutes.

The actual interval depends on server tick progression and backup execution time.

## Initial Backups

Sentinel can optionally perform a backup shortly after the server starts.

Enable:

```json
"runInitialBackupAfterStartup": true
```

Configure the delay:

```json
"initialBackupDelaySeconds": 60
```

This schedules the initial backup after the configured delay.

---

# Commands

Sentinel provides server commands for backup management and restoration.

## Create a Backup

```text
/sentinel backup now
```

Requests an immediate backup.

## Backup Status

```text
/sentinel status
```

Displays information about the backup system, including:

- Whether a backup is currently running
- Time remaining until the next scheduled backup
- Result of the most recent backup

## List Snapshots

```text
/sentinel list
```

Displays available snapshots.

Example:

```text
[Sentinel] 2026-05-24_14-25-12 - reason: pre rollback safety backup
[Sentinel] 2026-05-24_14-20-40 - reason: manual command
[Sentinel] 2026-05-24_14-17-26 [LOCKED] - reason: manual command
```

---

# Snapshot Restoration

Sentinel supports restoring previous server states using stored snapshots.

Restoration is coordinated with server shutdown to prevent files from being overwritten while Minecraft is running.

## List Available Rollback Snapshots

```text
/sentinel rollback list
```

## Restore the Latest Snapshot

```text
/sentinel rollback latest
```

Requests restoration of the most recent available snapshot.

## Restore a Specific Snapshot

```text
/sentinel rollback restore <snapshot>
```

Example:

```text
/sentinel rollback restore 2026-05-24_14-17-26
```

---

# Rollback Process

Sentinel coordinates restoration using an automated shutdown and recovery process.

When a rollback is requested:

```text
1. Validate the selected snapshot
2. Create a pre-rollback safety backup
3. Wait for the safety backup to finish
4. Launch the external restoration script
5. Initiate Minecraft server shutdown
6. Wait for the Minecraft process to terminate
7. Restore the selected snapshot
```

This prevents server files from being restored while Minecraft is actively using them.

## Pre-Rollback Safety Backups

By default, Sentinel creates a safety backup immediately before initiating restoration.

This preserves the current server state in case administrators need to recover files from before the rollback.

To disable this behaviour:

```json
"createPreRollbackSafetyBackup": false
```

Disabling safety backups is not recommended.

---

# Automatic Bootloop Recovery

Sentinel includes an **optional in-mod bootloop recovery system** intended for servers that are automatically restarted by their host after crashes. No separate Python process or manually configured external recovery daemon is required. Recovery runs through Fabric's early `preLaunch` entrypoint during the **next Minecraft process start**, before the world is loaded.

> [!WARNING]
> Automatic recovery performs destructive changes to the active world directory. **It is disabled by default.** Test it on a disposable server first, verify offline copies of critical data, and confirm that your host restarts the server after crashes. The feature has not been established as a complete integrity-repair system.

## Enable Recovery

Set the following options in `config/sentinel.json`:

```json
{
  "automaticRecoveryEnabled": true,
  "recoveryFailureThreshold": 3,
  "recoveryFailureWindowSeconds": 600,
  "recoveryHealthySeconds": 300,
  "recoveryMaxRollbackAttempts": 5,
  "recoveryPreserveFailedWorld": true
}
```

Add these entries to the existing configuration; do not replace your other backup settings. Recovery requires a valid `backupDirectory` located outside the server directory, available snapshots, and a functioning `rsync` installation.

## How Bootloop Detection Works

Sentinel creates a startup marker in its recovery directory as Minecraft launches. On a normal, completed shutdown the marker is removed. If the process crashes or stops without reaching the clean-shutdown event, the marker remains and the **next launch** counts this as an unclean exit.

If the count reaches `recoveryFailureThreshold` within `recoveryFailureWindowSeconds`, Sentinel enters recovery mode. An unclean exit is **not proof of a corrupted chunk**: watchdog termination, power loss, an out-of-memory failure, or other startup problems can all leave the same marker.

For example, with the defaults, three recorded unclean launches within ten minutes trigger the first rollback. The detection logic does not independently identify the failing chunk or read the watchdog report.

## Consecutive Snapshot Rollback

Sentinel restores snapshots from newest to oldest. If the server crashes again before passing health verification, the next JVM startup attempts the next eligible, older snapshot.

```text
15:00  Snapshot A
14:55  Snapshot B
14:50  Snapshot C
14:45  Snapshot D

15:02  Repeated unclean starts detected
       Preserve current failed world (if enabled)
       Restore Snapshot A before world loading
       Minecraft crashes again

       Next startup: restore Snapshot B
       Minecraft crashes again

       Next startup: restore Snapshot C
       Server continues ticking for five minutes
       Recovery marked successful; normal backups resume
```

The implementation tracks attempted snapshot IDs in persistent recovery state so the same failed candidate is not selected again within that recovery sequence. It stops when `recoveryMaxRollbackAttempts` is reached or no eligible snapshots remain. In those cases, startup can be halted pending administrator intervention; the host's service may nevertheless continue attempting restarts if configured to do so.

**Snapshot eligibility:** the existing implementation uses timestamp-named snapshot directories and expects a copy of the configured world folder inside each. Older BTFU-format snapshots can therefore be candidates without new recovery metadata, as long as the required directory layout is present. Compressed snapshots are not automatically extracted.

## Health Verification

After a restoration, Sentinel lets Minecraft start normally and uses successful main-thread ticks to measure the health verification period. After `recoveryHealthySeconds` (default: 300 seconds) of elapsed time once tick tracking begins, it clears the failure and recovery-attempt state and allows scheduled backups to resume.

This proves only that the server reached a period of successful ticking. **It does not verify every chunk**, and failures in regions that have not yet loaded may remain undetected.

## Preservation and Recovery State

Sentinel keeps persistent state under the configured backup directory:

```text
sentinel-backups/recovery/
├── state.json          # Failed launch count, tried snapshots, recovery status
├── boot-active.json    # Marker used to detect unclean JVM exits
├── recovery-active     # Marker set during a recovery attempt
└── preserved/          # Preserved copy of failed world, when enabled
```

These files are outside the live server directory so ordinary world restoration does not overwrite the recovery history. Before the first automatic restore, Sentinel can preserve the original failed world under `recovery/preserved/`. Ensure enough free disk space is available: this is a separate copy and may require substantial storage.

## Requirements and Limitations

- **A restart mechanism is required.** Sentinel does not start or supervise the Minecraft JVM itself; it resumes recovery only when the hosting service launches Minecraft again.
- **A permanently hung JVM cannot recover by itself.** If the watchdog fails to terminate a frozen process, the early-start handler never runs again.
- **Recovery must reach Fabric's `preLaunch`.** Failures before Fabric can load Sentinel cannot be handled by this mechanism.
- **World-only restoration.** Automatic rollback restores the configured main world directory. Additional world data maintained outside that directory may not be included.
- **No corruption diagnosis.** Any repeated unclean starts can trigger world rollback, including crashes unrelated to world corruption.
- **Limited candidate checks.** Recovery relies on snapshot directory names and expected world folders, not full snapshot integrity verification. Do not treat its selection as proof that a backup is complete or healthy.
- **Recovery interruption requires care.** If restoration itself is interrupted, the live world can be left partially restored. Preserve your external backups and inspect the recovery state before repeated restarts.
- **Normal manual rollback is separate.** The manually requested rollback workflow still uses its generated offline restoration scripts after Minecraft exits.

---

# Snapshot Locking

Sentinel allows administrators to protect important snapshots.

Locked snapshots are excluded from automatic retention cleanup and compression.

This is useful for preserving:

- Important server milestones
- Backups before major updates
- Backups before installing new mods
- Known stable server states
- Long-term recovery points

## Lock a Snapshot

```text
/sentinel snapshot lock <snapshot>
```

## Unlock a Snapshot

```text
/sentinel snapshot unlock <snapshot>
```

Locked snapshots are identified in snapshot listings:

```text
[LOCKED]
```

---

# Snapshot Metadata

Sentinel stores metadata alongside each snapshot.

Every snapshot contains:

```text
snapshot.json
```

Example:

```json
{
  "snapshotId": "2026-05-24_14-17-26",
  "createdAtUtc": "2026-05-24T14:17:26.452719818Z",
  "reason": "manual command",
  "minecraftVersion": "1.21.1",
  "serverDirectory": "/server",
  "compressed": false,
  "backupDurationMs": 89
}
```

This metadata provides information about when and why a backup was created.

It also provides a foundation for future recovery verification and snapshot management features.

---

# Snapshot Compression

Sentinel supports optional compression of older snapshots.

Compression can reduce storage consumption for historical backups that are no longer needed for immediate restoration.

Enable compression:

```json
"compressOldSnapshots": true
```

Set the compression age:

```json
"compressSnapshotsOlderThanDays": 7
```

Snapshots older than the configured threshold become eligible for compression.

Example:

```text
2026-05-24_14-17-26.tar.gz
```

## Compression Limitations

Currently, compressed snapshots are intended for archival storage.

They cannot be restored directly through Sentinel's rollback commands.

Administrators must manually extract compressed snapshots before restoration.

Automatic extraction and restoration are planned for a future release.

---

# Emergency Offline Recovery

Sentinel generates standalone rollback scripts that can restore backups without Minecraft running.

This is particularly useful when:

- Minecraft crashes during startup
- A mod prevents the server from loading
- World data becomes corrupted
- The server cannot reach a playable state
- In-game rollback commands are unavailable

Generated files:

```text
manual_rollback/
├── rollback.sh
└── rollback.bat
```

These scripts operate independently of Minecraft.

## Linux

Navigate to the recovery directory:

```bash
cd manual_rollback
```

### List Snapshots

```bash
./rollback.sh list
```

### Restore Latest Snapshot

```bash
./rollback.sh latest
```

### Restore Specific Snapshot

```bash
./rollback.sh restore 2026-05-24_14-17-26
```

## Windows

Navigate to the recovery directory and execute:

### List Snapshots

```bat
rollback.bat list
```

### Restore Latest Snapshot

```bat
rollback.bat latest
```

### Restore Specific Snapshot

```bat
rollback.bat restore 2026-05-24_14-17-26
```

**Important:** Always stop Minecraft completely before using offline restoration scripts.

---

# Safety & Data Integrity

Sentinel is designed to protect server data, but administrators should understand the limitations of filesystem backups and restoration.

## Never Restore a Running Server

World files must not be overwritten while Minecraft is actively using them.

Use Sentinel's coordinated rollback commands or stop the server completely before running offline restoration scripts.

## Use External Backup Storage

Store backups outside the Minecraft server directory.

For stronger protection against disk failure, use a separate physical drive or remote backup destination.

## Maintain Offsite Backups

Hardlinked snapshots protect against many forms of accidental data loss, but they do not protect against the complete failure of the storage device containing them.

Maintaining an additional offsite backup is recommended.

## Verify Important Backups

A completed backup does not necessarily guarantee that all world data is valid.

For example, corrupted chunks may already exist in the backed-up world.

Automated backup integrity verification is planned for a future release.

## Preserve Important Snapshots

Use snapshot locking to preserve known-good recovery points before major server changes.

---

# Known Limitations

The current implementation has several limitations:

- Native backup functionality is primarily designed for Linux.
- Windows-native hardlink optimization is not implemented.
- Compressed snapshots cannot be restored directly.
- Partial chunk rollback is not implemented.
- Individual region restoration is not implemented.
- Live world restoration is not supported.
- Automatic recovery does not repair individual corrupted chunks or guarantee a restored world is fully valid.
- Recovery requires JVM restarts managed by the server host; it cannot clear a permanent JVM hang on its own.
- Automatic recovery currently detects repeated unclean starts, not the specific crash cause.
- Continuous TPS/MSPT performance monitoring is not yet implemented; recovery uses a basic tick-based health check.
- Backup integrity verification is not yet implemented.
- No in-game administrative GUI is currently available.
- No remote web dashboard is currently available.

---

# Development Roadmap

Sentinel is being developed with the long-term goal of becoming a comprehensive Minecraft server maintenance and recovery solution.

The following features are planned and are not part of the current release.

## Advanced Automatic Recovery

The initial bootloop recovery workflow is implemented. Planned improvements include:

- Watchdog and crash-report-aware failure classification
- Distinguishing world corruption from mod-loading and configuration failures
- Snapshot integrity checks and stronger known-good verification
- Crash-safe transactional world restoration
- Administrator alerts and more detailed recovery journaling
- Broader support for modded worlds stored outside the main world directory
- Optional chunk- or region-level recovery strategies

## Server Health Monitoring

- TPS and MSPT monitoring
- Server tick stall detection
- Memory usage monitoring
- Garbage collection monitoring
- Chunk loading performance monitoring
- Server startup time tracking
- Historical performance statistics
- Automatic detection of abnormal server behaviour

## Crash Diagnostics

- Automatic crash report analysis
- Watchdog report analysis
- Detection of common mod-related failures
- Identification of potentially corrupted chunks
- Identification of problematic entities and block entities
- Persistent crash history
- Recovery recommendations based on detected failures

## World Maintenance

- Region file integrity scanning
- Chunk corruption detection
- Individual chunk restoration
- Region-level restoration
- Selective restoration from historical snapshots
- World integrity verification
- Automated world maintenance tasks
- Detection of abnormal world file growth

## Advanced Backup Management

- Backup integrity verification
- Snapshot tagging
- Restore previews
- Scheduled rollback operations
- Incremental backup improvements
- Offsite backup synchronization
- Remote backup destinations
- Automatic restoration of compressed snapshots
- Configurable retention policies
- Additional backup compression formats

## Administration & Monitoring

- Optional client-side administrator interface
- Backup and recovery management GUI
- Real-time server health dashboard
- Historical performance graphs
- Server maintenance notifications
- Remote web dashboard
- Configurable maintenance schedules
- Detailed maintenance and recovery logs

---

# Project Direction

Sentinel is intended to evolve beyond traditional server backups.

The project's long-term focus is built around four principles.

### Prevention

Identify potential problems before they result in server crashes or data loss.

### Protection

Maintain reliable recovery points and safeguard critical server data.

### Recovery

Automatically identify and recover from server failures wherever it is safe to do so.

### Maintenance

Provide administrators with the tools necessary to keep Minecraft servers healthy, stable, and operational.

The ultimate goal is to minimise downtime and data loss while reducing the amount of manual intervention required to maintain a Minecraft server.

---

# Credits

Sentinel's original incremental backup functionality was inspired by BTFU, a Minecraft server backup mod developed by the Elytra project.

Original project:

https://github.com/elytra/BTFU

Sentinel is an independently developed project and is not affiliated with or endorsed by the original BTFU developers.

---

# License

Copyright (c) 2026 Maximus.

All Rights Reserved.

This software may not be redistributed, modified, republished, or incorporated into derivative works without explicit permission from the copyright holder.