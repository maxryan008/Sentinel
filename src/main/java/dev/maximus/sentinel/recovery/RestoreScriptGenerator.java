package dev.maximus.sentinel.recovery;

import dev.maximus.sentinel.Sentinel;
import dev.maximus.sentinel.config.SentinelConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RestoreScriptGenerator {
    private RestoreScriptGenerator() {
    }

    public static void generate(SentinelConfig config, Path serverDirectory) {
        try {
            Path manualDirectory = serverDirectory.resolve("manual_rollback");
            Files.createDirectories(manualDirectory);

            Path sh = manualDirectory.resolve("rollback.sh");
            Path bat = manualDirectory.resolve("rollback.bat");

            Files.writeString(sh, shellScript(config, serverDirectory));
            Files.writeString(bat, batchScript(config, serverDirectory));

            sh.toFile().setExecutable(true);
            bat.toFile().setExecutable(true);

            Sentinel.LOGGER.info("Generated Sentinel rollback scripts in {}", manualDirectory);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to generate rollback scripts.", exception);
        }
    }

    private static String shellScript(SentinelConfig config, Path serverDirectory) {
        String server = serverDirectory.toAbsolutePath().normalize().toString();
        String snapshots = config.snapshotsDirectory().toAbsolutePath().normalize().toString();

        return """
                #!/usr/bin/env bash
                set -euo pipefail
                
                SERVER_DIR="%s"
                SNAPSHOTS_DIR="%s"
                
                list_snapshots() {
                  ls -1 "$SNAPSHOTS_DIR" | sort -r
                }
                
                latest_snapshot() {
                  list_snapshots | head -n 1
                }
                
                restore_snapshot() {
                  SNAPSHOT_ID="$1"
                  SNAPSHOT_PATH="$SNAPSHOTS_DIR/$SNAPSHOT_ID"
                
                  if [ ! -d "$SNAPSHOT_PATH" ]; then
                    echo "Snapshot does not exist: $SNAPSHOT_ID"
                    exit 1
                  fi
                
                  echo "Restoring snapshot: $SNAPSHOT_ID"
                  echo "Server dir: $SERVER_DIR"
                
                  rsync -a --delete \\
                    --exclude='manual_rollback/**' \\
                    "$SNAPSHOT_PATH/" "$SERVER_DIR/"
                
                  echo "Rollback complete."
                }
                
                restore_after_pid() {
                  SNAPSHOT_ID="$1"
                  PID="$2"
                
                  echo "Waiting for server process $PID to stop..."
                  while kill -0 "$PID" 2>/dev/null; do
                    sleep 1
                  done
                
                  restore_snapshot "$SNAPSHOT_ID"
                }
                
                restore_world_after_pid() {
                  SNAPSHOT_ID="$1"
                  PID="$2"
                  RELATIVE_WORLD="$3"
                  case "$RELATIVE_WORLD" in
                    ""|/*|..|../*|*/../*|*/..) echo "Unsafe world path" >&2; exit 1 ;;
                  esac
                  SNAPSHOT_PATH="$SNAPSHOTS_DIR/$SNAPSHOT_ID/$RELATIVE_WORLD"
                  WORLD_PATH="$SERVER_DIR/$RELATIVE_WORLD"
                  if [ ! -d "$SNAPSHOT_PATH" ] || [ ! -f "$SNAPSHOT_PATH/level.dat" ]; then
                    echo "Snapshot world missing: $SNAPSHOT_PATH" >&2
                    exit 1
                  fi
                  while kill -0 "$PID" 2>/dev/null; do sleep 1; done
                  mkdir -p "$WORLD_PATH"
                  rsync -a --delete -- "$SNAPSHOT_PATH/" "$WORLD_PATH/"
                }

                case "${1:-}" in
                  list)
                    list_snapshots
                    ;;
                  latest)
                    restore_snapshot "$(latest_snapshot)"
                    ;;
                  restore)
                    restore_snapshot "${2:-}"
                    ;;
                  restore-after-pid)
                    restore_after_pid "${2:-}" "${3:-}"
                    ;;
                  restore-world-after-pid)
                    restore_world_after_pid "${2:-}" "${3:-}" "${4:-}"
                    ;;
                  *)
                    echo "Usage:"
                    echo "  ./rollback.sh list"
                    echo "  ./rollback.sh latest"
                    echo "  ./rollback.sh restore <snapshot>"
                    exit 1
                    ;;
                esac
                """.formatted(server, snapshots);
    }

    private static String batchScript(SentinelConfig config, Path serverDirectory) {
        String server = serverDirectory.toAbsolutePath().normalize().toString();
        String snapshots = config.snapshotsDirectory().toAbsolutePath().normalize().toString();

        return """
                @echo off
                set SERVER_DIR=%s
                set SNAPSHOTS_DIR=%s
                
                if "%%1"=="list" goto list
                if "%%1"=="restore" goto restore
                if "%%1"=="latest" goto latest
                
                echo Usage:
                echo   rollback.bat list
                echo   rollback.bat latest
                echo   rollback.bat restore ^<snapshot^>
                exit /b 1
                
                :list
                dir /b /o-n "%%SNAPSHOTS_DIR%%"
                exit /b 0
                
                :latest
                for /f "delims=" %%%%i in ('dir /b /o-n "%%SNAPSHOTS_DIR%%"') do (
                    set SNAPSHOT=%%%%i
                    goto latest_restore
                )
                
                :latest_restore
                robocopy "%%SNAPSHOTS_DIR%%\\%%SNAPSHOT%%" "%%SERVER_DIR%%" /MIR /XD manual_rollback
                exit /b 0
                
                :restore
                set SNAPSHOT=%%2
                if not exist "%%SNAPSHOTS_DIR%%\\%%SNAPSHOT%%" (
                    echo Snapshot does not exist: %%SNAPSHOT%%
                    exit /b 1
                )
                robocopy "%%SNAPSHOTS_DIR%%\\%%SNAPSHOT%%" "%%SERVER_DIR%%" /MIR /XD manual_rollback
                exit /b 0
                """.formatted(server, snapshots);
    }
}